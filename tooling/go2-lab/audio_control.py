"""Local prerecorded clip -> existing WebRTC audio sender; no microphone capture."""
import asyncio
import io
import json
from pathlib import Path
import time
import wave

MAX_BYTES = 10 * 1024 * 1024
MAX_SECONDS = 60
VUI = 'rt/api/vui/request'


def normalize(raw):
    """Decode in memory, with no URL/protocol input, and bound decoded duration."""
    import av
    if not 0 < len(raw) <= MAX_BYTES:
        raise ValueError('FILE_SIZE')
    # Select a self-contained audio demuxer by signature. Never accept playlists/URLs.
    if raw[:4] == b'RIFF' and raw[8:12] == b'WAVE': container = 'wav'
    elif raw[:3] == b'ID3' or (len(raw)>1 and raw[0]==255 and raw[1]&0xe0==0xe0): container = 'mp3'
    elif raw[4:8] == b'ftyp': container = 'mov'
    elif raw[:4] == b'fLaC': container = 'flac'
    elif raw[:4] == b'OggS': container = 'ogg'
    else: raise ValueError('INVALID_AUDIO')
    output = io.BytesIO()
    count = 0
    try:
        with av.open(io.BytesIO(raw), format=container, options={'protocol_whitelist':'', 'enable_drefs':'0'}) as source, wave.open(output, 'wb') as target:
            if not source.streams.audio or source.streams.video:
                raise ValueError('INVALID_AUDIO')
            target.setnchannels(1); target.setsampwidth(2); target.setframerate(48000)
            resampler = av.AudioResampler(format='s16', layout='mono', rate=48000)
            for frame in source.decode(audio=0):
                for converted in resampler.resample(frame):
                    count += converted.samples
                    if count > MAX_SECONDS * 48000:
                        raise ValueError('DURATION_LIMIT')
                    target.writeframes(converted.to_ndarray().tobytes())
            for converted in resampler.resample(None):
                count += converted.samples
                if count > MAX_SECONDS * 48000: raise ValueError('DURATION_LIMIT')
                target.writeframes(converted.to_ndarray().tobytes())
        if not count: raise ValueError('INVALID_AUDIO')
        return output.getvalue(), round(count / 48000, 3)
    except ValueError:
        raise
    except Exception:
        raise ValueError('INVALID_AUDIO') from None


def playback_track():
    """Keep EOF inside this track: aiortc permanently exits a sender on track EOF."""
    from aiortc import AudioStreamTrack, MediaStreamError
    from av import AudioFrame
    from fractions import Fraction
    class ClipTrack(AudioStreamTrack):
        def __init__(self):
            super().__init__()
            self.source = None
            self.origin = time.monotonic()
            self.pts = -960
        async def recv(self):
            source = self.source
            frame = None
            if source:
                try: frame = await asyncio.wait_for(source.recv(), .5)
                except TimeoutError: pass
                except MediaStreamError:
                    if self.source is source: self.source = None
            if frame is None or source is not self.source:
                await asyncio.sleep(.02)
                frame = AudioFrame(format='s16', layout='stereo', samples=960)
                for plane in frame.planes: plane.update(bytes(plane.buffer_size))
                frame.sample_rate = 48000
            self.pts = max(self.pts + frame.samples, round((time.monotonic()-self.origin)*48000))
            frame.pts = self.pts; frame.time_base = Fraction(1,48000)
            return frame
    return ClipTrack()


class AudioControl:
    def __init__(self, bridge, directory=None):
        self.bridge = bridge
        self.directory = Path(directory or Path(__file__).resolve().parents[2] / 'out/go2-lab/audio')
        self.path = self.directory / 'clip.wav'
        self.imported_path = self.path
        self.clip = None
        try:
            meta = json.loads((self.directory / 'clip.json').read_text())
            if self.path.is_file() and isinstance(meta['name'], str) and 0 < meta['duration'] <= MAX_SECONDS:
                self.clip = meta
        except (OSError, ValueError, KeyError, TypeError): pass
        try:
            from audio_library import ROOT, read_preset
            selected = json.loads((self.directory / 'selection.json').read_text())['presetId']
            item, _ = read_preset(selected)
            self.path = ROOT / item['file']
            self.clip = {'name': item['title']['zh'] + '.wav', 'duration': item['duration'], 'presetId': selected}
        except (OSError, ValueError, KeyError, TypeError): pass
        self.volume = None
        self.state = 'idle'
        self.error = None
        self.busy = False
        self.sender = self.player = self.play_task = None
        self.stream = None
        self.permit = None
        self.future = None
        self.generation = 0
        self.rpc_id = int(time.time()*1000) % 2147483647
        self.play_started = 0
        self.sim_volume = 3
        from audio_hub import AudioHub
        self.hub = AudioHub(self.rpc)
        self.hub_task = None
        self.hub_active = False
        self.transfer_progress = 0
        self.prepared_preset = None

    def ready(self):
        b = self.bridge
        return bool(b.phase == 'connected' and b.conn and b.conn.isConnected and b.conn.datachannel.data_channel_opened)

    def snapshot(self):
        duration = self.clip['duration'] if self.clip else 0
        elapsed = min(duration, time.monotonic() - self.play_started) if self.state == 'streaming' else duration if self.state == 'finished' else 0
        return {'clip': self.clip, 'volume': self.volume if self.ready() else None, 'elapsed': max(0, elapsed),
                'state': self.state, 'busy': self.busy or self.state == 'preparing', 'error': self.error,
                'preparedPreset': self.prepared_preset, 'transferProgress': self.transfer_progress, 'playbackMethod': 'device-file' if self.clip and self.clip.get('presetId') and not self.bridge.simulated else 'webrtc',
                'connected': self.ready(), 'simulated': self.bridge.simulated,
                'replay': self.bridge.phase == 'replay', 'maxBytes': MAX_BYTES, 'maxSeconds': MAX_SECONDS}

    def allows(self, message):
        if self.permit is None or message != self.permit: return False
        self.permit = None  # One exact, explicitly requested volume RPC, not a topic-wide grant.
        return True

    async def transport_status(self):
        if not self.ready() or self.bridge.simulated: return {'connected': self.ready(), 'audio': []}
        pc = self.bridge.conn.pc
        report = await pc.getStats()
        audio = []
        for item in report.values():
            if getattr(item, 'kind', None) == 'audio':
                audio.append({name: getattr(item, name) for name in
                    ('type','packetsSent','bytesSent','packetsReceived','packetsLost','fractionLost','roundTripTime')
                    if hasattr(item, name)})
        return {'connected': self.ready(), 'audio': audio,
                'directions': [t.currentDirection for t in pc.getTransceivers() if t.kind == 'audio'],
                'track': self.stream.readyState if self.stream else None}

    async def rpc(self, api, parameter=None, topic=VUI):
        if not self.ready(): raise RuntimeError('NOT_CONNECTED')
        if self.bridge.simulated:
            if api == 1003: self.sim_volume = parameter['volume']
            return {'volume': self.sim_volume}
        channel = self.bridge.conn.datachannel.channel
        # Go2's WebRTC gateway clamps larger JSON IDs to signed int32 max.
        # Keep exact request/reply correlation without accepting a mismatched ID.
        self.rpc_id = self.rpc_id % 2147483647 + 1
        identity = {'id': self.rpc_id, 'api_id': api}
        future = asyncio.get_running_loop().create_future()
        self.future = future
        def receive(raw):
            try:
                m = json.loads(raw); data = m['data']; header = data['header']
                if (m.get('type') != 'res' or m.get('topic') not in {topic, topic.replace('/request','/response')}
                        or type(header['identity'].get('id')) is not int
                        or header['identity'].get('id') != identity['id']
                        or type(header['identity'].get('api_id')) is not int
                        or header['identity'].get('api_id') != api or future.done()): return
                code = header.get('status', {}).get('code')
                if type(code) is not int: return
                if code != 0: future.set_exception(RuntimeError('VOLUME_REJECTED' if topic == VUI else 'AUDIO_REJECTED')); return
                payload = data.get('data') or '{}'
                future.set_result(json.loads(payload) if isinstance(payload, str) else payload)
            except (ValueError, TypeError, KeyError, AttributeError): pass
        channel.on('message', receive)
        data = {'header': {'identity': identity}, 'parameter': json.dumps(parameter or {})}
        self.permit = {'type':'req', 'topic':topic, 'data':data}
        try:
            channel.send(json.dumps(self.permit))
            return await asyncio.wait_for(future, 3)
        finally:
            self.permit = None
            channel.remove_listener('message', receive)
            self.future = None

    async def select_preset(self, identity):
        if self.busy or self.state in {'streaming', 'preparing'}: return {**self.snapshot(), 'requestError': 'AUDIO_BUSY'}
        from audio_library import ROOT, read_preset
        self.busy = True
        failure = None
        try:
            item, _ = await asyncio.to_thread(read_preset, identity)
            self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
            temporary = self.directory / 'selection.tmp'
            temporary.write_text(json.dumps({'presetId': identity}));temporary.replace(self.directory / 'selection.json')
            self.path = ROOT / item['file']
            self.clip = {'name': item['title']['zh'] + '.wav', 'duration': item['duration'], 'presetId': identity}
            self.error = None; self.state = 'idle'
        except (ValueError, OSError, KeyError):
            failure = 'INVALID_PRESET'
        finally: self.busy = False
        return {**self.snapshot(), **({'requestError': failure} if failure else {})}

    async def import_clip(self, raw, name):
        if self.busy or self.state in {'streaming','preparing'}: return {**self.snapshot(), 'requestError':'AUDIO_BUSY'}
        self.busy = True
        try:
            pcm, duration = await asyncio.to_thread(normalize, raw)
            self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
            clip = {'name': Path(name).name[:100] or 'audio.wav', 'duration':duration}
            temporary = self.directory / 'clip.tmp'
            temporary.write_bytes(pcm); temporary.chmod(0o600); temporary.replace(self.imported_path)
            (self.directory / 'clip.json').write_text(json.dumps(clip, ensure_ascii=False))
            (self.directory / 'selection.json').unlink(missing_ok=True)
            self.path = self.imported_path
            self.clip, self.error, self.state = clip, None, 'idle'
        except ValueError as error:
            self.error = str(error) if str(error) in {'FILE_SIZE','DURATION_LIMIT','INVALID_AUDIO'} else 'INVALID_AUDIO'
        except OSError: self.error = 'SAVE_FAILED'
        finally: self.busy = False
        return self.snapshot()

    def stop(self):
        self.generation += 1
        if self.hub_task:
            if self.hub_task is not asyncio.current_task(): self.hub_task.cancel()
            self.hub_task = None
        if self.play_task:
            self.play_task.cancel(); self.play_task = None
        if self.stream: self.stream.source = None
        if self.sender:
            self.sender.replaceTrack(None); self.sender = None
        if self.player:
            if self.player.audio: self.player.audio.stop()
            self.player = None
        self.state = 'idle'

    def disconnected(self):
        self.stop(); self.volume = None
        self.hub_active = False; self.prepared_preset = None
        if self.stream: self.stream.stop(); self.stream = None
        if self.future and not self.future.done(): self.future.set_exception(RuntimeError('NOT_CONNECTED'))

    async def finish(self, generation, duration):
        # Poll track termination too: a decoder failure must not appear to keep playing.
        started = time.monotonic()
        while generation == self.generation:
            await asyncio.sleep(.1)
            if not self.ready():
                self.disconnected(); return
            ended = self.player and self.player.audio.readyState == 'ended'
            if ended or time.monotonic()-started > duration + .5:
                self.play_task = None
                self.stop()
                self.state = 'finished'
                return

    async def prepare_preset_on_device(self, generation):
        current = lambda: generation == self.generation and self.ready()
        try:
            raw = await asyncio.to_thread(self.path.read_bytes)
            def progress(value): self.transfer_progress = value
            if not self.bridge.simulated:
                await asyncio.wait_for(self.hub.ensure(raw, current, progress), 50)
            if current():
                self.prepared_preset = self.clip['presetId']
                self.transfer_progress = 100
                self.state = 'idle'
        except asyncio.CancelledError:
            raise
        except Exception as error:
            if current():
                self.error = str(error) if str(error) in {'AUDIO_REJECTED', 'AUDIO_NOT_SAVED'} else 'AUDIO_TIMEOUT' if isinstance(error, TimeoutError) else 'AUDIO_FAILED'
                self.state = 'idle'
        finally:
            if self.hub_task is asyncio.current_task(): self.hub_task = None

    async def play_preset_on_device(self, generation):
        current = lambda: generation == self.generation and self.ready()
        try:
            raw = await asyncio.to_thread(self.path.read_bytes)
            def progress(value): self.transfer_progress = value
            identity = await asyncio.wait_for(self.hub.ensure(raw, current, progress), 50)
            if not current(): return
            self.prepared_preset = self.clip['presetId']
            # Set before awaiting play so concurrent Stop will send Pause too.
            self.hub_active = True
            await self.hub.play(identity, current)
            if not current(): return
            self.state = 'streaming'; self.play_started = time.monotonic()
            await asyncio.sleep(self.clip['duration'] + .25)
            if current():
                await self.hub.stop()
                self.hub_active = False; self.state = 'finished'
        except asyncio.CancelledError:
            raise
        except Exception as error:
            if current():
                self.error = str(error) if str(error) in {'AUDIO_REJECTED', 'AUDIO_NOT_SAVED'} else 'AUDIO_TIMEOUT' if isinstance(error, TimeoutError) else 'AUDIO_FAILED'
                self.state = 'idle'
        finally:
            if self.hub_task is asyncio.current_task(): self.hub_task = None

    async def read_volume(self, generation, expected=None):
        # SetVolume acknowledges receipt before the amplifier applies it. Read
        # until confirmed; never repeat the write (it makes the robot chime).
        deadline = time.monotonic() + 2
        for attempt in range(10):
            if generation != self.generation: raise RuntimeError('CANCELLED')
            try:
                result = await self.rpc(1004)
            except TimeoutError:
                if attempt == 0:
                    await asyncio.sleep(.15)
                    continue
                raise
            volume = result.get('volume') if isinstance(result, dict) else None
            if type(volume) is not int or not 0 <= volume <= 10: raise RuntimeError('INVALID_VOLUME')
            if generation != self.generation: raise RuntimeError('CANCELLED')
            self.volume = volume
            if expected is None or volume == expected: return
            if time.monotonic() >= deadline: break
            await asyncio.sleep(.2)
        raise RuntimeError('VOLUME_MISMATCH')

    async def command(self, action, value):
        if action == 'stop':
            native = self.hub_active
            self.stop(); self.error = None
            if native and self.ready():
                try:
                    await self.hub.stop();self.hub_active = False
                except Exception:self.error = 'AUDIO_STOP_UNCONFIRMED'
            return self.snapshot()
        if self.busy or self.state == 'preparing': return {**self.snapshot(), 'requestError':'AUDIO_BUSY'}
        if not self.ready(): return {**self.snapshot(), 'requestError':'NOT_CONNECTED'}
        self.busy = True
        generation = self.generation
        try:
            if action in {'volume', 'refresh'}:
                if action == 'volume':
                    await self.rpc(1003, {'volume':value['volume']})
                await self.read_volume(generation, value['volume'] if action == 'volume' else None)
            elif action == 'prepare':
                if self.state == 'streaming': raise RuntimeError('AUDIO_BUSY')
                if not self.clip or not self.clip.get('presetId'): raise RuntimeError('NO_CLIP')
                self.stop()
                self.state = 'preparing'; self.transfer_progress = 0; self.error = None
                self.hub_task = asyncio.create_task(self.prepare_preset_on_device(self.generation))
                return self.snapshot()
            elif action == 'play':
                if not self.clip: raise RuntimeError('NO_CLIP')
                # Require a successfully read device volume before the first playback.
                if self.volume is None: raise RuntimeError('READ_VOLUME_FIRST')
                self.stop()
                if self.clip.get('presetId') and not self.bridge.simulated:
                    self.state = 'preparing';self.transfer_progress = 0;self.error = None
                    self.hub_task = asyncio.create_task(self.play_preset_on_device(self.generation))
                    return self.snapshot()
                if not self.bridge.simulated:
                    from aiortc.contrib.media import MediaPlayer
                    transceivers = self.bridge.conn.pc.getTransceivers()
                    transceiver = next((t for t in transceivers if t.kind == 'audio' and t.currentDirection in {'sendrecv','sendonly'}), None)
                    if transceiver is None: raise RuntimeError('AUDIO_NOT_NEGOTIATED')
                    player = MediaPlayer(str(self.path))
                    if not player.audio: raise RuntimeError('INVALID_AUDIO')
                    self.player = player
                    self.sender = transceiver.sender
                    if self.stream is None: self.stream = playback_track()
                    self.stream.source = player.audio
                    self.sender.replaceTrack(self.stream)
                self.state = 'streaming'
                self.play_started = time.monotonic()
                self.play_task = asyncio.create_task(self.finish(self.generation, self.clip['duration']))
            self.error = None
        except Exception as error:
            if action == 'play': self.stop()
            if action in {'volume','refresh'} and str(error) != 'VOLUME_MISMATCH': self.volume = None
            known = {'NOT_CONNECTED','VOLUME_REJECTED','INVALID_VOLUME','VOLUME_MISMATCH','CANCELLED',
                     'NO_CLIP','AUDIO_BUSY','READ_VOLUME_FIRST','AUDIO_NOT_NEGOTIATED','INVALID_AUDIO'}
            self.error = str(error) if str(error) in known else 'AUDIO_TIMEOUT' if isinstance(error, TimeoutError) else 'AUDIO_FAILED'
        finally: self.busy = False
        return self.snapshot()


def valid_command(action, value):
    if not isinstance(value, dict): return False
    if action == 'volume':
        return set(value) == {'volume'} and type(value['volume']) is int and 0 <= value['volume'] <= 10
    return action in {'refresh','play','stop','prepare'} and value == {}
