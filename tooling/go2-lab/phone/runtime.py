#!/usr/bin/env python3
"""Phone-owned bridge lifecycle. No connection or movement on startup."""
import argparse
import asyncio
import fcntl
import ipaddress
import json
import logging
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import threading
from http.server import ThreadingHTTPServer

SOURCE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SOURCE))
from bridge import Bridge, PORT, handler_class
from connection import DeviceKey, diagnose
from assistant import Assistant


def robot_address(value):
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError as error:
        raise argparse.ArgumentTypeError('A private LAN IPv4 address is required') from error
    if not any(address in ipaddress.IPv4Network(network) for network in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')):
        raise argparse.ArgumentTypeError('A private LAN IPv4 address is required')
    return str(address)


async def serve(mode, robot_ip, *, external_motion_control=None):
    key_store = DeviceKey(SOURCE.parents[1] / 'out/go2-lab/device-keys', robot_ip) if mode == 'real' else None
    if mode == 'simulation':
        from simulator import SimBridge
        bridge = SimBridge()
    else:
        bridge = Bridge(robot_ip, key_store.read())
    # This Pixel deployment leaves locomotion to the companion controller.
    # Simulation can opt into the same policy for offline HTTP verification.
    bridge.external_motion_control = mode == 'real' if external_motion_control is None else external_motion_control
    if mode == 'real':
        bridge.install_transport_guard()
    loop = asyncio.get_running_loop()
    assistant = Assistant(bridge)
    assistant_monitor = asyncio.create_task(assistant.monitor())
    stopped = asyncio.Event()
    base = handler_class(bridge, loop)

    class Handler(base):
        def navigator_post(self, action):
            if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
            future = None
            try:
                length = int(self.headers.get('Content-Length', '-1'))
                if self.headers.get('Content-Type') != 'application/json' or self.headers.get('Transfer-Encoding') or not 2 <= length <= 2048:
                    raise ValueError('INVALID_REQUEST')
                self.connection.settimeout(3)
                value = json.loads(self.rfile.read(length))
                if not isinstance(value, dict): raise ValueError('INVALID_REQUEST')
                async def dispatch():
                    nav = assistant.navigator
                    if action == 'phone-fix': return nav.phone_fix(value)
                    if action == 'observe': return nav.observe(value)
                    if action == 'map': return await assistant.maps.call(value)
                    if action == 'calibrate':
                        if value.get('source') != 'surveyed': raise ValueError('INVALID_ANCHOR')
                        return nav.calibrate(value)
                    if action == 'phone-anchor':
                        if not isinstance(value, dict) or set(value) != {'heading', 'headingAccuracy', 'floorZ', 'colocated'}:
                            raise ValueError('INVALID_ANCHOR')
                        phone = nav.phone_view()
                        import time
                        fix_received = time.monotonic() - phone.get('ageSeconds', 1e9)
                        if value['colocated'] is not True or not phone['valid'] or phone['accuracyMeters'] > 5:
                            raise ValueError('PHONE_COLOCATION_REQUIRED')
                        # Capture a fresh odometry pose on both sides of the network conversion.
                        before = nav.pose()['position'][:2]
                        from map_tools import request
                        converted = await asyncio.to_thread(request, '/convert', {'location': ','.join(map(str, phone['position'])), 'coordinateSystem': 'WGS84'})
                        import math
                        if math.dist(before, nav.pose()['position'][:2]) > .1 or not nav.phone_view()['valid'] or time.monotonic()-fix_received > 10:
                            raise ValueError('ANCHOR_MOVED')
                        lon, lat = map(float, converted['location'].split(','))
                        return nav.calibrate({**value, 'longitude': lon, 'latitude': lat, 'accuracy': phone['accuracyMeters'], 'source': 'phone-colocated'})
                    if value != {}: raise ValueError('INVALID_REQUEST')
                    if action == 'prepare': return await assistant.maps.prepare()
                    if action == 'sensors':
                        # Subscription only: do not connect, change gait, arm or issue StopMove.
                        if bridge.phase != 'connected': raise ValueError('NOT_CONNECTED')
                        await bridge.scan(True)
                        result = await bridge.camera.command('start')
                        if result.get('requestError'): raise ValueError(result['requestError'])
                        return nav.view()
                    if action == 'clear':
                        nav.reference = nav.route = nav.route_info = None
                        return nav.view()
                    raise ValueError('INVALID_REQUEST')
                future = asyncio.run_coroutine_threadsafe(dispatch(), loop)
                return self.reply(200, future.result(timeout=85))
            except (ValueError, KeyError, TypeError, UnicodeError) as e:
                import re
                code = str(e)
                return self.reply(409, {'error': code if re.fullmatch(r'[A-Z_0-9]{1,80}', code) else 'INVALID_REQUEST'})
            except TimeoutError:
                if future: future.cancel()
                return self.reply(503, {'error': 'BRIDGE_BUSY'})

        def follow_post(self, action):
            if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
            future = None
            try:
                length = int(self.headers.get('Content-Length', '-1'))
                if (self.headers.get('Content-Type') != 'application/json' or self.headers.get('Transfer-Encoding')
                        or not 2 <= length <= 2048): raise ValueError('INVALID_REQUEST')
                self.connection.settimeout(3)
                value = json.loads(self.rfile.read(length))
                fields = {'start': {'requestId'}, 'observe': {'token', 'frameKey', 'target'}, 'stop': set()}
                if action not in fields or not isinstance(value, dict) or (set(value) != fields[action] and not (action == 'stop' and set(value) == {'token'})):
                    raise ValueError('INVALID_REQUEST')
                async def dispatch():
                    if action == 'start': return await assistant.follow.start(value)
                    if action == 'observe': return assistant.follow.observe(value)
                    if 'token' in value and value['token'] != assistant.follow.token: return assistant.follow.view()
                    return await assistant.follow.stop()
                future = asyncio.run_coroutine_threadsafe(dispatch(), loop)
                return self.reply(200, future.result(timeout=5))
            except (ValueError, UnicodeError) as error:
                code = str(error)
                allowed = {'INVALID_REQUEST', 'FOLLOW_REQUEST_EXPIRED', 'FOLLOW_EXPIRED', 'INVALID_OBSERVATION',
                           'STALE_OBSERVATION', 'NOT_CONNECTED', 'CAMERA_NOT_FRESH', 'CONTROL_BUSY', 'OBSERVATION_ONLY'}
                return self.reply(409, {'error': code if code in allowed else 'INVALID_REQUEST'})
            except TimeoutError:
                if future: future.cancel()
                return self.reply(503, {'error': 'BRIDGE_BUSY'})

        def manual_post(self, action):
            if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
            future = None
            try:
                length = int(self.headers.get('Content-Length', '-1'))
                if (self.headers.get('Content-Type') != 'application/json' or self.headers.get('Transfer-Encoding')
                        or not 2 <= length <= 128): raise ValueError('INVALID_REQUEST')
                self.connection.settimeout(3)
                value = json.loads(self.rfile.read(length))
                from motion import valid_speeds
                if action == 'speed':
                    if not valid_speeds(value): raise ValueError('INVALID_SPEED')
                elif value != {} or action not in {'arm', 'forward', 'backward', 'left', 'right', 'normal'}:
                    raise ValueError('INVALID_REQUEST')
                async def dispatch():
                    # Check on the execution loop, after queued takeover requests.
                    if assistant.observation_only: raise ValueError('OBSERVATION_ONLY')
                    if assistant.owner != 'manual' or assistant.taking_over:
                        raise ValueError('MANUAL_TAKEOVER_REQUIRED')
                    if action == 'speed': return await bridge.motion_speed(value)
                    if action == 'normal': return await bridge.normal_mode()
                    return await bridge.motion_command(action)
                future = asyncio.run_coroutine_threadsafe(dispatch(), loop)
                result = future.result(timeout=15)
                return self.reply(409 if result.get('requestError') else 200, result)
            except (ValueError, UnicodeError) as error:
                return self.reply(409, {'error': str(error) if str(error) in {'INVALID_SPEED', 'MANUAL_TAKEOVER_REQUIRED'} else 'INVALID_REQUEST'})
            except TimeoutError:
                if future: future.cancel()
                return self.reply(503, {'error': 'BRIDGE_BUSY'})

        def assistant_post(self, action):
            if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
            try:
                length = int(self.headers.get('Content-Length', '-1'))
                if (self.headers.get('Content-Type') != 'application/json'
                        or self.headers.get('Transfer-Encoding') or not 2 <= length <= 4096):
                    raise ValueError('INVALID_REQUEST')
                self.connection.settimeout(5)
                value = json.loads(self.rfile.read(length))
                if not isinstance(value, dict): raise ValueError('INVALID_REQUEST')
                fields = {'observe': set(), 'start': set(), 'heartbeat': {'id', 'token'}, 'plan': {'id', 'token', 'plan'},
                          'confirm': {'id', 'token'}, 'cancel': set(), 'mode': {'mode'}, 'stop': set(), 'legacy-stop': set()}
                if action not in fields or set(value) != fields[action]: raise ValueError('INVALID_REQUEST')
                async def dispatch():
                    if action == 'observe': return await assistant.observe()
                    if action == 'start': return assistant.begin()
                    if action == 'heartbeat': return assistant.heartbeat(value)
                    if action == 'plan': return assistant.prepare(value)
                    if action == 'confirm': return assistant.confirm(value)
                    if action == 'mode': return await assistant.set_mode(value['mode'])
                    result = await assistant.cancel('STOP_REQUESTED' if action.endswith('stop') else 'CANCELED', stop=True)
                    return bridge.motion.snapshot() if action == 'legacy-stop' else result
                return self.reply(200, asyncio.run_coroutine_threadsafe(dispatch(), loop).result(timeout=15))
            except (ValueError, UnicodeError) as error:
                code = str(error)
                if code not in {'INVALID_REQUEST', 'INVALID_PLAN', 'TASK_EXPIRED', 'TASK_ALREADY_PLANNED',
                                'CONFIRMATION_NOT_EXPECTED', 'MANUAL_CONTROL', 'TASK_BUSY', 'INVALID_MODE', 'CONTROL_BUSY', 'OBSERVATION_ONLY', 'NAVIGATION_OBSERVATION_ONLY'}:
                    code = 'INVALID_REQUEST'
                return self.reply(409, {'error': code})
            except TimeoutError: return self.reply(503, {'error': 'BRIDGE_BUSY'})

        def do_GET(self):
            if self.path == '/api/navigator/state':
                if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
                async def state(): return assistant.navigator.view()
                try: return self.reply(200, asyncio.run_coroutine_threadsafe(state(), loop).result(timeout=3))
                except TimeoutError: return self.reply(503, {'error': 'BRIDGE_BUSY'})
            if self.path in {'/navigator', '/navigator.js', '/navigator.css'}:
                if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
                extension = 'html' if self.path == '/navigator' else self.path.rsplit('.', 1)[-1]
                mime = {'html': 'text/html', 'js': 'text/javascript', 'css': 'text/css'}[extension]
                return self.reply(200, (SOURCE / ('web/navigator.' + extension)).read_bytes(), mime + '; charset=utf-8')
            if self.path == '/api/follow/state':
                if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
                async def state(): return assistant.follow.view()
                try: return self.reply(200, asyncio.run_coroutine_threadsafe(state(), loop).result(timeout=3))
                except TimeoutError: return self.reply(503, {'error': 'BRIDGE_BUSY'})
            if self.path == '/api/assistant/state':
                if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
                async def state(): return assistant.view()
                try: return self.reply(200, asyncio.run_coroutine_threadsafe(state(), loop).result(timeout=3))
                except TimeoutError: return self.reply(503, {'error': 'BRIDGE_BUSY'})
            if self.path in {'/', '/phone.js'}:
                if not self.allowed():
                    return self.reply(403, {'error': 'LOCAL_ONLY'})
                if self.path == '/':
                    html = (SOURCE / 'web/index.html').read_text().replace('</head>', '<script src="/phone.js" defer></script></head>')
                    return self.reply(200, html.encode(), 'text/html; charset=utf-8')
                return self.reply(200, (SOURCE / 'phone/ui.js').read_bytes(), 'text/javascript; charset=utf-8')
            if self.path == '/api/phone':
                if not self.allowed():
                    return self.reply(403, {'error': 'LOCAL_ONLY'})
                return self.reply(200, {'runtime': 'pixel-local', 'mode': mode,
                    'robotIp': robot_ip, 'architecture': platform.machine(),
                    'python': platform.python_version(), 'pid': os.getpid(),
                    'keyConfigured': bool(bridge.aes_128_key), 'macRequired': False, 'version': 6})
            if self.path == '/api/phone/network':
                if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
                if mode != 'real': return self.reply(409, {'error': 'REAL_MODE_REQUIRED'})
                return self.reply(200, diagnose(robot_ip))
            super().do_GET()

        def do_POST(self):
            if self.path.startswith('/api/navigator/'):
                return self.navigator_post(self.path.rsplit('/', 1)[-1])
            if self.path.startswith('/api/follow/'):
                return self.follow_post(self.path.rsplit('/', 1)[1])
            # Other motion families must not run alongside visual following.
            if assistant.follow.active and self.path.startswith(('/api/navigation/', '/api/avoidance/', '/api/replay/', '/api/simulation/')):
                return self.reply(409, {'error': 'CONTROL_BUSY'})
            if assistant.observation_only and not self.path.startswith('/api/assistant/') and self.path not in {
                    '/api/connect', '/api/disconnect', '/api/camera/start', '/api/camera/stop', '/api/phone/shutdown', '/api/phone/key',
                    '/api/motion/stop',
                    '/api/audio/select', '/api/audio/refresh', '/api/audio/play', '/api/audio/stop', '/api/audio/volume', '/api/audio/prepare'}:
                return self.reply(409, {'error': 'OBSERVATION_ONLY'})
            if self.path.startswith('/api/assistant/'):
                return self.assistant_post(self.path.rsplit('/', 1)[1])
            if self.path == '/api/motion/stop': return self.assistant_post('legacy-stop')
            if self.path.startswith('/api/motion/'):
                return self.manual_post(self.path.rsplit('/', 1)[1])
            if self.path == '/api/phone/key':
                if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
                if mode != 'real' or bridge.phase not in {'disconnected', 'error'}:
                    return self.reply(409, {'error': 'DISCONNECT_BEFORE_CONFIGURING_KEY'})
                try:
                    length = int(self.headers.get('Content-Length', '-1'))
                    if self.headers.get('Content-Type') != 'application/json' or self.headers.get('Transfer-Encoding') or not 2 <= length <= 128:
                        raise ValueError()
                    self.connection.settimeout(3)
                    value = json.loads(self.rfile.read(length))
                    if not isinstance(value, dict) or set(value) != {'key'}: raise ValueError()
                    key_store.save(value['key'])
                    bridge.aes_128_key = key_store.read()
                except (ValueError, OSError):
                    return self.reply(400, {'error': 'DEVICE_KEY_NOT_SAVED'})
                return self.reply(200, {'configured': True})
            if self.path != '/api/phone/shutdown':
                return super().do_POST()
            if not self.allowed():
                return self.reply(403, {'error': 'LOCAL_ONLY'})
            if self.headers.get('Content-Type') != 'application/json' or self.headers.get('Content-Length') != '2' or self.headers.get('Transfer-Encoding'):
                return self.reply(400, {'error': 'INVALID_REQUEST'})
            self.connection.settimeout(3)
            if self.rfile.read(2) != b'{}':
                return self.reply(400, {'error': 'INVALID_REQUEST'})
            self.reply(200, {'stopping': True})
            loop.call_soon_threadsafe(stopped.set)

    server = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    bridge.monitor_task = asyncio.create_task(bridge.monitor())
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stopped.set)
    print(json.dumps({'runtime': 'pixel-local', 'mode': mode, 'ready': True}), flush=True)
    try:
        await stopped.wait()
    finally:
        try:
            await assistant.cancel('SERVICE_STOPPED')
            assistant_monitor.cancel()
            await asyncio.wait_for(bridge.command('disconnect'), timeout=12)
        finally:
            bridge.monitor_task.cancel()
            await asyncio.to_thread(bridge.recorder.stop)
            server.shutdown()
            server.server_close()
            if hasattr(bridge, 'http'):
                bridge.http.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('simulation', 'real'))
    parser.add_argument('--robot-ip', type=robot_address)
    args = parser.parse_args()
    if (args.mode == 'real') != bool(args.robot_ip):
        parser.error('Only real mode requires --robot-ip')
    # The OS releases this lock on any process exit, including a killed app.
    state_dir = SOURCE.parents[1] / 'out/go2-lab'
    state_dir.mkdir(parents=True, exist_ok=True)
    with open(state_dir / '.phone-runtime.lock', 'w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit('PHONE_RUNTIME_ALREADY_RUNNING')
        maps = subprocess.Popen([sys.executable, '-u', str(SOURCE / 'amap_service.py')])
        try:
            if args.mode == 'simulation':
                def deny_outbound(event, _args):
                    if event in {'socket.connect', 'socket.sendto', 'socket.getaddrinfo'}:
                        raise RuntimeError('SIMULATOR_OUTBOUND_NETWORK_FORBIDDEN')
                sys.addaudithook(deny_outbound)
            logging.basicConfig(level=logging.CRITICAL)
            logging.getLogger('aiortc.rtcrtpsender').setLevel(logging.WARNING)
            asyncio.run(serve(args.mode, args.robot_ip))
        finally:
            maps.terminate()
            try:
                maps.wait(timeout=3)
            except subprocess.TimeoutExpired:
                maps.kill()
                maps.wait()


if __name__ == '__main__':
    main()
