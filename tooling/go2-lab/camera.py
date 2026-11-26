"""Latest-frame camera preview. No recording, microphone or movement commands."""
import asyncio
from fractions import Fraction
import json
import struct
import time
import uuid

FPS = 12
STALE_SECONDS = 1.0


def jpeg(frame):
    import av
    if not 0 < frame.width <= 4096 or not 0 < frame.height <= 4096:
        raise ValueError('FRAME_SIZE')
    scale = min(1, 960/frame.width, 720/frame.height)
    width, height = max(2,int(frame.width*scale)//2*2), max(2,int(frame.height*scale)//2*2)
    ctx = av.CodecContext.create('mjpeg','w')
    # Avoid a worker pool per preview JPEG, especially costly inside PRoot.
    ctx.thread_count = 1
    ctx.width, ctx.height, ctx.pix_fmt = width, height, 'yuvj420p'
    ctx.time_base = Fraction(1,FPS)
    converted = frame.reformat(width=width,height=height,format='yuvj420p')
    converted.pts = 0
    data = b''.join(bytes(packet) for packet in ctx.encode(converted))
    data += b''.join(bytes(packet) for packet in ctx.encode(None))
    if not data or len(data) > 1024*1024: raise ValueError('FRAME_SIZE')
    return data, width, height


class Camera:
    def __init__(self, bridge):
        self.bridge = bridge
        self.enabled = False
        self.error = None
        self.reader = self.encoder = None
        self.permit = None
        self.generation = 0
        self.epoch = uuid.uuid4().hex[:8]
        self.clear()

    def clear(self):
        self.generation += 1
        self.image = b''
        self.received_at = None
        self.width = self.height = self.sequence = 0
        self.times = []
        self.last_encode = 0
        self.frame_times = {}

    def frame_time(self, key):
        return self.frame_times.get(key)

    def connected(self):
        b = self.bridge
        return bool(b.phase == 'connected' and b.conn and b.conn.isConnected and b.conn.datachannel.data_channel_opened)

    def snapshot(self):
        age = (time.monotonic()-self.received_at)*1000 if self.received_at is not None else None
        connected = self.connected()
        return {'enabled':self.enabled,'connected':connected,
                'source':'replay' if self.bridge.phase == 'replay' else 'simulation' if self.bridge.simulated else 'real',
                'fresh':bool(connected and self.enabled and self.image and age < STALE_SECONDS*1000),
                'ageMs':round(age) if age is not None else None,
                'frameKey':f'{self.epoch}:{self.generation}:{self.sequence}',
                'width':self.width,'height':self.height,'frames':self.sequence,
                'fps':round((len(self.times)-1)/(self.times[-1]-self.times[0]),1) if len(self.times)>1 else 0,
                'error':self.error}

    def packet(self, after=''):
        meta = self.snapshot()
        data = self.image if meta['fresh'] and meta['frameKey'] != after else b''
        header = json.dumps(meta,allow_nan=False).encode()
        return struct.pack('<I',len(header))+header+data

    def allows(self, message):
        if self.permit is None or message != self.permit: return False
        self.permit = None
        return True

    async def command(self, action):
        if action == 'start' and not self.connected():
            return {**self.snapshot(),'requestError':'NOT_CONNECTED'}
        if action == 'start' and not self.bridge.simulated and (self.reader is None or self.reader.done()):
            return {**self.snapshot(),'requestError':'NO_VIDEO_TRACK'}
        # Repeated start is idempotent; a deliberate stop/start resets the picture.
        if action == 'start' and self.enabled: return self.snapshot()
        self.clear(); self.error = None
        enabled = action == 'start'
        self.enabled = enabled
        if self.connected() and not self.bridge.simulated:
            self.permit = {'type':'vid','topic':'','data':'on' if enabled else 'off'}
            try:
                self.bridge.conn.datachannel.channel.send(json.dumps(self.permit))
            except Exception:
                self.enabled = False; self.error = 'CAMERA_SWITCH_FAILED'
            finally: self.permit = None
        return self.snapshot()

    def disconnect(self):
        self.enabled = False
        self.clear(); self.error = None
        for task in (self.reader,self.encoder):
            if task: task.cancel()
        self.reader = self.encoder = None

    def attach(self):
        # The upstream driver's video handler consumes one warm-up frame, then
        # has no registered callbacks. Own the remaining receive loop, even if
        # that first frame arrived before connect() completed.
        track = next((r.track for r in self.bridge.conn.pc.getReceivers()
                      if r.track and r.track.kind == 'video'),None)
        if track is None:
            self.error = 'NO_VIDEO_TRACK'; return
        self.reader = asyncio.create_task(self.consume(track))

    async def consume(self, track):
        try:
            while True:
                frame = await track.recv()
                self.offer(frame)
        except asyncio.CancelledError: raise
        except Exception:
            if self.enabled:
                self.clear(); self.error = 'CAMERA_STREAM_ENDED'

    def offer(self, frame):
        now = time.monotonic()
        if (not self.enabled or not self.connected() or now-self.last_encode < 1/FPS
                or self.encoder and not self.encoder.done()): return
        self.last_encode = now
        self.encoder = asyncio.create_task(self.encode(frame,now,self.generation))

    async def encode(self, frame, arrival, generation):
        try:
            data,width,height = await asyncio.to_thread(jpeg,frame)
            if generation != self.generation or not self.enabled or not self.connected(): return
            self.image,self.width,self.height = data,width,height
            self.received_at = arrival
            self.sequence += 1
            key = f'{self.epoch}:{self.generation}:{self.sequence}'
            self.frame_times = {k: t for k, t in self.frame_times.items() if arrival-t < 2}
            self.frame_times[key] = arrival
            self.times = [t for t in self.times if arrival-t<2]+[arrival]
            self.error = None
        except asyncio.CancelledError: raise
        except Exception:
            if generation == self.generation:
                self.image = b''; self.error = 'CAMERA_DECODE_FAILED'

    def simulate(self):
        """Explicit synthetic test pattern; never represents the robot's view."""
        if not self.enabled or not self.connected(): return
        import av
        import numpy as np
        if getattr(self.bridge,'scenario','').startswith('navigation-') or getattr(self.bridge,'scenario','')=='geo-route':
            from navigation_camera import render
            self.offer(av.VideoFrame.from_ndarray(render(self.bridge.position,self.bridge.yaw,outdoor=self.bridge.scenario=='geo-route',obstacle=getattr(self.bridge,'geo_obstacle',None)),format='rgb24'))
            return
        pixels = np.zeros((360,640,3),dtype=np.uint8)
        pixels[:180] = [72,103,119]; pixels[180:] = [54,66,56]
        x = int(time.monotonic()*90)%540
        pixels[100:240,x:x+100] = [224,174,80]
        pixels[::40,:,:] = [145,164,151]; pixels[:,::40,:] = [145,164,151]
        self.offer(av.VideoFrame.from_ndarray(pixels,format='rgb24'))
