import asyncio
from pathlib import Path
import sys
import time
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tooling/go2-lab'))
from motion import Motion

class FollowPulseTests(unittest.IsolatedAsyncioTestCase):
    async def test_expired_visual_guard_prevents_move_after_slow_stop(self):
        allowed=True;gate=asyncio.Event();moves=[]
        async def stop():await gate.wait()
        motion=Motion(lambda:True,moves.append,stop)
        task=asyncio.create_task(motion.command('arm',guard=lambda:allowed))
        await asyncio.sleep(.01);allowed=False;gate.set()
        result=await task
        self.assertFalse(result['armed']);self.assertEqual(moves,[])

    async def test_cancel_during_stop_ack_releases_busy_flag(self):
        count=0;final_stop=asyncio.Event();gate=asyncio.Event()
        async def stop():
            nonlocal count
            count+=1
            if count==3:final_stop.set();await gate.wait()
        motion=Motion(lambda:True,lambda _:None,stop)
        await motion.command('arm')
        task=asyncio.create_task(motion.command('forward',duration=.05,cooldown=0))
        await final_stop.wait();task.cancel()
        with self.assertRaises(asyncio.CancelledError):await task
        self.assertFalse(motion.busy);self.assertTrue(motion.idle.is_set())
        self.assertEqual((await motion.stop())['result'],'stop_acknowledged')

if __name__=='__main__':unittest.main()
