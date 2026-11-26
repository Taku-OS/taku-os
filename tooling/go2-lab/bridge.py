#!/usr/bin/env python3
"""Local Go2 bridge with perception and fixed, manually armed motion pulses."""
import argparse
import asyncio
from collections import Counter
import ipaddress
import json
import logging
from pathlib import Path
import re
import signal
import threading
import time
import uuid
from urllib.parse import urlsplit, parse_qs
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from protocol import LIDAR_TOPIC, TOPICS, permitted, telemetry, sample_view
from motion import Motion, SPORT_TOPIC, valid_speeds
from lab import Lab
from audio_control import AudioControl, MAX_BYTES, valid_command
from camera import Camera
from navigation import Navigation
from lidar_pose import LidarPose, TOPIC as POSE_TOPIC

PORT = 18845
WEB = Path(__file__).parent / 'web'


class Bridge(Lab):
    def __init__(self, ip, aes_128_key=None):
        self.ip = ip
        self.aes_128_key = aes_128_key
        self.external_motion_control = False
        self.phase = 'disconnected'
        self.error = None
        self.samples = {}
        self.sent = Counter()
        self.blocked = 0
        self.task = None
        self.conn = None
        self.lidar_enabled = False
        self.lidar_sample = None
        self.lidar_error = None
        self.lidar_sequence = 0
        self.stream_id = uuid.uuid4().hex[:8]
        self.motion = Motion(self.motion_ready, self.move_once, self.stop_move)
        self.motion_id = int(time.time()*1000) % 1000000000
        self.last_stop_code = None
        self.current_motion_mode = None
        self.last_move_code = None
        self.last_move_id = None
        self.init_lab()
        self.lidar_pose = LidarPose()
        self.audio = AudioControl(self)
        self.camera = Camera(self)
        self.navigation = Navigation(self)

    def motion_ready(self):
        if self.external_motion_control: return False
        sample = self.samples.get('rt/lf/sportmodestate')
        return bool(self.current_motion_mode == 'normal' and self.phase == 'connected' and self.conn and self.conn.isConnected
                    and self.conn.datachannel.data_channel_opened and sample
                    and time.monotonic()-sample['received'] < 0.5
                    and 0.18 <= (sample['values'].get('bodyHeight') or 0) <= 0.5)

    def move_once(self, vector):
        channel = self.conn.datachannel.channel
        if channel.readyState != 'open': raise ConnectionError('CHANNEL_CLOSED')
        self.motion_id += 1
        self.last_move_id, self.last_move_code = self.motion_id, None
        data = {'header': {'identity': {'id': self.motion_id, 'api_id': 1008}},
                'parameter': json.dumps(dict(zip(('x','y','z'), vector)))}
        channel.send(json.dumps({'type':'req', 'topic':SPORT_TOPIC, 'data':data}))
        self.recorder.emit('sport_move_sent', {'id':self.motion_id,'vector':vector})

    def receive_motion_reply(self, raw):
        if not isinstance(raw,str): return
        try:
            header = json.loads(raw).get('data', {}).get('header', {})
            identity = header.get('identity', {})
            if identity.get('api_id') == 1008 and identity.get('id') == self.last_move_id:
                code = header.get('status', {}).get('code')
                self.last_move_code = code if type(code) is int else None
                self.recorder.emit('sport_move_reply', {'id':self.last_move_id,'code':self.last_move_code})
                if type(code) is int and code != 0:
                    self.motion.error = 'MOVE_REJECTED'
                    self.motion.revoke()
        except (ValueError,AttributeError,TypeError): pass

    async def stop_move(self):
        if not self.conn or not self.conn.isConnected or not self.conn.datachannel.data_channel_opened:
            raise ConnectionError('CHANNEL_CLOSED')
        self.recorder.emit('sport_stop_request', {})
        response = await asyncio.wait_for(self.conn.datachannel.pub_sub.publish_request_new(
            SPORT_TOPIC, {'api_id':1003, 'parameter':'{}'}), timeout=1)
        code = response.get('data', {}).get('header', {}).get('status', {}).get('code')
        self.last_stop_code = code if type(code) is int else None
        self.recorder.emit('sport_stop_reply', {'code':self.last_stop_code})
        if type(code) is not int or code != 0: raise RuntimeError('STOP_REJECTED')

    async def motion_mode(self):
        if not self.conn or not self.conn.isConnected: return {'error':'NOT_CONNECTED'}
        try:
            response = await asyncio.wait_for(self.conn.datachannel.pub_sub.publish_request_new(
                'rt/api/motion_switcher/request', {'api_id':1001}), timeout=2)
            data = response.get('data', {})
            code = data.get('header', {}).get('status', {}).get('code')
            value = json.loads(data.get('data', '{}'))
            name = value.get('name') if isinstance(value, dict) else None
            self.current_motion_mode = name if code == 0 and name in {'normal','ai','mcf'} else None
            return {'code':code if type(code) is int else None, 'mode':name[:32] if isinstance(name,str) else None}
        except Exception as error:
            self.current_motion_mode = None
            return {'error':type(error).__name__}

    async def normal_mode(self):
        if self.external_motion_control: return {'error': 'OBSERVATION_ONLY'}
        if self.navigation.active() or self.lease.owner in {'avoidance','navigation'} or not self.navigation.stop_confirmed or self.avoidance.enabled is True or self.phase == 'replay':
            return {'error':'CONTROL_BUSY'}
        if self.motion.snapshot()['busy']: return {'error':'BUSY'}
        self.motion.revoke()
        self.motion.switching = True
        generation = self.motion.generation
        try:
            await self.stop_move()
            if generation != self.motion.generation: return {'error':'CANCELLED'}
            response = await asyncio.wait_for(self.conn.datachannel.pub_sub.publish_request_new(
                'rt/api/motion_switcher/request', {'api_id':1002, 'parameter':'{"name":"normal"}'}), timeout=8)
            code = response.get('data', {}).get('header', {}).get('status', {}).get('code')
            # The caller observes the robot standing steady before arming again.
            self.motion.cooldown_until = time.monotonic()+5
            return {'code':code if type(code) is int else None, 'state':await self.motion_mode()}
        except Exception as error:
            self.current_motion_mode = None
            return {'error':type(error).__name__}
        finally:
            self.motion.switching = False

    async def snapshot(self):
        connected = bool(self.phase == 'connected' and self.conn and self.conn.isConnected
                         and self.conn.datachannel.data_channel_opened)
        return {'robotIp': self.ip, 'phase': self.phase, 'connected': connected,
                'source':'replay' if self.phase=='replay' else 'simulation' if self.simulated else 'real',
                'readOnly': False, 'externalMotionControl': self.external_motion_control,
                'localization':self.lidar_pose.snapshot(), 'controlOwner':self.lease.owner, 'motion': {**self.motion.snapshot(),
                'ready':self.motion_ready() and self.lease.owner!='navigation' and not self.navigation.active(), 'mode':self.current_motion_mode,
                'moveCode':self.last_move_code, 'stopCode': self.last_stop_code}, 'error': self.error,
                'telemetry': {t: sample_view(self.samples.get(t), time.monotonic(), connected) for t in sorted(TOPICS)},
                'outgoing': dict(self.sent), 'blockedMessages': self.blocked}

    async def lidar_snapshot(self):
        connected = bool(self.phase == 'connected' and self.conn and self.conn.isConnected)
        snapshot = sample_view(self.lidar_sample, time.monotonic(), connected and self.lidar_enabled)
        if snapshot['values'] is not None:
            snapshot['values'] = {k: v for k, v in snapshot['values'].items() if k != 'pointBytes'}
        return {'enabled': self.lidar_enabled, 'error': self.lidar_error,
                'frameKey': f'{self.stream_id}:{self.lidar_sequence}', **snapshot}

    async def lidar_frame(self, after=''):
        from lidar import encode_frame
        snapshot = await self.lidar_snapshot()
        points = self.lidar_sample['values']['pointBytes'] if self.lidar_sample and after != snapshot['frameKey'] else b''
        return encode_frame(snapshot, points)

    async def scan(self, enabled):
        if not self.conn or not self.conn.isConnected or not self.conn.datachannel.data_channel_opened:
            return {'error': 'ROBOT_NOT_CONNECTED'}
        channel = self.conn.datachannel
        if enabled and not self.lidar_enabled:
            self.lidar_enabled = True
            self.lidar_sample, self.lidar_error = None, None
            channel.set_decoder(decoder_type='native')
            channel.pub_sub.subscribe(LIDAR_TOPIC, self.receive_lidar)
            channel.pub_sub.publish_without_callback('', {'req_type': 'disable_traffic_saving', 'instruction': 'on'}, 'rtc_inner_req')
            channel.pub_sub.publish_without_callback('rt/utlidar/switch', 'on')
        elif not enabled:
            self.lidar_enabled = False
            channel.pub_sub.unsubscribe(LIDAR_TOPIC)
            channel.pub_sub.subscriptions.pop(LIDAR_TOPIC, None)
        return await self.lidar_snapshot()

    def receive_lidar(self, message):
        if not self.lidar_enabled:
            return
        try:
            from lidar import point_cloud
            values = point_cloud(message['data'])
            previous = self.lidar_sample or {}
            # Firmware timestamps can be coarse floats; freshness is local arrival time.
            self.lidar_sequence += 1
            self.lidar_sample = {'values': values, 'received': time.monotonic(), 'count': previous.get('count', 0) + 1}
            self.recorder.emit('lidar', self.lidar_sample)
            self.lidar_error = None
        except Exception as error:
            self.lidar_error = type(error).__name__

    async def command(self, action):
        if action == 'disconnect':
            self.navigation.geo.invalidate('GEO_REFERENCE_CHANGED')
            await self.camera.command('stop')
            self.camera.disconnect()
            self.audio.disconnected()
            await self.close_controls()
            await self.stop_replay()
            if self.motion.snapshot()['busy'] or self.motion.snapshot()['armed']:
                await self.motion.stop()
                await asyncio.wait_for(self.motion.idle.wait(), timeout=3)
            self.motion.revoke()
            if self.task and not self.task.done():
                self.task.cancel()
                try:
                    await self.task
                except asyncio.CancelledError:
                    pass
            self.phase = 'disconnected'
            self.error = None
        elif not self.task or self.task.done():
            await self.stop_replay()
            self.navigation.geo.invalidate('GEO_REFERENCE_CHANGED')
            self.pose_sample=None
            self.lidar_pose = LidarPose()
            self.phase, self.error, self.samples = 'connecting', None, {}
            self.lidar_enabled, self.lidar_sample, self.lidar_error = False, None, None
            self.task = asyncio.create_task(self.session())
        return await self.snapshot()

    def receive(self, message):
        topic = message.get('topic')
        if topic == POSE_TOPIC:
            if self.lidar_pose.receive(message.get('data')):
                self.pose_sample = self.lidar_pose.sample
                self.recorder.emit('pose', self.pose_sample['values'])
            return
        values = telemetry(topic, message.get('data'))
        if topic in TOPICS and values:
            previous = self.samples.get(topic, {})
            self.samples[topic] = {'values': values, 'received': time.monotonic(),
                                   'count': previous.get('count', 0) + 1}
            self.recorder.emit('state', {'topic':topic, 'values':values})
            if values.get('pose') and self.lidar_pose.sample is None:
                self.pose_sample = {'values':values['pose'],'received':time.monotonic()}
                self.recorder.emit('pose', values['pose'])

    async def session(self):
        from unitree_webrtc_connect import UnitreeWebRTCConnection, WebRTCConnectionMethod
        self.conn = UnitreeWebRTCConnection(WebRTCConnectionMethod.LocalSTA, ip=self.ip, aes_128_key=self.aes_128_key)
        try:
            await asyncio.wait_for(self.conn.connect(), timeout=40)
            self.camera.attach()
            self.conn.datachannel.channel.on('message', self.receive_motion_reply)
            await self.motion_mode()
            self.phase = 'connected'
            for topic in sorted(TOPICS):
                self.conn.datachannel.pub_sub.subscribe(topic, self.receive)
            self.conn.datachannel.pub_sub.subscribe(POSE_TOPIC, self.receive)
            while self.conn.isConnected and self.conn.datachannel.data_channel_opened:
                await asyncio.sleep(1)
            raise ConnectionError('Data channel closed')
        except asyncio.CancelledError:
            self.phase = 'disconnected'
            raise
        except Exception as error:
            # Do not expose tokens, challenge data, raw exception text or credentials.
            self.phase, self.error = 'error', type(error).__name__
        finally:
            self.camera.disconnect()
            self.audio.disconnected()
            self.motion.revoke()
            self.lidar_enabled = False
            if self.conn and self.conn.pc:
                await asyncio.wait_for(self.conn.disconnect(), timeout=5)
            self.conn = None

    def transport_allows(self, message):
        if not isinstance(message, dict): return False
        # A companion-controlled phone may receive telemetry/video and play audio,
        # but must never send even StopMove or a motion-mode/ownership change.
        if self.external_motion_control:
            return permitted(message) or self.audio.allows(message) or self.camera.allows(message)
        return (permitted(message) or self.motion.allows(message) or self.native.allows(message)
                or self.audio.allows(message) or self.camera.allows(message))

    def install_transport_guard(self):
        from aiortc import RTCDataChannel
        from unitree_webrtc_connect import unitree_auth
        original_send = RTCDataChannel.send

        def send(channel, raw):
            message = json.loads(raw) if isinstance(raw, str) else {}
            if not self.transport_allows(message):
                self.blocked += 1
                raise RuntimeError('MESSAGE_NOT_ALLOWED')
            self.sent[message['type'] + ':' + message.get('topic', '')] += 1
            return original_send(channel, raw)

        RTCDataChannel.send = send
        self.http = unitree_auth.requests.Session()
        self.http.trust_env = False

        def post(url, data=None, headers=None):
            base = re.escape('http://' + self.ip)
            if not re.fullmatch(base + r'(?::9991/con_(?:notify|ing_[0-9]+)|:8081/offer)', url):
                raise RuntimeError('NON_LOCAL_SIGNALING_BLOCKED')
            return self.http.post(url, data=data, headers=headers, timeout=6)

        unitree_auth.requests.post = post


def handler_class(bridge, loop):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, status, data, content_type='application/json'):
            body = json.dumps(data, allow_nan=False).encode() if content_type == 'application/json' else data
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            wasm = " 'wasm-unsafe-eval'" if self.path.startswith(('/follow-', '/vision-assets/')) else ''
            self.send_header('Content-Security-Policy', "default-src 'self'; connect-src 'self' http://127.0.0.1:18846; style-src 'self'; script-src 'self'" + wasm + "; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(body)

        def allowed(self):
            host = self.headers.get('Host')
            origin = self.headers.get('Origin')
            return (host in {f'127.0.0.1:{PORT}', f'localhost:{PORT}'}
                    and origin in {None, f'http://127.0.0.1:{PORT}', f'http://localhost:{PORT}'})

        def do_GET(self):
            if not self.allowed():
                return self.reply(403, {'error': 'LOCAL_ONLY'})
            url = urlsplit(self.path)
            if url.path in {'/api/navigation','/api/navigation/image'}:
                key = parse_qs(url.query).get('id',[''])[0]
                async def navigation_read():
                    return bridge.navigation.image(key) if url.path.endswith('/image') else bridge.navigation.snapshot()
                try:
                    result = asyncio.run_coroutine_threadsafe(navigation_read(),loop).result(timeout=3)
                    if result is None: return self.reply(404,{'error':'OBSERVATION_EXPIRED'})
                    return self.reply(200,result,'image/jpeg' if url.path.endswith('/image') else 'application/json')
                except TimeoutError: return self.reply(503,{'error':'BRIDGE_BUSY'})
            if url.path == '/api/camera/frame':
                after = parse_qs(url.query).get('after',[''])[0]
                if len(after)>100: return self.reply(400,{'error':'INVALID_FRAME_KEY'})
                async def camera_frame(): return bridge.camera.packet(after)
                try:
                    data = asyncio.run_coroutine_threadsafe(camera_frame(),loop).result(timeout=3)
                    return self.reply(200,data,'application/octet-stream')
                except TimeoutError: return self.reply(503,{'error':'BRIDGE_BUSY'})
            if url.path.startswith('/api/audio/preset/'):
                from audio_library import read_preset
                try:
                    _, raw = read_preset(url.path.removeprefix('/api/audio/preset/'))
                    return self.reply(200, raw, 'audio/wav')
                except (OSError, ValueError, KeyError):
                    return self.reply(404, {'error': 'INVALID_PRESET'})
            if self.path == '/api/audio/library':
                from audio_library import public_catalog
                try: return self.reply(200, public_catalog())
                except (OSError, ValueError, KeyError): return self.reply(503, {'error': 'LIBRARY_UNAVAILABLE'})
            if self.path == '/api/audio/transport':
                return self.reply(200, asyncio.run_coroutine_threadsafe(bridge.audio.transport_status(), loop).result(timeout=4))
            if self.path == '/api/audio':
                async def audio_state(): return bridge.audio.snapshot()
                return self.reply(200, asyncio.run_coroutine_threadsafe(audio_state(), loop).result(timeout=4))
            if self.path == '/api/lab':
                try:
                    value = asyncio.run_coroutine_threadsafe(bridge.lab_snapshot(), loop).result(timeout=4)
                    return self.reply(200,value)
                except TimeoutError: return self.reply(503,{'error':'BRIDGE_BUSY'})
            if self.path == '/api/motion/mode':
                value = asyncio.run_coroutine_threadsafe(bridge.motion_mode(), loop).result(timeout=4)
                return self.reply(200, value)
            if url.path == '/api/lidar/frame':
                after = parse_qs(url.query).get('after', [''])[0]
                if len(after) > 80:
                    return self.reply(400, {'error': 'INVALID_FRAME_KEY'})
                try:
                    frame = asyncio.run_coroutine_threadsafe(bridge.lidar_frame(after), loop).result(timeout=8)
                    return self.reply(200, frame, 'application/octet-stream')
                except TimeoutError:
                    return self.reply(503, {'error': 'BRIDGE_BUSY'})
            if self.path in {'/api/state', '/api/lidar'}:
                try:
                    operation = bridge.snapshot() if self.path == '/api/state' else bridge.lidar_snapshot()
                    state = asyncio.run_coroutine_threadsafe(operation, loop).result(timeout=8)
                    return self.reply(200, state)
                except TimeoutError:
                    return self.reply(503, {'error': 'BRIDGE_BUSY'})
            assets = {'/': ('index.html', 'text/html; charset=utf-8'),
                      '/app.js': ('app.js', 'text/javascript; charset=utf-8'),
                      '/style.css': ('style.css', 'text/css; charset=utf-8'),
                      '/lidar.js': ('lidar.js', 'text/javascript; charset=utf-8'),
                      '/point-renderer.js': ('point-renderer.js', 'text/javascript; charset=utf-8'),
                      '/motion.js': ('motion.js', 'text/javascript; charset=utf-8')}
            for name, mime in (('panel.html', 'text/html; charset=utf-8'), ('panel.js', 'text/javascript; charset=utf-8'), ('panel.css', 'text/css; charset=utf-8'), ('design-system.css', 'text/css; charset=utf-8'), ('robot.png', 'image/png'), ('wallpaper.png', 'image/png')):
                assets['/panel' if name == 'panel.html' else '/' + name] = (name, mime)
            assets['/geo.js'] = ('geo.js', 'text/javascript; charset=utf-8')
            assets['/geo-projection.js'] = ('geo-projection.js', 'text/javascript; charset=utf-8')
            assets['/amap.js'] = ('amap.js', 'text/javascript; charset=utf-8')
            assets['/navigation.js'] = ('navigation.js', 'text/javascript; charset=utf-8')
            assets['/camera.js'] = ('camera.js', 'text/javascript; charset=utf-8')
            assets['/audio.js'] = ('audio.js', 'text/javascript; charset=utf-8')
            assets['/avoidance.js'] = ('avoidance.js', 'text/javascript; charset=utf-8')
            assets['/follow-demo'] = ('follow-demo.html', 'text/html; charset=utf-8')
            assets['/follow-demo.js'] = ('follow-demo.js', 'text/javascript; charset=utf-8')
            assets['/follow-tracker.js'] = ('follow-tracker.js', 'text/javascript; charset=utf-8')
            assets['/follow-demo.css'] = ('follow-demo.css', 'text/css; charset=utf-8')
            assets['/audio-library'] = ('audio-library.html', 'text/html; charset=utf-8')
            assets['/audio-library.js'] = ('audio-library.js', 'text/javascript; charset=utf-8')
            assets['/audio-library-controls.js'] = ('audio-library-controls.js', 'text/javascript; charset=utf-8')
            assets['/audio-library.css'] = ('audio-library.css', 'text/css; charset=utf-8')
            for name in ('follow-person.js', 'follow-vision-worker.js', 'follow-control.js', 'follow-guidance.js', 'follow-scene-voice.js'):
                assets['/' + name] = (name, 'text/javascript; charset=utf-8')
            for name in ('vision_bundle.js', 'vision_wasm_internal.js', 'vision_wasm_nosimd_internal.js', 'vision_wasm_module_internal.js',
                         'vision_wasm_internal.wasm', 'vision_wasm_nosimd_internal.wasm', 'vision_wasm_module_internal.wasm', 'pose_landmarker_lite.task'):
                mime = 'text/javascript' if name.endswith('.js') else 'application/wasm' if name.endswith('.wasm') else 'application/octet-stream'
                assets['/vision-assets/' + name] = ('vision-assets/' + name, mime)
            if self.path not in assets:
                return self.reply(404, {'error': 'NOT_FOUND'})
            name, mime = assets[self.path]
            return self.reply(200, (WEB / name).read_bytes(), mime)

        def do_POST(self):
            if not self.allowed():
                return self.reply(403, {'error': 'LOCAL_ONLY'})
            if self.path.startswith('/api/navigation/'):
                action = self.path.rsplit('/',1)[-1]
                if action not in {'prepare','observe','place','goal','visual','scan','stop','geo-prepare','geo-calibrate','geo-start','geo-heartbeat'}:
                    return self.reply(404,{'error':'UNKNOWN_ENDPOINT'})
                try:
                    length = int(self.headers.get('Content-Length','-1'))
                    if self.headers.get('Content-Type')!='application/json' or self.headers.get('Transfer-Encoding') or not 2<=length<=(262144 if action=='geo-prepare' else 1024): raise ValueError()
                    self.connection.settimeout(3)
                    value=json.loads(self.rfile.read(length))
                    from navigation import valid_request
                    if not valid_request(action,value): raise ValueError()
                    future=asyncio.run_coroutine_threadsafe(bridge.navigation.command(action,value),loop)
                    result=future.result(timeout=10)
                    return self.reply(409 if result.get('requestError') else 200,result)
                except (ValueError,UnicodeError): return self.reply(400,{'error':'INVALID_NAVIGATION_REQUEST'})
                except TimeoutError:
                    if 'future' in locals():future.cancel()
                    return self.reply(503,{'error':'NAVIGATION_TIMEOUT'})
            if urlsplit(self.path).path.startswith('/api/audio/'):
                url = urlsplit(self.path)
                action = url.path.rsplit('/', 1)[-1]
                if action not in {'import','select','refresh','play','stop','volume','prepare'}:
                    return self.reply(404, {'error':'UNKNOWN_ENDPOINT'})
                try:
                    length = int(self.headers.get('Content-Length', '-1'))
                    if self.headers.get('Transfer-Encoding'): raise ValueError()
                    self.connection.settimeout(10)
                    if action == 'import':
                        if self.headers.get('Content-Type') != 'application/octet-stream' or not 0 < length <= MAX_BYTES:
                            raise ValueError()
                        name = parse_qs(url.query).get('name', ['audio.wav'])[0]
                        if len(name) > 200: raise ValueError()
                        raw = self.rfile.read(length)
                        if len(raw) != length: raise ValueError()
                        operation = bridge.audio.import_clip(raw, name)
                    else:
                        if self.headers.get('Content-Type') != 'application/json' or not 2 <= length <= 128: raise ValueError()
                        value = json.loads(self.rfile.read(length))
                        if action == 'select':
                            if not isinstance(value, dict) or set(value) != {'id'} or not isinstance(value['id'], str): raise ValueError()
                            operation = bridge.audio.select_preset(value['id'])
                        else:
                            if not valid_command(action, value): raise ValueError()
                            operation = bridge.audio.command(action, value)
                    future = asyncio.run_coroutine_threadsafe(operation, loop)
                    result = future.result(timeout=30)
                    return self.reply(409 if result.get('requestError') else 200, result)
                except (ValueError, UnicodeError): return self.reply(400, {'error':'INVALID_AUDIO_REQUEST'})
                except TimeoutError:
                    if 'future' in locals(): future.cancel()
                    return self.reply(503, {'error':'AUDIO_TIMEOUT'})
            motion_paths = {'/api/motion/'+action for action in ('arm','stop','forward','backward','left','right','normal','speed')}
            from lab import SCENARIOS
            lab_paths = {'/api/'+family+'/'+action for family, actions in {
                'avoidance':('query','enable','disable','arm','stop','forward','backward','left','right','heartbeat'),
                'simulation':SCENARIOS, 'recording':('start','stop'), 'replay':('start','pause','resume','stop')}.items() for action in actions}
            if self.path not in {'/api/connect', '/api/disconnect', '/api/lidar/start', '/api/lidar/stop', '/api/camera/start', '/api/camera/stop'} | motion_paths | lab_paths:
                return self.reply(404, {'error': 'UNKNOWN_ENDPOINT'})
            try:
                length = int(self.headers.get('Content-Length', '-1'))
                if self.path == '/api/motion/speed':
                    if (self.headers.get('Content-Type') != 'application/json' or not 2 <= length <= 128
                            or self.headers.get('Transfer-Encoding')):
                        return self.reply(400, {'error': 'INVALID_SPEED'})
                    self.connection.settimeout(3)
                    try: value = json.loads(self.rfile.read(length))
                    except (ValueError, UnicodeError): return self.reply(400, {'error': 'INVALID_SPEED'})
                    if not valid_speeds(value): return self.reply(400, {'error': 'INVALID_SPEED'})
                    state = asyncio.run_coroutine_threadsafe(bridge.motion_speed(value), loop).result(timeout=4)
                    return self.reply(409 if state.get('requestError') else 200, state)
                if (self.headers.get('Content-Type') != 'application/json' or length != 2
                        or self.headers.get('Transfer-Encoding')):
                    return self.reply(400, {'error': 'EMPTY_JSON_REQUIRED'})
                self.connection.settimeout(3)
                if self.rfile.read(length) != b'{}':
                    return self.reply(400, {'error': 'EMPTY_JSON_REQUIRED'})
                action = self.path.rsplit('/', 1)[1]
                if self.path.startswith('/api/camera/'):
                    operation = bridge.camera.command(action)
                elif self.path in lab_paths:
                    operation = bridge.lab_command(self.path.split('/')[2],action)
                elif self.path in motion_paths:
                    operation = bridge.normal_mode() if action == 'normal' else (bridge.motion_command(action) if hasattr(bridge,'motion_command') else bridge.motion.command(action))
                else:
                    operation = bridge.scan(action == 'start') if self.path.startswith('/api/lidar/') else bridge.command(action)
                state = asyncio.run_coroutine_threadsafe(operation, loop).result(timeout=15)
                return self.reply(200, state)
            except (ValueError, TimeoutError):
                return self.reply(503, {'error': 'BRIDGE_BUSY'})
    return Handler


async def serve(ip):
    bridge = Bridge(ip)
    bridge.install_transport_guard()
    bridge.monitor_task = asyncio.create_task(bridge.monitor())
    loop = asyncio.get_running_loop()
    server = ThreadingHTTPServer(('127.0.0.1', PORT), handler_class(bridge, loop))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    stopped = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stopped.set)
    print(f'Go2 local bridge: http://127.0.0.1:{PORT} (robot {ip})', flush=True)
    try:
        await stopped.wait()
    finally:
        await bridge.command('disconnect')
        bridge.monitor_task.cancel()
        await asyncio.to_thread(bridge.recorder.stop)
        server.shutdown()
        server.server_close()
        bridge.http.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--robot-ip', required=True)
    args = parser.parse_args()
    address = ipaddress.IPv4Address(args.robot_ip)
    if not address.is_private or address.is_loopback or address.is_link_local or address.is_unspecified:
        parser.error('Robot must have a private LAN IPv4 address')
    logging.basicConfig(level=logging.CRITICAL)
    asyncio.run(serve(str(address)))
