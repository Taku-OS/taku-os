import asyncio
import json
from pathlib import Path
import sys
import time
import unittest
from types import SimpleNamespace
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from avoidance import Avoidance, ControlLease, NativeTransport

class Fake:
    actuation_allowed=True
    def __init__(self):
        self.enabled=False;self.moves=[];self.fail_stop=False;self.mismatch=False;self.gate=None
    async def query(self): return self.enabled
    async def set(self,value):
        if self.gate: await self.gate.wait()
        self.enabled=False if self.mismatch else value
    async def claim(self): pass
    def move(self,vector): self.moves.append(vector)
    async def stop(self):
        if self.fail_stop: raise TimeoutError()

class AvoidanceTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.transport=Fake();self.reason=None;self.lease=ControlLease()
        self.a=Avoidance(self.transport,lambda _:self.reason,self.lease)
    async def arm(self):
        await self.a.command('enable');await self.a.command('arm')
    async def test_enable_requires_readback(self):
        self.transport.mismatch=True
        r=await self.a.command('enable')
        self.assertEqual(r['error'],'READBACK_MISMATCH');self.assertIsNone(r['enabled'])
        self.assertEqual(self.transport.moves,[])
    async def test_locked_and_disabled_never_send(self):
        self.assertEqual((await self.a.command('forward'))['requestError'],'LOCKED')
        self.assertEqual((await self.a.command('arm'))['error'],'AVOIDANCE_DISABLED')
        self.assertEqual(self.transport.moves,[])
    async def test_stop_wins_during_enable(self):
        self.transport.gate=asyncio.Event()
        pending=asyncio.create_task(self.a.command('enable'));await asyncio.sleep(.01)
        await self.a.stop();self.transport.gate.set();await pending
        self.assertFalse(self.a.snapshot()['armed']);self.assertEqual(self.transport.moves,[])
    async def test_mutual_exclusion_with_manual(self):
        self.lease.acquire('manual')
        self.assertEqual((await self.a.command('enable'))['requestError'],'CONTROL_BUSY')
    async def test_unknown_boolean_type_rejected(self):
        async def bad(): return 'true'
        self.transport.query=bad
        self.assertEqual((await self.a.command('arm'))['error'],'INVALID_AVOIDANCE_STATE')
        self.assertIsNone(self.a.snapshot()['enabled'])
    async def test_obstacle_preflight_blocks_and_midpulse_stops(self):
        await self.arm();self.reason='OBSTACLE_BLOCKED'
        r=await self.a.command('forward')
        self.assertEqual(r['error'],'OBSTACLE_BLOCKED');self.assertEqual(self.transport.moves,[])
        self.reason=None;await self.arm()
        pending=asyncio.create_task(self.a.command('forward'));await asyncio.sleep(.05)
        self.reason='CLOUD_STALE';r=await pending
        self.assertEqual(r['error'],'CLOUD_STALE');self.assertFalse(r['armed'])
        self.assertLess(len(self.transport.moves),8)
    async def test_pulse_is_bounded_and_locks(self):
        await self.arm();r=await self.a.command('left')
        self.assertTrue(1<=len(self.transport.moves)<=18)
        self.assertTrue(all(v==(0,0,.15) for v in self.transport.moves))
        self.assertFalse(r['armed']);self.assertEqual(r['result'],'stop_acknowledged')
    async def test_stop_failure_latches_and_requires_retry(self):
        await self.arm();self.transport.fail_stop=True
        self.assertFalse((await self.a.stop())['stopConfirmed'])
        self.assertEqual((await self.a.command('arm'))['requestError'],'STOP_UNCONFIRMED')
        self.transport.fail_stop=False;self.assertTrue((await self.a.stop())['stopConfirmed'])
    async def test_enable_preflight_stop_failure_latches(self):
        self.transport.fail_stop=True
        result=await self.a.command('enable')
        self.assertFalse(result['stopConfirmed'])
        self.assertEqual((await self.a.command('arm'))['requestError'],'STOP_UNCONFIRMED')
        self.assertFalse(self.transport.enabled)
    async def test_heartbeat_expiry_locks(self):
        await self.arm();self.a.heartbeat_at-=1
        await self.a.watchdog();self.assertFalse(self.a.snapshot()['armed'])
        self.assertEqual(self.a.error,'CONTROL_LEASE_EXPIRED')
    async def test_stop_pending_rejects_arm(self):
        await self.arm();gate=asyncio.Event()
        async def delayed(): await gate.wait()
        self.transport.stop=delayed
        pending=asyncio.create_task(self.a.stop());await asyncio.sleep(0)
        self.assertEqual((await self.a.command('arm'))['requestError'],'BUSY')
        gate.set();await pending
    def test_real_write_gate_and_exact_wire(self):
        bridge=SimpleNamespace();t=NativeTransport(bridge)
        msg={'type':'req','topic':t.TOPIC,'data':{'header':{'identity':{'id':1,'api_id':1003}},
             'parameter':json.dumps({'x':.08,'y':0,'yaw':0,'mode':0})}}
        t.sending=True
        self.assertFalse(t.allows(msg));t.actuation_allowed=True;self.assertTrue(t.allows(msg))
        msg['data']['parameter']=json.dumps({'x':1,'y':0,'yaw':0,'mode':0})
        self.assertFalse(t.allows(msg))
    def test_native_rpc_requires_exact_boolean_schema(self):
        t=NativeTransport(SimpleNamespace(),allowed=True)
        for api,key in [(1001,'enable'),(1004,'is_remote_commands_from_api')]:
            t.active_wire=(api,{key:True})
            def message(value):
                return {'type':'req','topic':t.TOPIC,'data':{'header':{'identity':{'id':1,'api_id':api}},
                        'parameter':json.dumps({key:value})}}
            self.assertTrue(t.allows(message(True)))
            for value in [1,'true',None]:self.assertFalse(t.allows(message(value)))

if __name__=='__main__': unittest.main()
