"""Bounded, fixed-destination map RPC. No model-supplied URL or credential."""
import asyncio
import http.client
import json
import re


def valid_map(action):
    if not isinstance(action, dict): return False
    name = action.get('action')
    fields = {'map_search': {'action', 'keyword', 'city'}, 'map_route': {'action', 'origin', 'destination', 'city'}}
    return (name in fields and set(action) == fields[name]
            and all(isinstance(v, str) and len(v) <= (40 if k == 'city' else 80 if k == 'keyword' else 120)
                    and (k == 'city' or bool(v.strip())) for k, v in action.items()))


def request(path, value=None):
    # Only this process talks to its separate read-only map service.
    if path not in {'/mcp/search', '/mcp/route', '/mcp/latest', '/status', '/convert'}:
        raise ValueError('INVALID_MAP_REQUEST')
    connection = http.client.HTTPConnection('127.0.0.1', 18846, timeout=75)
    try:
        connection.request('GET' if value is None else 'POST', path,
                           None if value is None else json.dumps(value), {'Content-Type': 'application/json'})
        response = connection.getresponse()
        raw = response.read(4_000_001)
        if len(raw) > 4_000_000: raise ValueError('MAP_RESPONSE_LIMIT')
        data = json.loads(raw)
        if not isinstance(data, dict): raise ValueError('MAP_INVALID_RESPONSE')
        if response.status != 200:
            error = data.get('error', '')
            raise ValueError(error if isinstance(error, str) and re.fullmatch(r'[A-Z_0-9]{1,80}', error) else 'MAP_REQUEST_FAILED')
        return data
    except (OSError, http.client.HTTPException): raise ValueError('MAP_SERVICE_UNAVAILABLE') from None
    except json.JSONDecodeError: raise ValueError('MAP_INVALID_RESPONSE') from None
    finally: connection.close()


class MapTools:
    def __init__(self, navigation, transport=request):
        self.navigation, self.transport = navigation, transport

    async def call(self, action):
        if not valid_map(action): raise ValueError('INVALID_MAP_REQUEST')
        data = {k: v for k, v in action.items() if k != 'action'}
        route = action['action'] == 'map_route'
        if route and data['origin'].strip().lower() in {'robot', 'current', '当前位置', '机器狗当前位置'}:
            robot = self.navigation.view()['robot']
            if not robot.get('valid') or robot.get('assumedErrorMeters', 1e9) > 5:
                raise ValueError('ROBOT_LOCATION_REQUIRED')
            data['origin'] = ','.join(map(str, robot['position']))
        result = await asyncio.to_thread(self.transport, '/mcp/route' if route else '/mcp/search', data)
        # Keep assistant receipts below the native client's 64 KiB limit.
        return {k: result[k] for k in ('provider', 'protocol', 'coordinateSystem', 'places', 'distance',
                'duration', 'geometryId', 'geometrySource', 'routeAgreement') if k in result}

    async def prepare(self):
        latest = await asyncio.to_thread(self.transport, '/mcp/latest')
        if latest.get('kind') != 'route': raise ValueError('MAP_ROUTE_REQUIRED')
        return self.navigation.prepare(latest['value'])
