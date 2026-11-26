import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tooling/go2-lab'))
from amap_service import AMap, MapError, coordinate, validate, handler_class


class FixtureAMap(AMap):
    def fetch(self, path, params, image=False):
        if image: raise MapError('MAP_IMAGE_UNAVAILABLE')
        if path.endswith('/text'):
            return {'pois': [{'name': '测试图书馆', 'address': '测试地址', 'location': '116.4,39.9'}, {'location': 'invalid'}]}
        return {'route': {'paths': [{'distance': '180', 'duration': '150', 'steps': [
            {'instruction': '沿测试道路向东步行', 'distance': '180', 'polyline': '116.4,39.9;116.401,39.9;116.402,39.901'}]}]}}


class MapTests(unittest.TestCase):
    def test_schema_and_coordinates(self):
        for bad in ['NaN,1', '181,1', '1,90', '1', None, [1, 2], '1,2,3']:
            with self.assertRaises((ValueError, TypeError)): coordinate(bad)
        self.assertEqual(coordinate('116.4,39.9'), '116.400000,39.900000')
        for action, data in [('search', {'keyword': '', 'city': ''}), ('route', {'origin': '1,2', 'destination': '2,3', 'execute': True}), ('move', {})]:
            with self.assertRaises(ValueError): validate(action, data)

    def test_missing_private_key_and_permissions(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'key'; client = AMap(p)
            self.assertEqual(client.status()['error'], 'KEY_REQUIRED')
            p.write_text('a' * 32); p.chmod(0o644)
            self.assertEqual(client.status()['error'], 'KEY_FILE_PERMISSIONS')
            p.chmod(0o600)
            self.assertTrue(client.status()['configured'])
            self.assertNotIn('a' * 32, json.dumps(client.status()))

    def test_search_and_preview_only_route(self):
        client = FixtureAMap()
        self.assertEqual(len(client.run('search', {'keyword': '图书馆', 'city': '北京'})['places']), 1)
        r = client.run('route', {'origin': '116.4,39.9', 'destination': '116.402,39.901'})
        self.assertEqual(r['coordinateSystem'], 'GCJ-02')
        self.assertFalse(r['robotExecutionAllowed'])
        self.assertEqual(r['distance'], 180)
        self.assertEqual(len(r['steps'][0]['polyline']), 3)
        self.assertIsNone(r['mapImage'])
        self.assertEqual(r['mapError'], 'MAP_IMAGE_UNAVAILABLE')
        self.assertIn('mode=walk', r['url'])
        self.assertEqual(client.routes[r['geometryId']],r)
        self.assertEqual(len(r['geometryId']),64)
        client.lock.acquire()
        with self.assertRaisesRegex(MapError, 'MAP_BUSY'): client.run('search', {'keyword': 'test', 'city': ''})
        client.lock.release()

    def test_http_boundary(self):
        client=FixtureAMap()
        route=client.run('route',{'origin':'116.4,39.9','destination':'116.402,39.901'})
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler_class(client))
        thread = threading.Thread(target=server.serve_forever); thread.start()
        def call(path, data, origin='http://127.0.0.1:18845', host='127.0.0.1:18846'):
            conn = http.client.HTTPConnection('127.0.0.1', server.server_port)
            conn.request('POST', path, json.dumps(data), {'Host': host, 'Origin': origin, 'Content-Type': 'application/json'})
            r = conn.getresponse(); result = r.status, r.getheader('Access-Control-Allow-Origin'), json.loads(r.read()); conn.close(); return result
        try:
            conn=http.client.HTTPConnection('127.0.0.1',server.server_port)
            conn.request('GET','/route/'+route['geometryId'],headers={'Host':'127.0.0.1:18846'})
            response=conn.getresponse()
            self.assertEqual(response.status,200)
            self.assertEqual(json.loads(response.read())['geometryId'],route['geometryId']);conn.close()
            data = {'keyword': '门口', 'city': ''}
            self.assertEqual(call('/search', data)[0:2], (200, 'http://127.0.0.1:18845'))
            self.assertEqual(call('/search', data, origin='https://uri.amap.com')[0], 403)
            self.assertEqual(call('/search', data, origin='null')[0], 403)
            self.assertEqual(call('/search', data, host='evil.example')[0], 403)
            self.assertEqual(call('/move', {})[0], 404)
            self.assertEqual(call('/route', {'origin': '0,0', 'destination': 'NaN,0'})[0], 400)
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == '__main__': unittest.main()
