"""Phone runtime HTTP regression in offline simulation; no robot or provider calls."""
import http.client
import json
import socket
import subprocess
import sys
import time
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for port in (18845,18846):
            with socket.socket() as s:
                try:s.bind(('127.0.0.1',port))
                except OSError:raise unittest.SkipTest('Test ports occupied; do not interrupt another runtime')
        cls.process=subprocess.Popen([sys.executable,str(ROOT/'tooling/go2-lab/phone/runtime.py'),'simulation'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        for _ in range(50):
            try:
                if cls.call('GET','/api/phone')[0]==200:return
            except OSError:pass
            time.sleep(.1)
        cls.process.terminate();cls.process.wait(timeout=10);raise RuntimeError('Runtime failed to start')

    @classmethod
    def tearDownClass(cls):
        cls.process.terminate()
        try:cls.process.wait(timeout=15)
        except subprocess.TimeoutExpired:cls.process.kill();cls.process.wait()

    @staticmethod
    def call(method,path,value=None,**headers):
        c=http.client.HTTPConnection('127.0.0.1',18845,timeout=3)
        c.request(method,path,None if value is None else json.dumps(value),{'Content-Type':'application/json',**headers})
        r=c.getresponse();raw=r.read();status=r.status;c.close()
        return status,json.loads(raw) if r.getheader('Content-Type','').startswith('application/json') else raw

    def test_no_execution_endpoint_and_invalid_input(self):
        for action,value in [('execute',{}),('start',{}),('phone-fix',{}),('calibrate',[]),('calibrate',{'source':'phone-colocated'})]:
            self.assertEqual(self.call('POST','/api/navigator/'+action,value)[0],409)
        self.assertFalse(self.call('GET','/api/navigator/state')[1]['realActuationAllowed'])
        self.assertFalse(self.call('GET','/api/state')[1]['connected'])

    def test_loopback_browser_boundary(self):
        self.assertEqual(self.call('GET','/navigator',Origin='https://evil.example')[0],403)
        self.assertEqual(self.call('GET','/api/navigator/state',Host='evil.example')[0],403)
        self.assertEqual(self.call('POST','/api/navigator/clear',{},Origin='null')[0],403)
        for path in ['/navigator','/navigator.js','/navigator.css']:
            self.assertEqual(self.call('GET',path)[0],200)

    def test_phone_fix_is_not_robot_anchor_over_http(self):
        data={'session':'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','sequence':1,'longitude':116.4,'latitude':39.9,'accuracy':30.,'ageMs':0,'provider':'network','mock':False}
        self.assertEqual(self.call('POST','/api/navigator/phone-fix',data)[0],200)
        v=self.call('GET','/api/navigator/state')[1]
        self.assertTrue(v['phone']['valid']);self.assertFalse(v['robot']['valid'])
        self.assertEqual(self.call('POST','/api/navigator/phone-anchor',{'heading':90,'headingAccuracy':5,'floorZ':0,'colocated':True})[0],409)
        self.assertFalse(self.call('GET','/api/state')[1]['connected'])


if __name__=='__main__':unittest.main()
