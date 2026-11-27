import asyncio
import math
from pathlib import Path
import sys
import time
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from simulator import SimBridge
from bridge import Bridge
from navigation import valid_request


class NavigationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.b=SimBridge()
        result=await self.b.navigation.command('prepare',{})
        self.assertNotIn('requestError',result)
    async def asyncTearDown(self):await self.b.command('disconnect')
    async def start(self,target=(1.7,1.3)):
        return await self.b.navigation.command('goal',{'mapId':self.b.navigation.map_id,'name':'目标','x':target[0],'y':target[1]})
    async def test_map_goal_and_manual_takeover(self):
        r=await self.start();self.assertTrue(r['active']);self.assertGreater(len(r['route']),2)
        self.assertEqual((await self.b.motion_command('arm'))['error'],'CONTROL_BUSY')
        self.assertEqual((await self.b.lab_command('avoidance','enable'))['requestError'],'CONTROL_BUSY')
        await asyncio.sleep(.2)
        await self.b.motion_command('stop')
        self.assertFalse(self.b.navigation.active());self.assertEqual(self.b.desired,(0,0,0));self.assertIsNone(self.b.lease.owner)
    async def test_stale_camera_stops(self):
        await self.start();await asyncio.sleep(.1)
        await self.b.camera.command('stop');await asyncio.sleep(.15)
        self.assertEqual(self.b.navigation.reason,'CAMERA_STALE');self.assertEqual(self.b.desired,(0,0,0))
    async def test_changed_map_rejects_place(self):
        old=self.b.navigation.map_id
        await self.b.set_scenario('navigation-room')
        r=await self.b.navigation.command('place',{'mapId':old,'name':'旧地点','x':0,'y':1})
        self.assertEqual(r['requestError'],'MAP_CHANGED')
    async def test_unknown_target_and_real_source(self):
        self.assertIn('requestError',await self.start((3,3)))
        real=Bridge('192.0.2.1')
        for action,value in [('prepare',{}),('goal',{'mapId':real.navigation.map_id,'name':'门口'}),('scan',{'direction':'left'})]:
            self.assertEqual((await real.navigation.command(action,value))['requestError'],'REAL_NAVIGATION_DISABLED')
        self.assertIsNone(real.conn)
    async def test_visual_projection_uses_frozen_observation(self):
        obs=await self.b.navigation.command('observe',{})
        # Door ground point projected through the synthetic camera, not a semantic oracle.
        value={'observationId':obs['observationId'],'label':'门口','u':.5-1.3/2.5*.8,'v':.5+.38/2.5*(512/360)}
        self.assertTrue(self.b.navigation.image(value['observationId']))
        self.assertTrue((await self.b.navigation.command('visual',value))['active'])
        await self.b.navigation.stop()
        self.b.position[0]+=.1;self.b.update_sensors(force=True)
        self.assertEqual((await self.b.navigation.command('visual',value))['requestError'],'OBSERVATION_MOVED')
    async def test_schema_rejects_extra_controls(self):
        self.assertFalse(valid_request('goal',{'mapId':'x'*32,'name':'x','x':float('nan'),'y':0}))
        self.assertFalse(valid_request('scan',{'direction':'forward'}))
        self.assertFalse(valid_request('visual',{'observationId':'a'*32,'u':True,'v':.9,'label':'x'}))
    async def test_stop_preempts_grid_construction(self):
        original=self.b.navigation.rebuild;entered=asyncio.Event();resume=asyncio.Event()
        async def slow():entered.set();await resume.wait();await original()
        self.b.navigation.rebuild=slow
        task=asyncio.create_task(self.start());await entered.wait()
        await self.b.navigation.stop();resume.set()
        self.assertEqual((await task)['requestError'],'CANCELLED');self.assertEqual(self.b.sim_commands,0)
    async def test_disconnected_mid_mission(self):
        await self.start();await asyncio.sleep(.1);await self.b.command('disconnect')
        self.assertFalse(self.b.navigation.active());self.assertEqual(self.b.desired,(0,0,0))
    async def test_cloud_loss_stops_without_another_goal(self):
        await self.start();await asyncio.sleep(.1)
        self.b.scenario='stale-cloud'
        await asyncio.sleep(.65)
        self.assertFalse(self.b.navigation.active());self.assertIn(self.b.navigation.reason,{'CLOUD_STALE','TIME_SKEW'})
        self.assertEqual(self.b.desired,(0,0,0))
    async def test_changed_obstacles_stop_scan(self):
        await self.b.navigation.command('scan',{'direction':'left'})
        await asyncio.sleep(.1)
        import numpy as np
        self.b.world_points=np.concatenate([self.b.world_points,np.array([[.4,0,.2]])])
        self.b.update_sensors(force=True);await asyncio.sleep(.65)
        self.assertEqual(self.b.navigation.state,'blocked');self.assertEqual(self.b.desired,(0,0,0))
    async def test_failed_stop_keeps_control_locked_until_retry(self):
        await self.start();await asyncio.sleep(.1)
        original=self.b.native.stop
        async def fail():raise ConnectionError('test stop failure')
        self.b.native.stop=fail
        await self.b.navigation.stop()
        self.assertFalse(self.b.navigation.stop_confirmed);self.assertEqual(self.b.lease.owner,'navigation')
        self.assertEqual((await self.b.motion_command('arm'))['error'],'CONTROL_BUSY')
        self.b.native.stop=original;await self.b.navigation.stop()
        self.assertTrue(self.b.navigation.stop_confirmed);self.assertIsNone(self.b.lease.owner)
    async def test_source_change_before_execution_never_moves(self):
        await self.start();self.b.simulated=False
        await asyncio.sleep(.1)
        self.assertEqual(self.b.sim_commands,0);self.assertEqual(self.b.navigation.reason,'REAL_NAVIGATION_DISABLED')
        self.b.simulated=True
    async def test_wall_has_no_route(self):
        await self.b.set_scenario('navigation-blocked')
        r=await self.start((1.9,0));self.assertEqual(r['requestError'],'NO_ROUTE');self.assertEqual(self.b.sim_commands,0)

if __name__=='__main__':unittest.main()
