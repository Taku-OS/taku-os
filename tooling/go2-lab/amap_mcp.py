"""Read-only AMap MCP client. Credentials never leave the official provider request."""
import json
import threading
import time
import requests


class McpError(ValueError):
    pass


class AMapMcp:
    ENDPOINT = 'https://mcp.amap.com/mcp'
    TOOLS = {'maps_text_search', 'maps_search_detail', 'maps_geo', 'maps_regeocode', 'maps_direction_walking'}

    def __init__(self, key_provider, transport=None):
        self.key_provider = key_provider
        self.transport = transport or self._request
        self.lock = threading.Lock()
        self.counter = 0
        self.headers = {'Accept': 'application/json, text/event-stream', 'Content-Type': 'application/json'}
        self.tools = None
        self.server = None

    def _request(self, payload, headers):
        started = time.monotonic()
        try:
            with requests.Session() as session:
                session.trust_env = False
                with session.post(self.ENDPOINT, params={'key': self.key_provider()}, json=payload,
                                  headers=headers, timeout=(5, 12), allow_redirects=False, stream=True) as r:
                    if payload.get('method') == 'notifications/initialized':
                        return r.status_code, dict(r.headers), b''
                    raw = bytearray()
                    for chunk in r.iter_content(1024):
                        raw.extend(chunk)
                        if len(raw) > 2_000_000 or time.monotonic() - started > 25:
                            raise McpError('MCP_RESPONSE_LIMIT')
                    return r.status_code, dict(r.headers), bytes(raw)
        except requests.RequestException:
            # requests exceptions may include the URL containing the private key.
            raise McpError('MCP_UNREACHABLE') from None

    def _rpc(self, method, params=None, notification=False):
        self.counter += 1
        payload = {'jsonrpc': '2.0', 'method': method}
        if not notification: payload['id'] = self.counter
        if params is not None: payload['params'] = params
        status, headers, raw = self.transport(payload, dict(self.headers))
        if status not in (200, 202, 204): raise McpError('MCP_HTTP_' + str(status))
        headers = {k.lower(): v for k, v in headers.items()}
        if 'mcp-session-id' in headers: self.headers['Mcp-Session-Id'] = headers['mcp-session-id']
        if notification: return None
        try:
            if 'text/event-stream' in headers.get('content-type', ''):
                messages = []
                for event in raw.decode().replace('\r\n', '\n').split('\n\n'):
                    data = '\n'.join(line[5:].lstrip() for line in event.splitlines() if line.startswith('data:'))
                    if data: messages.append(json.loads(data))
                message = next(m for m in messages if m.get('id') == self.counter)
            else: message = json.loads(raw)
            if message.get('id') != self.counter: raise McpError('MCP_RESPONSE_ID')
            if 'error' in message: raise McpError('MCP_RPC_ERROR')
            result = message['result']
            if not isinstance(result, dict): raise McpError('MCP_INVALID_RESPONSE')
            return result
        except (KeyError, TypeError, AttributeError, StopIteration, UnicodeError, json.JSONDecodeError):
            raise McpError('MCP_INVALID_RESPONSE') from None

    def call(self, name, arguments):
        if name not in self.TOOLS or not isinstance(arguments, dict): raise McpError('MCP_TOOL_NOT_ALLOWED')
        with self.lock:
            try:
                if self.tools is None:
                    init = self._rpc('initialize', {'protocolVersion': '2025-03-26', 'capabilities': {},
                        'clientInfo': {'name': 'taku-go2-phone', 'version': '1.0'}})
                    if init.get('protocolVersion') not in {'2024-11-05', '2025-03-26', '2025-06-18'}:
                        raise McpError('MCP_PROTOCOL_UNSUPPORTED')
                    self.headers['MCP-Protocol-Version'] = init['protocolVersion']
                    self.server = init.get('serverInfo', {})
                    self._rpc('notifications/initialized', notification=True)
                    listing = self._rpc('tools/list', {})
                    self.tools = {t['name'] for t in listing['tools']}
                if name not in self.tools: raise McpError('MCP_TOOL_UNAVAILABLE')
                result = self._rpc('tools/call', {'name': name, 'arguments': arguments})
                if result.get('isError'): raise McpError('MCP_TOOL_ERROR')
                texts = [c['text'] for c in result.get('content', []) if c.get('type') == 'text']
                if len(texts) != 1: raise McpError('MCP_INVALID_TOOL_RESULT')
                value = json.loads(texts[0])
                if not isinstance(value, dict): raise McpError('MCP_INVALID_TOOL_RESULT')
                return value
            except (KeyError, TypeError, AttributeError, json.JSONDecodeError):
                self.tools = None
                raise McpError('MCP_INVALID_RESPONSE') from None
            except McpError:
                self.tools = None
                self.headers.pop('Mcp-Session-Id', None)
                raise
