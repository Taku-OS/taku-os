import asyncio
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
import wave
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from audio_control import AudioControl, normalize, valid_command, VUI


def wav(seconds=.1):
    data=io.BytesIO()
    with wave.open(data,'wb') as f:
        f.setnchannels(1);f.setsampwidth(2);f.setframerate(8000)
        f.writeframes(b'\x00\x00'*round(8000*seconds))
    return data.getvalue()


class AudioTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.bridge=SimpleNamespace(phase='connected',simulated=True,conn=SimpleNamespace(isConnected=True,datachannel=SimpleNamespace(data_channel_opened=True)))
        self.audio=AudioControl(self.bridge,self.temp.name)
    async def asyncTearDown(self):
        self.audio.disconnected();self.temp.cleanup()
    async def test_import_play_stop_and_volume_readback(self):
        s=await self.audio.import_clip(wav(1),'录音.wav');self.assertEqual(s['clip']['duration'],1)
        self.assertEqual((await self.audio.command('play',{}))['error'],'READ_VOLUME_FIRST')
        self.assertEqual((await self.audio.command('volume',{'volume':0}))['volume'],0)
        self.assertEqual((await self.audio.command('play',{}))['state'],'streaming')
        self.assertEqual((await self.audio.import_clip(wav(),'other.wav'))['requestError'],'AUDIO_BUSY')
        self.assertEqual((await self.audio.command('stop',{}))['state'],'idle')
    async def test_finish_disconnect_replay(self):
        await self.audio.import_clip(wav(),'test.wav');await self.audio.command('refresh',{})
        await self.audio.command('play',{});await asyncio.sleep(.8)
        self.assertEqual(self.audio.state,'finished')
        await self.audio.command('play',{});self.bridge.phase='disconnected';self.audio.disconnected()
        self.assertIsNone(self.audio.volume);self.assertEqual(self.audio.state,'idle')
        self.bridge.phase='replay'
        self.assertEqual((await self.audio.command('play',{}))['requestError'],'NOT_CONNECTED')
    async def test_bad_import_preserves_clip(self):
        await self.audio.import_clip(wav(),'original.wav')
        for data,code in [(b'x','INVALID_AUDIO'),(b'','FILE_SIZE'),(wav(61),'DURATION_LIMIT')]:
            s=await self.audio.import_clip(data,'bad');self.assertEqual(s['error'],code)
            self.assertEqual(s['clip']['name'],'original.wav')
        restored=AudioControl(self.bridge,self.temp.name)
        self.assertEqual(restored.clip['name'],'original.wav')
    async def test_real_sender_stops_without_mic_or_motion(self):
        class Track:
            readyState='live'
            def stop(self): self.readyState='ended'
        class Sender:
            track=None
            def replaceTrack(self,track): self.track=track
        sender=Sender();track=Track()
        from unittest.mock import patch
        self.bridge.simulated=False
        self.bridge.conn.pc=SimpleNamespace(getTransceivers=lambda:[SimpleNamespace(kind='audio',currentDirection='sendrecv',sender=sender)])
        await self.audio.import_clip(wav(),'test.wav');self.audio.volume=2
        with patch('aiortc.contrib.media.MediaPlayer',return_value=SimpleNamespace(audio=track)):
            s=await self.audio.command('play',{});self.assertEqual(s['state'],'streaming');self.assertIs(sender.track.source,track)
            self.audio.disconnected();self.assertIsNone(sender.track);self.assertEqual(track.readyState,'ended')
    async def test_rpc_correlation_guard_and_rejection(self):
        self.bridge.simulated=False
        controller=self.audio
        controller.rpc_id=2147483647
        request_ids=[]
        class Channel:
            callback=None
            reject=False
            def on(self,event,callback): self.callback=callback
            def remove_listener(self,event,callback): self.callback=None
            def send(self,raw):
                message=json.loads(raw)
                self.assert_guard(message)
                ident=message['data']['header']['identity']
                request_ids.append(ident['id'])
                assert 0 < ident['id'] <= 2147483647
                response={'type':'res','topic':VUI,'data':{'header':{'identity':ident,'status':{'code':0}},'data':'{"volume":4}'}}
                wrong=json.loads(json.dumps(response));wrong['data']['header']['identity']['id']-=1
                self.callback(json.dumps(wrong));assert not controller.future.done()
                wrong=json.loads(json.dumps(response));wrong['topic']='rt/api/sport/request'
                self.callback(json.dumps(wrong));assert not controller.future.done()
                if self.reject: response['data']['header']['status']['code']=3203
                self.callback(json.dumps(response))
            def assert_guard(self,message):
                wrong=json.loads(json.dumps(message));wrong['data']['parameter']='{"volume":10}'
                assert not controller.allows(wrong)
                assert controller.allows(message)
                assert not controller.allows(message)
        channel=Channel();self.bridge.conn.datachannel.channel=channel
        self.assertEqual((await controller.command('refresh',{}))['volume'],4)
        self.assertEqual((await controller.command('volume',{'volume':3}))['error'],'VOLUME_MISMATCH')
        channel.reject=True
        self.assertEqual((await controller.command('refresh',{}))['error'],'VOLUME_REJECTED')
        self.assertIsNone(controller.volume);self.assertIsNone(channel.callback)
        self.assertEqual(request_ids,list(range(1,len(request_ids)+1)))
    async def test_volume_waits_for_delayed_device_apply_without_rewriting(self):
        calls=[];readbacks=iter([2,2,5])
        async def rpc(api,parameter=None):
            calls.append(api)
            return {} if api==1003 else {'volume':next(readbacks)}
        self.audio.rpc=rpc
        result=await self.audio.command('volume',{'volume':5})
        self.assertIsNone(result['error']);self.assertEqual(result['volume'],5)
        self.assertEqual(calls,[1003,1004,1004,1004])
    async def test_volume_read_retries_one_dropped_reply(self):
        calls=[]
        async def rpc(api,parameter=None):
            calls.append(api)
            if len(calls)==1:raise TimeoutError()
            return {'volume':2}
        self.audio.rpc=rpc
        result=await self.audio.command('refresh',{})
        self.assertIsNone(result['error']);self.assertEqual(result['volume'],2)
        self.assertEqual(calls,[1004,1004])
    async def test_two_playbacks_over_local_webrtc(self):
        from aiortc import RTCPeerConnection, RTCConfiguration, MediaStreamError
        import math, struct
        from audio_control import playback_track
        sender=RTCPeerConnection(RTCConfiguration(iceServers=[]))
        receiver=RTCPeerConnection(RTCConfiguration(iceServers=[]))
        count=0;reader=None
        @receiver.on('track')
        def on_track(track):
            async def read():
                nonlocal count
                try:
                    while True:
                        frame=await track.recv()
                        if abs(frame.to_ndarray().astype('int32')).max()>100: count+=1
                except MediaStreamError: pass
            nonlocal reader
            reader=asyncio.create_task(read())
        stream=playback_track();sender.addTransceiver(stream,direction='sendrecv')
        try:
            await sender.setLocalDescription(await sender.createOffer())
            await receiver.setRemoteDescription(sender.localDescription)
            await receiver.setLocalDescription(await receiver.createAnswer())
            await sender.setRemoteDescription(receiver.localDescription)
            self.bridge.simulated=False;self.bridge.conn.pc=sender;self.audio.stream=stream
            buf=io.BytesIO()
            with wave.open(buf,'wb') as f:
                f.setnchannels(1);f.setsampwidth(2);f.setframerate(8000)
                f.writeframes(b''.join(struct.pack('<h',round(2000*math.sin(i*.3))) for i in range(4000)))
            await self.audio.import_clip(buf.getvalue(),'local-test.wav');self.audio.volume=2
            await self.audio.command('play',{})
            await asyncio.sleep(.08)
            await self.audio.command('stop',{})
            self.assertEqual(self.audio.state,'idle')
            for _ in range(2):
                before=count
                result=await self.audio.command('play',{});self.assertEqual(result['state'],'streaming')
                for tick in range(80):
                    await asyncio.sleep(.05)
                    if self.audio.state=='finished': break
                self.assertEqual(self.audio.state,'finished')
                self.assertGreater(count,before+2,'Audible frames must arrive on BOTH plays')
        finally:
            self.audio.disconnected();await sender.close();await receiver.close()
            if reader: await reader

    def test_mp3_m4a_conversion_and_playlist_rejection(self):
        import av
        for fmt,codec in [('mp3','libmp3lame'),('mp4','aac')]:
            output=io.BytesIO()
            with av.open(output,'w',format=fmt) as container:
                stream=container.add_stream(codec,rate=48000);stream.layout='mono'
                with av.open(io.BytesIO(wav(.5))) as source:
                    for frame in source.decode(audio=0):
                        for packet in stream.encode(frame): container.mux(packet)
                for packet in stream.encode(): container.mux(packet)
            pcm,duration=normalize(output.getvalue())
            self.assertTrue(.4<duration<.7,fmt)
            with wave.open(io.BytesIO(pcm)) as result:
                self.assertEqual(result.getframerate(),48000)
                self.assertEqual(result.getnchannels(),1)
        with self.assertRaises(ValueError): normalize(b'#EXTM3U\nhttp://127.0.0.1/private.wav')

    def test_validation(self):
        for v in [-1,11,True,1.5,'3',None]: self.assertFalse(valid_command('volume',{'volume':v}))
        self.assertFalse(valid_command('play',{'loop':True}))
        self.assertFalse(valid_command('move',{}))
        self.assertTrue(valid_command('volume',{'volume':10}))

if __name__=='__main__':unittest.main()
