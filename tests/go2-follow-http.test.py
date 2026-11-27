"""End-to-end phone HTTP contract, in a separate simulation-only process."""
import json
import socket
import subprocess
import sys
import time
import unittest
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler
from urllib.error import HTTPError


class FollowHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0)); cls.port=sock.getsockname()[1]
        root=Path(__file__).resolve().parents[1]
        program=f"import sys,asyncio;sys.path[:0]=['tooling/go2-lab/phone','tooling/go2-lab'];import runtime,bridge;runtime.PORT=bridge.PORT={cls.port};asyncio.run(runtime.serve('simulation',None))"
        cls.process=subprocess.Popen([sys.executable,'-c',program],cwd=root,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        for _ in range(100):
            try: cls.req('/api/phone'); break
            except Exception: time.sleep(.05)
        else: raise RuntimeError('Simulation HTTP server not ready')

    @classmethod
    def tearDownClass(cls):
        cls.process.terminate();cls.process.wait(timeout=10)

    @classmethod
    def req(cls,path,body=None):
        req=Request(f'http://127.0.0.1:{cls.port}'+path,data=json.dumps(body).encode() if body is not None else None,
                    headers={'Content-Type':'application/json'})
        try:
            with build_opener(ProxyHandler({})).open(req,timeout=2) as response: return response.status,json.load(response)
        except HTTPError as response: return response.code,json.load(response)

    def test_offline_plan_opens_page_but_cannot_queue_motion(self):
        code,lease=self.req('/api/assistant/start',{})
        self.assertEqual(code,200)
        code,_=self.req('/api/assistant/plan',{'id':lease['id'],'token':lease['token'],
                       'plan':{'actions':['follow_start'],'message':'准备跟随'}})
        self.assertEqual(code,200)
        for _ in range(20):
            _,state=self.req('/api/assistant/state')
            if state['job']['status']=='completed':break
            time.sleep(.05)
        self.assertEqual(state['job']['results'][0]['result']['page'],'follow_auto')
        request={'requestId':state['follow']['requestId']}
        self.assertEqual(self.req('/api/follow/start',request),(409,{'error':'NOT_CONNECTED'}))
        self.assertEqual(self.req('/api/follow/start',request),(409,{'error':'FOLLOW_REQUEST_EXPIRED'}))
        self.assertFalse(self.req('/api/follow/state')[1]['active'])
        self.assertFalse(self.req('/api/state')[1]['motion']['armed'])

    def test_follow_endpoints_reject_untrusted_payloads(self):
        for path,value in [('/api/follow/start',{}),('/api/follow/observe',{'token':'fake','frameKey':'x','target':None}),
                           ('/api/follow/stop',{'action':'forward'}),('/api/follow/anything',{})]:
            code,_=self.req(path,value);self.assertEqual(code,409)


if __name__=='__main__':unittest.main()
