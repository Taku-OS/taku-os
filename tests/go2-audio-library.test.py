import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
import wave
import json
import hashlib
import io
import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from audio_library import read_preset, public_catalog
from audio_control import AudioControl

class LibraryTests(unittest.IsolatedAsyncioTestCase):
    async def test_bundled_assets_decode_and_have_attribution(self):
        clips=public_catalog()['clips']
        self.assertEqual(len(clips),27)
        self.assertEqual(len({c['id'] for c in clips}),27)
        english=[c for c in clips if c.get('language')=='en-US']
        self.assertEqual(len(english),21)
        for c in english:
            self.assertTrue(c['synthetic']);self.assertTrue(c['transcript'])
            self.assertTrue(c['description']['zh']);self.assertTrue(c['voiceStyle']['en'])
        for c in clips:
            item,raw=read_preset(c['id'])
            with wave.open(io.BytesIO(raw)) as w:
                self.assertEqual(w.getnchannels(),1);self.assertEqual(w.getframerate(),48000)
                self.assertAlmostEqual(w.getnframes()/48000,c['duration'],places=2)
            for k in ['source','license','licenseUrl','author','changes']:self.assertTrue(c[k])
        for invalid in ['../clip','http://example.com','hello.wav',None,{},'unknown']:
            with self.assertRaises(ValueError):read_preset(invalid)

    async def test_tampered_file_is_rejected(self):
        item,raw=read_preset('hello')
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'catalog.json').write_text(json.dumps({'clips':[item]}));(root/item['file']).write_bytes(raw+b'x')
            with self.assertRaisesRegex(ValueError,'PRESET_INTEGRITY'):read_preset('hello',root)

    async def test_selection_persists_without_overwriting_the_imported_recording(self):
        bridge=SimpleNamespace(phase='connected',simulated=True,conn=SimpleNamespace(isConnected=True,datachannel=SimpleNamespace(data_channel_opened=True)))
        with tempfile.TemporaryDirectory() as d:
            audio=AudioControl(bridge,d);_,raw=read_preset('hello')
            await audio.import_clip(raw,'my-recording.wav');before=(Path(d)/'clip.wav').read_bytes()
            result=await audio.select_preset('thanks');self.assertEqual(result['clip']['presetId'],'thanks')
            self.assertEqual((Path(d)/'clip.wav').read_bytes(),before)
            restored=AudioControl(bridge,d);self.assertEqual(restored.clip['presetId'],'thanks')
            await audio.command('refresh',{});await audio.command('play',{})
            self.assertEqual((await audio.select_preset('hello'))['requestError'],'AUDIO_BUSY')
            audio.stop();await audio.import_clip(raw,'replacement.wav')
            self.assertNotIn('presetId',AudioControl(bridge,d).clip)

    async def test_real_preset_uses_device_player_and_stop_requires_pause_receipt(self):
        bridge=SimpleNamespace(phase='connected',simulated=False,conn=SimpleNamespace(isConnected=True,datachannel=SimpleNamespace(data_channel_opened=True)))
        with tempfile.TemporaryDirectory() as d:
            audio=AudioControl(bridge,d);await audio.select_preset('hello');audio.volume=2
            audio.hub=SimpleNamespace(ensure=AsyncMock(return_value='device-id'),play=AsyncMock(),stop=AsyncMock())
            self.assertEqual((await audio.command('play',{}))['state'],'preparing')
            for _ in range(30):
                if audio.state=='streaming':break
                await asyncio.sleep(.01)
            self.assertEqual(audio.state,'streaming');audio.hub.play.assert_awaited_once()
            self.assertEqual((await audio.command('stop',{}))['state'],'idle')
            audio.hub.stop.assert_awaited_once();self.assertFalse(audio.hub_active)
            audio.hub_active=True;audio.hub.stop.side_effect=TimeoutError()
            self.assertEqual((await audio.command('stop',{}))['error'],'AUDIO_STOP_UNCONFIRMED')

    async def test_stop_during_transfer_prevents_late_play(self):
        bridge=SimpleNamespace(phase='connected',simulated=False,conn=SimpleNamespace(isConnected=True,datachannel=SimpleNamespace(data_channel_opened=True)))
        with tempfile.TemporaryDirectory() as d:
            audio=AudioControl(bridge,d);await audio.select_preset('hello');audio.volume=2
            started=asyncio.Event()
            async def ensure(*args):started.set();await asyncio.Future()
            audio.hub=SimpleNamespace(ensure=ensure,play=AsyncMock(),stop=AsyncMock())
            await audio.command('play',{});await started.wait()
            self.assertEqual((await audio.select_preset('thanks'))['requestError'],'AUDIO_BUSY')
            await audio.command('stop',{});await asyncio.sleep(0)
            self.assertEqual(audio.state,'idle');audio.hub.play.assert_not_awaited()

    async def test_prepare_uploads_without_playing_and_disconnect_invalidates_ready(self):
        bridge=SimpleNamespace(phase='connected',simulated=False,conn=SimpleNamespace(isConnected=True,datachannel=SimpleNamespace(data_channel_opened=True)))
        with tempfile.TemporaryDirectory() as d:
            audio=AudioControl(bridge,d);await audio.select_preset('en-taunt-enough')
            audio.hub=SimpleNamespace(ensure=AsyncMock(return_value='id'),play=AsyncMock(),stop=AsyncMock())
            result=await audio.command('prepare',{})
            self.assertEqual(result['state'],'preparing')
            await audio.hub_task
            self.assertEqual(audio.snapshot()['preparedPreset'],'en-taunt-enough')
            self.assertEqual(audio.state,'idle');self.assertIsNone(audio.volume)
            audio.hub.play.assert_not_awaited();audio.hub.stop.assert_not_awaited()
            audio.disconnected();self.assertIsNone(audio.snapshot()['preparedPreset'])

    async def test_prepare_cancellation_does_not_mark_clip_ready(self):
        bridge=SimpleNamespace(phase='connected',simulated=False,conn=SimpleNamespace(isConnected=True,datachannel=SimpleNamespace(data_channel_opened=True)))
        with tempfile.TemporaryDirectory() as d:
            audio=AudioControl(bridge,d);await audio.select_preset('en-taunt-enough')
            started=asyncio.Event()
            async def ensure(*args):started.set();await asyncio.Future()
            audio.hub=SimpleNamespace(ensure=ensure,play=AsyncMock(),stop=AsyncMock())
            await audio.command('prepare',{});await started.wait();audio.disconnected();await asyncio.sleep(0)
            self.assertIsNone(audio.prepared_preset);self.assertEqual(audio.state,'idle');audio.hub.play.assert_not_awaited()

if __name__=='__main__':unittest.main()
