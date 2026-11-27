"""Finite app actions; model output is data, never shell or robot wire commands."""
import asyncio
import hmac
import secrets
import time
from distance import valid_distance, valid_turn, move_distance
from follow_control import FollowControl
from map_tools import MapTools, valid_map
from navigation_preview import NavigationPreview

MOVES = {'forward', 'backward', 'left', 'right'}
ACTIONS = MOVES | {'inspect', 'connect', 'disconnect', 'camera_on', 'camera_off',
                   'lidar_on', 'lidar_off', 'stop', 'show_state', 'show_camera',
                   'show_environment', 'show_motion', 'show_audio', 'show_avoidance', 'show_navigation', 'show_amap',
                   'audio_status', 'audio_play', 'audio_stop', 'show_follow', 'follow_stop', 'follow_start',
                   'navigation_status', 'navigation_preview', 'show_navigator'}
ACTIVE = {'planning', 'confirming', 'running'}
OBSERVATION_ACTIONS = {'inspect', 'connect', 'camera_on', 'camera_off', 'show_camera', 'show_state',
                       'show_follow', 'follow_stop', 'follow_start', 'show_audio', 'audio_status', 'audio_play', 'audio_stop'}


def movement(action):
    return valid_distance(action) or valid_turn(action) or (isinstance(action, str) and action in MOVES)


def valid_volume(action):
    return (isinstance(action, dict) and set(action) == {'action', 'volume'} and action['action'] == 'set_volume'
            and type(action['volume']) is int and 0 <= action['volume'] <= 10)


def validated_plan(value):
    if not isinstance(value, dict) or set(value) != {'actions', 'message'}:
        raise ValueError('INVALID_PLAN')
    actions, message = value['actions'], value['message']
    if (not isinstance(actions, list) or len(actions) > 8
            or any(not (isinstance(a, str) and a in ACTIONS) and not valid_distance(a) and not valid_turn(a) and not valid_volume(a) and not valid_map(a) for a in actions)
            or sum(movement(a) for a in actions) > 3
            or not isinstance(message, str) or len(message) > 500):
        raise ValueError('INVALID_PLAN')
    if any(valid_map(a) or (isinstance(a, str) and a.startswith('navigation_')) for a in actions) and any(movement(a) or a == 'follow_start' for a in actions):
        raise ValueError('NAVIGATION_OBSERVATION_ONLY')
    return actions[:], message


class Assistant:
    def __init__(self, bridge):
        self.bridge = bridge
        self.owner = 'ai'
        self.epoch = 0
        self.job = None
        self.task = None
        self.taking_over = False
        self.error = None
        self.observation_only = bool(getattr(bridge, 'external_motion_control', False))
        self.follow = FollowControl(self)
        self.navigator = NavigationPreview(bridge)
        self.maps = MapTools(self.navigator)

    def view(self):
        job = None if self.job is None else {k: v for k, v in self.job.items() if k not in {'token', 'lastSeen', 'created'}}
        connected = self.bridge.phase == 'connected'
        return {'owner': self.owner, 'epoch': self.epoch, 'takingOver': self.taking_over,
                'connected': connected, 'robotMode': self.bridge.current_motion_mode if connected else None,
                'error': self.error, 'job': job, 'observationOnly': self.observation_only, 'follow': self.follow.view()}

    async def observe(self):
        # Do not change gait or send StopMove when the remote is controlling the robot.
        # An already-running AI motion must finish/cancel before entering this mode.
        if self.job and self.job['status'] in ACTIVE:
            raise ValueError('TASK_BUSY')
        await self.follow.stop()
        self.observation_only = True
        self.owner = 'ai'
        self.error = None
        return self.view()

    def authorized(self, value):
        job = self.job
        if (self.owner != 'ai' or self.taking_over or job is None
                or job['epoch'] != self.epoch or job['status'] not in ACTIVE
                or not isinstance(value, dict) or value.get('id') != job['id']
                or not isinstance(value.get('token'), str)
                or not hmac.compare_digest(value['token'], job['token'])):
            raise ValueError('TASK_EXPIRED')
        if time.monotonic() - job['lastSeen'] > 4 or time.monotonic() - job['created'] > 120:
            raise ValueError('TASK_EXPIRED')
        return job

    def begin(self):
        if self.owner != 'ai' or self.taking_over: raise ValueError('MANUAL_CONTROL')
        if self.job and self.job['status'] in ACTIVE: raise ValueError('TASK_BUSY')
        if self.follow.active: raise ValueError('FOLLOW_BUSY')
        self.job = {'id': secrets.token_hex(8), 'token': secrets.token_hex(24),
                    'epoch': self.epoch, 'status': 'planning', 'actions': [], 'message': '',
                    'results': [], 'error': None, 'lastSeen': time.monotonic(), 'created': time.monotonic()}
        self.error = None
        return {'id': self.job['id'], 'token': self.job['token'], 'state': self.view()}

    def heartbeat(self, value):
        self.authorized(value)['lastSeen'] = time.monotonic()
        return self.view()

    def prepare(self, value):
        job = self.authorized(value)
        if job['status'] != 'planning': raise ValueError('TASK_ALREADY_PLANNED')
        actions, message = validated_plan(value.get('plan'))
        if getattr(self.bridge, 'external_motion_control', False):
            # Keep visual tracking and guidance available without starting servo control.
            actions = ['show_follow' if action == 'follow_start' else action for action in actions]
        # A follow request owns its session. Do not mix it with model-generated movements.
        if 'follow_start' in actions and actions != ['follow_start']: raise ValueError('INVALID_PLAN')
        if 'show_follow' in actions:
            self.observation_only = True
        if self.observation_only and any(not valid_volume(a) and not valid_map(a) and (not isinstance(a, str) or a not in OBSERVATION_ACTIONS | {'navigation_status', 'navigation_preview', 'show_navigator', 'show_amap'}) for a in actions):
            raise ValueError('OBSERVATION_ONLY')
        job.update(actions=actions, message=message)
        if any(movement(a) for a in actions): job['status'] = 'confirming'
        else: self.launch(job)
        return self.view()

    def confirm(self, value):
        job = self.authorized(value)
        if job['status'] != 'confirming': raise ValueError('CONFIRMATION_NOT_EXPECTED')
        self.launch(job)
        return self.view()

    def launch(self, job):
        job['status'] = 'running'
        self.task = asyncio.create_task(self.execute(job))

    async def execute(self, job):
        try:
            async with asyncio.timeout(105):
                for action in job['actions']:
                    self.authorized(job)
                    result = await self.perform(action, job)
                    self.authorized(job)
                    if result.get('error') or result.get('requestError'):
                        raise ValueError(result.get('error') or result['requestError'])
                    job['results'].append({'action': action, 'result': result})
            job['status'] = 'completed'
        except asyncio.CancelledError:
            job['status'] = 'canceled'
            raise
        except Exception as error:
            job['status'] = 'failed'
            job['error'] = str(error) if isinstance(error, ValueError) else type(error).__name__

    async def perform(self, action, job):
        b = self.bridge
        if isinstance(action, dict):
            if valid_map(action): return await self.maps.call(action)
            if action['action'] == 'set_volume': return await b.audio.command('volume', {'volume': action['volume']})
            return await move_distance(self, action, job)
        if action == 'navigation_status': return self.navigator.view()
        if action == 'navigation_preview': return await self.maps.prepare()
        if action == 'follow_start': return self.follow.request()
        if action == 'follow_stop':
            await self.follow.stop()
            return {'page': 'follow_stop'}
        if action.startswith('audio_'):
            if action == 'audio_status': return await b.audio.command('refresh', {})
            if action == 'audio_play':
                refreshed = await b.audio.command('refresh', {})
                if refreshed.get('error') or refreshed.get('requestError'): return refreshed
            return await b.audio.command('play' if action == 'audio_play' else 'stop', {})
        if action.startswith('show_'): return {'page': action[5:]}
        if action == 'inspect': return await b.snapshot()
        if action in {'connect', 'disconnect'}:
            result = await b.command(action)
            if action == 'connect':
                while not (result['connected'] and all(v['fresh'] for v in result['telemetry'].values())):
                    if result['phase'] == 'error': return result
                    self.authorized(job)
                    await asyncio.sleep(.2); result = await b.snapshot()
            return result
        if action.startswith('camera_'):
            result = await b.camera.command('start' if action.endswith('_on') else 'stop')
            if result.get('requestError'): return result
            if action == 'camera_on':
                deadline = time.monotonic() + 8
                while not result['fresh'] and time.monotonic() < deadline:
                    self.authorized(job); await asyncio.sleep(.2); result = b.camera.snapshot()
                if not result['fresh']: return {'error': 'CAMERA_NOT_FRESH'}
            return result
        if action.startswith('lidar_'):
            result = await b.scan(action.endswith('_on'))
            if result.get('error'): return result
            if action == 'lidar_on':
                deadline = time.monotonic() + 8
                while not result['fresh'] and time.monotonic() < deadline:
                    self.authorized(job); await asyncio.sleep(.2); result = await b.lidar_snapshot()
                if not result['fresh']: return {'error': 'LIDAR_NOT_FRESH'}
            return result
        if action == 'stop': return await b.motion_command('stop')
        if action in MOVES:
            await self.ensure_normal(job)
            self.authorized(job)
            try:
                armed = await b.motion_command('arm')
                if not armed.get('armed'): return {'error': armed.get('error') or 'STATE_NOT_READY'}
                self.authorized(job)
                result = await b.motion_command(action)
                if result.get('result') != 'stop_acknowledged': return {'error': result.get('error') or 'STOP_UNCONFIRMED'}
                return result
            finally:
                b.motion.revoke()
                b.release_expired_manual()
        raise ValueError('INVALID_ACTION')

    async def ensure_normal(self, job):
        b = self.bridge
        self.authorized(job)
        if b.current_motion_mode != 'normal':
            result = await b.normal_mode()
            if result.get('error') or result.get('code') != 0 or result.get('state', {}).get('mode') != 'normal':
                raise ValueError('MODE_NOT_CONFIRMED')
        while b.motion.snapshot()['cooldownMs']:
            self.authorized(job); await asyncio.sleep(.1)

    async def cancel(self, reason='CANCELED', stop=False):
        self.epoch += 1  # Revoke before any await; a late model reply cannot regain control.
        await self.follow.stop()
        job = self.job
        if job and job['status'] in ACTIVE: job.update(status='canceled', error=reason)
        self.bridge.motion.revoke()
        if self.task and not self.task.done():
            self.task.cancel()
            try: await self.task
            except asyncio.CancelledError: pass
        if job and 'audio_play' in job.get('actions', []) and hasattr(self.bridge, 'audio'):
            self.bridge.audio.stop()
        if (stop and not self.observation_only and not getattr(self.bridge, 'external_motion_control', False)
                and self.bridge.phase == 'connected'):
            result = await self.bridge.motion_command('stop')
            if result.get('error'): self.error = result['error']
        return self.view()

    async def set_mode(self, mode):
        if not isinstance(mode, str) or mode not in {'ai', 'manual'}: raise ValueError('INVALID_MODE')
        if self.taking_over: raise ValueError('CONTROL_BUSY')
        if self.observation_only:
            await self.cancel('CONTROL_CHANGED')
            self.owner = mode
            return self.view()
        self.taking_over = True; self.owner = 'manual'; self.error = None
        try:
            await self.cancel('MANUAL_TAKEOVER' if mode == 'manual' else 'CONTROL_CHANGED', stop=True)
            if self.error: return self.view()
            if mode == 'manual':
                if self.bridge.phase != 'connected': self.error = 'NOT_CONNECTED'
                elif self.bridge.current_motion_mode != 'normal':
                    result = await self.bridge.normal_mode()
                    if result.get('code') != 0 or result.get('state', {}).get('mode') != 'normal': self.error = 'MODE_NOT_CONFIRMED'
            else: self.owner = 'ai'
        finally: self.taking_over = False
        return self.view()

    async def monitor(self):
        while True:
            if self.job and self.job['status'] in ACTIVE:
                now = time.monotonic()
                if now - self.job['lastSeen'] > 4 or now - self.job['created'] > 120:
                    await self.cancel('APP_NOT_FOREGROUND', stop=self.job['status'] == 'running')
            await asyncio.sleep(.2)
