import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from recording import Recorder, replay_cloud
from lidar import point_cloud

class RecordingTests(unittest.TestCase):
    def test_roundtrip_full_points_and_ignore_commands(self):
        with tempfile.TemporaryDirectory() as root:
            r=Recorder(root);r.start('simulation')
            points=[[i/100,1,.3] for i in range(64000)]
            cloud=point_cloud({'frame_id':'odom','data':{'points':points}})
            r.emit('lidar',{'values':cloud,'count':1})
            r.emit('avoidance_action',{'action':'forward'})
            r.emit('pose',{'position':[0,0,.32]})
            self.assertIsNone(r.stop()['error'])
            events=r.load_latest()
            self.assertEqual([e['kind'] for e in events],['header','lidar','pose'])
            self.assertEqual(replay_cloud(events[1]['payload'])['values']['pointBytes'],cloud['pointBytes'])
    def test_active_and_unfinished_cannot_replay(self):
        with tempfile.TemporaryDirectory() as root:
            r=Recorder(root)
            with self.assertRaises(ValueError):r.load_latest()
            r.start('simulation')
            with self.assertRaises(ValueError):r.load_latest()
            r.stop()
    def test_corrupt_timeline_and_cloud_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            r=Recorder(root);r.start('simulation');r.stop()
            with r.last_path.open('a') as f:f.write(json.dumps({'t':-1,'kind':'state','payload':{}})+'\n')
            with self.assertRaises(ValueError):r.load_latest()
        with self.assertRaises(ValueError):replay_cloud({'values':{'pointBytes':{'encoding':'base64','data':'AAAA'},'pointCount':1}})
    def test_size_limit_is_explicit_and_not_replayable(self):
        with tempfile.TemporaryDirectory() as root, patch('recording.MAX_BYTES',64):
            r=Recorder(root);r.start('simulation');result=r.stop()
            self.assertEqual(result['error'],'RECORDING_LIMIT')
            self.assertFalse(result['replayAvailable'])
    def test_non_finite_file_and_invalid_header_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            r=Recorder(root);r.start('simulation');r.stop()
            for content in ['{"t":NaN,"kind":"header","payload":{}}\n',
                            '{"t":0,"kind":"header","payload":null}\n']:
                r.last_path.write_text(content)
                with self.assertRaises(ValueError):r.load_latest()

if __name__=='__main__':unittest.main()
