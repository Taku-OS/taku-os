"""Phone-local visual servo. The model requests a session; fresh camera samples drive it."""
import asyncio
import hmac
import math
import secrets
import time

FRESH = .7


def finite(value, low, high):
    return type(value) in (int, float) and math.isfinite(value) and low <= value <= high


def decision(target, maximum=.20):
    """Maintain the initially selected body's apparent scale; turn before translating."""
    if target is None: return 'hold', .08, .15
    x, ratio = target['x'], target['ratio']
    offset = x - .5
    if abs(offset) > .10:
        angular = round(min(.40, max(.15, abs(offset)*1.2))/.05)*.05
        return ('right' if offset > 0 else 'left'), .08, round(angular, 2)
    error = ratio - 1
    if abs(error) <= .12: return 'hold', .08, .15
    speed = round(min(maximum, max(.08, abs(error)*.4)), 2)
    return ('forward', speed, .15) if error > 0 else ('backward', .08, .15)


class FollowControl:
    def __init__(self, assistant):
        self.ai, self.bridge = assistant, assistant.bridge
        self.request_id = None
        self.request_at = 0
        self.token = None
        self.task = None
        self.active = False
        self.state, self.error, self.action = 'idle', None, 'hold'
        self.sample = None
        self.last_seen = self.started = 0
        self.last_frame = None
        self.last_stamp = 0
        self.target_id = None
        self.stable = self.steps = 0
        self.max_speed = .20
        self.ever_moved = False
        self.original_speed = None

    def view(self):
        return {'active': self.active, 'state': self.state, 'error': self.error,
                'action': self.action, 'steps': self.steps, 'requestId': self.request_id,
                'maxSpeed': self.max_speed, 'distanceMode': 'initial',
                'linearSpeed': self.bridge.motion.linear_speed if self.active else 0}

    def request(self):
        if getattr(self.bridge, 'external_motion_control', False): raise ValueError('OBSERVATION_ONLY')
        if self.active: raise ValueError('FOLLOW_BUSY')
        self.request_id, self.request_at = secrets.token_hex(12), time.monotonic()
        self.state, self.error = 'requested', None
        return {'page': 'follow_auto'}

    async def start(self, value):
        if getattr(self.bridge, 'external_motion_control', False): raise ValueError('OBSERVATION_ONLY')
        if (self.ai.owner != 'ai' or self.ai.taking_over or self.active or not self.request_id
                or value.get('requestId') != self.request_id or time.monotonic()-self.request_at > 20):
            raise ValueError('FOLLOW_REQUEST_EXPIRED')
        self.request_id = None  # Single use, including failed connection/readiness checks.
        b = self.bridge
        if b.phase != 'connected':
            self.state, self.error = 'stopped', 'NOT_CONNECTED'
            raise ValueError(self.error)
        if not b.camera.snapshot()['fresh']:
            raise ValueError('CAMERA_NOT_FRESH')
        if b.motion.snapshot()['busy'] or b.navigation.active() or b.lease.owner not in (None, 'manual'):
            raise ValueError('CONTROL_BUSY')
        self.active = True
        self.token = secrets.token_hex(24)
        self.state, self.error, self.action = 'waiting', None, 'hold'
        self.sample = None
        self.last_frame = self.target_id = None
        self.last_stamp = 0
        self.stable = self.steps = 0
        self.ever_moved = False
        self.started = self.last_seen = time.monotonic()
        self.ai.observation_only = False
        self.original_speed = {'linearSpeed': b.motion.linear_speed, 'angularSpeed': b.motion.angular_speed}
        self.task = asyncio.create_task(self.run())
        return {**self.view(), 'token': self.token}

    def authorized(self, value):
        if (not self.active or self.ai.owner != 'ai' or self.ai.taking_over
                or not isinstance(value.get('token'), str) or not self.token
                or not hmac.compare_digest(self.token, value['token'])):
            raise ValueError('FOLLOW_EXPIRED')

    def observe(self, value):
        self.authorized(value)
        target, key, now = value.get('target'), value.get('frameKey'), time.monotonic()
        if not isinstance(key, str): raise ValueError('INVALID_OBSERVATION')
        stamp = self.bridge.camera.frame_time(key)
        # Camera-issued keys tie inference to an actual received frame, not a browser clock.
        if stamp is None or now-stamp > FRESH or stamp <= self.last_stamp or key == self.last_frame:
            self.sample = None; self.stable = 0
            raise ValueError('STALE_OBSERVATION')
        if target is not None and (not isinstance(target, dict) or set(target) != {'x', 'ratio', 'id'}
                or not finite(target['x'], 0, 1) or not finite(target['ratio'], .25, 4)
                or type(target['id']) is not int or target['id'] < 0):
            self.sample = None; self.stable = 0
            raise ValueError('INVALID_OBSERVATION')
        self.last_frame, self.last_seen, self.last_stamp = key, now, stamp
        if target is None:
            self.sample = None; self.stable = 0; self.action = 'hold'
            return self.view()
        if target['id'] != self.target_id:
            self.sample = None; self.stable = 0; self.target_id = target['id']
        self.stable += 1
        self.sample = {'target': dict(target), 'at': stamp}
        return self.view()

    def command(self):
        b = self.bridge
        if (not self.active or self.ai.owner != 'ai' or self.ai.taking_over or self.stable < 3
                or self.sample is None or time.monotonic()-self.sample['at'] > FRESH
                or not b.camera.snapshot()['fresh'] or not b.motion_ready()):
            return 'hold', .08, .15
        return decision(self.sample['target'], self.max_speed)

    async def stop(self, reason=None):
        self.request_id = None
        self.active = False
        self.token = None
        if reason: self.error = reason
        task = self.task
        if task and not task.done():
            self.bridge.motion.revoke()
            task.cancel()
            try: await task
            except asyncio.CancelledError: pass
        self.state, self.action = 'stopped', 'hold'
        return self.view()

    async def run(self):
        b = self.bridge
        try:
            if b.current_motion_mode != 'normal':
                result = await b.normal_mode()
                if result.get('error') or result.get('code') != 0 or result.get('state', {}).get('mode') != 'normal':
                    raise ValueError('MODE_NOT_CONFIRMED')
            while self.active:
                now = time.monotonic()
                if b.phase != 'connected': raise ValueError('NOT_CONNECTED')
                if self.ai.owner != 'ai' or self.ai.taking_over: raise ValueError('MANUAL_TAKEOVER')
                if now-self.last_seen > 2.5: raise ValueError('FOLLOW_INPUT_EXPIRED')
                if now-self.started > 300: raise ValueError('FOLLOW_TIME_LIMIT')
                action, linear, angular = self.command()
                self.action = action
                self.state = 'tracking' if action != 'hold' else 'holding' if self.stable >= 3 else 'waiting'
                if not b.motion_ready(): self.state = 'robot_not_ready'
                elif b.motion.snapshot()['cooldownMs']: self.state = 'preparing'
                if action == 'hold' or b.motion.snapshot()['cooldownMs']:
                    await asyncio.sleep(.025); continue
                target_id = self.target_id
                current = lambda: self.target_id == target_id and self.command()[0] == action
                b.motion.revoke()
                speed = await b.motion_speed({'linearSpeed': linear, 'angularSpeed': angular})
                if speed.get('error') or speed.get('requestError'):
                    raise ValueError(speed.get('requestError') or speed['error'])
                if not current(): continue
                armed = await b.motion_command('arm', guard=current)
                if armed.get('error') == 'CONTROL_EXPIRED': continue
                if not armed.get('armed'): raise ValueError(armed.get('error') or 'STATE_NOT_READY')
                if not current(): b.motion.revoke(); continue
                self.ever_moved = True
                result = await b.motion_command(action, sustain=True, guard=current, duration=.30, cooldown=.05)
                b.motion.revoke(); b.release_expired_manual()
                if result.get('error') == 'CONTROL_EXPIRED': continue
                if result.get('error') or result.get('result') != 'stop_acknowledged':
                    raise ValueError(result.get('error') or 'STOP_UNCONFIRMED')
                self.steps += 1
                b.recorder.emit('visual_follow_step', {'action': action, 'linearSpeed': linear, 'angularSpeed': angular,
                                                     'ratio': self.sample['target']['ratio'] if self.sample else None})
        except asyncio.CancelledError:
            raise
        except Exception as error:
            self.error = str(error) if isinstance(error, ValueError) else type(error).__name__
        finally:
            self.active = False; self.token = None; self.action = 'hold'; self.state = 'stopped'
            b.motion.revoke()
            if self.ever_moved:
                result = await b.motion_command('stop')
                if result.get('error'): self.error = 'STOP_UNCONFIRMED'
            b.release_expired_manual()
            if self.original_speed:
                await b.motion_speed(self.original_speed)
