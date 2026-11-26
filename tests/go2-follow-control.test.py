"""Autonomous control tests use only SimBridge; no hardware or real transport is opened."""
import asyncio
import math
from pathlib import Path
import sys
import time
import unittest
from unittest.mock import AsyncMock
sys.path[:0] = [str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'), str(Path(__file__).resolve().parents[1]/'tooling/go2-lab/phone')]
from assistant import Assistant
from follow_control import decision
from simulator import SimBridge


class DecisionTests(unittest.TestCase):
    def test_distance_direction_and_speed(self):
        self.assertEqual(decision({'x': .5, 'ratio': 1})[0], 'hold')
        self.assertEqual(decision({'x': .5, 'ratio': 1.5})[:2], ('forward', .2))
        self.assertEqual(decision({'x': .5, 'ratio': .7})[:2], ('backward', .08))
        self.assertLess(decision({'x': .5, 'ratio': 1.3})[1], decision({'x': .5, 'ratio': 1.8})[1])
        self.assertEqual(decision({'x': .2, 'ratio': 2})[0], 'left')
        self.assertEqual(decision({'x': .8, 'ratio': 2})[0], 'right')


class FollowTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.b = SimBridge(); await self.b.command('connect'); await asyncio.sleep(.12)
        self.b.current_motion_mode = 'normal'
        self.moves = []
        send = self.b.motion.send_move
        self.b.motion.send_move = lambda vector: (self.moves.append(vector), send(vector))
        self.ai = Assistant(self.b); self.c = self.ai.follow
        self.frame_times = {}; self.fresh = True; self.seq = 0
        self.b.camera.snapshot = lambda: {'fresh': self.fresh, 'source': 'simulation'}
        self.b.camera.frame_time = self.frame_times.get
        self.c.request(); self.lease = await self.c.start({'requestId': self.c.request_id})

    async def asyncTearDown(self):
        await self.c.stop(); await self.b.command('disconnect')

    def sample(self, x=.5, ratio=1.5, target=True, age=0, identity=1):
        self.seq += 1; key = str(self.seq); self.frame_times[key] = time.monotonic()-age
        return {'token': self.lease['token'], 'frameKey': key,
                'target': {'x': x, 'ratio': ratio, 'id': identity} if target else None}

    async def feed(self, duration=.6, **kwargs):
        end = time.monotonic()+duration
        while time.monotonic()<end:
            if self.c.active: self.c.observe(self.sample(**kwargs))
            await asyncio.sleep(.07)

    async def test_closed_loop_moves_then_holds_and_restores_speed(self):
        await self.feed(.65)
        self.assertGreater(len(self.moves), 0)
        self.assertGreater(self.b.position[0], 0)
        await self.feed(.4, ratio=1)
        self.assertEqual(self.b.desired, (0,0,0))
        await self.c.stop(); self.assertFalse(self.b.motion.snapshot()['armed'])
        self.assertEqual(self.b.motion.linear_speed, .08)
        self.assertNotIn(self.lease['token'], str(self.ai.view()))

    async def test_lost_target_interrupts_pulse_and_reacquires(self):
        await self.feed(.3)
        self.c.observe(self.sample(target=False))
        await asyncio.sleep(.09)
        self.assertEqual(self.b.desired, (0,0,0))
        before = len(self.moves)
        await self.feed(.3, target=False)
        self.assertEqual(len(self.moves), before)
        await self.feed(.5)
        self.assertGreater(len(self.moves), before)

    async def test_stale_unknown_and_duplicate_camera_keys_never_move(self):
        for value in [self.sample(age=2), {**self.sample(), 'frameKey': 'invented'}]:
            with self.assertRaisesRegex(ValueError,'STALE_OBSERVATION'): self.c.observe(value)
        old = self.sample(age=.2)
        sample = self.sample(); self.c.observe(sample)
        with self.assertRaisesRegex(ValueError,'STALE_OBSERVATION'): self.c.observe(old)
        with self.assertRaisesRegex(ValueError,'STALE_OBSERVATION'): self.c.observe(sample)
        await asyncio.sleep(.1)
        self.assertEqual(len(self.moves), 0)

    async def test_observation_contract_and_session_token(self):
        for ratio in (math.nan, True, '1', 0, 99):
            with self.assertRaisesRegex(ValueError, 'INVALID_OBSERVATION'): self.c.observe(self.sample(ratio=ratio))
        with self.assertRaisesRegex(ValueError, 'FOLLOW_EXPIRED'):
            self.c.observe({**self.sample(), 'token': 'bad'})
        self.assertEqual(len(self.moves), 0)

    async def test_camera_loss_and_no_browser_heartbeat_stop(self):
        await self.feed(.3); self.fresh = False
        await asyncio.sleep(.1); self.assertEqual(self.b.desired, (0,0,0))
        self.c.last_seen -= 3
        await asyncio.sleep(.1); self.assertFalse(self.c.active)
        self.assertEqual(self.c.error, 'FOLLOW_INPUT_EXPIRED')

    async def test_manual_takeover_revokes_late_observations(self):
        await self.feed(.3)
        await self.ai.set_mode('manual')
        before = len(self.moves)
        with self.assertRaisesRegex(ValueError, 'FOLLOW_EXPIRED'): self.c.observe(self.sample())
        await asyncio.sleep(.2)
        self.assertEqual(len(self.moves), before)
        self.assertEqual(self.b.desired, (0,0,0))

    async def test_disconnect_never_auto_rearms(self):
        await self.feed(.3); await self.b.command('disconnect')
        await asyncio.sleep(.1); self.assertFalse(self.c.active)
        await self.b.command('connect'); await asyncio.sleep(.1)
        with self.assertRaisesRegex(ValueError, 'FOLLOW_EXPIRED'): self.c.observe(self.sample())
        self.assertFalse(self.b.motion.snapshot()['armed'])

    async def test_request_is_single_use_and_offline_start_cannot_queue_movement(self):
        await self.c.stop(); await self.b.command('disconnect')
        self.c.request(); request = {'requestId': self.c.request_id}
        with self.assertRaisesRegex(ValueError, 'NOT_CONNECTED'): await self.c.start(request)
        with self.assertRaisesRegex(ValueError, 'FOLLOW_REQUEST_EXPIRED'): await self.c.start(request)
        self.assertFalse(self.c.active)

    async def test_failed_stop_halts_controller_without_new_pulses(self):
        await self.feed(.3)
        self.b.motion.send_stop = AsyncMock(side_effect=TimeoutError())
        await self.feed(.5)
        before = len(self.moves)
        self.assertFalse(self.c.active)
        await asyncio.sleep(.2)
        self.assertEqual(len(self.moves), before)
        self.assertEqual(self.c.error, 'STOP_UNCONFIRMED')

    async def test_model_follow_plan_cannot_include_other_motion(self):
        await self.c.stop()
        lease = self.ai.begin()
        with self.assertRaisesRegex(ValueError, 'INVALID_PLAN'):
            self.ai.prepare({**lease, 'plan': {'actions': ['follow_start','forward'], 'message': ''}})


if __name__ == '__main__': unittest.main()
