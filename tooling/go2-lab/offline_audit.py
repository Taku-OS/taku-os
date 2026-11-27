#!/usr/bin/env python3
"""Read-only readiness probes, fake transport only. Gaps are NOT passing features.

Run with the Go2 virtualenv Python; stdout is a JSON report. This is a transport
fixture, not a physics simulator or a replica of Unitree's avoidance algorithm.
"""
import asyncio
import json
from pathlib import Path
import statistics
import sys
import time
from types import SimpleNamespace

def deny_network(event, args):
    if event in {'socket.connect', 'socket.sendto', 'socket.getaddrinfo'}:
        raise RuntimeError('OFFLINE_AUDIT_NETWORK_FORBIDDEN')

sys.addaudithook(deny_network)
from bridge import Bridge
from motion import Motion
from protocol import permitted, telemetry
from lidar import point_cloud, encode_frame
import numpy as np

results = []
def record(name, passed, evidence):
    results.append({'name': name, 'status': 'pass' if passed else 'gap', 'evidence': evidence})

def fake_bridge(reply):
    bridge = Bridge('OFFLINE-NO-ROBOT')
    async def request(*args): return await reply(*args)
    bridge.conn = SimpleNamespace(isConnected=True, datachannel=SimpleNamespace(
        data_channel_opened=True, pub_sub=SimpleNamespace(publish_request_new=request)))
    return bridge

async def main():
    # No imports of the real driver, no session/connect/startup call.
    try:
        import socket
        socket.getaddrinfo('127.0.0.1', 18845)
        blocked = False
    except RuntimeError:
        blocked = True
    record('network_guard', blocked, 'DNS and outbound socket operations denied by audit hook')

    for label, response in [('success', {'data': {'header': {'status': {'code': 0}}}}),
                            ('rejected', {'data': {'header': {'status': {'code': 7}}}}),
                            ('missing_code', {'data': {}})]:
        async def reply(*args): return response
        bridge = fake_bridge(reply)
        result = await bridge.motion.stop()
        expected = 'stop_acknowledged' if label == 'success' else 'stop_unconfirmed'
        record('stop_' + label, result['result'] == expected and not result['armed'],
               {'result': result['result'], 'armed': result['armed']})

    async def never_reply(*args): await asyncio.Event().wait()
    bridge = fake_bridge(never_reply)
    start = time.monotonic()
    result = await bridge.motion.stop()
    record('stop_timeout', result['error'] == 'STOP_UNCONFIRMED' and not result['armed'],
           {'error': result['error'], 'elapsedMs': round((time.monotonic()-start)*1000)})

    async def mode_reply(*args):
        return {'data': {'header': {'status': {'code': 0}}, 'data': '{"name":"normal"}'}}
    bridge = fake_bridge(mode_reply)
    await bridge.motion_mode()
    bridge.conn.datachannel.pub_sub.publish_request_new = never_reply
    value = await bridge.motion_mode()
    record('mode_timeout_clears_old_mode', bridge.current_motion_mode is None,
           {'currentMode': bridge.current_motion_mode, 'queryResult': value})

    sample = telemetry('rt/lf/sportmodestate', {'position': [1,2,0],
        'imu_state': {'quaternion': [1,0,0,0], 'rpy': [0,0,0]},
        'range_obstacle': [1,2,3,4], 'velocity': [0,0,0], 'body_height': .32, 'mode': 1})
    record('pose_preserved_by_current_parser', 'position' in sample and 'imu_state' in sample,
           {'outputFields': sorted(sample), 'finding': 'No pose/frame transform pipeline implemented'})

    bridge = fake_bridge(mode_reply)
    bridge.phase, bridge.current_motion_mode = 'connected', 'normal'
    bridge.samples['rt/lf/sportmodestate'] = {'received': time.monotonic(),
        'values': {'bodyHeight': .32}, 'count': 1}
    record('lidar_required_for_motion', not bridge.motion_ready(),
           {'lidarEnabled': bridge.lidar_enabled, 'hasCloud': bridge.lidar_sample is not None,
            'motionReady': bridge.motion_ready(), 'scope': 'Missing requirement for future protected mode'})
    denied = []
    for message in [
        {'type':'subscribe','topic':'rt/utlidar/robot_pose'},
        {'type':'req','topic':'rt/api/obstacles_avoid/request','data':{
            'header':{'identity':{'id':1,'api_id':1002}},'parameter':'{}'}},
        {'type':'msg','topic':'rt/wirelesscontroller','data':{'lx':0,'ly':0,'rx':0,'ry':0,'keys':0}},
    ]:
        denied.append(not (permitted(message) or bridge.motion.allows(message)))
    record('unimplemented_routes_remain_blocked', all(denied),
           {'blocked': denied, 'meaning': 'Native avoidance and robot_pose are not integrated'})

    ready = True
    events = []
    async def stop(): events.append(('stop', time.monotonic()))
    def move(vector):
        nonlocal ready
        events.append(('move', time.monotonic()))
        ready = False  # Status ceases to be usable immediately after send.
    motion = Motion(lambda: ready, move, stop)
    await motion.command('arm')
    events.clear()
    await motion.command('forward')
    elapsed = events[-1][1] - next(t for name,t in events if name == 'move')
    record('state_loss_interrupts_active_pulse', elapsed < .20,
           {'timeUntilFinalStopMs': round(elapsed*1000),
            'finding': 'Checks before send; no continuous readiness monitor inside the pulse'})

    # CPU-only synthetic processing, not sensor/WebRTC/Android or avoidance timing.
    rng = np.random.default_rng(26)
    points = rng.uniform(-10, 10, (64000, 3))
    durations = []
    for _ in range(35):
        started = time.perf_counter()
        cloud = point_cloud({'data': {'points': points}, 'frame_id': 'odom'})
        raw = cloud.pop('pointBytes')
        packet = encode_frame({'values': cloud}, raw)
        durations.append((time.perf_counter()-started)*1000)
    record('synthetic_full_cloud_encoding', len(raw) == 64000*12 and len(packet) > len(raw),
           {'points': 64000, 'runs': len(durations), 'medianMs': round(statistics.median(durations),3),
            'maxMs': round(max(durations),3), 'scope': 'Filtering + bounds + Float32 + encoding only'})
    print(json.dumps({'kind':'offline-readiness-audit', 'robotConnected':False,
                      'results':results}, indent=2))

if __name__ == '__main__': asyncio.run(main())
