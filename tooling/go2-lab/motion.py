"""Bounded single pulses with a locked, server-validated speed selection."""
import asyncio
import json
import math
import time

SPORT_TOPIC = 'rt/api/sport/request'
DURATION = 0.70
VECTORS = {'forward': (0.08, 0, 0), 'backward': (-0.08, 0, 0),
           'left': (0, 0, 0.15), 'right': (0, 0, -0.15)}
SPEED_LIMITS = {'linearSpeed': (0.08, 0.20, 0.01), 'angularSpeed': (0.15, 0.40, 0.05)}


def valid_speeds(value):
    if not isinstance(value, dict) or set(value) != set(SPEED_LIMITS): return False
    for key, (low, high, step) in SPEED_LIMITS.items():
        n = value[key]
        if type(n) not in (int, float) or not math.isfinite(n) or not low <= n <= high: return False
        if not math.isclose((n-low)/step, round((n-low)/step), abs_tol=1e-8): return False
    return True


def wire_kind(message, vectors=None):
    if set(message) != {'type', 'topic', 'data'} or message['topic'] not in {SPORT_TOPIC,'rt/api/motion_switcher/request'}:
        return None
    data = message['data']
    if not isinstance(data, dict): return None
    header = data.get('header', {})
    if not isinstance(header, dict): return None
    identity = header.get('identity', {})
    if not isinstance(identity, dict) or set(identity) != {'id', 'api_id'}: return None
    if type(identity['id']) is not int or type(identity['api_id']) is not int: return None
    if message['topic'] == 'rt/api/motion_switcher/request':
        return 'normal' if (message['type']=='req' and identity['api_id']==1002
                           and set(data)=={'header','parameter'} and set(header)=={'identity'}
                           and data['parameter']=='{"name":"normal"}') else None
    if (message['type'] == 'req' and identity['api_id'] == 1003
            and set(data) == {'header', 'parameter'} and set(header) == {'identity'}
            and data['parameter'] == '{}'):
        return 'stop'
    if (message['type'] != 'req' or identity['api_id'] != 1008
            or set(data) != {'header', 'parameter'} or set(header) != {'identity'}):
        return None
    try: values = json.loads(data['parameter'])
    except (ValueError, TypeError): return None
    if not isinstance(values, dict) or set(values) != {'x', 'y', 'z'}: return None
    vector = tuple(values[k] for k in ('x', 'y', 'z'))
    return 'move' if all(type(v) in (int, float) for v in vector) and vector in (VECTORS if vectors is None else vectors).values() else None


class Motion:
    def __init__(self, ready, move, stop):
        self.ready, self.send_move, self.send_stop = ready, move, stop
        self.armed_until = self.cooldown_until = 0
        self.busy = self.sending = False
        self.switching = False
        self.stopping = 0
        self.idle = asyncio.Event()
        self.idle.set()
        self.cancel = asyncio.Event()
        self.generation = 0
        self.result = 'locked'
        self.error = None
        self.linear_speed, self.angular_speed = 0.08, 0.15

    def vectors(self):
        return {'forward': (self.linear_speed, 0, 0), 'backward': (-self.linear_speed, 0, 0),
                'left': (0, 0, self.angular_speed), 'right': (0, 0, -self.angular_speed)}

    async def set_speed(self, value):
        if not valid_speeds(value): return {**self.snapshot(), 'requestError': 'INVALID_SPEED'}
        if self.busy or self.stopping or self.switching: return {**self.snapshot(), 'requestError': 'BUSY'}
        if self.snapshot()['armed']: return {**self.snapshot(), 'requestError': 'LOCK_FIRST'}
        if self.error == 'STOP_UNCONFIRMED': return {**self.snapshot(), 'requestError': 'STOP_UNCONFIRMED'}
        self.revoke()
        self.linear_speed = round(value['linearSpeed'], 2)
        self.angular_speed = round(value['angularSpeed'], 2)
        # Local configuration only: no Move, StopMove or connection is sent here.
        return self.snapshot()

    def allows(self, message):
        kind = wire_kind(message, self.vectors())
        return kind == 'stop' or (kind == 'move' and self.sending) or (kind == 'normal' and self.switching)

    def snapshot(self):
        now = time.monotonic()
        return {'armed': now < self.armed_until, 'remainingMs': max(0, round((self.armed_until-now)*1000)),
                'busy': self.busy or self.stopping > 0 or self.switching, 'cooldownMs': max(0, math.ceil((self.cooldown_until-now)*1000)),
                'result': self.result, 'error': self.error, 'ready': self.ready(),
                'durationMs': round(DURATION*1000), 'linearSpeed': self.linear_speed, 'angularSpeed': self.angular_speed,
                'speedLimits': {k: {'min': v[0], 'max': v[1], 'step': v[2]} for k, v in SPEED_LIMITS.items()}}

    def revoke(self):
        self.generation += 1
        self.armed_until = 0
        self.cancel.set()

    async def stop(self):
        self.revoke()
        self.stopping += 1
        try:
            await self.send_stop()
            self.result, self.error = 'stop_acknowledged', None
        except Exception:
            self.result, self.error = 'stop_unconfirmed', 'STOP_UNCONFIRMED'
        finally:
            self.stopping -= 1
        return self.snapshot()

    async def command(self, action, *, sustain=False, guard=None, duration=DURATION, cooldown=2):
        # Only in-process controllers can shorten a pulse. Public HTTP actions use defaults.
        if not 0 < duration <= DURATION or not 0 <= cooldown <= 2:
            raise ValueError('INVALID_PULSE')
        if action == 'stop': return await self.stop()
        if self.busy or self.stopping or self.switching: return {**self.snapshot(), 'error': 'BUSY'}
        if not self.ready(): return {**self.snapshot(), 'error': 'STATE_NOT_READY'}
        if action != 'arm' and action not in VECTORS: return {**self.snapshot(), 'error': 'INVALID_ACTION'}
        if action != 'arm' and time.monotonic() >= self.armed_until:
            return {**self.snapshot(), 'error': 'LOCKED'}
        if guard is not None and not guard(): return {**self.snapshot(), 'error': 'CONTROL_EXPIRED'}
        if time.monotonic() < self.cooldown_until: return {**self.snapshot(), 'error': 'COOLDOWN'}
        self.busy = True
        self.idle.clear()
        self.cancel = asyncio.Event()
        generation = self.generation
        moved = False
        try:
            # Acknowledged StopMove is required before any non-zero command.
            await self.send_stop()
            if generation != self.generation or self.cancel.is_set(): return self.snapshot()
            if guard is not None and not guard(): return {**self.snapshot(), 'error': 'CONTROL_EXPIRED'}
            if not self.ready(): raise RuntimeError('STATE_NOT_READY')
            if action == 'arm':
                self.armed_until = time.monotonic()+60
                self.result, self.error = 'armed', None
            else:
                if time.monotonic() >= self.armed_until: raise RuntimeError('LOCKED')
                self.result, self.error = 'executing', None
                self.sending = True
                try:
                    moved = True  # Also stop when the send itself raises.
                    self.send_move(self.vectors()[action])
                finally: self.sending = False
                deadline = time.monotonic()+duration
                refresh_at = time.monotonic()+.1
                while not self.cancel.is_set() and time.monotonic() < deadline:
                    if guard is not None and not guard(): break
                    if not self.ready():
                        raise RuntimeError('STATE_NOT_READY')
                    # Measured plans may refresh the same permitted vector during
                    # this bounded segment; legacy one-shot controls are unchanged.
                    if sustain and time.monotonic() >= refresh_at:
                        self.sending = True
                        try: self.send_move(self.vectors()[action])
                        finally: self.sending = False
                        refresh_at = time.monotonic()+.1
                    try:
                        await asyncio.wait_for(self.cancel.wait(), timeout=min(.025, max(.001, deadline-time.monotonic())))
                    except asyncio.TimeoutError:
                        pass
        except Exception as error:
            self.revoke()
            self.result, self.error = 'failed', str(error) if str(error) in {'LOCKED','STATE_NOT_READY'} else 'COMMAND_FAILED'
        finally:
            try:
                if moved:
                    try:
                        await self.send_stop()
                        if self.error is None: self.result = 'stop_acknowledged'
                    except Exception:
                        self.revoke()
                        self.result, self.error = 'stop_unconfirmed', 'STOP_UNCONFIRMED'
                    self.cooldown_until = time.monotonic()+cooldown
            finally:
                # A takeover can cancel while a stop acknowledgement is in flight.
                self.sending = self.busy = False
                self.idle.set()
        return self.snapshot()
