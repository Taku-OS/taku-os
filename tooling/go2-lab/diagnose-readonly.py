#!/usr/bin/env python3
"""Offline plan by default. --connect performs bounded, strictly read-only diagnostics.

Close other robot controllers first. This tool never enables radar/avoidance,
changes mode, takes command ownership, starts media, or sends movement/stop.
"""
import argparse
import asyncio
from collections import Counter
from datetime import datetime, timezone
import ipaddress
import json
import logging
from pathlib import Path
import re
import secrets
import time

from diagnostic_protocol import READS, TOPICS, ReadOnlyRPC, allowed, switch_value, uwb_switch_value


def safe(value, depth=0):
    if depth > 8:
        return '[depth-limited]'
    if isinstance(value, dict):
        return {str(k): safe(v, depth+1) for k, v in list(value.items())[:100]
                if not re.search(r'token|password|passwd|secret|credential|serial|sn$|key$', str(k), re.I)}
    if isinstance(value, list):
        return [safe(v, depth+1) for v in value[:100]]
    if isinstance(value, str):
        # Parse nested JSON to apply the same credential filtering.
        try:
            decoded = json.loads(value)
            if isinstance(decoded, (dict, list)):
                return safe(decoded, depth+1)
        except ValueError:
            pass
        return value[:8192]
    return value if value is None or type(value) in (int, float, bool) else '[non-json]'


async def run(args, emit):
    from aiortc import RTCDataChannel
    from unitree_webrtc_connect import UnitreeWebRTCConnection, WebRTCConnectionMethod, unitree_auth

    counts, received, blocked = Counter(), {}, []
    original_send, original_post = RTCDataChannel.send, unitree_auth.requests.post
    http = unitree_auth.requests.Session()
    http.trust_env = False

    def guarded_send(channel, raw):
        try:
            message = json.loads(raw) if isinstance(raw, str) else None
        except ValueError:
            message = None
        if not allowed(message):
            blocked.append('MESSAGE_NOT_ALLOWED')
            raise RuntimeError('MESSAGE_NOT_ALLOWED')
        counts[message['type'] + ':' + message.get('topic', '')] += 1
        return original_send(channel, raw)

    def local_post(url, data=None, headers=None):
        if not re.fullmatch(re.escape('http://' + args.robot_ip)
                            + r'(?::9991/con_(?:notify|ing_[0-9]+)|:8081/offer)', url):
            raise RuntimeError('NON_LOCAL_SIGNALING_BLOCKED')
        return http.post(url, data=data, headers=headers, timeout=6)

    RTCDataChannel.send, unitree_auth.requests.post = guarded_send, local_post
    conn = UnitreeWebRTCConnection(WebRTCConnectionMethod.LocalSTA, ip=args.robot_ip)
    rpc = None
    try:
        await asyncio.wait_for(conn.connect(), 40)
        rpc = ReadOnlyRPC(conn.datachannel.channel.send, emit, secrets.randbelow(1000000000)+1)

        def receive(raw):
            now = time.monotonic()
            if not isinstance(raw, str):
                return  # No point-cloud subscription; binary frames are out of scope.
            try:
                message = json.loads(raw)
            except ValueError:
                return
            if not isinstance(message, dict):
                return
            rpc.receive(message)
            topic = message.get('topic')
            if isinstance(topic, str) and topic in TOPICS and message.get('type') == 'msg':
                item = received.setdefault(topic, {'count': 0, 'first': now})
                item.update(count=item['count']+1, last=now)
                emit('telemetry', {'topic': topic, 'rxMonotonic': now, 'data': message.get('data')})

        conn.datachannel.channel.on('message', receive)
        topics = TOPICS if args.follow else TOPICS - {'rt/uwbstate'}
        for topic in sorted(topics):
            conn.datachannel.pub_sub.subscribe(topic)
        before = await rpc.query('motion_switcher', 1001)
        switches = []
        for _ in range(3):
            switches.append(switch_value(await rpc.query('obstacles_avoid', 1002)))
            await asyncio.sleep(.3)
        follow_before = None
        if args.follow:
            follow_before = await rpc.query('uwbswitch', 1002)
            await rpc.query('uwbswitch', 1003)
        if args.extended:
            for service, api in READS:
                if (api == 1 or service == 'robot_state') and (service != 'uwbswitch' or args.follow):
                    await rpc.query(service, api)
        await asyncio.sleep(args.seconds)
        follow_after = None
        if args.follow:
            follow_after = await rpc.query('uwbswitch', 1002)
            await rpc.query('uwbswitch', 1003)
        after = await rpc.query('motion_switcher', 1001)
        emit('summary', {
            'switchReadbacks': switches,
            'interpretation': 'This is only the obstacles_avoid service flag; not a functional safety test.',
            'modeBefore': before, 'modeAfter': after,
            'follow': {'requested': args.follow, 'switchBefore': uwb_switch_value(follow_before),
                       'switchAfter': uwb_switch_value(follow_after),
                       'trackingInterpretation': 'Raw API 1003 result only; schema and firmware compatibility unverified.'},
            'telemetry': {t: {'count': received.get(t, {}).get('count', 0),
                             'lastAgeMs': round((time.monotonic()-received[t]['last'])*1000)
                             if t in received else None} for t in sorted(topics)},
            'outgoing': dict(counts), 'blockedCount': len(blocked),
            'actuationAllowed': False, 'source': 'real-read-only',
        })
    finally:
        try:
            await asyncio.wait_for(conn.disconnect(), 5)
        finally:
            RTCDataChannel.send, unitree_auth.requests.post = original_send, original_post
            http.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--connect', action='store_true', help='Explicitly opt in to a real read-only session')
    parser.add_argument('--robot-ip')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--extended', action='store_true', help='Also query service API versions and service list')
    parser.add_argument('--follow', action='store_true', help='Read UWB follow switch/tracking status; never enable following')
    parser.add_argument('--seconds', type=int, default=10)
    args = parser.parse_args()
    if not args.connect:
        print(json.dumps({'networkAccess': False, 'follow': args.follow, 'extended': args.extended, 'reads': [
            {'service': s, 'api': a, 'purpose': n} for (s, a), n in READS.items()
            if (s != 'uwbswitch' or args.follow) and (args.extended or (a != 1 and s != 'robot_state'))],
            'subscriptions': sorted(TOPICS if args.follow else TOPICS - {'rt/uwbstate'}), 'writes': [],
            'next': 'Use --connect --robot-ip VERIFIED_IP --output NEW_LOG only after robot is available.'}, indent=2))
        return
    if not args.robot_ip or not args.output or not 1 <= args.seconds <= 30:
        parser.error('--connect requires --robot-ip, --output and seconds in 1..30')
    try:
        address = ipaddress.IPv4Address(args.robot_ip)
    except ValueError:
        parser.error('Expected a verified LAN IPv4 address')
    if not address.is_private or address.is_loopback or address.is_link_local or address.is_unspecified or address.is_multicast:
        parser.error('Expected a verified private LAN IPv4 address')
    args.robot_ip = str(address)
    logging.basicConfig(level=logging.CRITICAL)
    # Refuse to overwrite prior evidence; no driver import or connection before this succeeds.
    with args.output.open('x', encoding='utf8') as log:
        def emit(event, value):
            row = {'utc': datetime.now(timezone.utc).isoformat(), 'event': event, 'value': safe(value)}
            log.write(json.dumps(row, ensure_ascii=False) + '\n')
            log.flush()
        emit('start', {'robotIp': args.robot_ip, 'readOnly': True, 'extended': args.extended, 'follow': args.follow})
        try:
            asyncio.run(run(args, emit))
        except Exception as error:
            emit('error', {'type': type(error).__name__, 'result': 'inconclusive'})
            print('Diagnostic incomplete:', type(error).__name__)
            raise SystemExit(1)
    print('Read-only diagnostic saved:', args.output)


if __name__ == '__main__':
    main()
