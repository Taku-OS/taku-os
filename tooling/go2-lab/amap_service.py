#!/usr/bin/env python3
"""Personal, read-only AMap adapter. Runs separately from the offline robot simulator."""
import base64
import getpass
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import re
import sys
import threading
from urllib.parse import urlencode
import requests
from amap_mcp_adapter import McpMaps
from amap_mcp import McpError

PORT = 18846
KEY_FILE = Path.home() / 'Library/Application Support/TakuGo2/amap-web-service.key'
ORIGINS = {'http://127.0.0.1:18845', 'http://localhost:18845'}


class MapError(Exception):
    pass


def coordinate(value):
    if not isinstance(value, str) or len(value) > 48:
        raise ValueError('INVALID_COORDINATE')
    parts = value.split(',')
    if len(parts) != 2:
        raise ValueError('INVALID_COORDINATE')
    lon, lat = map(float, parts)
    if not (math.isfinite(lon) and math.isfinite(lat) and -180 <= lon <= 180 and -85 <= lat <= 85):
        raise ValueError('INVALID_COORDINATE')
    return f'{lon:.6f},{lat:.6f}'


def validate(action, value):
    if not isinstance(value, dict):
        raise ValueError()
    if action == 'search' and set(value) == {'keyword', 'city'}:
        if all(isinstance(value[k], str) and len(value[k]) <= n for k, n in [('keyword', 80), ('city', 40)]) and value['keyword'].strip():
            return {'keywords': value['keyword'].strip(), 'region': value['city'].strip(), 'page_size': 10}
    if action == 'route' and set(value) == {'origin', 'destination'}:
        return {k: coordinate(v) for k, v in value.items()}
    raise ValueError()


def map_viewport(points):
    pairs=[list(map(float,p.split(','))) for p in points]
    world=[((lon+180)/360,(1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2) for lon,lat in pairs]
    low=[min(p[i] for p in world) for i in (0,1)];high=[max(p[i] for p in world) for i in (0,1)]
    center=[(low[i]+high[i])/2 for i in (0,1)]
    # staticmaps scale=1 empirically uses a 512 * 2**zoom world in image pixels.
    zoom=max(1,min(17,math.floor(math.log2(min(504/max(high[0]-low[0],1e-9),304/max(high[1]-low[1],1e-9))/512))))
    return {'center':[round(center[0]*360-180,6),round(math.degrees(math.atan(math.sinh(math.pi*(1-2*center[1])))),6)],
            'zoom':zoom,'width':600,'height':400,'coordinateSystem':'GCJ-02'}


class AMap:
    def __init__(self, key_file=KEY_FILE):
        self.key_file = key_file
        self.lock = threading.Lock()
        self.routes = {}  # At most four read-only previews, shared with the local Phone CLI.
        self.mcp = McpMaps(self)
        self.mcp_lock = threading.Lock()

    def key(self):
        try:
            if self.key_file.stat().st_mode & 0o077:
                raise MapError('KEY_FILE_PERMISSIONS')
            value = self.key_file.read_text().strip()
        except FileNotFoundError:
            raise MapError('KEY_REQUIRED') from None
        if not re.fullmatch(r'[a-fA-F0-9]{32}', value):
            raise MapError('KEY_INVALID')
        return value

    def status(self):
        try:
            self.key()
            error = None
        except (MapError, OSError) as e:
            error = str(e) if isinstance(e, MapError) else 'KEY_UNREADABLE'
        return {'configured': error is None, 'error': error, 'provider': 'amap',
                'coordinateSystem': 'GCJ-02', 'robotExecutionAllowed': False,
                'mcp': {'available': self.mcp.client.tools is not None, 'transport': 'streamable-http'}}

    def fetch(self, path, params, image=False):
        # No user-selected URLs, redirects or robot transport in this service.
        try:
            with requests.Session() as session:
                session.trust_env = False
                with session.get('https://restapi.amap.com' + path,
                                 params={**params, 'key': self.key()}, timeout=(4, 8),
                                 allow_redirects=False, stream=True) as response:
                    if response.status_code != 200:
                        raise MapError('PROVIDER_HTTP_ERROR')
                    raw = bytearray()
                    for chunk in response.iter_content(65536):
                        raw.extend(chunk)
                        if len(raw) > 3_000_000:
                            raise MapError('PROVIDER_RESPONSE_TOO_LARGE')
            if image and bytes(raw).startswith(b'\x89PNG\r\n\x1a\n'):
                return 'data:image/png;base64,' + base64.b64encode(raw).decode()
            if image and bytes(raw).startswith(b'\xff\xd8\xff'):
                return 'data:image/jpeg;base64,' + base64.b64encode(raw).decode()
            data = json.loads(raw)
            if data.get('status') != '1':
                code = str(data.get('infocode', ''))
                raise MapError('AMAP_' + code if re.fullmatch(r'\d{5}', code) else 'PROVIDER_ERROR')
            if image:
                raise MapError('MAP_IMAGE_UNAVAILABLE')
            return data
        except requests.RequestException:
            raise MapError('PROVIDER_UNREACHABLE') from None
        except (ValueError, TypeError, AttributeError):
            raise MapError('PROVIDER_INVALID_RESPONSE') from None

    def run(self, action, value):
        params = validate(action, value)
        if not self.lock.acquire(blocking=False):
            raise MapError('MAP_BUSY')
        try:
            if action == 'search':
                data = self.fetch('/v5/place/text', params)
                places = []
                for p in data.get('pois', [])[:10]:
                    try:
                        location = coordinate(p.get('location'))
                    except (ValueError, TypeError):
                        continue
                    places.append({'name': str(p.get('name', ''))[:120], 'address': str(p.get('address', ''))[:240], 'location': location})
                return {'provider': 'amap', 'coordinateSystem': 'GCJ-02', 'places': places}
            data = self.fetch('/v3/direction/walking', params)
            paths = data.get('route', {}).get('paths', [])
            if not paths:
                raise MapError('NO_ROUTE')
            path = paths[0]
            steps = []
            points = []
            for step in path.get('steps', []):
                line = [coordinate(p) for p in step.get('polyline', '').split(';') if p]
                points.extend(line)
                steps.append({'instruction': str(step.get('instruction', ''))[:300],
                              'distance': float(step.get('distance', 0)), 'polyline': line})
            if not points:
                raise MapError('NO_ROUTE_GEOMETRY')
            result = {'provider': 'amap', 'coordinateSystem': 'GCJ-02', 'robotExecutionAllowed': False,
                      'origin': params['origin'], 'destination': params['destination'],
                      'distance': float(path['distance']), 'duration': float(path['duration']), 'steps': steps,
                      'mapImage': None, 'mapError': None}
            # Limit only the static overview; retain every route point in the steps above.
            preview = points[::max(1, math.ceil(len(points) / 80))]
            if preview[-1] != points[-1]: preview.append(points[-1])
            try:
                result['mapViewport']=map_viewport(points+[params['origin'],params['destination']])
                viewport=result['mapViewport']
                result['mapImage'] = self.fetch('/v3/staticmap', {'size': '600*400', 'scale':'1',
                    'location':coordinate(','.join(map(str,viewport['center']))), 'zoom':viewport['zoom'],
                    'markers': 'mid,0x28734d,A:' + params['origin'] + '|mid,0xa35f24,B:' + params['destination'],
                    'paths': '5,0x28734d,1,,:' + ';'.join(preview)}, image=True)
            except MapError as e:
                result['mapError'] = str(e)
            result['url'] = 'https://uri.amap.com/navigation?' + urlencode({
                'from': params['origin'], 'to': params['destination'], 'mode': 'walk',
                'coordinate': 'gaode', 'src': 'taku-go2', 'callnative': '0'})
            result['geometryId']=hashlib.sha256(';'.join(points).encode()).hexdigest()
            self.routes[result['geometryId']]=result
            while len(self.routes)>4: self.routes.pop(next(iter(self.routes)))
            return result
        finally:
            self.lock.release()


def handler_class(client):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass

        def allowed(self):
            return self.headers.get('Host') in {f'127.0.0.1:{PORT}', f'localhost:{PORT}'} and self.headers.get('Origin') in ORIGINS | {None}

        def reply(self, status, value):
            raw = json.dumps(value, allow_nan=False).encode()
            self.send_response(status)
            if self.allowed() and self.headers.get('Origin') in ORIGINS:
                self.send_header('Access-Control-Allow-Origin', self.headers['Origin'])
                self.send_header('Vary', 'Origin')
                self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
                self.send_header('Access-Control-Allow-Headers', 'Content-Type')
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_OPTIONS(self):
            self.reply(200 if self.allowed() else 403, {})

        def do_GET(self):
            if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
            if self.path == '/mcp/latest': return self.reply(200, client.mcp.latest or {})
            if re.fullmatch(r'/route/[a-f0-9]{64}',self.path):
                route=client.routes.get(self.path.rsplit('/',1)[-1])
                return self.reply(200,route) if route else self.reply(404,{'error':'ROUTE_PREVIEW_EXPIRED'})
            self.reply(200, client.status()) if self.path == '/status' else self.reply(404, {'error': 'NOT_FOUND'})

        def do_POST(self):
            if not self.allowed(): return self.reply(403, {'error': 'LOCAL_ONLY'})
            if self.path == '/convert':
                try:
                    length = int(self.headers.get('Content-Length', '-1'))
                    if self.headers.get('Content-Type') != 'application/json' or self.headers.get('Transfer-Encoding') or not 2 <= length <= 256:
                        raise ValueError()
                    self.connection.settimeout(3)
                    value = json.loads(self.rfile.read(length))
                    if not isinstance(value, dict) or set(value) != {'location', 'coordinateSystem'} or value['coordinateSystem'] != 'WGS84':
                        raise ValueError()
                    result = client.fetch('/v3/assistant/coordinate/convert', {'locations': coordinate(value['location']), 'coordsys': 'gps'})
                    return self.reply(200, {'location': coordinate(result['locations']), 'coordinateSystem': 'GCJ-02', 'provider': 'amap'})
                except (MapError, McpError) as e: return self.reply(503, {'error': str(e)})
                except (ValueError, KeyError, TypeError, OSError): return self.reply(400, {'error': 'INVALID_MAP_REQUEST'})
            if self.path in {'/mcp/search', '/mcp/route'}:
                try:
                    length = int(self.headers.get('Content-Length', '-1'))
                    if self.headers.get('Content-Type') != 'application/json' or self.headers.get('Transfer-Encoding') or not 2 <= length <= 1024:
                        raise ValueError()
                    self.connection.settimeout(3)
                    value = json.loads(self.rfile.read(length))
                except (ValueError, UnicodeError, OSError): return self.reply(400, {'error': 'INVALID_MAP_REQUEST'})
                if not client.mcp_lock.acquire(blocking=False): return self.reply(409, {'error': 'MAP_BUSY'})
                try: return self.reply(200, client.mcp.run(self.path.rsplit('/', 1)[-1], value))
                except (MapError, McpError) as e: return self.reply(503, {'error': str(e)})
                except (ValueError, KeyError, TypeError, AttributeError): return self.reply(400, {'error': 'INVALID_MAP_REQUEST'})
                finally: client.mcp_lock.release()
            if self.path not in {'/search', '/route'}: return self.reply(404, {'error': 'NOT_FOUND'})
            try:
                length = int(self.headers.get('Content-Length', '-1'))
                if self.headers.get('Content-Type') != 'application/json' or self.headers.get('Transfer-Encoding') or not 2 <= length <= 1024:
                    raise ValueError()
                self.connection.settimeout(3)
                value = json.loads(self.rfile.read(length))
                validate(self.path[1:], value)
            except (ValueError, UnicodeError, OSError):
                return self.reply(400, {'error': 'INVALID_MAP_REQUEST'})
            try:
                self.reply(200, client.run(self.path[1:], value))
            except MapError as e:
                self.reply(409 if str(e) == 'MAP_BUSY' else 503, {'error': str(e)})
            except (ValueError, TypeError, KeyError, AttributeError, OSError):
                self.reply(502, {'error': 'MAP_SERVICE_ERROR'})
    return Handler


if __name__ == '__main__':
    if sys.argv[1:] == ['configure']:
        value = getpass.getpass('Personal AMap Web Service key (hidden): ').strip()
        if not re.fullmatch(r'[a-fA-F0-9]{32}', value): raise SystemExit('Invalid key format')
        KEY_FILE.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        fd = os.open(KEY_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as f: f.write(value)
        print('Personal key saved outside the repository. No restart needed.')
    elif len(sys.argv) == 1:
        print(f'AMap read-only service http://127.0.0.1:{PORT}', flush=True)
        ThreadingHTTPServer(('127.0.0.1', PORT), handler_class(AMap())).serve_forever()
    else:
        raise SystemExit('Usage: amap_service.py [configure]')
