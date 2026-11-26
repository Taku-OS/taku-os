"""No hardware: fixed pulses, stop failures, cancellation and transport gate."""
import asyncio
import json
from pathlib import Path
import sys
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from motion import Motion, VECTORS, DURATION, SPORT_TOPIC, wire_kind, valid_speeds
from bridge import Bridge

class MotionTests(unittest.IsolatedAsyncioTestCase):
    async def test_submillisecond_cooldown_does_not_report_ready_early(self):
        self.motion.cooldown_until = 100.0002
        with patch('motion.time.monotonic', return_value=100.):
            self.assertEqual(self.motion.snapshot()['cooldownMs'], 1)

    async def asyncSetUp(self):
        self.events=[]
        self.stop_error=False
        async def stop():
            self.events.append(('stop',time.monotonic()))
            if self.stop_error: raise TimeoutError()
        self.motion=Motion(lambda:True,lambda vector:self.events.append(('move',vector,time.monotonic())),stop)
    async def test_locked_and_failed_stop_never_move(self):
        self.assertEqual((await self.motion.command('forward'))['error'],'LOCKED')
        self.stop_error=True
        result=await self.motion.command('arm')
        self.assertFalse(result['armed'])
        self.assertFalse(any(e[0]=='move' for e in self.events))
    async def test_exact_pulse_then_stop_and_cooldown(self):
        await self.motion.command('arm')
        self.events.clear()
        result=await self.motion.command('backward')
        self.assertEqual([e[0] for e in self.events],['stop','move','stop'])
        self.assertEqual(self.events[1][1],VECTORS['backward'])
        elapsed=self.events[2][1]-self.events[1][2]
        self.assertEqual(result['durationMs'],700)
        self.assertGreaterEqual(elapsed,.70)
        self.assertGreaterEqual(elapsed,DURATION)
        self.assertLess(elapsed,DURATION+0.15)
        self.assertEqual(result['result'],'stop_acknowledged')
        self.assertEqual((await self.motion.command('left'))['error'],'COOLDOWN')

    async def test_speed_selection_stays_locked_and_emits_no_robot_command(self):
        result=await self.motion.set_speed({'linearSpeed':.20,'angularSpeed':.40})
        self.assertFalse(result['armed']);self.assertEqual(self.events,[])
        self.assertEqual(result['linearSpeed'],.20);self.assertEqual(result['angularSpeed'],.40)
        self.assertEqual(self.motion.vectors(),{'forward':(.2,0,0),'backward':(-.2,0,0),'left':(0,0,.4),'right':(0,0,-.4)})
        await self.motion.command('arm');self.events.clear()
        result=await self.motion.set_speed({'linearSpeed':.08,'angularSpeed':.15})
        self.assertEqual(result['requestError'],'LOCK_FIRST');self.assertEqual(self.events,[])
        await self.motion.command('forward')
        self.assertEqual(self.events[1][1],(.2,0,0))
        self.assertEqual([e[0] for e in self.events],['stop','move','stop'])

    async def test_invalid_speeds_and_busy_changes_are_rejected(self):
        for key,bad in [('linearSpeed',.07),('linearSpeed',.21),('linearSpeed',.085),
                        ('linearSpeed',True),('linearSpeed','0.1'),('linearSpeed',float('nan')),
                        ('angularSpeed',.1),('angularSpeed',.45),('angularSpeed',.16),
                        ('angularSpeed',float('inf'))]:
            values={'linearSpeed':.08,'angularSpeed':.15,key:bad}
            self.assertFalse(valid_speeds(values))
            self.assertEqual((await self.motion.set_speed(values))['requestError'],'INVALID_SPEED')
        for values in [{},[],{'linearSpeed':.08}, {'linearSpeed':.08,'angularSpeed':.15,'duration':1}]:
            self.assertFalse(valid_speeds(values))
        self.motion.busy=True
        self.assertEqual((await self.motion.set_speed({'linearSpeed':.2,'angularSpeed':.4}))['requestError'],'BUSY')
        self.motion.busy=False;self.motion.error='STOP_UNCONFIRMED'
        self.assertEqual((await self.motion.set_speed({'linearSpeed':.2,'angularSpeed':.4}))['requestError'],'STOP_UNCONFIRMED')
        self.assertEqual(self.events,[])

    async def test_wire_only_accepts_current_speed_and_send_window(self):
        await self.motion.set_speed({'linearSpeed':.12,'angularSpeed':.25})
        for vector,expected in [((.12,0,0),True),((0,0,-.25),True),((.2,0,0),False),((.08,0,0),False),((.12,0,.25),False)]:
            msg={'type':'req','topic':SPORT_TOPIC,'data':{'header':{'identity':{'id':1,'api_id':1008}},
                 'parameter':json.dumps(dict(zip(('x','y','z'),vector)))}}
            self.motion.sending=False;self.assertFalse(self.motion.allows(msg))
            self.motion.sending=True;self.assertEqual(self.motion.allows(msg),expected)
        # Manual speed changes do not change the separately validated avoidance profile.
        self.assertEqual(VECTORS['forward'],(.08,0,0))

    async def test_bridge_speed_change_respects_other_controller_and_replay(self):
        bridge=Bridge('127.0.0.1')
        for phase,owner,error in [('replay',None,'REPLAY_ACTIVE'),('disconnected','avoidance','CONTROL_BUSY')]:
            bridge.phase=phase;bridge.lease.owner=owner
            self.assertEqual((await bridge.motion_speed({'linearSpeed':.2,'angularSpeed':.4}))['requestError'],error)
        bridge.lease.owner=None;bridge.avoidance.stop_confirmed=False
        self.assertEqual((await bridge.motion_speed({'linearSpeed':.2,'angularSpeed':.4}))['requestError'],'STOP_UNCONFIRMED')
        self.assertEqual(bridge.motion.linear_speed,.08)
    async def test_busy_rejects_queued_moves_and_stop_interrupts(self):
        await self.motion.command('arm')
        task=asyncio.create_task(self.motion.command('forward'))
        # Interrupt in the new second half, after the old 350 ms pulse would end.
        await asyncio.sleep(.45)
        self.assertEqual((await self.motion.command('right'))['error'],'BUSY')
        await self.motion.command('stop')
        await task
        self.assertFalse(self.motion.snapshot()['armed'])
        self.assertEqual(sum(e[0]=='move' for e in self.events),1)
        move=next(e for e in self.events if e[0]=='move')
        self.assertLess(self.events[-1][1]-move[2],DURATION)
    async def test_stop_during_preflight_prevents_late_move(self):
        await self.motion.command('arm')
        gate=asyncio.Event()
        async def slowstop(): await gate.wait()
        self.motion.send_stop=slowstop
        task=asyncio.create_task(self.motion.command('forward'))
        await asyncio.sleep(0)
        stop=asyncio.create_task(self.motion.stop())
        await asyncio.sleep(0)
        gate.set()
        await asyncio.gather(task,stop)
        self.assertFalse(any(e[0]=='move' for e in self.events))
    async def test_failed_final_stop_locks_motion(self):
        await self.motion.command('arm')
        self.motion.send_move=lambda vector:setattr(self,'stop_error',True)
        result=await self.motion.command('right')
        self.assertEqual(result['error'],'STOP_UNCONFIRMED')
        self.assertFalse(result['armed'])
    async def test_expiry_and_stale_state(self):
        await self.motion.command('arm')
        self.motion.armed_until=time.monotonic()-1
        self.assertEqual((await self.motion.command('forward'))['error'],'LOCKED')
        self.motion.ready=lambda:False
        self.assertEqual((await self.motion.command('arm'))['error'],'STATE_NOT_READY')

    async def test_stale_during_pulse_interrupts_and_locks(self):
        await self.motion.command('arm')
        task=asyncio.create_task(self.motion.command('forward'))
        await asyncio.sleep(.04)
        self.motion.ready=lambda:False
        result=await task
        self.assertEqual(result['error'],'STATE_NOT_READY')
        self.assertFalse(result['armed'])
        move=next(e for e in self.events if e[0]=='move')
        self.assertLess(self.events[-1][1]-move[2],.2)
    def test_wire_rejects_arbitrary_parameters_and_requires_send_window(self):
        move={'type':'req','topic':SPORT_TOPIC,'data':{'header':{'identity':{'id':1,'api_id':1008}},'parameter':json.dumps({'x':.08,'y':0,'z':0})}}
        self.assertEqual(wire_kind(move),'move')
        self.assertFalse(self.motion.allows(move))
        self.motion.sending=True
        self.assertTrue(self.motion.allows(move))
        for params in [{'x':1,'y':0,'z':0},{'x':.08,'y':.1,'z':0},{'x':.08,'y':0,'z':.15},{'x':.08,'y':False,'z':0}]:
            move['data']['parameter']=json.dumps(params)
            self.assertFalse(self.motion.allows(move))
        for api in [1001,1004,1005,1006,1015,1027,2058]:
            msg={'type':'req','topic':SPORT_TOPIC,'data':{'header':{'identity':{'id':1,'api_id':api}},'parameter':'{}'}}
            self.assertFalse(self.motion.allows(msg))

    def test_bridge_uses_driver_channel_and_fixed_wire_shape(self):
        bridge=Bridge('127.0.0.1')
        sent=[]
        channel=SimpleNamespace(readyState='open',send=sent.append)
        bridge.conn=SimpleNamespace(datachannel=SimpleNamespace(channel=channel))
        bridge.move_once(VECTORS['forward'])
        self.assertEqual(len(sent),1)
        self.assertEqual(wire_kind(json.loads(sent[0])),'move')
        channel.readyState='closed'
        with self.assertRaises(ConnectionError): bridge.move_once(VECTORS['forward'])
        self.assertEqual(len(sent),1)

    def test_move_rejection_revokes_arm(self):
        bridge=Bridge('127.0.0.1');bridge.last_move_id=7
        bridge.motion.armed_until=time.monotonic()+60
        bridge.receive_motion_reply(json.dumps({'data':{'header':{'identity':{'id':7,'api_id':1008},'status':{'code':123}}}}))
        self.assertEqual(bridge.last_move_code,123)
        self.assertFalse(bridge.motion.snapshot()['armed'])
        self.assertTrue(bridge.motion.cancel.is_set())

    def test_mode_switch_only_allows_normal_in_explicit_window(self):
        msg={'type':'req','topic':'rt/api/motion_switcher/request','data':{'header':{'identity':{'id':1,'api_id':1002}},'parameter':'{"name":"normal"}'}}
        self.assertFalse(self.motion.allows(msg))
        self.motion.switching=True
        self.assertTrue(self.motion.allows(msg))
        msg['data']['parameter']='{"name":"ai"}'
        self.assertFalse(self.motion.allows(msg))

if __name__=='__main__': unittest.main()
