import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('amap_mcp', Path(__file__).resolve().parents[1] / 'tooling/go2-lab/amap_mcp.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)


class McpProtocolTests(unittest.TestCase):
    def client(self, failure=None, sse=False):
        calls = []
        def transport(payload, headers):
            calls.append(payload)
            method = payload['method']
            if method == 'initialize': result = {'protocolVersion': '2025-03-26', 'serverInfo': {'name': 'fixture'}}
            elif method == 'notifications/initialized': return 202, {}, b''
            elif method == 'tools/list': result = {'tools': [{'name': 'maps_geo'}]}
            else:
                if failure == 'http': return 403, {}, b'credential-bearing-provider-error'
                result = {'isError': failure == 'tool', 'content': [{'type': 'text', 'text': '{"results":[]}'}]}
            data = {'jsonrpc': '2.0', 'id': payload['id'] + (1 if failure == 'id' else 0), 'result': result}
            body = json.dumps(data).encode()
            return 200, {'Content-Type': 'text/event-stream' if sse else 'application/json'}, b'data: ' + body + b'\n\n' if sse else body
        return module.AMapMcp(lambda: 'private-key-never-in-output', transport), calls

    def test_handshake_tool_discovery_and_call_are_actual_mcp_messages(self):
        client, calls = self.client()
        self.assertEqual(client.call('maps_geo', {'address': 'public landmark'}), {'results': []})
        self.assertEqual([c['method'] for c in calls], ['initialize', 'notifications/initialized', 'tools/list', 'tools/call'])
        self.assertNotIn('id', calls[1])
        client.call('maps_geo', {'address': 'second public landmark'})
        self.assertEqual(len(calls), 5)

    def test_sse_response_is_supported(self):
        client, _ = self.client(sse=True)
        self.assertEqual(client.call('maps_geo', {}), {'results': []})

    def test_mismatched_response_id_rejected(self):
        client, _ = self.client('id')
        with self.assertRaisesRegex(module.McpError, 'MCP_RESPONSE_ID'): client.call('maps_geo', {})

    def test_tool_error_is_not_success(self):
        client, _ = self.client('tool')
        with self.assertRaisesRegex(module.McpError, 'MCP_TOOL_ERROR'): client.call('maps_geo', {})

    def test_http_error_does_not_expose_provider_error_or_key(self):
        client, _ = self.client('http')
        with self.assertRaises(module.McpError) as error: client.call('maps_geo', {})
        self.assertEqual(str(error.exception), 'MCP_HTTP_403')

    def test_arbitrary_tool_and_unavailable_tool_rejected(self):
        client, calls = self.client()
        with self.assertRaisesRegex(module.McpError, 'MCP_TOOL_NOT_ALLOWED'): client.call('shell', {})
        self.assertEqual(calls, [])
        with self.assertRaisesRegex(module.McpError, 'MCP_TOOL_UNAVAILABLE'): client.call('maps_direction_walking', {})
        self.assertNotIn('tools/call', [c['method'] for c in calls])


if __name__ == '__main__': unittest.main()
