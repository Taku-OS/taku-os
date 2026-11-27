"""Camera tests are offline; no connection to the robot."""
import asyncio
import io
import json
from pathlib import Path
import struct
import sys
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from camera import Camera, jpeg


def frame():
    import av,numpy as np
    return av.VideoFrame.from_ndarray(np.full((360,640,3),80,dtype=np.uint8),format='rgb24')


def unpack(packet):
    n=struct.unpack('<I',packet[:4])[0]
    return json.loads(packet[4:4+n]),packet[4+n:]


class CameraTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.bridge=SimpleNamespace(simulated=True,phase='connected',conn=SimpleNamespace(isConnected=True,datachannel=SimpleNamespace(data_channel_opened=True)))
        self.camera=Camera(self.bridge)
    def tearDown(self): self.camera.disconnect()
    async def publish(self):
        self.camera.offer(frame())
        if self.camera.encoder: await self.camera.encoder
    async def test_off_start_frame_same_key_stop(self):
        await self.publish();self.assertFalse(unpack(self.camera.packet())[1])
        await self.camera.command('start');await self.publish()
        meta,data=unpack(self.camera.packet());self.assertTrue(meta['fresh']);self.assertEqual(meta['source'],'simulation')
        import av
        with av.open(io.BytesIO(data),format='mjpeg') as decoder:
            image=next(decoder.decode(video=0));self.assertEqual((image.width,image.height),(640,360))
        same,empty=unpack(self.camera.packet(meta['frameKey']));self.assertTrue(same['fresh']);self.assertFalse(empty)
        await self.camera.command('stop');self.assertFalse(unpack(self.camera.packet())[1])
    async def test_stale_disconnect_and_replay_never_return_old_image(self):
        await self.camera.command('start');await self.publish()
        self.camera.received_at=time.monotonic()-1.1
        meta,data=unpack(self.camera.packet());self.assertFalse(meta['fresh']);self.assertFalse(data)
        self.bridge.phase='replay'
        self.assertEqual((await self.camera.command('start'))['requestError'],'NOT_CONNECTED')
        self.assertEqual(unpack(self.camera.packet())[0]['source'],'replay')
        self.camera.disconnect();self.assertFalse(self.camera.enabled);self.assertFalse(self.camera.image)
    async def test_stop_during_encode_cannot_restore_image(self):
        await self.camera.command('start')
        def slow(_): time.sleep(.05);return b'jpeg',640,360
        with patch('camera.jpeg',side_effect=slow):
            self.camera.offer(frame());task=self.camera.encoder
            await asyncio.sleep(.01);await self.camera.command('stop');await task
        self.assertFalse(self.camera.image);self.assertEqual(self.camera.sequence,0)
    async def test_one_encoder_drops_frames_instead_of_queueing(self):
        await self.camera.command('start')
        def slow(_): time.sleep(.12);return b'jpeg',640,360
        with patch('camera.jpeg',side_effect=slow) as encode:
            self.camera.offer(frame());task=self.camera.encoder
            for _ in range(20): self.camera.offer(frame())
            self.assertIs(task,self.camera.encoder);await task
            self.assertEqual(encode.call_count,1)
    async def test_switch_grants_only_one_exact_video_message(self):
        self.bridge.simulated=False;sent=[]
        self.camera.reader=asyncio.create_task(asyncio.Event().wait())
        def send(raw):
            msg=json.loads(raw)
            self.assertFalse(self.camera.allows({**msg,'type':'aud'}))
            self.assertFalse(self.camera.allows({**msg,'data':'arbitrary'}))
            self.assertTrue(self.camera.allows(msg));self.assertFalse(self.camera.allows(msg));sent.append(msg)
        self.bridge.conn.datachannel.channel=SimpleNamespace(send=send)
        await self.camera.command('start');await self.camera.command('start');await self.camera.command('stop')
        self.assertEqual(sent,[{'type':'vid','topic':'','data':'on'},{'type':'vid','topic':'','data':'off'}])
        self.assertIsNone(self.camera.permit)
    async def test_end_of_track_and_reconnect(self):
        class Track:
            kind='video'
            count=0
            async def recv(track):
                track.count+=1
                if track.count==1: return frame()
                await asyncio.sleep(.05)
                raise RuntimeError('end')
        self.bridge.conn.pc=SimpleNamespace(getReceivers=lambda:[SimpleNamespace(track=Track())])
        await self.camera.command('start');self.camera.attach();await self.camera.reader
        self.assertEqual(self.camera.error,'CAMERA_STREAM_ENDED');self.assertFalse(self.camera.image)
        old=self.camera.snapshot()['frameKey'];self.camera.disconnect()
        await self.camera.command('start');await self.publish()
        self.assertNotEqual(old,self.camera.snapshot()['frameKey']);self.assertTrue(self.camera.snapshot()['fresh'])
    def test_large_frame_scales_down(self):
        import av,numpy as np
        source=av.VideoFrame.from_ndarray(np.zeros((1080,1920,3),dtype=np.uint8),format='rgb24')
        data,w,h=jpeg(source);self.assertEqual((w,h),(960,540));self.assertLess(len(data),1024*1024)

if __name__=='__main__':unittest.main()
