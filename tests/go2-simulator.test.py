import asyncio
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from simulator import SimBridge
from recording import Recorder

class SimulatorTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.b=SimBridge();self.temp=tempfile.TemporaryDirectory()
        self.b.recorder=Recorder(self.temp.name);self.b.avoidance.event=self.b.recorder.emit
        await self.b.command('connect');await asyncio.sleep(.03)
    async def asyncTearDown(self):
        self.b.scenario='clear'
        await self.b.command('disconnect');self.b.recorder.stop();self.temp.cleanup()
    async def arm(self):
        self.assertIsNone((await self.b.lab_command('avoidance','enable'))['error'])
        self.assertTrue((await self.b.lab_command('avoidance','arm'))['armed'])
    async def test_forward_moves_simulated_pose_and_stops_locked(self):
        await self.arm();r=await self.b.lab_command('avoidance','forward')
        self.assertGreater(self.b.position[0],.01);self.assertLess(self.b.position[0],.04)
        self.assertFalse(r['armed']);self.assertEqual(self.b.desired,(0,0,0))
        self.assertNotIn('unitree_webrtc_connect',sys.modules)
    async def test_front_obstacle_blocks_before_transport(self):
        await self.b.set_scenario('front');await self.arm()
        r=await self.b.lab_command('avoidance','forward')
        self.assertEqual(r['error'],'OBSTACLE_BLOCKED');self.assertEqual(self.b.sim_commands,0)
    async def test_obstacle_appearing_interrupts_pulse(self):
        await self.b.set_scenario('obstacle-appears');await self.arm()
        r=await self.b.lab_command('avoidance','forward')
        self.assertEqual(r['error'],'OBSTACLE_BLOCKED');self.assertLess(self.b.position[0],.025)
    async def test_native_block_is_simulated_feedback_not_motion_ack(self):
        await self.b.set_scenario('native-block');await self.arm()
        await self.b.lab_command('avoidance','forward')
        self.assertEqual(self.b.position[0],0);self.assertGreater(self.b.native_blocks,0)
    async def test_unknown_geometry_cannot_arm(self):
        for name in ['empty-cloud','pose-mismatch','stale-pose','stale-cloud']:
            await self.b.set_scenario(name)
            if name.startswith('stale'):await asyncio.sleep(.45)
            await self.b.lab_command('avoidance','enable')
            r=await self.b.lab_command('avoidance','arm')
            self.assertFalse(r['armed'],name);self.assertIsNotNone(r['error'],name)
    async def test_manual_cannot_bypass_native_mode(self):
        await self.arm()
        self.assertEqual((await self.b.motion_command('arm'))['error'],'CONTROL_BUSY')
    async def test_record_replay_reconstructs_cloud_and_cannot_move(self):
        await self.b.lab_command('recording','start')
        await asyncio.sleep(.22);await self.arm();await self.b.lab_command('avoidance','forward')
        await self.b.lab_command('recording','stop')
        count=self.b.sim_commands
        result=await self.b.lab_command('replay','start')
        self.assertTrue(result['replay']['active'])
        await asyncio.sleep(.25)
        self.assertIsNotNone(self.b.lidar_sample)
        self.assertEqual((await self.b.lab_command('avoidance','forward'))['requestError'],'REPLAY_ACTIVE')
        self.assertEqual((await self.b.motion_command('forward'))['error'],'REPLAY_ACTIVE')
        self.assertEqual(self.b.sim_commands,count)
        await self.b.lab_command('replay','pause');elapsed=self.b.replay_time
        await asyncio.sleep(.1);self.assertAlmostEqual(self.b.replay_time,elapsed,places=2)
        await self.b.lab_command('replay','stop');self.assertEqual(self.b.phase,'disconnected')

if __name__=='__main__':unittest.main()
