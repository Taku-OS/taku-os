import asyncio
import importlib.util
import math
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tooling/go2-lab'))
sys.path.insert(0, str(ROOT/'tooling/go2-lab/phone'))
from assistant import Assistant, validated_plan
from distance import displacement, valid_distance, valid_turn
from simulator import SimBridge


class DistanceTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.bridge = SimBridge(); await self.bridge.command('connect'); await asyncio.sleep(.15)
        self.ai = Assistant(self.bridge)

    async def asyncTearDown(self):
        await self.ai.cancel(); await self.bridge.command('disconnect')

    def request(self, distance=.5):
        session = self.ai.begin()
        self.ai.prepare({**session, 'plan': {'actions': [{'action':'move_distance','direction':'backward','distance_m':distance}], 'message':''}})
        return session

    async def keep_alive(self, session):
        while True:
            try: self.ai.heartbeat(session)
            except ValueError: return
            await asyncio.sleep(.2)

    async def test_backward_half_meter_uses_measured_feedback_and_restores_speed(self):
        await self.bridge.motion_speed({'linearSpeed':.15,'angularSpeed':.2})
        session=self.request(); self.assertEqual(self.ai.job['status'],'confirming')
        self.assertEqual(self.bridge.position[0],0)
        heartbeat=asyncio.create_task(self.keep_alive(session))
        self.ai.confirm(session)
        try: await asyncio.wait_for(self.ai.task,75)
        finally: heartbeat.cancel()
        self.assertEqual(self.ai.job['status'],'completed',self.ai.job)
        result=self.ai.job['results'][0]['result']
        self.assertGreaterEqual(result['traveledM'],.485)
        self.assertLess(result['traveledM'],.55)
        self.assertGreater(result['pulses'],1)
        self.assertEqual(result['result'],'stop_acknowledged')
        self.assertLess(self.bridge.position[0],-.48)
        self.assertFalse(self.bridge.motion.snapshot()['armed'])
        self.assertEqual(self.bridge.motion.linear_speed,.15)
        self.assertEqual(self.bridge.desired,(0,0,0))

    async def test_manual_takeover_cancels_distance_and_prevents_more_pulses(self):
        session=self.request(); self.ai.confirm(session); await asyncio.sleep(.2)
        result=await self.ai.set_mode('manual')
        self.assertEqual(result['job']['status'],'canceled')
        self.assertEqual(result['owner'],'manual')
        position=self.bridge.position[:]; await asyncio.sleep(.2)
        self.assertEqual(self.bridge.position,position)
        self.assertEqual(self.bridge.desired,(0,0,0))
        self.assertFalse(self.bridge.motion.snapshot()['armed'])

    async def test_missing_position_cannot_start_distance_motion(self):
        session=self.request()
        with patch('distance.pose',side_effect=ValueError('DISTANCE_POSE_UNAVAILABLE')):
            self.ai.confirm(session); await self.ai.task
        self.assertEqual(self.ai.job['error'],'DISTANCE_POSE_UNAVAILABLE')
        self.assertEqual(self.bridge.position[0],0)

    async def test_pose_failure_during_pulse_stops_before_next_pulse(self):
        session=self.request(); self.ai.confirm(session); await asyncio.sleep(.2)
        with patch('distance.pose',side_effect=ValueError('DISTANCE_POSE_UNAVAILABLE')):
            await self.ai.task
        self.assertEqual(self.ai.job['error'],'DISTANCE_POSE_UNAVAILABLE')
        self.assertEqual(self.bridge.desired,(0,0,0))
        self.assertFalse(self.bridge.motion.snapshot()['armed'])

    async def test_stop_failure_aborts_distance_without_another_pulse(self):
        original=self.bridge.motion.send_stop;calls=0
        async def stop():
            nonlocal calls
            calls+=1
            await original()
            if calls>=3:raise TimeoutError('fixture stop acknowledgment missing')
        self.bridge.motion.send_stop=stop
        session=self.request();self.ai.confirm(session);await self.ai.task
        self.assertEqual(self.ai.job['error'],'STOP_UNCONFIRMED')
        self.assertFalse(self.bridge.motion.snapshot()['armed'])
        self.assertLess(abs(self.bridge.position[0]),.15)

    async def test_no_progress_stops_instead_of_retrying_forever(self):
        self.bridge.motion.send_move=lambda vector:None
        session=self.request();heartbeat=asyncio.create_task(self.keep_alive(session));self.ai.confirm(session)
        try:await asyncio.wait_for(self.ai.task,15)
        finally:heartbeat.cancel()
        self.assertEqual(self.ai.job['error'],'DISTANCE_NO_PROGRESS')
        self.assertEqual(self.bridge.position[0],0)
        self.assertFalse(self.bridge.motion.snapshot()['armed'])

    def test_distance_shape_and_range_are_enforced(self):
        base={'action':'move_distance','direction':'backward','distance_m':.5}
        self.assertTrue(valid_distance(base))
        for value in (True,-.5,0,1.1,float('nan'),float('inf'),'0.5'):
            self.assertFalse(valid_distance({**base,'distance_m':value}))
        for plan in ([base]*4,[base,'forward','right','left'],[{**base,'speed':2}],[{**base,'direction':'jump'}]):
            with self.assertRaises(ValueError): validated_plan({'actions':plan,'message':''})

    async def test_left_turn_across_yaw_wrap_stops_at_measured_angle(self):
        self.bridge.yaw=math.radians(175);await asyncio.sleep(.1)
        session=self.ai.begin()
        self.ai.prepare({**session,'plan':{'actions':[{'action':'turn_angle','direction':'left','angle_deg':15}],'message':''}})
        self.assertEqual(self.ai.job['status'],'confirming')
        heartbeat=asyncio.create_task(self.keep_alive(session));self.ai.confirm(session)
        try:await asyncio.wait_for(self.ai.task,20)
        finally:heartbeat.cancel()
        self.assertEqual(self.ai.job['status'],'completed',self.ai.job)
        r=self.ai.job['results'][0]['result']
        self.assertGreaterEqual(r['turnedDegrees'],13);self.assertLess(r['turnedDegrees'],18)
        self.assertEqual(self.bridge.desired,(0,0,0))
        self.assertFalse(self.bridge.motion.snapshot()['armed'])
        self.assertEqual(self.bridge.motion.angular_speed,.15)

    def test_turn_and_volume_plans_are_bounded(self):
        base={'action':'turn_angle','direction':'left','angle_deg':45}
        self.assertTrue(valid_turn(base))
        for value in (True,0,181,float('nan')):self.assertFalse(valid_turn({**base,'angle_deg':value}))
        for volume in (-1,11,True,1.5):
            with self.assertRaises(ValueError):validated_plan({'actions':[{'action':'set_volume','volume':volume}],'message':''})

    def test_displacement_respects_heading_and_rejects_drift(self):
        self.assertAlmostEqual(displacement((3,2,math.pi/2),(3,1.5,math.pi/2),'backward',.5),.5)
        for pose,code in (((.05,0,.3),'DISTANCE_PATH_DRIFT'),((.1,.2,0),'DISTANCE_PATH_DRIFT'),((-.1,0,0),'DISTANCE_WRONG_DIRECTION'),((.7,0,0),'DISTANCE_OVERSHOOT')):
            with self.assertRaisesRegex(ValueError,code): displacement((0,0,0),pose,'forward',.5)


if __name__=='__main__': unittest.main()
