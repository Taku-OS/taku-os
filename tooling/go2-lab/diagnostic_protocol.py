"""Strict read-only RPC correlation, independent of the driver's future resolver."""
import asyncio
import json
import time

READS = {
    ('motion_switcher', 1001): 'motion-mode',
    ('obstacles_avoid', 1002): 'avoidance-switch',
    ('motion_switcher', 1): 'motion-api-version',
    ('obstacles_avoid', 1): 'avoidance-api-version',
    ('sport', 1): 'sport-api-version',
    ('robot_state', 1003): 'service-list',
    ('uwbswitch', 1002): 'follow-switch',
    ('uwbswitch', 1003): 'follow-is-tracking',
    ('uwbswitch', 1): 'follow-api-version',
}
TOPICS = {
    'rt/lf/sportmodestate', 'rt/utlidar/lidar_state',
    'rt/utlidar/robot_pose', 'rt/servicestate', 'rt/multiplestate',
    'rt/uwbstate',
}


def allowed(message):
    """No lidar power, traffic change, movement, stop, mode or switch writes."""
    if not isinstance(message, dict):
        return False
    kind, topic = message.get('type'), message.get('topic', '')
    if not isinstance(kind, str) or not isinstance(topic, str):
        return False
    if kind in {'subscribe', 'unsubscribe'}:
        return set(message) == {'type', 'topic'} and topic in TOPICS
    if kind == 'req':
        try:
            data = message['data']; header = data['header']; ident = header['identity']
            return (set(message) == {'type', 'topic', 'data'}
                    and set(data) == {'header', 'parameter'} and set(header) == {'identity'}
                    and set(ident) == {'id', 'api_id'}
                    and type(ident['id']) is int and 0 < ident['id'] < 2**31
                    and type(ident['api_id']) is int
                    and (topic, ident['api_id']) in {
                        (f'rt/api/{service}/request', api) for service, api in READS}
                    and data['parameter'] == '{}')
        except (KeyError, TypeError):
            return False
    if topic != '' or set(message) - {'type', 'topic', 'data'}:
        return False
    data = message.get('data')
    if kind == 'validation':
        return isinstance(data, str)
    if kind == 'heartbeat':
        return isinstance(data, dict) and set(data) == {'timeInStr', 'timeInNum'}
    return (kind == 'rtc_inner_req' and isinstance(data, dict)
            and data.get('req_type') in {'public_network_status', 'rtt_probe_send_from_mechine'})


def response_fields(message):
    try:
        data = message['data']; header = data['header']; ident = header['identity']
        return {'type': message.get('type'), 'topic': message.get('topic'),
                'id': ident.get('id'), 'api': ident.get('api_id'),
                'code': header.get('status', {}).get('code'), 'payload': data.get('data')}
    except (KeyError, TypeError, AttributeError):
        return None


class ReadOnlyRPC:
    def __init__(self, send, emit, start_id=100000000):
        self.send, self.emit = send, emit
        self.next_id = start_id
        self.pending = {}
        self.lock = asyncio.Lock()

    def receive(self, message):
        fields = response_fields(message)
        if fields is None or type(fields['id']) is not int:
            return
        entry = self.pending.get(fields['id'])
        if entry is None:
            return
        service, api, future, sent = entry
        # WebRTC implementations may echo the request topic or use /response.
        matched = (fields['type'] == 'res' and isinstance(fields['topic'], str)
                   and fields['topic'] in {f'rt/api/{service}/request', f'rt/api/{service}/response'}
                   and type(fields['api']) is int and fields['api'] == api
                   and type(fields['code']) is int)
        self.emit('rpc-candidate', {**fields, 'matched': matched,
                                   'roundTripMs': round((time.monotonic()-sent)*1000, 2)})
        if matched and not future.done():
            future.set_result(fields)

    async def query(self, service, api, timeout=3):
        if (service, api) not in READS:
            raise ValueError('WRITE_OR_UNKNOWN_API_BLOCKED')
        async with self.lock:
            self.next_id += 1
            if not 0 < self.next_id < 2**31:
                raise RuntimeError('REQUEST_ID_EXHAUSTED')
            ident = self.next_id
            message = {'type': 'req', 'topic': f'rt/api/{service}/request', 'data': {
                'header': {'identity': {'id': ident, 'api_id': api}}, 'parameter': '{}'}}
            if not allowed(message):
                raise RuntimeError('READ_GUARD_REJECTED')
            future = asyncio.get_running_loop().create_future()
            self.pending[ident] = (service, api, future, time.monotonic())
            self.emit('rpc-request', message)
            try:
                # Register before send, including for an immediate synchronous test reply.
                self.send(json.dumps(message))
                result = await asyncio.wait_for(future, timeout)
                self.emit('rpc-result', {'name': READS[service, api], **result})
                return result
            except asyncio.TimeoutError:
                self.emit('rpc-timeout', {'name': READS[service, api], 'id': ident,
                                          'interpretation': 'unknown-not-unsupported'})
                return None
            finally:
                self.pending.pop(ident, None)
                if not future.done():
                    future.cancel()


def switch_value(result):
    """Never coerce strings, numbers, nonzero statuses or missing fields to a bool."""
    if not isinstance(result, dict) or type(result.get('code')) is not int or result['code'] != 0:
        return None
    try:
        value = json.loads(result['payload'])
        enabled = value.get('enable') if isinstance(value, dict) else None
        return enabled if type(enabled) is bool else None
    except (KeyError, TypeError, ValueError):
        return None


def uwb_switch_value(result):
    """UtrackSwitchGetData uses int32 enable; do not reuse obstacle bool schema."""
    if not isinstance(result, dict) or type(result.get('code')) is not int or result['code'] != 0:
        return None
    try:
        value = json.loads(result['payload'])
        enabled = value.get('enable') if isinstance(value, dict) else None
        if type(enabled) is bool:
            return enabled
        return bool(enabled) if type(enabled) is int and enabled in (0, 1) else None
    except (KeyError, TypeError, ValueError):
        return None
