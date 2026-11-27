"""Offline adversarial checks; no driver import, robot or socket is needed."""
import asyncio
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tooling/go2-lab'))
from diagnostic_protocol import READS, TOPICS, ReadOnlyRPC, allowed, switch_value, uwb_switch_value

spec = importlib.util.spec_from_file_location('diagnose', ROOT / 'tooling/go2-lab/diagnose-readonly.py')
diagnose = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnose)


def request(service='obstacles_avoid', api=1002):
    return {'type': 'req', 'topic': f'rt/api/{service}/request', 'data': {
        'header': {'identity': {'id': 123, 'api_id': api}}, 'parameter': '{}'}}


def reply(sent, code=0, payload='{"enable":true}'):
    message = deepcopy(sent)
    message['type'] = 'res'
    message['topic'] = message['topic'].replace('/request', '/response')
    message['data']['header']['status'] = {'code': code}
    message['data']['data'] = payload
    return message


class GuardTests(unittest.TestCase):
    def test_only_known_reads(self):
        for service, api in READS:
            self.assertTrue(allowed(request(service, api)))
        for service, api in [('obstacles_avoid', 1001), ('obstacles_avoid', 1003),
                             ('obstacles_avoid', 1004), ('motion_switcher', 1002),
                             ('sport', 1003), ('sport', 1008), ('sport', 2048),
                             ('sport', 2058), ('robot_state', 1001), ('robot_state', 1002),
                             ('uwbswitch', 1001), ('sport', 1045), ('sport', 2056), ('sport', 1018)]:
            self.assertFalse(allowed(request(service, api)))
        for topic in TOPICS:
            self.assertTrue(allowed({'type': 'subscribe', 'topic': topic}))
        self.assertFalse(allowed({'type': 'msg', 'topic': 'rt/utlidar/switch', 'data': 'on'}))
        self.assertFalse(allowed({'type': 'msg', 'topic': 'rt/wirelesscontroller', 'data': {'ly': .1}}))
        self.assertFalse(allowed({'type': 'rtc_inner_req', 'topic': '', 'data': {
            'req_type': 'disable_traffic_saving', 'instruction': 'on'}}))

    def test_malformed_and_expanded_requests_fail_closed(self):
        for value in [None, [], 1, 'req']:
            self.assertFalse(allowed(value))
        mutations = []
        for key, value in [('id', True), ('id', -1), ('api_id', True), ('api_id', '1002')]:
            m = request(); m['data']['header']['identity'][key] = value; mutations.append(m)
        m = request(); m['data']['parameter'] = '{"enable":true}'; mutations.append(m)
        m = request(); m['data']['header']['policy'] = {'priority': 1}; mutations.append(m)
        m = request(); m['extra'] = 'hidden'; mutations.append(m)
        m = request(); m['topic'] = []; mutations.append(m)
        for m in mutations:
            self.assertFalse(allowed(m))

    def test_boolean_parsing_and_error_status(self):
        for value in [None, 0, 1, 'true', 'false', [], {}]:
            self.assertIsNone(switch_value({'code': 0, 'payload': json.dumps({'enable': value})}))
        for code in [3203, None, False, '0']:
            self.assertIsNone(switch_value({'code': code, 'payload': '{"enable":true}'}))
        self.assertIs(switch_value({'code': 0, 'payload': '{"enable":false}'}), False)
        self.assertIs(switch_value({'code': 0, 'payload': '{"enable":true}'}), True)

    def test_default_cli_is_offline_plan_without_driver(self):
        result = subprocess.run([sys.executable, '-S', str(ROOT / 'tooling/go2-lab/diagnose-readonly.py')],
                                capture_output=True, text=True, check=True)
        plan = json.loads(result.stdout)
        self.assertIs(plan['networkAccess'], False)
        self.assertEqual(plan['writes'], [])
        self.assertNotIn('rt/uwbstate', plan['subscriptions'])
        self.assertFalse(any(item['service'] == 'uwbswitch' for item in plan['reads']))

    def test_follow_cli_stays_offline_and_advertises_only_selected_reads(self):
        result = subprocess.run([sys.executable, '-S', str(ROOT / 'tooling/go2-lab/diagnose-readonly.py'), '--follow'],
                                capture_output=True, text=True, check=True)
        plan = json.loads(result.stdout)
        self.assertIs(plan['networkAccess'], False)
        self.assertEqual(plan['writes'], [])
        self.assertIn('rt/uwbstate', plan['subscriptions'])
        self.assertEqual([item['api'] for item in plan['reads'] if item['service'] == 'uwbswitch'], [1002, 1003])

    def test_log_filters_nested_credentials(self):
        filtered = diagnose.safe({'payload': '{"enable":true,"token":"secret"}',
                                  'serialNumber': 'private', 'header': {'identity': {'id': 4}}})
        self.assertEqual(filtered, {'payload': {'enable': True}, 'header': {'identity': {'id': 4}}})

    def test_uwb_switch_schema_is_not_obstacles_schema(self):
        for wire, expected in [(0, False), (1, True), (False, False), (True, True)]:
            self.assertIs(uwb_switch_value({'code': 0, 'payload': json.dumps({'enable': wire})}), expected)
        for wire in [-1, 2, 1.0, '1', 'true', None, {}, []]:
            self.assertIsNone(uwb_switch_value({'code': 0, 'payload': json.dumps({'enable': wire})}))
        self.assertIsNone(uwb_switch_value({'code': 3203, 'payload': '{"enable":1}'}))


class RPCTests(unittest.IsolatedAsyncioTestCase):
    async def test_immediate_reply_and_each_supported_response_topic(self):
        for suffix in ['request', 'response']:
            events = []
            def send(raw):
                m = reply(json.loads(raw)); m['topic'] = f'rt/api/obstacles_avoid/{suffix}'
                rpc.receive(m)
            rpc = ReadOnlyRPC(send, lambda *v: events.append(v))
            result = await rpc.query('obstacles_avoid', 1002)
            self.assertIs(switch_value(result), True)
            self.assertEqual(rpc.pending, {})

    async def test_wrong_topic_api_id_type_status_do_not_complete(self):
        for field, bad in [('topic', 'rt/api/motion_switcher/response'), ('api', 1001),
                           ('id', 999), ('type', 'msg'), ('code', False), ('topic', [])]:
            def send(raw):
                m = reply(json.loads(raw))
                if field in {'api', 'id'}:
                    m['data']['header']['identity']['api_id' if field == 'api' else 'id'] = bad
                elif field == 'code':
                    m['data']['header']['status']['code'] = bad
                else:
                    m[field] = bad
                rpc.receive(m)
            rpc = ReadOnlyRPC(send, lambda *_: None)
            self.assertIsNone(await rpc.query('obstacles_avoid', 1002, .001))
            self.assertEqual(rpc.pending, {})

    async def test_timeout_late_reply_and_next_request_are_isolated(self):
        sent = []
        rpc = ReadOnlyRPC(lambda raw: sent.append(json.loads(raw)), lambda *_: None)
        self.assertIsNone(await rpc.query('obstacles_avoid', 1002, .001))
        rpc.receive(reply(sent[0]))  # Must neither throw nor resurrect a cancelled future.
        task = asyncio.create_task(rpc.query('obstacles_avoid', 1002))
        await asyncio.sleep(0)
        rpc.receive(reply(sent[0]))
        self.assertFalse(task.done())
        rpc.receive(reply(sent[1], code=3203))
        self.assertEqual((await task)['code'], 3203)
        self.assertGreater(sent[1]['data']['header']['identity']['id'], sent[0]['data']['header']['identity']['id'])

    async def test_cancellation_and_unapproved_api_leave_no_pending(self):
        rpc = ReadOnlyRPC(lambda _: None, lambda *_: None)
        with self.assertRaises(ValueError):
            await rpc.query('sport', 2048)
        task = asyncio.create_task(rpc.query('obstacles_avoid', 1002))
        await asyncio.sleep(0)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(rpc.pending, {})

    async def test_whole_readonly_session_with_fake_sdk(self):
        sent, events, lifecycle = [], [], []

        class Channel:
            def send(self, raw):
                message = json.loads(raw); sent.append(message)
                if message['type'] == 'req':
                    payload = '{"enable":true}' if 'obstacles_avoid' in message['topic'] else '{"name":"ai"}'
                    self.callback(json.dumps(reply(message, payload=payload)))

            def on(self, event, callback):
                self.callback = callback
                callback(json.dumps({'type': 'msg', 'topic': []}))  # Malformed telemetry is ignored.

        class Connection:
            def __init__(self, method, ip):
                channel = Channel()
                self.datachannel = SimpleNamespace(channel=channel, pub_sub=SimpleNamespace(
                    subscribe=lambda topic: channel.send(json.dumps({'type': 'subscribe', 'topic': topic}))))

            async def connect(self):
                lifecycle.append('connect')
                self.datachannel.channel.send(json.dumps({'type': 'validation', 'data': 'private-challenge'}))

            async def disconnect(self):
                lifecycle.append('disconnect')

        auth = SimpleNamespace(requests=SimpleNamespace(post=lambda *_: None,
            Session=lambda: SimpleNamespace(trust_env=True, close=lambda: None)))
        original_send, original_post = Channel.send, auth.requests.post
        modules = {'aiortc': SimpleNamespace(RTCDataChannel=Channel),
                   'unitree_webrtc_connect': SimpleNamespace(UnitreeWebRTCConnection=Connection,
                       WebRTCConnectionMethod=SimpleNamespace(LocalSTA='sta'), unitree_auth=auth)}
        with patch.dict(sys.modules, modules):
            await diagnose.run(SimpleNamespace(robot_ip='192.168.5.128', extended=True, follow=True, seconds=0),
                               lambda *event: events.append(event))
        self.assertEqual(lifecycle, ['connect', 'disconnect'])
        self.assertIs(Channel.send, original_send)
        self.assertIs(auth.requests.post, original_post)
        self.assertTrue(all(allowed(message) for message in sent))
        self.assertNotIn('private-challenge', json.dumps(events))
        summary = next(value for event, value in events if event == 'summary')
        self.assertEqual(summary['switchReadbacks'], [True, True, True])
        self.assertIs(summary['actuationAllowed'], False)
        self.assertEqual(summary['blockedCount'], 0)
        uwb_api_ids = [m['data']['header']['identity']['api_id'] for m in sent
                       if m.get('topic') == 'rt/api/uwbswitch/request']
        self.assertEqual(uwb_api_ids, [1002, 1003, 1, 1002, 1003])
        self.assertIsNone(summary['follow']['switchBefore'])  # Unknown payload must not become false.


if __name__ == '__main__':
    unittest.main()
