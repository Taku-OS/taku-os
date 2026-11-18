#!/usr/bin/env python3
import json
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[3]
plugin = root / "examples/godmode/hello-world"
manifest = json.loads((plugin / "manifest.json").read_text())

assert manifest["id"] == "org.taku.hello-world"
assert manifest["version"] == "1.0.0"
assert manifest["type"] == "tool-provider"
assert manifest["mcp"]["command"] == "/system/bin/sh"
assert manifest["mcp"]["args"] == ["server.sh"]
assert manifest["skills"] == ["skills/hello-world/SKILL.md"]

requests = [
    {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "hello-world-test", "version": "1"},
        },
    },
    {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
    {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
    {
        "jsonrpc": "2.0",
        "id": 3,
        "method": "tools/call",
        "params": {"name": "hello_world", "arguments": {}},
    },
]
process = subprocess.run(
    ["/bin/sh", str(plugin / "server.sh")],
    input="\n".join(json.dumps(item) for item in requests) + "\n",
    text=True,
    capture_output=True,
    timeout=5,
    check=True,
)
replies = [json.loads(line) for line in process.stdout.splitlines() if line.strip()]
assert [reply["id"] for reply in replies] == [1, 2, 3]
assert replies[0]["result"]["serverInfo"]["name"] == "taku-hello-world"
assert replies[1]["result"]["tools"][0]["name"] == "hello_world"
assert replies[1]["result"]["tools"][0]["inputSchema"]["additionalProperties"] is False
assert replies[2]["result"]["content"][0]["text"] == "Hello, World! 👋 from the first Taku OS plugin"
assert replies[2]["result"]["structuredContent"] == {
    "message": "Hello, World! 👋",
    "runtime": "on-device",
    "plugin": "org.taku.hello-world",
}
print("PASS: Hello World plugin protocol")
