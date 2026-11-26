#!/usr/bin/env python3
"""Exercise the installed phone service over phone loopback, never a Mac tunnel.

All actuation checks require source=simulation. --real-check never connects.
"""
import argparse
import atexit
import io
import json
import shlex
import struct
import subprocess
import time
import wave

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--real-check', action='store_true')
parser.add_argument('--basic-only', action='store_true', help='Skip the known-unverified on-phone autonomous navigation execution')
args = parser.parse_args()
adb = [args.adb, '-s', args.serial]
checks = []


def request(path, body=None, binary=False, mime='application/json', port=18845, headers=()):
    command = ['curl', '--noproxy', '*', '-sS', '--fail-with-body', '--max-time', '35']
    for header in headers:
        command += ['-H', header]
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        command += ['-X', 'POST', '-H', 'Content-Type: ' + mime, '--data-binary', '@-']
    command += [f'http://127.0.0.1:{port}/' + path]
    result = subprocess.run(adb + ['shell', shlex.join(command)], input=data, capture_output=True)
    if result.returncode:
        # Retain the server's reason (including rejected navigation targets).
        raise subprocess.CalledProcessError(result.returncode, command, output=result.stdout, stderr=result.stderr)
    return result.stdout if binary else json.loads(result.stdout)


def check(name, condition):
    if not condition:
        raise AssertionError(name)
    checks.append(name)
    print(json.dumps({'check': name, 'passed': True}), flush=True)


runtime = request('api/phone')
state = request('api/state')
check('phone-owned ARM64 runtime', runtime['runtime'] == 'pixel-local' and runtime['architecture'] == 'aarch64' and not runtime['macRequired'])
check('no ADB reverse', not subprocess.check_output(adb + ['reverse', '--list']).strip())
check('no automatic robot connection', not state['connected'] and state['phase'] == 'disconnected')
check('map service available', request('status', port=18846)['provider'] == 'amap')
if args.real_check:
    check('real transport loaded without connecting', state['source'] == 'real' and runtime['mode'] == 'real')
    nav = request('api/navigation')
    check('real autonomous navigation remains blocked', 'REAL_NAVIGATION_DISABLED' in json.dumps(nav))
else:
    check('simulation required before any action', state['source'] == 'simulation' and runtime['mode'] == 'simulation')
    def cleanup():
        try:
            request('api/disconnect', {})
        except Exception:
            pass
    atexit.register(cleanup)
    try:
        request('api/phone/shutdown', {}, headers=('Origin: https://example.com',))
        raise AssertionError('foreign origin accepted')
    except subprocess.CalledProcessError as error:
        check('foreign origin rejected', b'LOCAL_ONLY' in error.stdout)
    html = request('', binary=True).decode()
    check('phone labels loaded after base scripts', '<script src="/phone.js" defer>' in html)
    state = request('api/connect', {})
    deadline = time.monotonic() + 5
    while not state['connected'] and time.monotonic() < deadline:
        time.sleep(.2)
        state = request('api/state')
    check('simulator connects', state['connected'] and state['source'] == 'simulation')
    request('api/lidar/start', {})
    time.sleep(.3)
    lidar = request('api/lidar')
    check('fresh simulated point cloud', lidar['fresh'] and lidar['values'] is not None)
    request('api/camera/start', {})
    time.sleep(.8)
    packet = request('api/camera/frame', binary=True)
    length = struct.unpack('<I', packet[:4])[0]
    camera = json.loads(packet[4:4+length])
    jpeg = packet[4+length:]
    check('on-phone simulated JPEG frames', camera['fresh'] and camera['source'] == 'simulation' and jpeg.startswith(b'\xff\xd8'))
    try:
        request('api/motion/forward', {})
        raise AssertionError('manual movement bypassed AI owner')
    except subprocess.CalledProcessError as error:
        check('manual movement requires explicit takeover', b'MANUAL_TAKEOVER_REQUIRED' in error.stdout)
    takeover = request('api/assistant/mode', {'mode': 'manual'})
    check('manual owner and mode confirmed', takeover['owner'] == 'manual' and takeover['robotMode'] == 'normal' and not takeover['error'])
    locked = request('api/motion/forward', {})
    check('motion locked before arming', locked.get('error') == 'LOCKED')
    request('api/motion/arm', {})
    motion = request('api/motion/forward', {})
    check('simulated 700 ms pulse ends with stop acknowledgment', motion.get('result') == 'stop_acknowledged' and motion.get('durationMs') == 700)
    stopped = request('api/motion/stop', {})
    check('manual control relocked', not stopped['armed'] and not stopped['busy'])
    recording = io.BytesIO()
    with wave.open(recording, 'wb') as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(16000); wav.writeframes(b'\0' * 6400)
    audio = request('api/audio/import?name=pixel-offline-test.wav', recording.getvalue(), mime='application/octet-stream')
    check('audio import and decode on phone', audio.get('clip') is not None and not audio.get('requestError'))
    nav = request('api/navigation/prepare', {})
    check('simulated navigation map prepared', nav.get('mapId') is not None and not nav.get('requestError'))
    if args.basic_only:
        request('api/disconnect', {})
        check('disconnect clears live state', not request('api/state')['connected'])
        print(json.dumps({'passed':len(checks), 'scope':'basic-only', 'realRobotConnected':False,
                          'autonomousNavigationVerified':False, 'runtime':runtime}))
        raise SystemExit(0)
    deadline = time.monotonic() + 5
    while nav.get('readiness') and time.monotonic() < deadline:
        time.sleep(.2)
        nav = request('api/navigation')
    check('navigation sensors ready', nav.get('readiness') is None)
    request('api/navigation/goal', {'mapId': nav['mapId'], 'name': 'Pixel offline check', 'x': 1.7, 'y': 1.3})
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        nav = request('api/navigation')
        if not nav['active']:
            break
        time.sleep(.5)
    check('simulated goal reached and stopped: ' + str(nav.get('reason')), nav['state'] == 'arrived' and nav['stopConfirmed'] and not nav['active'])
    request('api/disconnect', {})
    check('disconnect clears live state', not request('api/state')['connected'])
print(json.dumps({'passed': len(checks), 'runtime': runtime, 'realRobotConnected': False}, ensure_ascii=False))
