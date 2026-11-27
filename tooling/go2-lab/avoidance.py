"""Bounded native-avoidance control, independent from ordinary Sport Move.

Transport implements query/set/claim/move/stop. A simulated transport exercises
the same state machine. No failed action falls back to ordinary movement.
"""
import asyncio
import time
from motion import VECTORS

# Keep the separately validated avoidance pulse independent of manual UI tuning.
DURATION = 0.35


class ControlLease:
    def __init__(self): self.owner = None
    def acquire(self, owner):
        if self.owner not in (None, owner): raise RuntimeError('CONTROL_BUSY')
        self.owner = owner
    def release(self, owner):
        if self.owner == owner: self.owner = None


class Avoidance:
    def __init__(self, transport, ready, lease, event=lambda *_: None):
        self.transport, self.ready, self.lease, self.event = transport, ready, lease, event
        self.enabled = None
        self.checked = self.armed_until = self.heartbeat_at = self.cooldown = 0
        self.busy = False
        self.stopping = 0
        self.generation = 0
        self.error = None
        self.result = 'locked'
        self.stop_confirmed = True
        self.cancel = asyncio.Event()
        self.idle = asyncio.Event(); self.idle.set()

    def snapshot(self):
        now = time.monotonic()
        fresh = now-self.checked < .8
        return {'enabled': self.enabled if fresh else None, 'statusFresh': fresh,
                'armed': now < self.armed_until, 'remainingMs': max(0, round((self.armed_until-now)*1000)),
                'busy': self.busy or self.stopping > 0, 'cooldownMs': max(0, round((self.cooldown-now)*1000)),
                'error': self.error, 'result': self.result, 'stopConfirmed': self.stop_confirmed,
                'actuationAllowed': self.transport.actuation_allowed, 'owner': self.lease.owner}

    def revoke(self):
        self.generation += 1
        self.armed_until = 0
        self.cancel.set()

    async def _read(self):
        enabled = await asyncio.wait_for(self.transport.query(), 1)
        if type(enabled) is not bool: raise RuntimeError('INVALID_AVOIDANCE_STATE')
        return enabled

    async def refresh(self):
        if self.busy or self.stopping: return self.snapshot()
        generation = self.generation
        try:
            enabled = await self._read()
            if generation != self.generation: return self.snapshot()
            self.enabled, self.checked = enabled, time.monotonic()
            if not enabled and self.armed_until:
                await self.stop('AVOIDANCE_DISABLED')
        except Exception:
            if generation == self.generation:
                self.enabled, self.checked = None, 0
                if self.armed_until: await self.stop('AVOIDANCE_UNKNOWN')
        return self.snapshot()

    async def stop(self, reason=None):
        self.revoke()
        self.stopping += 1
        try:
            await asyncio.wait_for(self.transport.stop(), 1.5)
            self.stop_confirmed = True
            self.error, self.result = reason, 'stop_acknowledged'
            if not self.busy: self.lease.release('avoidance')
        except Exception:
            self.stop_confirmed = False
            self.error, self.result = 'STOP_UNCONFIRMED', 'stop_unconfirmed'
        finally:
            self.stopping -= 1
        self.event('avoidance_stop', {'result': self.result, 'reason': self.error})
        return self.snapshot()

    def heartbeat(self):
        if self.snapshot()['armed']: self.heartbeat_at = time.monotonic()
        return self.snapshot()

    async def watchdog(self):
        if self.armed_until and (time.monotonic() >= self.armed_until
                                or time.monotonic()-self.heartbeat_at > .8):
            await self.stop('CONTROL_LEASE_EXPIRED')

    async def command(self, action):
        if action == 'stop': return await self.stop()
        if action == 'query': return await self.refresh()
        if action == 'heartbeat': return self.heartbeat()
        if action not in {'enable', 'disable', 'arm'} | set(VECTORS):
            return {**self.snapshot(), 'requestError': 'INVALID_ACTION'}
        if not self.transport.actuation_allowed:
            return {**self.snapshot(), 'requestError': 'REAL_VALIDATION_REQUIRED'}
        if self.busy or self.stopping: return {**self.snapshot(), 'requestError': 'BUSY'}
        if not self.stop_confirmed:
            return {**self.snapshot(), 'requestError': 'STOP_UNCONFIRMED'}
        if action in VECTORS and not self.snapshot()['armed']:
            return {**self.snapshot(), 'requestError': 'LOCKED'}
        if action in VECTORS and time.monotonic() < self.cooldown:
            return {**self.snapshot(), 'requestError': 'COOLDOWN'}
        try: self.lease.acquire('avoidance')
        except RuntimeError: return {**self.snapshot(), 'requestError': 'CONTROL_BUSY'}
        self.busy = True; self.idle.clear(); self.cancel = asyncio.Event()
        generation = self.generation
        claimed = False
        def current():
            if generation != self.generation or self.cancel.is_set(): raise RuntimeError('CANCELLED')
        try:
            if action in {'enable', 'disable'}:
                self.armed_until = 0
                self.stop_confirmed = False
                await asyncio.wait_for(self.transport.stop(), 1.5)
                self.stop_confirmed = True
                current()
                await asyncio.wait_for(self.transport.set(action == 'enable'), 1); current()
                enabled = await self._read(); current()
                if enabled != (action == 'enable'): raise RuntimeError('READBACK_MISMATCH')
                self.enabled, self.checked = enabled, time.monotonic()
                self.error, self.result = None, 'enabled' if enabled else 'disabled'
            else:
                enabled = await self._read(); current()
                self.enabled, self.checked = enabled, time.monotonic()
                if not enabled: raise RuntimeError('AVOIDANCE_DISABLED')
                reason = self.ready(action)
                if reason: raise RuntimeError(reason)
                if action == 'arm':
                    self.heartbeat_at = time.monotonic()
                    self.armed_until = self.heartbeat_at+60
                    self.error, self.result = None, 'armed'
                else:
                    claimed = True  # Cleanup even if a delayed claim times out.
                    await asyncio.wait_for(self.transport.claim(), 1); current()
                    self.error, self.result = None, 'executing'
                    deadline = time.monotonic()+DURATION
                    while time.monotonic() < deadline:
                        current()
                        reason = self.ready(action)
                        if reason: raise RuntimeError(reason)
                        if not self.snapshot()['armed'] or time.monotonic()-self.heartbeat_at > .8:
                            raise RuntimeError('CONTROL_LEASE_EXPIRED')
                        if not self.snapshot()['statusFresh']: raise RuntimeError('AVOIDANCE_UNKNOWN')
                        self.transport.move(VECTORS[action])
                        await asyncio.sleep(.025)
        except Exception as error:
            self.revoke()
            allowed = {'CANCELLED', 'READBACK_MISMATCH', 'AVOIDANCE_DISABLED', 'CONTROL_LEASE_EXPIRED',
                       'AVOIDANCE_UNKNOWN', 'OBSTACLE_BLOCKED', 'CLOUD_STALE', 'POSE_STALE', 'TIME_SKEW',
                       'POSE_UNVERIFIED', 'FRAME_MISMATCH', 'EMPTY_OR_INVALID_CLOUD', 'NO_DATA',
                       'STATE_NOT_READY', 'REPLAY_ACTIVE', 'INVALID_AVOIDANCE_STATE'}
            self.error = str(error) if str(error) in allowed else 'AVOIDANCE_REQUEST_FAILED'
            self.result = 'blocked'
            if action in {'enable', 'disable'} or self.error in {'AVOIDANCE_REQUEST_FAILED','INVALID_AVOIDANCE_STATE'}:
                self.enabled, self.checked = None, 0
        finally:
            if claimed:
                reason = self.error
                await self.stop(reason)
                self.cooldown = time.monotonic()+2
            self.busy = False; self.idle.set()
            if not self.snapshot()['armed'] and self.stop_confirmed: self.lease.release('avoidance')
        self.event('avoidance_action', {'action': action, **self.snapshot()})
        return self.snapshot()


class NativeTransport:
    """Experimental wire adapter. No writes until startup explicitly allows them.

    Native Move has no reply in the SDK: never claim its send is an acknowledgement.
    Stop sends native zero, requests Sport StopMove, and releases API command source.
    All three must complete; physical standstill still requires observation.
    """
    TOPIC = 'rt/api/obstacles_avoid/request'

    def __init__(self, bridge, allowed=False):
        self.bridge, self.actuation_allowed, self.sending = bridge, allowed, False
        self.active_wire = None

    async def rpc(self, api, parameter):
        if api != 1002 and not self.actuation_allowed: raise RuntimeError('REAL_VALIDATION_REQUIRED')
        conn = self.bridge.conn
        if not conn or not conn.isConnected: raise ConnectionError('DISCONNECTED')
        self.active_wire = (api, parameter)
        self.bridge.recorder.emit('native_rpc_request', {'api':api,'parameter':parameter})
        try:
            response = await conn.datachannel.pub_sub.publish_request_new(self.TOPIC, {'api_id':api, 'parameter':parameter})
        except BaseException as error:
            self.bridge.recorder.emit('native_rpc_error', {'api':api,'errorType':type(error).__name__})
            raise
        finally: self.active_wire = None
        data = response.get('data', {})
        code = data.get('header', {}).get('status', {}).get('code')
        self.bridge.recorder.emit('native_rpc_response', {'api':api,'code':code if type(code) is int else None})
        if type(code) is not int or code != 0: raise RuntimeError('NATIVE_REJECTED')
        return data

    async def query(self):
        import json
        data = await self.rpc(1002, {})
        value = json.loads(data.get('data', ''))
        return value.get('enable') if isinstance(value, dict) else None

    async def set(self, enabled): await self.rpc(1001, {'enable': enabled})
    async def claim(self): await self.rpc(1004, {'is_remote_commands_from_api': True})

    def move(self, vector):
        import json
        if not self.actuation_allowed: raise RuntimeError('REAL_VALIDATION_REQUIRED')
        if vector not in tuple(VECTORS.values())+((0,0,0),): raise ValueError('INVALID_VECTOR')
        channel = self.bridge.conn.datachannel.channel
        if channel.readyState != 'open': raise ConnectionError('DISCONNECTED')
        self.bridge.motion_id += 1
        self.sending = True
        try:
            channel.send(json.dumps({'type':'req', 'topic':self.TOPIC, 'data':{
                'header':{'identity':{'id':self.bridge.motion_id,'api_id':1003}},
                'parameter':json.dumps({'x':vector[0],'y':vector[1],'yaw':vector[2],'mode':0})}}))
        finally: self.sending = False

    async def stop(self):
        self.move((0,0,0))
        results = await asyncio.gather(self.bridge.stop_move(),
            self.rpc(1004, {'is_remote_commands_from_api': False}), return_exceptions=True)
        if any(isinstance(result, BaseException) for result in results): raise RuntimeError('STOP_UNCONFIRMED')

    def allows(self, message):
        import json
        if message.get('type') != 'req' or message.get('topic') != self.TOPIC: return False
        try:
            if set(message) != {'type','topic','data'}: return False
            data = message['data']; header = data['header']; identity = header['identity']
            if set(data) != {'header','parameter'} or set(header) != {'identity'}: return False
            if set(identity) != {'id','api_id'} or type(identity['id']) is not int: return False
            api = identity['api_id']; value = json.loads(data['parameter'])
            if type(api) is not int: return False
            if api == 1003:
                return (self.actuation_allowed and self.sending and isinstance(value,dict)
                        and set(value) == {'x','y','yaw','mode'} and type(value['mode']) is int and value['mode']==0
                        and all(type(value[k]) in (int,float) for k in ('x','y','yaw'))
                        and tuple(value[k] for k in ('x','y','yaw')) in tuple(VECTORS.values())+((0,0,0),))
            if api == 1002 and value != {}: return False
            if api in (1001,1004):
                key = 'enable' if api == 1001 else 'is_remote_commands_from_api'
                if not isinstance(value,dict) or set(value)!={key} or type(value[key]) is not bool: return False
            if api not in (1001,1002,1004): return False
            return self.active_wire == (api,value) and (api == 1002 or self.actuation_allowed)
        except (ValueError,TypeError,KeyError): return False
