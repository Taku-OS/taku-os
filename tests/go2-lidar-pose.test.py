import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from lidar_pose import LidarPose, decode, TOPIC
from protocol import permitted

def pose(sec=1):
    return {'header':{'frame_id':'odom','stamp':{'sec':sec,'nanosec':0}},
            'pose':{'position':{'x':1.,'y':2.,'z':.27},
                    'orientation':{'w':1.,'x':0.,'y':0.,'z':0.}}}

class PoseTests(unittest.TestCase):
    def test_named_quaternion_and_explicit_frame(self):
        p=pose();p['pose']['orientation']={'w':.70710678,'x':0.,'y':0.,'z':.70710678}
        result=decode(p)
        self.assertAlmostEqual(result['yaw'],1.57079632679)
        self.assertEqual(result['frameId'],'odom')
        for field,value in [('frame_id','base_link'),('frame_id','')]:
            p['header'][field]=value
            with self.assertRaises(ValueError):decode(p)

    def test_reject_malformed_nonfinite_and_bad_stamp(self):
        for data in [None,{}, {'header':None}, {'header':{'frame_id':'odom'},'pose':None}]:
            with self.assertRaises(ValueError):decode(data)
        for value in [float('nan'),True,'1']:
            p=pose();p['pose']['position']['x']=value
            with self.assertRaises(ValueError):decode(p)
        for value in [True,-1,10**9,1.5]:
            p=pose();p['header']['stamp']['nanosec']=value
            with self.assertRaises(ValueError):decode(p)

    def test_replay_jump_and_reset_latch_without_refreshing(self):
        for failure in ['replay','jump','reset']:
            tracker=LidarPose();self.assertTrue(tracker.receive(pose(10),100))
            p=pose(11)
            if failure=='replay':p=pose(10)
            if failure=='reset':p=pose(0)
            if failure=='jump':p['pose']['position']['x']=3.
            self.assertFalse(tracker.receive(p,100.05));self.assertIsNotNone(tracker.error)
            self.assertFalse(tracker.receive(pose(12),100.1))
            self.assertEqual(tracker.sample['received'],100)

    def test_subscription_is_not_a_write_permission(self):
        self.assertTrue(permitted({'type':'subscribe','topic':TOPIC}))
        for message in [{'type':'msg','topic':TOPIC,'data':pose()},
                        {'type':'subscribe','topic':TOPIC,'extra':1}]:
            self.assertFalse(permitted(message))

if __name__=='__main__':unittest.main()
