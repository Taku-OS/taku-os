import asyncio
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
import sys
from unittest.mock import AsyncMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tooling/go2-lab/phone'))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tooling/go2-lab'))
spec = importlib.util.spec_from_file_location('phone_assistant', Path(__file__).resolve().parents[1] / 'tooling/go2-lab/phone/assistant.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
Assistant, validated_plan = module.Assistant, module.validated_plan


class AssistantBoundaryTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self): self.ai = Assistant(SimpleNamespace(current_motion_mode='ai', phase='connected'))

    async def test_model_cannot_introduce_arbitrary_operations(self):
        for value in ({}, {'actions': ['curl http://example.com'], 'message': ''},
                      {'actions': ['forward']*4, 'message': ''},
                      {'actions': ['inspect'], 'message': '', 'shell': 'anything'},
                      {'actions': [None], 'message': ''}, {'actions': ['inspect'] * 9, 'message': ''}):
            with self.assertRaises(ValueError): validated_plan(value)

    async def test_no_action_from_planning_and_confirmation_is_required(self):
        session = self.ai.begin()
        self.assertIsNone(self.ai.task)
        self.ai.prepare({**session, 'plan': {'actions': ['forward'], 'message': '轻走一下'}})
        self.assertEqual(self.ai.job['status'], 'confirming')
        self.assertIsNone(self.ai.task)

    async def test_capability_is_not_in_public_state(self):
        session = self.ai.begin()
        self.assertNotIn(session['token'], str(self.ai.view()))
        with self.assertRaisesRegex(ValueError, 'TASK_EXPIRED'):
            self.ai.prepare({'id': session['id'], 'token': 'forged', 'plan': {'actions': [], 'message': ''}})

    async def test_old_result_cannot_run_after_owner_epoch_changes(self):
        session = self.ai.begin(); self.ai.epoch += 1; self.ai.owner = 'manual'
        with self.assertRaisesRegex(ValueError, 'TASK_EXPIRED'):
            self.ai.prepare({**session, 'plan': {'actions': ['camera_on'], 'message': ''}})

    async def test_heartbeat_expiry_rejects_a_late_plan(self):
        session = self.ai.begin(); self.ai.job['lastSeen'] -= 5
        with self.assertRaisesRegex(ValueError, 'TASK_EXPIRED'): self.ai.heartbeat(session)

    async def test_confirmation_is_single_use(self):
        session = self.ai.begin()
        self.ai.prepare({**session, 'plan': {'actions': ['forward'], 'message': ''}})
        self.ai.confirm(session)
        with self.assertRaises(ValueError): self.ai.confirm(session)
        await self.ai.task

    async def test_disconnected_mode_is_not_reported_from_cached_state(self):
        self.ai.bridge.phase = 'disconnected'
        self.assertIsNone(self.ai.view()['robotMode'])
        self.assertFalse(self.ai.view()['connected'])

    async def test_invalid_mode_does_not_change_owner(self):
        for value in ([], {}, None, 'anything'):
            with self.assertRaisesRegex(ValueError, 'INVALID_MODE'):
                await self.ai.set_mode(value)
            self.assertEqual(self.ai.owner, 'ai')


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tooling/go2-lab'))
from simulator import SimBridge


class AssistantExecutionTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.bridge = SimBridge(); self.ai = Assistant(self.bridge)
        await self.bridge.command('connect')
        await asyncio.sleep(.15)

    async def asyncTearDown(self):
        await self.ai.cancel(); await self.bridge.command('disconnect')

    async def test_status_and_camera_use_existing_controls(self):
        session = self.ai.begin()
        self.ai.prepare({**session, 'plan': {'actions': ['inspect', 'camera_on', 'show_camera'], 'message': ''}})
        await self.ai.task
        self.assertEqual(self.ai.job['status'], 'completed')
        self.assertTrue(self.ai.job['results'][1]['result']['fresh'])
        self.assertEqual(self.ai.job['results'][2]['result']['page'], 'camera')
        self.assertFalse(self.bridge.motion.snapshot()['armed'])

    async def test_observation_has_no_motor_or_mode_side_effects(self):
        self.bridge.motion_command = AsyncMock()
        self.bridge.normal_mode = AsyncMock()
        await self.ai.observe()
        session = self.ai.begin()
        self.ai.prepare({**session, 'plan': {'actions': ['show_follow'], 'message': ''}})
        await self.ai.task
        self.assertEqual(self.ai.job['results'][0]['result']['page'], 'follow')
        await self.ai.cancel(stop=True)
        await self.ai.set_mode('manual')
        await self.ai.set_mode('ai')
        self.bridge.motion_command.assert_not_called()
        self.bridge.normal_mode.assert_not_called()

    async def test_observation_rejects_motor_actions_even_in_a_mixed_model_plan(self):
        for actions in (['forward'], ['stop'], ['show_follow', 'forward'], [{'action': 'move_distance', 'direction': 'backward', 'distance_m': .5}]):
            await self.ai.observe()
            session = self.ai.begin()
            with self.assertRaisesRegex(ValueError, 'OBSERVATION_ONLY'):
                self.ai.prepare({**session, 'plan': {'actions': actions, 'message': ''}})
            self.assertIsNone(self.ai.task)
            await self.ai.cancel()

    async def test_observation_allows_sound_without_enabling_motion(self):
        self.bridge.motion_command = AsyncMock()
        self.bridge.normal_mode = AsyncMock()
        await self.ai.observe()
        await self.bridge.audio.select_preset('hello')
        session = self.ai.begin()
        self.ai.prepare({**session, 'plan': {'actions': ['show_audio', {'action': 'set_volume', 'volume': 2}, 'audio_play', 'audio_stop'], 'message': ''}})
        await self.ai.task
        self.assertEqual(self.ai.job['status'], 'completed')
        self.assertEqual(self.bridge.audio.snapshot()['volume'], 2)
        self.assertEqual(self.bridge.audio.snapshot()['state'], 'idle')
        self.assertTrue(self.ai.observation_only)
        self.bridge.motion_command.assert_not_called()
        self.bridge.normal_mode.assert_not_called()

    async def test_confirmed_single_pulse_stops_and_relocks(self):
        session = self.ai.begin()
        self.ai.prepare({**session, 'plan': {'actions': ['forward'], 'message': ''}})
        before = self.bridge.position[:]
        await asyncio.sleep(.1)
        self.assertEqual(self.bridge.position, before)
        self.ai.confirm(session); await self.ai.task
        self.assertEqual(self.ai.job['status'], 'completed')
        self.assertEqual(self.ai.job['results'][0]['result']['result'], 'stop_acknowledged')
        self.assertFalse(self.bridge.motion.snapshot()['armed'])

    async def test_takeover_cancels_inflight_motion_and_rejects_late_results(self):
        session = self.ai.begin()
        self.ai.prepare({**session, 'plan': {'actions': ['forward', 'camera_on'], 'message': ''}})
        self.ai.confirm(session); await asyncio.sleep(.1)
        state = await self.ai.set_mode('manual')
        self.assertEqual(state['owner'], 'manual')
        self.assertEqual(state['job']['status'], 'canceled')
        self.assertFalse(self.bridge.camera.enabled)
        self.assertFalse(self.bridge.motion.snapshot()['armed'])
        await self.ai.set_mode('ai')
        with self.assertRaises(ValueError): self.ai.confirm(session)

    async def test_takeover_checks_robot_mode_readback(self):
        self.bridge.current_motion_mode = 'ai'
        self.bridge.normal_mode = AsyncMock(return_value={'code': 0, 'state': {'mode': 'ai'}})
        state = await self.ai.set_mode('manual')
        self.assertEqual(state['error'], 'MODE_NOT_CONFIRMED')
        self.assertEqual(state['owner'], 'manual')
        with self.assertRaises(ValueError): self.ai.begin()

    async def test_missing_heartbeat_cancels_pending_plan(self):
        self.ai.begin(); self.ai.job['lastSeen'] -= 5
        task = asyncio.create_task(self.ai.monitor())
        try:
            await asyncio.sleep(.25)
            self.assertEqual(self.ai.job['status'], 'canceled')
            self.assertEqual(self.ai.job['error'], 'APP_NOT_FOREGROUND')
        finally:
            task.cancel()
            try: await task
            except asyncio.CancelledError: pass

    async def test_disconnected_mode_change_never_claims_robot_takeover(self):
        await self.bridge.command('disconnect')
        state = await self.ai.set_mode('manual')
        self.assertEqual(state['error'], 'NOT_CONNECTED')


if __name__ == '__main__': unittest.main()
