# Local socket security

The servers derive the caller UID from Android Unix socket peer credentials.

| Endpoint | Allowed caller | Rejection | Host coverage |
| --- | --- | --- | --- |
| Display, `taku-mirror` and slot-specific sockets | Root (0), system (1000), shell (2000) | Closes before reading the command. | `python3 -B tests/local-socket-policy.test.py` |
| Audio, `taku-audio-engine` | Root (0), shell (2000) | Closes before attaching the audio CLI or reading commands. System UID is denied. | `python3 -B tests/local-socket-policy.test.py` |
| Plugins, default `taku-godmode` | Root (0), system (1000), shell (2000). An app UID (10000 or above) requires the permission provider to allow `taku.root`. | Returns JSON `PERMISSION_DENIED` before reading the request. Provider failures deny access. | `python3 -B tests/local-socket-policy.test.py` |
| Broker, `taku-agent-native` | Root (0) and system (1000) pass the socket gate. Shell (2000) and callers granted root socket access by the permission policy can use the plugin request subset. Individual operations may add further checks. | Reads and parses the request before authorization. Denied requests close without dispatch. Reads exceeding 2,000,000 bytes fail before JSON parsing or authorization and the socket closes. | `bash system/assistant/test-native-routing-host.sh` (`NativeBridgeSocketTest`) |

The policy tests compile the Display and Plugins predicates and the Audio rejection condition
from their server sources. The Broker test uses the production request reader and authorization
method. These tests exercise policy and input handling on a host JVM. Android peer credential
retrieval and the permission provider are outside the host test.

The Broker recognizes `pluginCatalogAdd`, the plugin and MCP RPCs enumerated in
`NativeBridge.pluginClient`, and boolean `upsert` writes to a plugin's `enabled` key.
The Broker socket has a 45-second per-read timeout.
