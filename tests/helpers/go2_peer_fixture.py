#!/usr/bin/env python3
"""Protocol fixture, NOT a robot. Run separately from the guarded Go2 bridge.

Binds only to an address owned by this host; never connects to a real robot.
Supports legacy or per-device encrypted signaling, real ICE/DTLS/SCTP/RTP,
synthetic telemetry/video and request ACKs. Does not emulate robot physics.
Control/status is loopback-only. Never installed as a production service.
"""
import argparse
import asyncio
import base64
from collections import Counter
from fractions import Fraction
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import signal
import threading
import time

import av
import ifaddr
import numpy as np
from aiortc import RTCPeerConnection, RTCSessionDescription, RTCConfiguration, VideoStreamTrack
from Crypto.Cipher import PKCS1_v1_5
from Crypto.PublicKey import RSA
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from unitree_webrtc_connect.encryption import aes_decrypt, aes_encrypt


class Pattern(VideoStreamTrack):
    """Moving bars; these pixels are synthetic, never a robot camera."""
    def __init__(self, owner):
        super().__init__(); self.owner = owner; self.index = 0

    async def recv(self):
        await asyncio.sleep(.1)
        pixels = np.zeros((180, 320, 3), dtype=np.uint8)
        pixels[:, :, :] = (20, 30, 60)
        x = self.index * 5 % 280
        pixels[40:140, x:x+40] = (230, 180, 60)
        if not self.owner.video: pixels[:] = 0
        frame = av.VideoFrame.from_ndarray(pixels, format='rgb24')
        frame.pts = self.index * 9000; frame.time_base = Fraction(1, 90000)
        self.index += 1
        return frame


class Peer:
    def __init__(self):
        self.pc = None; self.channel = None; self.video = False
        self.validated = False; self.subscriptions = set(); self.counts = Counter()
        self.busy = False; self.telemetry = True
        self.challenge = 'GO2_PROTOCOL_FIXTURE_NOT_HARDWARE'

    def status(self):
        return {'fixture': 'go2-protocol-peer', 'hardware': False,
                'peerState': self.pc.connectionState if self.pc else 'closed',
                'validated': self.validated, 'counts': dict(self.counts),
                'subscriptions': sorted(self.subscriptions), 'video': self.video}

    def send(self, message):
        if self.channel and self.channel.readyState == 'open':
            self.channel.send(json.dumps(message))

    async def close(self):
        if self.pc: await self.pc.close()
        self.pc = None; self.channel = None; self.validated = False
        self.subscriptions.clear(); self.video = False

    async def offer(self, offer):
        if self.busy: return {'sdp': 'reject', 'type': 'answer'}
        await self.close()
        pc = self.pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        pc.addTrack(Pattern(self))

        @pc.on('datachannel')
        def on_channel(channel):
            self.channel = channel
            @channel.on('message')
            def message(raw):
                value = json.loads(raw)
                kind, topic, data = value.get('type'), value.get('topic', ''), value.get('data')
                self.counts[str(kind) + ':' + topic] += 1
                if kind == 'validation':
                    expected = base64.b64encode(hashlib.md5(('UnitreeGo2_' + self.challenge).encode()).digest()).decode()
                    self.validated = data == expected
                    if self.validated: self.send({'type': 'validation', 'topic': '', 'data': 'Validation Ok.'})
                elif kind == 'subscribe': self.subscriptions.add(topic)
                elif kind == 'unsubscribe': self.subscriptions.discard(topic)
                elif kind == 'heartbeat': self.send(value)
                elif kind == 'rtc_inner_req':
                    self.send({'type': kind, 'topic': '', 'info': {**data, 'status': 'NetworkStatus.ON_WIFI_CONNECTED', 'execution': 'ok'}})
                elif kind == 'vid': self.video = data == 'on'
                elif kind == 'req':
                    identity = data['header']['identity']
                    self.counts['api:' + topic + ':' + str(identity['api_id'])] += 1
                    self.send({'type': 'res', 'topic': topic, 'data': {'header': {'identity': identity, 'status': {'code': 0}},
                        'data': json.dumps({'name': 'normal'}) if topic == 'rt/api/motion_switcher/request' else '{}'}})
            self.send({'type': 'validation', 'topic': '', 'data': self.challenge})

        await pc.setRemoteDescription(RTCSessionDescription(sdp=offer['sdp'], type=offer['type']))
        await pc.setLocalDescription(await pc.createAnswer())
        return {'sdp': pc.localDescription.sdp, 'type': pc.localDescription.type}

    async def stream(self):
        while True:
            if self.validated and self.telemetry:
                now = time.time()
                values = {'rt/lf/lowstate': {'bms_state': {'soc': 81}, 'power_v': 29.8},
                    'rt/lf/sportmodestate': {'body_height': .32, 'mode': 1, 'velocity': [0, 0, 0], 'yaw_speed': 0,
                        'position': [0, 0, .32], 'imu_state': {'quaternion': [1, 0, 0, 0]},
                        'stamp': {'sec': int(now), 'nanosec': int(now % 1 * 1e9)}}}
                for topic in self.subscriptions & values.keys():
                    self.send({'type': 'msg', 'topic': topic, 'data': values[topic]})
            await asyncio.sleep(.1)


async def main(args):
    ips = {ip.ip for adapter in ifaddr.get_adapters() for ip in adapter.ips if isinstance(ip.ip, str)}
    if args.bind not in ips: raise SystemExit('Fixture must bind to this host, never a robot IP')
    key = os.urandom(16)
    private = RSA.generate(2048)
    public = base64.b64encode(private.publickey().export_key(format='DER')).decode()
    os.umask(0o077)
    Path(args.config).write_text(json.dumps({'fixture': 'go2-protocol-peer', 'ip': args.bind, 'key': key.hex()}))
    peer = Peer(); loop = asyncio.get_running_loop(); done = asyncio.Event()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def reply(self, value, status=200):
            data = value.encode() if isinstance(value, str) else json.dumps(value).encode()
            self.send_response(status); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
        def do_GET(self):
            if self.server.server_port == 18847 and self.path == '/status': return self.reply(peer.status())
            self.reply({}, 404)
        def do_POST(self):
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 <= size <= 131072: return self.reply({}, 413)
                self.connection.settimeout(10)
                value = json.loads(self.rfile.read(size)) if size else {}
                if self.server.server_port == 18847:
                    if self.path == '/close': asyncio.run_coroutine_threadsafe(peer.close(), loop).result(8)
                    elif self.path == '/busy': peer.busy = bool(value['enabled'])
                    elif self.path == '/telemetry': peer.telemetry = bool(value['enabled'])
                    elif self.path == '/shutdown': loop.call_soon_threadsafe(done.set)
                    else: return self.reply({}, 404)
                    return self.reply(peer.status())
                if self.path == '/con_notify' and args.signaling == 'v3':
                    data1 = '0123456789' + public + '0A0B0C0D0E'
                    nonce = os.urandom(12); encrypted = AESGCM(key).encrypt(nonce, data1.encode(), None)
                    data1 = base64.b64encode(encrypted[:-16] + nonce + encrypted[-16:]).decode()
                    return self.reply(base64.b64encode(json.dumps({'data1': data1, 'data2': 3}).encode()).decode())
                if self.path == '/con_ing_01234' and args.signaling == 'v3':
                    aes_key = PKCS1_v1_5.new(private).decrypt(base64.b64decode(value['data2']), None).decode()
                    offer = json.loads(aes_decrypt(value['data1'], aes_key))
                    answer = asyncio.run_coroutine_threadsafe(peer.offer(offer), loop).result(10)
                    return self.reply(aes_encrypt(json.dumps(answer), aes_key))
                if self.path == '/offer' and args.signaling == 'legacy':
                    return self.reply(asyncio.run_coroutine_threadsafe(peer.offer(value), loop).result(10))
                self.reply({}, 404)
            except Exception as error:
                self.reply({'error': type(error).__name__}, 400)

    servers = [ThreadingHTTPServer((args.bind, 9991 if args.signaling == 'v3' else 8081), Handler),
               ThreadingHTTPServer(('127.0.0.1', 18847), Handler)]
    for server in servers: threading.Thread(target=server.serve_forever, daemon=True).start()
    for sig in (signal.SIGINT, signal.SIGTERM): loop.add_signal_handler(sig, done.set)
    stream = asyncio.create_task(peer.stream())
    print(json.dumps({'fixture': 'go2-protocol-peer', 'hardware': False, 'ready': True, 'signaling': args.signaling}), flush=True)
    try:
        await asyncio.wait_for(done.wait(), timeout=1800)
    finally:
        stream.cancel(); await peer.close()
        for server in servers: server.shutdown(); server.server_close()
        Path(args.config).unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--bind', required=True)
    parser.add_argument('--config', required=True)
    parser.add_argument('--signaling', choices=('legacy', 'v3'), default='v3')
    asyncio.run(main(parser.parse_args()))
