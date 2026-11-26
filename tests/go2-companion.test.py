"""Companion controller retains locomotion; tests never contact a real robot."""
import asyncio
import json
from pathlib import Path
import socket
import subprocess
import sys
import time
import unittest
from unittest.mock import AsyncMock, Mock
from urllib.request import Request, build_opener, ProxyHandler
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / 'tooling/go2-lab'), str(ROOT / 'tooling/go2-lab/phone')]
from bridge import Bridge
from assistant import Assistant
from simulator import SimBridge


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.bridge = Bridge('192.0.2.1')
        self.bridge.external_motion_control = True

    def test_even_previously_authorized_movement_stop_and_mode_writes_are_blocked(self):
        b = self.bridge
        b.motion.allows = Mock(return_value=True)
        b.native.allows = Mock(return_value=True)
        for topic in ['rt/api/sport/request', 'rt/api/obstacles_avoid/request', 'rt/api/motion_switcher/request', 'rt/lowcmd']:
            for api in [1002, 1003, 1004, 1008]:
                message = {'type': 'req', 'topic': topic, 'data': {
                    'header': {'identity': {'id': 1, 'api_id': api}}, 'parameter': '{}'}}
                self.assertFalse(b.transport_allows(message), message)
        b.motion.allows.assert_not_called()
        b.native.allows.assert_not_called()
        self.assertFalse(b.motion_ready())

    def test_video_audio_and_telemetry_still_have_exact_permissions(self):
        b = self.bridge
        self.assertTrue(b.transport_allows({'type': 'subscribe', 'topic': 'rt/lf/lowstate'}))
        for component, message in [(b.camera, {'type': 'vid', 'topic': '', 'data': 'on'}),
                                   (b.audio, {'type': 'req', 'topic': 'rt/api/audiohub/request', 'data': {'id': 1}})]:
            self.assertFalse(b.transport_allows(message))
            component.permit = message
            self.assertTrue(b.transport_allows(message))
            self.assertFalse(b.transport_allows(message))


class AssistantTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.bridge = SimBridge()
        self.bridge.external_motion_control = True
        self.ai = Assistant(self.bridge)
        await self.bridge.command('connect')
        self.bridge.motion_command = AsyncMock()
        self.bridge.normal_mode = AsyncMock()

    async def asyncTearDown(self):
        await self.ai.cancel()
        await self.bridge.command('disconnect')

    async def test_startup_pause_stop_takeover_and_return_do_not_control_motors(self):
        self.assertTrue(self.ai.observation_only)
        await self.ai.observe()
        await self.ai.cancel(stop=True)
        await self.ai.set_mode('manual')
        await self.ai.set_mode('ai')
        self.assertTrue(Assistant(self.bridge).observation_only)
        self.bridge.motion_command.assert_not_called()
        self.bridge.normal_mode.assert_not_called()

    async def test_ai_follow_request_becomes_visual_guidance_and_cannot_start_servo(self):
        session = self.ai.begin()
        self.ai.prepare({**session, 'plan': {'actions': ['follow_start'], 'message': 'Track the person'}})
        await self.ai.task
        self.assertEqual(self.ai.job['results'][0]['result'], {'page': 'follow'})
        self.assertTrue(self.ai.observation_only)
        self.assertFalse(self.ai.follow.active)
        with self.assertRaisesRegex(ValueError, 'OBSERVATION_ONLY'):
            self.ai.follow.request()
        with self.assertRaisesRegex(ValueError, 'OBSERVATION_ONLY'):
            await self.ai.follow.start({'requestId': 'stale'})
        self.bridge.motion_command.assert_not_called()
        self.bridge.normal_mode.assert_not_called()

    async def test_camera_and_voice_actions_do_not_regain_motion_permission(self):
        await self.bridge.audio.select_preset('hello')
        session = self.ai.begin()
        self.ai.prepare({**session, 'plan': {'actions': ['camera_on', 'show_camera', 'show_audio', 'audio_play', 'audio_stop'], 'message': ''}})
        await self.ai.task
        self.assertEqual(self.ai.job['status'], 'completed')
        self.assertTrue(self.bridge.camera.snapshot()['fresh'])
        self.assertTrue(self.ai.observation_only)
        self.bridge.motion_command.assert_not_called()
        self.bridge.normal_mode.assert_not_called()


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); cls.port = sock.getsockname()[1]
        program = ("import sys,asyncio;sys.path[:0]=['tooling/go2-lab/phone','tooling/go2-lab'];"
                   f"import runtime,bridge;runtime.PORT=bridge.PORT={cls.port};"
                   "asyncio.run(runtime.serve('simulation',None,external_motion_control=True))")
        cls.process = subprocess.Popen([sys.executable, '-c', program], cwd=ROOT,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(100):
            try: cls.call('/api/state'); return
            except OSError: time.sleep(.05)
        cls.process.terminate(); cls.process.wait(timeout=10)
        raise RuntimeError('Offline runtime failed to start')

    @classmethod
    def tearDownClass(cls):
        cls.process.terminate(); cls.process.wait(timeout=10)

    @classmethod
    def call(cls, path, body=None):
        req = Request(f'http://127.0.0.1:{cls.port}'+path,
                      data=None if body is None else json.dumps(body).encode(),
                      headers={'Content-Type': 'application/json'})
        try:
            with build_opener(ProxyHandler({})).open(req, timeout=3) as r: return r.status, json.load(r)
        except HTTPError as r: return r.code, json.load(r)

    def test_motion_routes_denied_and_ui_lifecycle_is_local(self):
        self.assertTrue(self.call('/api/state')[1]['externalMotionControl'])
        for path in ['motion/forward', 'motion/backward', 'motion/left', 'motion/right', 'motion/arm',
                     'motion/normal', 'avoidance/forward', 'navigation/start', 'follow/start']:
            self.assertEqual(self.call('/api/'+path, {})[0], 409, path)
        for path, body in [('assistant/stop', {}), ('motion/stop', {}), ('assistant/cancel', {}),
                           ('assistant/mode', {'mode': 'manual'}), ('assistant/mode', {'mode': 'ai'})]:
            self.assertEqual(self.call('/api/'+path, body)[0], 200, path)
        self.assertTrue(self.call('/api/assistant/state')[1]['observationOnly'])
        self.assertEqual(self.call('/api/state')[1]['motion']['result'], 'locked')


if __name__ == '__main__': unittest.main()
