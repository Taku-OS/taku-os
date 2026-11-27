import sys,unittest
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from navigation_map import Grid


def cloud(obstacle=True):
    floor=[[x,y,0] for x in np.linspace(-2.5,2.5,64) for y in np.linspace(-2.5,2.5,64)]
    return np.array(floor+([[.95,y,.2] for y in np.linspace(-.1,.1,5)] if obstacle else []),dtype='<f4').tobytes()


class Tests(unittest.TestCase):
    def test_detour_and_footprint(self):
        grid=Grid(cloud());route=grid.route([0,0],[1.8,0])
        self.assertGreater(max(abs(p[1]) for p in route),.75)
        self.assertTrue(all(grid.line(a,b) for a,b in zip(route,route[1:])))
        self.assertFalse(grid.line([0,0],[1.8,0]))
    def test_unknown_and_occupied_targets_rejected(self):
        grid=Grid(cloud())
        for target in [[2.5,0],[.95,0],[100,0]]:
            with self.assertRaises(ValueError):grid.route([0,0],target)
        self.assertEqual(len(Grid(b'').free),0)
    def test_visual_approach_is_reachable_standoff(self):
        grid=Grid(cloud());p=grid.approach([0,0],[2.5,.6]);self.assertTrue(grid.route([0,0],p))
        self.assertAlmostEqual(np.linalg.norm(np.array(p)-[2.5,.6]),.9,places=2)
if __name__=='__main__':unittest.main()
