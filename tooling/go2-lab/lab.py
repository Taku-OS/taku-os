"""Bridge composition: protected control, recording and sensor-only replay."""
import asyncio
from pathlib import Path
import time
from avoidance import Avoidance, ControlLease, NativeTransport
from perception import geometry
from recording import Recorder, replay_cloud

SCENARIOS = ('clear','front','rear','left','right','obstacle-appears','native-block',
             'stale-cloud','stale-pose','empty-cloud','pose-mismatch','enable-rejected',
             'readback-mismatch','query-timeout','invalid-state','stop-timeout','mode-change','disconnect','navigation-room','navigation-blocked')


class Lab:
    def init_lab(self):
        self.simulated = False
        self.pose_sample = None
        self.recorder = Recorder(Path(__file__).resolve().parents[2]/'out/go2-lab/recordings')
        self.lease = ControlLease()
        self.native = NativeTransport(self)  # Real writes remain disabled in this release.
        self.avoidance = Avoidance(self.native, self.protected_ready, self.lease, self.recorder.emit)
        self.replay_task = None
        self.replay_paused = False
        self.replay_time = self.replay_duration = 0
        self.replay_error = None
        self.monitor_task = None

    def environment(self):
        return geometry(self.lidar_sample if self.lidar_enabled else None, self.pose_sample)

    def protected_ready(self, action):
        if self.lease.owner=='navigation' or self.navigation.active(): return 'CONTROL_BUSY'
        if self.phase == 'replay': return 'REPLAY_ACTIVE'
        if not self.motion_ready(): return 'STATE_NOT_READY'
        value = self.environment()
        if not value['valid']: return value['reason']
        if action in value['directions'] and value['directions'][action] != 'clear': return 'OBSTACLE_BLOCKED'
        return None

    async def motion_speed(self, value):
        if self.lease.owner=='navigation' or self.navigation.active() or self.navigation.command_lock.locked(): return {**self.motion.snapshot(),'requestError':'CONTROL_BUSY'}
        if self.phase == 'replay': return {**self.motion.snapshot(), 'requestError':'REPLAY_ACTIVE'}
        if not self.avoidance.stop_confirmed: return {**self.motion.snapshot(), 'requestError':'STOP_UNCONFIRMED'}
        if self.lease.owner == 'avoidance' or self.avoidance.enabled is True:
            return {**self.motion.snapshot(), 'requestError':'CONTROL_BUSY'}
        result = await self.motion.set_speed(value)
        self.release_expired_manual()
        self.recorder.emit('manual_speed', result)
        return result

    async def motion_command(self, action, *, sustain=False, guard=None, duration=None, cooldown=None):
        if action=='stop': await self.navigation.stop()
        elif self.lease.owner=='navigation' or self.navigation.active() or self.navigation.command_lock.locked(): return {**self.motion.snapshot(),'error':'CONTROL_BUSY'}
        if self.phase == 'replay': return {**self.motion.snapshot(), 'error':'REPLAY_ACTIVE'}
        if action != 'stop' and not self.avoidance.stop_confirmed:
            return {**self.motion.snapshot(), 'error':'STOP_UNCONFIRMED'}
        if action == 'stop' and self.lease.owner == 'avoidance': return await self.avoidance.stop()
        if action != 'stop' and (self.lease.owner == 'avoidance' or self.avoidance.enabled is True):
            return {**self.motion.snapshot(), 'error':'CONTROL_BUSY'}
        if action == 'arm':
            try: self.lease.acquire('manual')
            except RuntimeError: return {**self.motion.snapshot(), 'error':'CONTROL_BUSY'}
        options = {'sustain': sustain}
        if guard is not None: options['guard'] = guard
        if duration is not None: options['duration'] = duration
        if cooldown is not None: options['cooldown'] = cooldown
        result = await self.motion.command(action, **options)
        if not result['armed'] and not result['busy']: self.lease.release('manual')
        self.recorder.emit('manual_action', {'action':action, 'result':result})
        return result

    async def lab_snapshot(self):
        self.release_expired_manual()
        return {'source':'replay' if self.phase=='replay' else 'simulation' if self.simulated else 'real',
                'connected':self.phase=='connected', 'controlOwner':self.lease.owner, 'avoidance':self.avoidance.snapshot(),
                'readiness':self.protected_ready('arm') if self.phase=='connected' else 'STATE_NOT_READY',
                'geometry':self.environment(), 'pose':self.pose_sample['values'] if self.pose_sample else None,
                'recording':self.recorder.snapshot(),
                'replay':{'active':self.phase=='replay', 'paused':self.replay_paused,
                          'time':round(self.replay_time,2), 'duration':round(self.replay_duration,2),
                          'error':self.replay_error, 'ended':bool(self.replay_task and self.replay_task.done())},
                'scenario':getattr(self,'scenario',None), 'simulation':self.simulation_status() if self.simulated else None}

    def release_expired_manual(self):
        m = self.motion.snapshot()
        if not m['armed'] and not m['busy']: self.lease.release('manual')

    async def monitor(self):
        next_query = 0
        query = None
        try:
            while True:
                self.release_expired_manual()
                await self.avoidance.watchdog()
                if (not self.external_motion_control and self.phase == 'connected' and self.lease.owner!='navigation'
                        and time.monotonic() >= next_query and (not query or query.done())):
                    query = asyncio.create_task(self.avoidance.refresh())
                    next_query = time.monotonic()+.25
                await asyncio.sleep(.025)
        finally:
            if query and not query.done():
                query.cancel()
                try: await query
                except asyncio.CancelledError: pass

    async def lab_command(self, family, action):
        if family == 'avoidance':
            if action=='stop': await self.navigation.stop()
            elif self.lease.owner=='navigation' or self.navigation.active() or self.navigation.command_lock.locked(): return {**self.avoidance.snapshot(),'requestError':'CONTROL_BUSY'}
            if self.phase == 'replay': return {**self.avoidance.snapshot(),'requestError':'REPLAY_ACTIVE'}
            self.release_expired_manual()
            return await self.avoidance.command(action)
        if family == 'simulation':
            if not self.simulated: return {'error':'SIMULATION_ONLY'}
            if self.phase == 'replay': return {'error':'REPLAY_ACTIVE'}
            return await self.set_scenario(action)
        if family == 'recording':
            if action == 'start':
                if self.phase != 'connected': return {'error':'NOT_CONNECTED'}
                try: self.recorder.start('simulation' if self.simulated else 'real')
                except RuntimeError: return {'error':'RECORDING_BUSY'}
                # Include the baseline pose/state even before the next update.
                if self.pose_sample: self.recorder.emit('pose', self.pose_sample['values'])
                for topic, sample in self.samples.items(): self.recorder.emit('state',{'topic':topic,'values':sample['values']})
            else: await asyncio.to_thread(self.recorder.stop)
            return self.recorder.snapshot()
        if family == 'replay':
            if action == 'start':
                try:
                    events = await asyncio.to_thread(self.recorder.load_latest)
                    await self.command('disconnect')
                    await self.stop_replay()
                    self.phase = 'replay'; self.replay_error = None; self.replay_paused = False
                    self.replay_time = 0; self.replay_duration = events[-1]['t']
                    self.replay_task = asyncio.create_task(self.replay(events))
                except Exception: return {'error':'REPLAY_LOAD_FAILED'}
            elif action == 'stop': await self.stop_replay()
            else: self.replay_paused = action == 'pause'
            return await self.lab_snapshot()

    async def close_controls(self):
        await self.navigation.stop()
        if self.avoidance.snapshot()['armed'] or self.avoidance.busy or not self.avoidance.stop_confirmed:
            await self.avoidance.stop()
            await asyncio.wait_for(self.avoidance.idle.wait(), 4)
        self.avoidance.revoke(); self.avoidance.enabled = None; self.avoidance.checked = 0
        if self.avoidance.stop_confirmed: self.lease.release('avoidance')

    async def stop_replay(self):
        if self.replay_task and not self.replay_task.done():
            self.replay_task.cancel()
            try: await self.replay_task
            except asyncio.CancelledError: pass
        self.replay_task = None
        if self.phase == 'replay':
            self.phase = 'disconnected'; self.lidar_enabled = False
            self.lidar_sample = self.pose_sample = None

    async def replay(self, events):
        index = 0; previous = time.monotonic()
        try:
            while index < len(events):
                now = time.monotonic()
                if not self.replay_paused: self.replay_time += now-previous
                previous = now
                while not self.replay_paused and index < len(events) and events[index]['t'] <= self.replay_time:
                    event = events[index]; payload = event['payload']; index += 1
                    if event['kind'] == 'pose':
                        self.pose_sample = {'values':payload,'received':now}
                    elif event['kind'] == 'lidar':
                        self.lidar_sample = replay_cloud(payload); self.lidar_enabled = True; self.lidar_sequence += 1
                    elif event['kind'] == 'state':
                        self.samples[payload['topic']] = {'values':payload['values'],'received':now,'count':index}
                await asyncio.sleep(.02)
            self.replay_time = self.replay_duration
        except asyncio.CancelledError: raise
        except Exception: self.replay_error = 'INVALID_REPLAY_DATA'
