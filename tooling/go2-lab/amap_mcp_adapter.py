"""MCP map tools, with explicitly labelled Web Service geometry supplementation."""
import math
import re
from amap_mcp import AMapMcp, McpError


def number(value):
    if isinstance(value, str) and re.fullmatch(r'\d+(?:\.\d+)?', value): value = float(value)
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise McpError('MCP_INVALID_TOOL_RESULT')
    return value


class McpMaps:
    def __init__(self, maps):
        self.maps = maps
        self.client = AMapMcp(maps.key)
        self.latest = None

    def location(self, address, city=''):
        from amap_service import coordinate
        try: return coordinate(address)
        except (ValueError, TypeError): pass
        data = self.client.call('maps_geo', {'address': address, 'city': city})
        places = data.get('results', [])
        if not isinstance(places, list) or len(places) != 1: raise McpError('MAP_PLACE_AMBIGUOUS')
        return coordinate(places[0]['location'])

    def run(self, action, value):
        from amap_service import coordinate
        if not isinstance(value, dict): raise ValueError('INVALID_MAP_REQUEST')
        if action == 'search':
            if set(value) != {'keyword', 'city'} or not self.text(value['keyword'], 80) or not self.text(value['city'], 40, True):
                raise ValueError('INVALID_MAP_REQUEST')
            data = self.client.call('maps_text_search', {'keywords': value['keyword'], 'city': value['city']})
            pois = data.get('pois')
            if not isinstance(pois, list): raise McpError('MCP_INVALID_TOOL_RESULT')
            places = []
            for p in pois[:3]:
                ident = p.get('id') if isinstance(p, dict) else None
                if not isinstance(ident, str) or not re.fullmatch(r'[A-Za-z0-9]{1,40}', ident):
                    raise McpError('MCP_INVALID_TOOL_RESULT')
                detail = self.client.call('maps_search_detail', {'id': ident})
                places.append({'id': ident, 'name': str(detail.get('name', ''))[:120],
                               'address': str(detail.get('address', ''))[:240], 'location': coordinate(detail['location'])})
            result = {'places': places}
        elif action == 'route':
            if set(value) != {'origin', 'destination', 'city'} or not all(self.text(value[k], 120, k == 'city') for k in value):
                raise ValueError('INVALID_MAP_REQUEST')
            if value['origin'].lower() in {'robot', 'phone', 'current', '当前位置', '机器狗当前位置'}:
                raise McpError('ROBOT_LOCATION_REQUIRED')
            origin, destination = (self.location(value[k], value['city']) for k in ('origin', 'destination'))
            if origin == destination: raise McpError('MAP_SAME_LOCATION')
            response = self.client.call('maps_direction_walking', {'origin': origin, 'destination': destination})
            paths = response.get('route', {}).get('paths', [])
            if not isinstance(paths, list) or not paths: raise McpError('NO_ROUTE')
            first = paths[0]
            distance, duration = number(first['distance']), number(first['duration'])
            # MCP currently omits polylines. Fetch an independent route geometry,
            # preserving its own steps rather than attaching them to MCP steps.
            result = self.maps.run('route', {'origin': origin, 'destination': destination})
            result['geometrySource'] = 'amap-web-service'
            result['mcpSummary'] = {'distance': distance, 'duration': duration,
                                    'stepCount': len(first.get('steps', []))}
            result['routeAgreement'] = abs(result['distance'] - distance) <= max(20, distance * .1)
            result['robotExecutionAllowed'] = False
        else: raise ValueError('INVALID_MAP_REQUEST')
        result.update(provider='amap', protocol='mcp', coordinateSystem='GCJ-02')
        self.latest = {'kind': action, 'value': result}
        return result

    @staticmethod
    def text(value, maximum, empty=False):
        return isinstance(value, str) and len(value) <= maximum and (empty or bool(value.strip()))
