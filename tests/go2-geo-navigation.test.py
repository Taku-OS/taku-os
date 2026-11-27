import asyncio
import math
from pathlib import Path
import sys
import time
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from simulator import SimBridge
from bridge import Bridge
from geo_reference import GeoReference
from navigation import valid_request
from navigation_map import Grid


class GeoNavigationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):self.b=SimBridge()
    async def asyncTearDown(self):await self.b.command('disconnect')
    def route(self,points=((0,0),(6,0))):
        f=GeoReference([116.4,39.9],[0,0],0,90,'e','odom','simulation')
        return {'points':[f.to_geo(p) for p in points],'name':'测试路线','scope':'full'}
    async def prepare(self,points=((0,0),(6,0))):
        r=await self.b.navigation.command('geo-prepare',self.route(points))
        self.assertNotIn('requestError',r,r.get('requestError'))
        return {'mapId':r['mapId'],'routeId':r['geo']['routeId']}
    async def test_prepare_and_start_route_around_obstacle(self):
        v=await self.prepare()
        self.assertEqual(self.b.sim_commands,0)
        self.assertTrue(self.b.navigation.geo.localization()['valid'])
        self.assertLess(abs(self.b.navigation.geo.reference.rotation),1e-6)
        r=await self.b.navigation.command('geo-start',v)
        self.assertNotIn('requestError',r,r.get('requestError'))
        self.assertTrue(r['active'])
        self.assertTrue(any(abs(p[1])>.7 for p in r['route']))
        await asyncio.sleep(.2)
        await self.b.navigation.command('stop',{})
        self.assertEqual(self.b.desired,(0,0,0));self.assertFalse(self.b.navigation.active())
    async def test_supervision_loss_stops(self):
        v=await self.prepare();await self.b.navigation.command('geo-start',v)
        await asyncio.sleep(.2);self.b.navigation.geo.heartbeat-=4
        await asyncio.sleep(.15)
        self.assertEqual(self.b.navigation.reason,'SUPERVISION_LOST')
        self.assertEqual(self.b.desired,(0,0,0))
    async def test_route_epoch_and_real_gate(self):
        v=await self.prepare();await self.prepare()
        self.assertEqual((await self.b.navigation.command('geo-start',v))['requestError'],'GEO_ROUTE_CHANGED')
        b=Bridge('192.0.2.1')
        r=await b.navigation.command('geo-prepare',self.route())
        self.assertEqual(r['requestError'],'REAL_NAVIGATION_DISABLED');self.assertIsNone(b.conn)
    async def test_discontinuity_latches_and_disconnect_invalidates(self):
        await self.prepare();g=self.b.navigation.geo;g.localization()
        self.b.position[0]=2.;self.b.update_sensors(force=True)
        self.assertEqual(g.localization()['reason'],'ODOMETRY_DISCONTINUITY')
        await self.b.command('disconnect')
        self.assertFalse(g.localization()['valid'])
    async def test_ten_meter_prefix_does_not_claim_full_arrival(self):
        v=self.route(((0,0),(100,0)));v['scope']='first10m'
        r=await self.b.navigation.command('geo-prepare',v)
        self.assertNotIn('requestError',r)
        self.assertAlmostEqual(r['geo']['length'],10,places=5)
        self.assertAlmostEqual(r['geo']['fullLength'],100,places=5)
        self.assertEqual(r['geo']['scope'],'first10m')
        from hashlib import sha256
        identity=sha256(';'.join(f'{lon:.6f},{lat:.6f}' for lon,lat in v['points']).encode()).hexdigest()
        self.assertEqual(r['geo']['geometryId'],identity)
    async def test_rolling_grid_and_manual_uncertainty(self):
        await self.prepare();self.b.position=[30.,20.,.32];self.b.update_sensors(force=True)
        grid=Grid(self.b.lidar_sample['values']['pointBytes'],(30,20))
        self.assertTrue(grid.line([30,20],[31,20]))
        r=await self.b.navigation.command('geo-calibrate',{'mapId':self.b.navigation.map_id,'longitude':116.4,'latitude':39.9,'heading':0})
        self.assertNotIn('requestError',r)
        self.assertIsNone(r['geo']['routeId'])
        self.assertFalse(r['geo']['localization']['absoluteFix'])
    async def test_schema_rejects_frame_and_control_injection(self):
        self.assertFalse(valid_request('geo-prepare',{**self.route(),'real':True}))
        self.assertFalse(valid_request('geo-calibrate',{'mapId':'x'*32,'longitude':True,'latitude':39,'heading':0}))
        self.assertFalse(valid_request('geo-prepare',{**self.route(),'points':[[float('nan'),0],[0,1]]}))

    async def test_pose_stale_stops_running_route(self):
        ids=await self.prepare();await self.b.navigation.command('geo-start',ids)
        await asyncio.sleep(.15)
        self.b.scenario='stale-pose'
        await asyncio.sleep(.5)
        self.assertEqual(self.b.navigation.state,'blocked')
        # A fresh cloud vs frozen pose can trip skew before the .3 s stale limit.
        self.assertIn(self.b.navigation.reason,{'POSE_STALE','TIME_SKEW'})
        self.assertEqual(self.b.desired,(0,0,0))

    async def test_no_local_path_is_not_skipped(self):
        await self.prepare()
        class Blocked:
            def route(self,*args): raise ValueError('NO_PATH')
        with self.assertRaisesRegex(ValueError,'GEO_LOCAL_PATH_BLOCKED'):
            self.b.navigation.geo.target([0,0],Blocked())

    async def test_manual_reference_cannot_unlock_execution(self):
        await self.prepare()
        n=self.b.navigation
        n.geo.reference.accuracy=3.
        n.geo.heartbeat=time.monotonic()
        self.assertEqual(n.geo.guard(),'GEO_UNCERTAINTY_HIGH')


if __name__=='__main__':unittest.main()
