import math
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from geo_reference import GeoReference, RouteProgress, coordinate


class GeoTests(unittest.TestCase):
    def frame(self, heading=90): return GeoReference([116.4,39.9],[5,7],.3,heading,'epoch','odom','manual')
    def test_round_trip_and_compass(self):
        for heading in [0,90,180,270,359]:
            frame=self.frame(heading);local=[13.,-5.]
            self.assertLess(math.dist(frame.to_local(frame.to_geo(local)),local),1e-7)
            s={'received':1.,'values':{'position':[5,7,0],'yaw':.3,'frameId':'odom'}}
            self.assertAlmostEqual(frame.update(s,'epoch','manual',now=1)['heading'],heading)
        north=GeoReference([116.4,39.9],[0,0],0,0,'e','odom','manual')
        self.assertGreater(north.to_local([116.4,39.9001])[0],11)
        self.assertLess(abs(north.to_local([116.4,39.9001])[1]),1e-8)
    def test_invalid_values_extent(self):
        for v in [[True,0],[float('nan'),1],[181,1],[1,86],['1',1]]:
            with self.assertRaises(ValueError): coordinate(v)
        with self.assertRaisesRegex(ValueError,'GEO_EXTENT'): self.frame().to_local([117.4,39.9])
    def test_reset_jump_and_stale(self):
        f=self.frame();s={'received':10.,'values':{'position':[5,7,0],'yaw':0,'frameId':'odom'}}
        self.assertTrue(f.update(s,'epoch','manual',now=10)['valid'])
        self.assertEqual(f.update(s,'epoch','manual',now=11)['reason'],'POSE_STALE')
        s={'received':10.1,'values':{**s['values'],'position':[0,0,0]}}
        self.assertEqual(f.update(s,'epoch','manual',now=10.1)['reason'],'ODOMETRY_DISCONTINUITY')
        self.assertEqual(self.frame().update(s,'reset','manual',now=10.1)['reason'],'GEO_REFERENCE_CHANGED')
    def test_route_projection_turns_and_crossings(self):
        r=RouteProgress([[0,0],[3,0],[3,3],[0,3],[0,0],[5,0]])
        self.assertEqual(r.at(4),[3.,1.])
        r.project([.1,0]);self.assertLess(r.progress,.2)
        for p in [[1,0],[2,0],[2.9,0],[3,1]]:r.project(p)
        self.assertAlmostEqual(r.progress,4)
        with self.assertRaisesRegex(ValueError,'ROUTE_DEVIATION'):r.project([10,10])
        with self.assertRaisesRegex(ValueError,'ROUTE_TOO_SHORT'):RouteProgress([[0,0],[0,0]])


if __name__=='__main__':unittest.main()
