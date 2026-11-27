#!/usr/bin/env python3
"""Real phone transport against an explicitly local protocol fixture, not hardware.

Requires a separately running go2_peer_fixture.py on the phone's OWN wlan0 IP
and the phone runtime in real mode with that exact IP. No network discovery.
Does not write any fixture secrets to output. Leaves the test service disconnected.
"""
import argparse
import json
import shlex
import struct
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--signaling', choices=('v3', 'legacy'), default='v3')
args = parser.parse_args()
adb = [args.adb, '-s', args.serial]
checks = []


def shell(command):
    return subprocess.check_output(adb + ['shell', shlex.join(command)])


def request(path, body=None, port=18845, binary=False, headers=(), expect=200):
    command = ['curl', '--noproxy', '*', '-sS', '--max-time', '15', '-w', '\n%{http_code}']
    data = None
    for header in headers: command += ['-H', header]
    if body is not None:
        data = json.dumps(body).encode()
        command += ['-H', 'Content-Type: application/json', '--data-binary', '@-']
    command += [f'http://127.0.0.1:{port}/' + path]
    result = subprocess.run(adb + ['shell', shlex.join(command)], input=data, capture_output=True, check=True)
    payload, status = result.stdout.rsplit(b'\n', 1)
    if int(status) != expect: raise AssertionError(f'{path}: HTTP {status.decode()} (expected {expect})')
    return payload if binary else json.loads(payload)


def check(name, ok):
    if not ok: raise AssertionError(name)
    checks.append(name); print(json.dumps({'check': name, 'passed': True}), flush=True)


def wait_state(predicate, timeout=50):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try: state = request('api/state')
        except (AssertionError, subprocess.CalledProcessError):
            time.sleep(.5); continue
        if predicate(state): return state
        time.sleep(.3)
    raise AssertionError('Timed out waiting for state')


runtime = request('api/phone')
fixture = request('status', port=18847)
addresses = shell(['ip', '-o', '-4', 'addr', 'show', 'wlan0']).decode().split()
own_ip = addresses[addresses.index('inet') + 1].split('/')[0]
check('fixture only: runtime target is this phone itself', runtime['mode'] == 'real' and runtime['robotIp'] == own_ip
      and fixture['fixture'] == 'go2-protocol-peer' and fixture['hardware'] is False)
check('no ADB forwarding or root required', not subprocess.check_output(adb + ['reverse', '--list']).strip()
      and shell(['id', '-u']).strip() == b'2000')
config = json.loads(shell(['run-as', 'com.termux', 'cat', 'files/home/taku-go2/rootfs/tmp/go2-peer-config.json']))
assert config['fixture'] == fixture['fixture'] and config['ip'] == own_ip
try:
    request('api/disconnect', {})
    check('LAN probe does not claim a robot connection', request('api/phone/network')['reachable']
          and not request('api/phone/network')['robotConnected'])
    request('api/phone/key', {'key': config['key']}, headers=['Origin: https://example.com'], expect=403)
    check('foreign-origin key write rejected', True)
    if args.signaling == 'v3':
        check('new fixture starts without a saved key', not runtime['keyConfigured'])
        request('api/connect', {})
        state = wait_state(lambda s: s['phase'] == 'error')
        check('encrypted firmware reports missing key', state['error'] == 'AesKeyRequiredError')
        request('api/phone/key', {'key': 'not-a-key'}, expect=400)
        check('invalid key is not saved', not request('api/phone')['keyConfigured'])
        request('api/phone/key', {'key': '0' * 32})
        request('api/connect', {})
        state = wait_state(lambda s: s['phase'] == 'error')
        check('wrong key reports decryption failure', state['error'] == 'AesKeyRejectedError')
        request('api/phone/key', {'key': config['key']})
    request('api/connect', {})
    state = wait_state(lambda s: s['connected'] and all(v['fresh'] for v in s['telemetry'].values()))
    check('real driver completes encrypted signaling and SCTP validation' if args.signaling == 'v3' else 'real driver completes legacy signaling and SCTP validation',
          request('status', port=18847)['validated'] and state['source'] == 'real')
    check('battery and posture parsed from protocol messages', state['telemetry']['rt/lf/lowstate']['values']['batteryPercent'] == 81
          and state['telemetry']['rt/lf/sportmodestate']['values']['bodyHeight'] == .32)
    counts = request('status', port=18847)['counts']
    check('connection does not send motion commands', not any(k.startswith('api:rt/api/sport/') for k in counts)
          and not state['motion']['armed'])
    check('AI owner blocks direct manual movement', request('api/motion/forward', {}, expect=409).get('error') == 'MANUAL_TAKEOVER_REQUIRED')
    takeover = request('api/assistant/mode', {'mode': 'manual'})
    check('manual takeover confirms normal mode', takeover['owner'] == 'manual' and takeover['robotMode'] == 'normal' and not takeover['error'])
    check('controls remain locked after takeover', request('api/motion/forward', {}).get('error') == 'LOCKED')
    request('api/phone/key', {'key': config['key']}, expect=409)
    check('cannot replace device key during a live session', True)
    check('key never appears in status API', config['key'] not in json.dumps(request('api/phone')) + json.dumps(state))
    camera = request('api/camera/start', {})
    check('video receiver is attached', not camera.get('requestError'))
    deadline = time.monotonic() + 15
    frames = set()
    while time.monotonic() < deadline and len(frames) < 3:
        packet = request('api/camera/frame', binary=True)
        size = struct.unpack('<I', packet[:4])[0]
        meta = json.loads(packet[4:4+size])
        if meta['fresh'] and packet[4+size:].startswith(b'\xff\xd8'): frames.add(meta['frameKey'])
        time.sleep(.3)
    check('RTP video is decoded into changing JPEG frames on Pixel', len(frames) >= 3)
    request('api/camera/stop', {})
    request('api/motion/arm', {})
    pulse = request('api/motion/forward', {})
    check('fixture receives bounded pulse and acknowledges stop', pulse.get('result') == 'stop_acknowledged' and pulse.get('durationMs') == 700)
    request('api/motion/stop', {})
    check('manual stop relocks controls', not request('api/state')['motion']['armed'])
    request('telemetry', {'enabled': False}, port=18847)
    time.sleep(5.5)
    state = request('api/state')
    check('stale telemetry is not shown as live or movement-ready', not state['motion']['ready']
          and not any(v['fresh'] for v in state['telemetry'].values()))
    request('telemetry', {'enabled': True}, port=18847)
    wait_state(lambda s: s['motion']['ready'])
    request('api/motion/arm', {})
    request('close', {}, port=18847)
    state = wait_state(lambda s: s['phase'] == 'error')
    check('peer disconnect revokes movement permission', not state['connected'] and not state['motion']['armed'])
    request('api/connect', {})
    state = wait_state(lambda s: s['connected'])
    check('reconnect requires fresh motion confirmation', not state['motion']['armed'])
    request('api/disconnect', {})
    request('busy', {'enabled': True}, port=18847)
    request('api/connect', {})
    state = wait_state(lambda s: s['phase'] == 'error')
    check('occupied robot has an actionable error', state['error'] == 'RobotBusyError')
    print(json.dumps({'passed': len(checks), 'scope': 'on-phone-protocol-fixture', 'signaling': args.signaling,
                      'realRobotConnected': False, 'crossDeviceHotspotVerified': False}), flush=True)
finally:
    request('busy', {'enabled': False}, port=18847)
    request('telemetry', {'enabled': True}, port=18847)
    request('api/disconnect', {})
