import math
import struct
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
sys.path[:0] = [str(Path(__file__).resolve().parents[1] / 'tooling/go2-lab'), str(Path(__file__).resolve().parents[1] / 'tooling/go2-lab/phone')]
from navigation_preview import NavigationPreview


class NavigationTests(unittest.TestCase):
    def setUp(self):
        self.now = 100.
        self.clock = patch('time.monotonic', side_effect=lambda: self.now); self.clock.start(); self.addCleanup(self.clock.stop)
        self.b = SimpleNamespace(phase='connected', stream_id='session1', lidar_enabled=True)
        self.b.camera = SimpleNamespace(snapshot=lambda: {'fresh': True}, frame_time=lambda k: self.now if k == 'new' else None)
        self.nav = NavigationPreview(self.b)
        self.sample()

    def sample(self, x=0., y=0., yaw=0., obstacle=False, floor=True):
        self.b.pose_sample = {'received': self.now, 'values': {'position': [x,y,.35], 'yaw': yaw, 'frameId': 'odom', 'aligned': True}}
        points = [(d,side,0) for d in (.5,.7,.9,1.1,1.3) for side in (-.3,0,.3)] if floor else []
        if obstacle: points.append((.6,0,.3))
        c,s = math.cos(yaw), math.sin(yaw)
        raw = b''.join(struct.pack('<fff', x+d*c-side*s, y+d*s+side*c,z) for d,side,z in points)
        self.b.lidar_sample = {'received': self.now, 'values': {'frameId': 'odom', 'pointBytes': raw}}
        self.nav.vision = {'received': self.now, 'boxes': []}

    def anchor(self, **changes):
        return self.nav.calibrate({'longitude':116.4,'latitude':39.9,'heading':90.,'accuracy':.05,'headingAccuracy':.5,
                                  'floorZ':0.,'source':'surveyed','colocated':False, **changes})

    def route(self, points=None):
        points = points or [[0,0],[3,0],[3,3]]
        coords = [','.join(map(str,self.nav.reference.to_geo(p))) for p in points]
        return self.nav.prepare({'protocol':'mcp','coordinateSystem':'GCJ-02','routeAgreement':True,'geometrySource':'amap-web-service',
                                 'geometryId':'a'*64,'steps':[{'polyline':coords}]})

    def test_no_anchor_no_invented_robot_location(self):
        v=self.nav.view();self.assertIn('ROBOT_ANCHOR_REQUIRED',v['reasons']);self.assertNotIn('position',v['robot'])
        self.assertFalse(v['realActuationAllowed']);self.assertEqual(v['proposal']['forwardMps'],0)

    def test_phone_fix_never_becomes_robot_location(self):
        value={'session':'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','sequence':1,'longitude':116.4,'latitude':39.9,'accuracy':2.,'ageMs':0.,'provider':'gps','mock':False}
        self.nav.phone_fix(value)
        self.assertTrue(self.nav.view()['phone']['valid']);self.assertFalse(self.nav.view()['robot']['valid'])
        with self.assertRaisesRegex(ValueError,'STALE_PHONE_FIX'):self.nav.phone_fix(value)
        self.now+=11;self.assertFalse(self.nav.phone_view()['valid'])
        self.nav.phone_fix({**value,'sequence':2,'mock':True});self.assertFalse(self.nav.phone_view()['valid'])

    def test_no_phone_calibration_with_bad_accuracy_or_no_colocation(self):
        with self.assertRaisesRegex(ValueError,'PHONE_COLOCATION_REQUIRED'):self.anchor(source='phone-colocated')

    def test_coarse_network_callback_does_not_replace_fresh_gps(self):
        base={'session':'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','sequence':1,'longitude':116.4,'latitude':39.9,'accuracy':2.,'ageMs':0.,'provider':'gps','mock':False}
        self.nav.phone_fix(base)
        self.now+=1
        self.nav.phone_fix({**base,'sequence':2,'accuracy':100.,'provider':'network'})
        self.assertEqual(self.nav.phone_view()['provider'],'gps')
        self.now+=9.5
        self.assertEqual(self.nav.phone_view()['provider'],'network')

    def test_frame_and_quality_checks(self):
        self.b.pose_sample['values']['aligned']=False
        with self.assertRaisesRegex(ValueError,'POSE_FRAME_UNVERIFIED'):self.anchor()
        self.sample();self.anchor(accuracy=20)
        with self.assertRaisesRegex(ValueError,'POSITION_TOO_UNCERTAIN'):self.route()

    def test_route_origin_and_coordinate_datum_checked(self):
        self.anchor()
        with self.assertRaisesRegex(ValueError,'ROUTE_START_TOO_FAR'):self.route([[10,0],[12,0]])
        with self.assertRaisesRegex(ValueError,'MAP_ROUTE_DISAGREEMENT'):self.nav.prepare({'coordinateSystem':'WGS84'})

    def test_hazard_vetoes_and_recovery(self):
        self.anchor();self.route()
        self.assertGreater(self.nav.view()['proposal']['forwardMps'],0)
        self.sample(obstacle=True);v=self.nav.view();self.assertIn('LIDAR_OBSTACLE',v['reasons']);self.assertEqual(v['proposal']['forwardMps'],0)
        self.sample(floor=False);self.assertIn('LIDAR_INVALID',self.nav.view()['reasons'])
        self.sample();self.nav.vision['boxes']=[[.4,.1,.2,.7]]
        self.assertIn('PERSON_IN_PATH',self.nav.view()['reasons'])
        self.sample();self.nav.vision=None;self.assertIn('VISION_STALE',self.nav.view()['reasons'])
        self.sample();self.assertGreater(self.nav.view()['proposal']['forwardMps'],0)

    def test_stale_and_epoch_change_stop_proposals(self):
        self.anchor();self.route();self.now+=.5
        self.assertEqual(self.nav.view()['proposal']['forwardMps'],0)
        self.sample();self.b.stream_id='session2'
        self.assertIn('GEO_REFERENCE_CHANGED',self.nav.view()['reasons'])

    def test_jump_invalidates_anchor(self):
        self.anchor();self.route();self.now+=.1;self.sample(x=10)
        self.assertIn('ODOMETRY_DISCONTINUITY',self.nav.view()['reasons'])

    def test_camera_observation_is_bound_to_real_frame(self):
        with self.assertRaisesRegex(ValueError,'VISION_STALE'):self.nav.observe({'frameKey':'made-up','boxes':[]})
        self.now+=.1;self.nav.observe({'frameKey':'new','boxes':[]})
        with self.assertRaisesRegex(ValueError,'VISION_STALE'):self.nav.observe({'frameKey':'new','boxes':[]})

    def test_closed_loop_simulated_plant_follows_turn_and_arrives(self):
        self.anchor();self.route();x=y=yaw=0.;turned=False;max_speed=0
        for _ in range(800):
            self.sample(x,y,yaw);v=self.nav.view()
            self.assertFalse(v['realActuationAllowed'])
            if 'ARRIVED' in v['reasons']:break
            self.assertEqual(v['reasons'],[])
            u=v['proposal'];max_speed=max(max_speed,u['forwardMps']);turned |= abs(u['yawRadps'])>.1
            x+=u['forwardMps']*math.cos(yaw)*.1;y+=u['forwardMps']*math.sin(yaw)*.1;yaw+=u['yawRadps']*.1;self.now+=.1
        else:self.fail('Did not arrive')
        self.assertLess(math.dist([x,y],[3,3]),.4);self.assertTrue(turned);self.assertLessEqual(max_speed,.2)


if __name__=='__main__':unittest.main()
