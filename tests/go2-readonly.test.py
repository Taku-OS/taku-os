import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('protocol', Path(__file__).resolve().parents[1] / 'tooling/go2-lab/protocol.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)

class ReadOnlyTests(unittest.TestCase):
    def test_all_motion_and_unlisted_messages_blocked(self):
        for kind in ['req', 'msg', 'aud', 'vid', 'unknown']:
            for topic in ['', 'rt/lowcmd', 'rt/api/sport/request', 'rt/lf/lowstate']:
                self.assertFalse(p.permitted({'type': kind, 'topic': topic, 'data': {}}))
        self.assertFalse(p.permitted({'type': 'rtc_inner_req', 'data': {'req_type': 'push_static_file'}}))
        self.assertFalse(p.permitted({'type': 'subscribe', 'topic': 'rt/lowcmd'}))
        self.assertFalse(p.permitted({'type': 'subscribe', 'topic': 'rt/lf/lowstate', 'data': {}}))

    def test_only_connection_and_state_reads_allowed(self):
        for topic in p.TOPICS:
            self.assertTrue(p.permitted({'type': 'subscribe', 'topic': topic}))
        self.assertTrue(p.permitted({'type': 'validation', 'data': 'challenge'}))
        self.assertTrue(p.permitted({'type': 'heartbeat', 'data': {'timeInStr': '', 'timeInNum': 1}}))
        self.assertTrue(p.permitted({'type': 'rtc_inner_req', 'data': {'req_type': 'public_network_status'}}))

    def test_stale_and_disconnected_data_never_live(self):
        sample = {'received': 100, 'values': {'batteryPercent': 90}, 'count': 1}
        self.assertTrue(p.sample_view(sample, 102, True)['fresh'])
        self.assertFalse(p.sample_view(sample, 106, True)['fresh'])
        self.assertFalse(p.sample_view(sample, 101, False)['fresh'])
        self.assertFalse(p.sample_view(None, 101, True)['fresh'])

    def test_mode_query_cannot_change_mode(self):
        query={'type':'req','topic':'rt/api/motion_switcher/request','data':{'header':{'identity':{'id':1,'api_id':1001}},'parameter':''}}
        self.assertTrue(p.permitted(query))
        query['data']['header']['identity']['api_id']=1002
        query['data']['parameter']='{"name":"normal"}'
        self.assertFalse(p.permitted(query))
        query['data']['header']=123
        self.assertFalse(p.permitted(query))

    def test_invalid_values_do_not_become_battery_readings(self):
        for value in [None, '90', True, -1, 101, float('nan')]:
            self.assertIsNone(p.telemetry('rt/lf/lowstate', {'bms_state': {'soc': value}})['batteryPercent'])
        self.assertEqual(p.telemetry('rt/lf/lowstate', {'bms_state': {'soc': 90}})['batteryPercent'], 90)
        self.assertEqual(p.telemetry('other', {'batteryPercent': 90}), {})

if __name__ == '__main__':
    unittest.main()
