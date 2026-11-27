import math
from pathlib import Path
import sys
import unittest
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from perception import sport_pose, world_to_body, geometry
from lidar import point_cloud

class PerceptionTests(unittest.TestCase):
    def pose(self, yaw=0):
        return sport_pose({'position':[1,2,.32], 'imu_state':{
            'quaternion':[math.cos(yaw/2),0,0,math.sin(yaw/2)]}}, 'odom', True)
    def scene(self, points):
        return {'received':10, 'values':point_cloud({'frame_id':'odom','data':{'points':points}})}
    def test_unverified_by_default_and_invalid_quaternion_rejected(self):
        value=sport_pose({'position':[0,0,0], 'imu_state':{'quaternion':[1,0,0,0]}})
        self.assertFalse(value['aligned']);self.assertIsNone(value['frameId'])
        for q in [[0,0,0,0],[True,0,0,0],[float('nan'),0,0,0],[1,0,0]]:
            self.assertIsNone(sport_pose({'position':[0,0,0],'imu_state':{'quaternion':q}}))
    def test_yaw_and_translation_transform(self):
        pose=self.pose(math.pi/2)
        np.testing.assert_allclose(world_to_body(np.array([[1,3,.32]]),pose),[[1,0,0]],atol=1e-6)
        self.assertAlmostEqual(pose['yaw'],math.pi/2)
    def test_forward_envelope_and_ground_filter(self):
        pose={'received':10,'values':self.pose()}
        self.assertEqual(geometry(self.scene([[1.61,2,.32]]),pose,10)['directions']['forward'],'blocked')
        self.assertEqual(geometry(self.scene([[1.61,2,.32]]),pose,10)['directions']['backward'],'clear')
        self.assertEqual(geometry(self.scene([[1,2,0],[3,2,.32]]),pose,10)['directions']['forward'],'clear')
    def test_turn_sweeps_corner(self):
        g=geometry(self.scene([[1.58,2.42,.32]]),{'received':10,'values':self.pose()},10)
        self.assertEqual(g['directions']['left'],'blocked')
        self.assertEqual(g['directions']['right'],'clear')
    def test_stale_empty_unaligned_and_mismatched_are_unknown(self):
        p={'received':10,'values':self.pose()};c=self.scene([[3,2,.32]])
        self.assertFalse(geometry(c,p,11)['valid'])
        self.assertFalse(geometry(self.scene([]),p,10)['valid'])
        p['values']['aligned']=False
        self.assertEqual(geometry(c,p,10)['reason'],'POSE_UNVERIFIED')
        p['values']['aligned']=True;p['values']['frameId']='other'
        self.assertEqual(geometry(c,p,10)['reason'],'FRAME_MISMATCH')

if __name__=='__main__': unittest.main()
