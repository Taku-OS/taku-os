"""Offline HTTP boundary checks; never connects to a robot."""
import asyncio
import http.client
import json
from pathlib import Path
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tooling/go2-lab'))
from bridge import handler_class

class FakeBridge:
    def __init__(self): self.actions = []; self.motion = self; self.audio = self; self.camera = self
    def packet(self, after=''):
        self.actions.append(('camera-frame',after));return b'frame'
    async def import_clip(self, raw, name):
        self.actions.append(('audio-import',len(raw),name));return {'clip':{'name':name}}
    async def select_preset(self, identity):
        self.actions.append(('audio-select',identity));return {'clip':{'presetId':identity}}
    async def command_audio(self, action, value):
        self.actions.append(('audio',action,value));return {'volume':value.get('volume')}
    async def snapshot(self): return {'connected': False, 'readOnly': True}
    async def lidar_snapshot(self): return {'enabled':False, 'fresh':False, 'values':None}
    async def lidar_frame(self, after=''):
        self.actions.append(('frame',after))
        return b'\x04\x00\x00\x00{}  '
    async def scan(self, enabled):
        self.actions.append('lidar-start' if enabled else 'lidar-stop')
        return await self.lidar_snapshot()
    async def command(self, action, value=None):
        if value is not None: return await self.command_audio(action,value)
        self.actions.append(action)
        return await self.snapshot()
    async def lab_command(self, family, action):
        self.actions.append((family,action))
        return {'source':'simulation'}
    async def motion_speed(self,value):
        self.actions.append(('speed',value))
        return {'armed':False,**value}

class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.loop = asyncio.new_event_loop()
        cls.worker = threading.Thread(target=cls.loop.run_forever)
        cls.worker.start()
        cls.bridge = FakeBridge()
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), handler_class(cls.bridge, cls.loop))
        cls.server_thread = threading.Thread(target=cls.server.serve_forever)
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.server_thread.join()
        cls.loop.call_soon_threadsafe(cls.loop.stop); cls.worker.join(); cls.loop.close()

    def request(self, method, path, body=None, **extra):
        c = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        headers = {'Host': '127.0.0.1:18845', **extra}
        c.request(method, path, body, headers)
        r = c.getresponse(); data = r.read(); status = r.status; c.close()
        return status, data

    def setUp(self): self.bridge.actions.clear()

    def test_lidar_lifecycle_has_no_robot_payload(self):
        for action in ['start','stop']:
            self.assertEqual(self.request('POST', '/api/lidar/'+action, '{}', **{'Content-Type':'application/json'})[0],200)
        self.assertEqual(self.bridge.actions,['lidar-start','lidar-stop'])
        self.assertEqual(self.request('GET','/api/lidar')[0],200)
        self.assertEqual(self.request('POST','/api/lidar/start','{"action":"move"}',**{'Content-Type':'application/json'})[0],400)

    def test_state_is_read_only(self):
        status, raw = self.request('GET', '/api/state')
        self.assertEqual(status, 200); self.assertTrue(json.loads(raw)['readOnly'])

    def test_binary_frame_and_origin_boundary(self):
        status, raw = self.request('GET','/api/lidar/frame?after=stream%3A12')
        self.assertEqual(status,200)
        self.assertEqual(raw,b'\x04\x00\x00\x00{}  ')
        self.assertEqual(self.bridge.actions,[('frame','stream:12')])
        self.assertEqual(self.request('GET','/api/lidar/frame',Origin='https://example.com')[0],403)
        self.assertEqual(self.request('GET','/api/lidar/frame?after='+'x'*81)[0],400)
        self.assertEqual(len(self.bridge.actions),1)

    def test_motion_endpoints_and_unexpected_inputs_rejected(self):
        self.assertEqual(self.request('POST', '/api/move', '{}', **{'Content-Type': 'application/json'})[0], 404)
        self.assertEqual(self.request('POST', '/api/connect', '{"ip":"other"}', **{'Content-Type': 'application/json'})[0], 400)
        self.assertEqual(self.request('POST', '/api/connect', '{}', **{'Content-Type': 'text/plain'})[0], 400)

    def test_fixed_motion_routes_reject_speed_and_arbitrary_actions(self):
        for action in ['arm','stop','forward','backward','left','right']:
            self.assertEqual(self.request('POST','/api/motion/'+action,'{}',**{'Content-Type':'application/json'})[0],200)
        self.assertEqual(self.bridge.actions,['arm','stop','forward','backward','left','right'])
        self.assertEqual(self.request('POST','/api/motion/forward','{"speed":1}',**{'Content-Type':'application/json'})[0],400)
        self.assertEqual(self.request('POST','/api/motion/stand','{}',**{'Content-Type':'application/json'})[0],404)
        self.assertEqual(self.request('POST','/api/motion/forward','{}',**{'Content-Type':'application/json','Origin':'https://example.com'})[0],403)
        self.assertEqual(len(self.bridge.actions),6)

    def test_cross_site_and_host_rejected(self):
        self.assertEqual(self.request('GET', '/api/state', Origin='https://example.com')[0], 403)
        self.assertEqual(self.request('GET', '/api/state', Host='example.com:18845')[0], 403)

    def test_speed_route_enforces_bounds_schema_and_origin(self):
        path='/api/motion/speed';headers={'Content-Type':'application/json'}
        valid='{"linearSpeed":0.2,"angularSpeed":0.4}'
        self.assertEqual(self.request('POST',path,valid,**headers)[0],200)
        self.assertEqual(self.bridge.actions,[('speed',{'linearSpeed':.2,'angularSpeed':.4})])
        for body in ['{}','[]','null','{bad', '{"linearSpeed":NaN,"angularSpeed":0.2}',
                     '{"linearSpeed":true,"angularSpeed":0.2}', '{"linearSpeed":0.21,"angularSpeed":0.2}',
                     '{"linearSpeed":0.1,"angularSpeed":0.16}',
                     '{"linearSpeed":0.1,"angularSpeed":0.2,"duration":5}', ' '*129]:
            self.assertEqual(self.request('POST',path,body,**headers)[0],400,body)
        self.assertEqual(self.request('POST',path,valid,Origin='https://example.com',**headers)[0],403)
        self.assertEqual(len(self.bridge.actions),1)

    def test_audio_validation_and_origin(self):
        headers={'Content-Type':'application/json'}
        self.assertEqual(self.request('POST','/api/audio/volume','{"volume":3}',**headers)[0],200)
        for payload in ['{"volume":11}','{"volume":true}','{"volume":3.5}','{"volume":0,"x":1}','null']:
            self.assertEqual(self.request('POST','/api/audio/volume',payload,**headers)[0],400)
        self.assertEqual(self.request('POST','/api/audio/play','{}',Origin='https://bad.test',**headers)[0],403)
        self.assertEqual(self.request('POST','/api/audio/delete','{}',**headers)[0],404)
        self.assertEqual(self.request('POST','/api/audio/import?name=clip.wav',b'wav',**{'Content-Type':'application/octet-stream'})[0],200)
        self.assertEqual(self.bridge.actions,[('audio','volume',{'volume':3}),('audio-import',3,'clip.wav')])

    def test_sound_catalog_and_selection_boundary(self):
        status,raw=self.request('GET','/api/audio/library')
        self.assertEqual(status,200)
        clips=json.loads(raw)['clips'];self.assertEqual(len(clips),27)  # Includes the encounter main line.
        self.assertNotIn('file',clips[0])
        self.assertEqual(self.request('GET','/api/audio/library',Origin='https://other.test')[0],403)
        headers={'Content-Type':'application/json'}
        self.assertEqual(self.request('POST','/api/audio/select','{"id":"hello"}',**headers)[0],200)
        for body in ['[]','null','{"id":2}','{"id":"hello","path":"/tmp/x"}']:
            self.assertEqual(self.request('POST','/api/audio/select',body,**headers)[0],400)
        self.assertEqual(self.request('POST','/api/audio/select','{"id":"hello"}',Origin='https://other.test',**headers)[0],403)
        self.assertEqual(self.bridge.actions,[('audio-select','hello')])
        for asset in ['audio-library','audio-library.js','audio-library.css','audio-library-controls.js']:
            self.assertEqual(self.request('GET','/'+asset)[0],200)

    def test_encounter_assets_and_saved_audio_are_local_and_bounded(self):
        for asset in ['follow-demo','follow-demo.js','follow-demo.css','follow-guidance.js','follow-scene-voice.js']:
            self.assertEqual(self.request('GET','/'+asset)[0],200)
        status, raw=self.request('GET','/api/audio/preset/en-taunt-enough')
        self.assertEqual(status,200);self.assertEqual(raw[:4],b'RIFF')
        for identity in ['unknown','../catalog.json','%2e%2e%2fclip','hello.wav']:
            self.assertEqual(self.request('GET','/api/audio/preset/'+identity)[0],404)
        self.assertEqual(self.request('GET','/api/audio/preset/en-taunt-enough',Origin='https://other.test')[0],403)
        headers={'Content-Type':'application/json'}
        self.assertEqual(self.request('POST','/api/audio/prepare','{}',**headers)[0],200)
        self.assertEqual(self.request('POST','/api/audio/prepare','{"play":true}',**headers)[0],400)
        self.assertEqual(self.bridge.actions,[('audio','prepare',{})])

    def test_camera_is_bounded_and_local_only(self):
        self.assertEqual(self.request('GET','/api/camera/frame?after=abc')[0],200)
        self.assertEqual(self.bridge.actions,[('camera-frame','abc')])
        self.assertEqual(self.request('GET','/api/camera/frame?after='+'x'*101)[0],400)
        self.assertEqual(self.request('GET','/api/camera/frame',Origin='https://other.test')[0],403)
        headers={'Content-Type':'application/json'}
        for action in ['start','stop']:
            self.assertEqual(self.request('POST','/api/camera/'+action,'{}',**headers)[0],200)
        self.assertEqual(self.request('POST','/api/camera/start','{"record":true}',**headers)[0],400)
        self.assertEqual(self.request('POST','/api/camera/move','{}',**headers)[0],404)
        self.assertEqual(self.bridge.actions,[('camera-frame','abc'),'start','stop'])

    def test_lifecycle_only(self):
        for action in ['connect', 'disconnect']:
            self.assertEqual(self.request('POST', '/api/' + action, '{}', **{'Content-Type': 'application/json'})[0], 200)
        self.assertEqual(self.bridge.actions, ['connect', 'disconnect'])
    def test_lab_routes_reject_parameters_and_unknown_actions(self):
        for route in ['avoidance/query','avoidance/forward','simulation/front','recording/start','replay/start']:
            self.assertEqual(self.request('POST','/api/'+route,'{}',**{'Content-Type':'application/json'})[0],200)
        self.assertEqual(len(self.bridge.actions),5)
        for route in ['avoidance/forward','simulation/front','replay/start']:
            self.assertEqual(self.request('POST','/api/'+route,'{"speed":1}',**{'Content-Type':'application/json'})[0],400)
            self.assertEqual(self.request('POST','/api/'+route,'{}',**{'Content-Type':'application/json','Origin':'https://example.com'})[0],403)
        self.assertEqual(self.request('POST','/api/avoidance/jump','{}',**{'Content-Type':'application/json'})[0],404)
        self.assertEqual(len(self.bridge.actions),5)

if __name__ == '__main__': unittest.main()
