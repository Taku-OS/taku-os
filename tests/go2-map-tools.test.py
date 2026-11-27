import asyncio
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
sys.path[:0] = [str(Path(__file__).resolve().parents[1] / 'tooling/go2-lab'), str(Path(__file__).resolve().parents[1] / 'tooling/go2-lab/phone')]
from amap_service import AMap
from amap_mcp import McpError
from map_tools import MapTools, valid_map
from assistant import validated_plan


class StubAMap(AMap):
    def run(self, action, value):
        return {'distance': 100., 'duration': 90., 'geometryId': 'a'*64, 'mapImage': 'large-image',
                'steps': [{'polyline': ['116.4,39.9', '116.401,39.9']}], **value}


class Tests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.maps = StubAMap()
        self.calls = []
        def call(name, args):
            self.calls.append((name, args))
            return {'maps_geo': {'results': [{'location': '116.4,39.9'}]},
                    'maps_direction_walking': {'route': {'paths': [{'distance': '100', 'duration': 90, 'steps': []}]}},
                    'maps_text_search': {'pois': [{'id': 'B123'}]},
                    'maps_search_detail': {'location': '116.4,39.9', 'name': 'Library'}}[name]
        self.maps.mcp.client.call = call

    def test_protocol_and_geometry_sources_remain_separate(self):
        result = self.maps.mcp.run('route', {'origin': 'Library', 'destination': '116.401,39.9', 'city': ''})
        self.assertEqual(result['protocol'], 'mcp')
        self.assertEqual(result['geometrySource'], 'amap-web-service')
        self.assertTrue(result['routeAgreement']); self.assertFalse(result['robotExecutionAllowed'])
        self.assertEqual([c[0] for c in self.calls], ['maps_geo', 'maps_direction_walking'])

    def test_ambiguous_geocode_is_not_guessed(self):
        self.maps.mcp.client.call = lambda *_: {'results': [{'location': '1,1'}, {'location': '2,2'}]}
        with self.assertRaisesRegex(McpError, 'MAP_PLACE_AMBIGUOUS'): self.maps.mcp.location('park')

    def test_search_retrieves_coordinates_for_named_results(self):
        result = self.maps.mcp.run('search', {'keyword': 'library', 'city': ''})
        self.assertEqual(result['places'][0]['location'], '116.400000,39.900000')
        self.assertEqual(self.calls[-1][0], 'maps_search_detail')

    def test_unknown_origin_is_not_ip_location(self):
        with self.assertRaisesRegex(McpError, 'ROBOT_LOCATION_REQUIRED'):
            self.maps.mcp.run('route', {'origin': 'robot', 'destination': 'park', 'city': ''})
        self.assertEqual(self.calls, [])

    async def test_robot_origin_comes_only_from_trusted_navigation(self):
        self.state = {'valid': False}
        nav = SimpleNamespace(view=lambda: {'robot': self.state})
        called = []
        def transport(path, body):
            called.append((path, body)); return {'protocol': 'mcp', 'mapImage': 'x'*100000, 'distance': 100}
        tools = MapTools(nav, transport)
        action = {'action': 'map_route', 'origin': 'robot', 'destination': 'Library', 'city': ''}
        with self.assertRaisesRegex(ValueError, 'ROBOT_LOCATION_REQUIRED'): await tools.call(action)
        self.assertEqual(called, [])
        self.state = {'valid': True, 'assumedErrorMeters': 1, 'position': [116.4, 39.9]}
        result = await tools.call(action)
        self.assertEqual(called[0][1]['origin'], '116.4,39.9')
        self.assertNotIn('mapImage', result)

    def test_model_cannot_mix_navigation_and_movement(self):
        good = {'action': 'map_search', 'keyword': 'Library', 'city': ''}
        self.assertTrue(valid_map(good)); self.assertFalse(valid_map({**good, 'url': 'https://elsewhere'}))
        for actions in [[good, 'forward'], ['navigation_preview', {'action': 'move_distance', 'direction': 'forward', 'distance_m': .5}], [good, 'follow_start']]:
            with self.assertRaisesRegex(ValueError, 'NAVIGATION_OBSERVATION_ONLY'):
                validated_plan({'actions': actions, 'message': ''})
        self.assertEqual(validated_plan({'actions': [good, 'show_amap'], 'message': ''})[0][0], good)


if __name__ == '__main__': unittest.main()
