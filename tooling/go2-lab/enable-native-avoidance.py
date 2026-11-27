"""Explicit one-shot switch enable; never grants movement or command-source access.

Disconnect the UI bridge first. Reconnect it afterwards to independently read back.
Use only with the robot supervised and stationary. No motion test is performed.
"""
import argparse
import asyncio
import ipaddress
import json
import logging

from avoidance import NativeTransport
from bridge import Bridge


class EnableOnlyTransport(NativeTransport):
    def __init__(self, bridge):
        super().__init__(bridge)
        self.enabling = False

    def allows(self, message):
        if super().allows(message):
            return True
        if not self.enabling:
            return False
        try:
            data = message['data']
            header = data['header']
            identity = header['identity']
            return (set(message) == {'type', 'topic', 'data'}
                    and message['type'] == 'req' and message['topic'] == self.TOPIC
                    and set(data) == {'header', 'parameter'}
                    and set(header) == {'identity'}
                    and set(identity) == {'id', 'api_id'}
                    and type(identity['id']) is int
                    and type(identity['api_id']) is int and identity['api_id'] == 1001
                    and json.loads(data['parameter']) == {'enable': True}
                    and type(json.loads(data['parameter'])['enable']) is bool)
        except (KeyError, TypeError, ValueError):
            return False

    async def enable_once(self):
        self.enabling = True
        try:
            response = await asyncio.wait_for(
                self.bridge.conn.datachannel.pub_sub.publish_request_new(
                    self.TOPIC, {'api_id': 1001, 'parameter': {'enable': True}}), 3)
            code = response.get('data', {}).get('header', {}).get('status', {}).get('code')
            return code if type(code) is int else None
        finally:
            self.enabling = False

    async def inspect_switch(self):
        self.active_wire = (1002, {})
        try:
            response = await asyncio.wait_for(
                self.bridge.conn.datachannel.pub_sub.publish_request_new(
                    self.TOPIC, {'api_id': 1002, 'parameter': {}}), 3)
            data = response.get('data', {})
            code = data.get('header', {}).get('status', {}).get('code')
            try:
                value = json.loads(data.get('data', '{}')).get('enable')
            except (ValueError, TypeError, AttributeError):
                value = None
            result = {'queryCode': code if type(code) is int else None,
                      'enabled': value if type(value) is bool else None}
            print(json.dumps(result), flush=True)
            return value if code == 0 and type(code) is int else None
        finally:
            self.active_wire = None


async def run(ip, query_only=False):
    bridge = Bridge(ip)
    transport = EnableOnlyTransport(bridge)
    bridge.native = transport
    bridge.install_transport_guard()
    try:
        await bridge.command('connect')
        for _ in range(240):
            if bridge.phase != 'connecting':
                break
            await asyncio.sleep(.2)
        if bridge.phase != 'connected':
            raise RuntimeError('CONNECTION_FAILED')
        before = await transport.inspect_switch()
        if query_only:
            return
        if type(before) is not bool:
            raise RuntimeError('INVALID_SWITCH_STATE')
        code = None if before else await transport.enable_once()
        print(json.dumps({'setCode': code, 'alreadyEnabled': before}), flush=True)
        readings = []
        for _ in range(3):
            await asyncio.sleep(.3)
            readings.append(await transport.inspect_switch())
        print(json.dumps({'before': before, 'setCode': code, 'readbacks': readings,
                          'verifiedEnabled': all(v is True for v in readings),
                          'motionArmed': bridge.motion.snapshot()['armed'],
                          'movementAccess': transport.actuation_allowed}), flush=True)
        if code not in (None, 0):
            raise RuntimeError('ENABLE_REJECTED')
        if not all(v is True for v in readings):
            raise RuntimeError('ENABLE_NOT_CONFIRMED')
    finally:
        await bridge.command('disconnect')
        bridge.http.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--robot-ip', required=True)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--enable', action='store_true')
    action.add_argument('--query-only', action='store_true')
    args = parser.parse_args()
    address = ipaddress.IPv4Address(args.robot_ip)
    if not address.is_private or address.is_loopback or address.is_link_local or address.is_unspecified:
        parser.error('Expected verified private LAN IPv4 address')
    logging.basicConfig(level=logging.CRITICAL)
    asyncio.run(run(str(address), args.query_only))
