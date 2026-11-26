"""Short-lived loopback relay for one explicitly configured Figma file.

Importing this module never binds a socket or reads existing credentials.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import copy
import hmac
import json
import math
import os
import re
import secrets
import threading
import time

PHONE_PORT = 8790
PLUGIN_PORT = 8792
MAX_COMMAND_BYTES = 65_536
MAX_RECEIPT_BYTES = 262_144
MAX_REQUESTS = 500
FILE_KEY = re.compile(r"[-A-Za-z0-9]{1,100}\Z")
SCHEMA = json.loads((Path(__file__).resolve().parents[2]
                     / "spec/figma-desktop-bridge.schema.json").read_text())


def validate_schema(value, schema):
    """Validate the closed, bounded subset used by this adapter's wire schema."""
    if "$ref" in schema:
        return validate_schema(value, SCHEMA["$defs"][schema["$ref"].split("/")[-1]])
    if "oneOf" in schema:
        matches = 0
        for variant in schema["oneOf"]:
            try:
                validate_schema(value, variant)
                matches += 1
            except ValueError:
                pass
        if matches != 1:
            raise ValueError("Invalid declarative action")
        return
    kind = schema.get("type")
    valid_type = {"object": isinstance(value, dict), "array": isinstance(value, list),
                  "string": isinstance(value, str), "boolean": isinstance(value, bool),
                  "number": type(value) is int or type(value) is float and math.isfinite(value)}
    if kind and not valid_type[kind]:
        raise ValueError("Invalid command type")
    if "const" in schema and value != schema["const"] or "enum" in schema and value not in schema["enum"]:
        raise ValueError("Invalid command value")
    if isinstance(value, dict):
        properties = schema.get("properties", {})
        if (set(schema.get("required", [])) - set(value)
                or schema.get("additionalProperties") is False and set(value) - set(properties)):
            raise ValueError("Missing or unknown command fields")
        for name, item in value.items():
            if name in properties:
                validate_schema(item, properties[name])
    if isinstance(value, list):
        if len(value) > schema.get("maxItems", len(value)):
            raise ValueError("Command item limit")
        for item in value:
            validate_schema(item, schema.get("items", {}))
    if isinstance(value, str):
        if (len(value) > schema.get("maxLength", len(value))
                or "pattern" in schema and not re.fullmatch(schema["pattern"], value)):
            raise ValueError("Invalid command string")
    if type(value) in (int, float):
        if (value < schema.get("minimum", value) or value > schema.get("maximum", value)
                or "exclusiveMinimum" in schema and value <= schema["exclusiveMinimum"]):
            raise ValueError("Command number out of bounds")


class State:
    def __init__(self, file_key, phone_token, plugin_token, ttl, log=None, clock=time.monotonic):
        if not isinstance(file_key, str) or not FILE_KEY.fullmatch(file_key):
            raise ValueError("Invalid file key")
        if not 60 <= ttl <= 7200:
            raise ValueError("TTL must be between 60 and 7200 seconds")
        if phone_token == plugin_token or not all(isinstance(v, str) and len(v) >= 32
                                                  for v in (phone_token, plugin_token)):
            raise ValueError("Distinct session credentials are required")
        self.file_key = file_key
        self.tokens = {"phone": phone_token, "plugin": plugin_token}
        self.clock = clock
        self.until = clock() + ttl
        self.log = log
        self.lock = threading.Lock()
        self.request = None
        self.ack = None
        self.error = None
        self.history = {}

    def auth(self, role, header):
        return (role in self.tokens and self.clock() < self.until
                and hmac.compare_digest((header or "").encode(), ("Bearer " + self.tokens[role]).encode()))

    def read(self):
        with self.lock:
            return copy.deepcopy({"target": {"fileKey": self.file_key},
                                  "request": self.request, "ack": self.ack, "error": self.error})

    @staticmethod
    def validate(value):
        validate_schema(value, SCHEMA)

    def submit(self, value):
        self.validate(value)
        with self.lock:
            old = self.history.get(value["requestId"])
            if old is not None:
                if old["input"] != value:
                    raise ValueError("Request ID reused with different data")
                return {"accepted": True, "requestId": value["requestId"], "duplicate": True,
                        "ack": copy.deepcopy(old.get("ack")), "error": old.get("error")}
            if self.request and self.ack is None and self.error is None:
                raise ValueError("Wait for the current desktop operation")
            if len(self.history) >= MAX_REQUESTS:
                raise ValueError("Session request limit reached; create a new bounded session")
            self.request = copy.deepcopy({**value, "fileKey": self.file_key})
            self.ack = None
            self.error = None
            self.history[value["requestId"]] = {"input": copy.deepcopy(value)}
            self.record({"type": "request", "request": self.request})
            return {"accepted": True, "requestId": value["requestId"]}

    def finish(self, value, error=False):
        with self.lock:
            if not self.request or value.get("requestId") != self.request["requestId"]:
                raise ValueError("Stale receipt")
            if error:
                if not isinstance(value.get("error"), str) or not value["error"]:
                    raise ValueError("Missing plugin error")
                candidate = value["error"][:500]
                if self.ack is not None or self.error is not None and self.error != candidate:
                    raise ValueError("Conflicting receipt")
                self.error = candidate
            else:
                if (value.get("fileKey") != self.file_key
                        or value.get("job") != self.request["job"]
                        or value.get("action") != self.request["body"]["action"]
                        or value.get("executedBy") != "desktop-plugin"
                        or not isinstance(value.get("executedAt"), str)
                        or not value["executedAt"]):
                    raise ValueError("Receipt mismatch")
                if self.error is not None or self.ack is not None and self.ack != value:
                    raise ValueError("Conflicting receipt")
                self.ack = copy.deepcopy(value)
            self.history[value["requestId"]].update(ack=self.ack, error=self.error)
            self.record({"type": "result", "ack": self.ack, "error": self.error})
            return {"ok": True}

    def record(self, value):
        if self.log is not None:
            # Receipts can contain private design text. No HTTP/auth headers are logged.
            with self.log.open("a", encoding="utf-8") as output:
                output.write(json.dumps(value, ensure_ascii=False) + "\n")


def permitted(role, method, path, origin):
    if method == "GET":
        return path == "/state"
    if method != "POST":
        return False
    if role == "phone":
        return path == "/request" and not origin
    return role == "plugin" and path in ("/ack", "/error")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def cors(self):
        origin = self.headers.get("Origin", "")
        if origin in ("null", "https://www.figma.com", "https://figma.com"):
            self.send_header("Access-Control-Allow-Origin", origin)
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

    def send(self, code, value):
        raw = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(code)
        self.cors()
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_OPTIONS(self):
        self.send_response(204)
        self.cors()
        self.end_headers()

    def authorized(self):
        if not self.server.state.auth(self.server.role, self.headers.get("Authorization")):
            self.send(401, {"error": "Session expired or credential rejected"})
            return False
        if not permitted(self.server.role, self.command, self.path, self.headers.get("Origin")):
            self.send(403, {"error": "Operation not allowed on this endpoint"})
            return False
        return True

    def do_GET(self):
        if self.authorized():
            self.send(200, self.server.state.read())

    def do_POST(self):
        if not self.authorized():
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            maximum = MAX_COMMAND_BYTES if self.path == "/request" else MAX_RECEIPT_BYTES
            if not 0 < length <= maximum:
                raise ValueError("Body size limit")
            value = json.loads(self.rfile.read(length))
            if not isinstance(value, dict):
                raise ValueError("Expected object")
            if self.path == "/request":
                result = self.server.state.submit(value)
            else:
                result = self.server.state.finish(value, self.path == "/error")
            self.send(200, result)
        except (ValueError, TypeError, KeyError):
            # Do not echo untrusted input, tokens, or design text in errors.
            self.send(400, {"error": "Invalid, conflicting, or pending desktop operation"})


def write_private(path, value):
    # Never overwrite a live session, signing file, or another task's configuration.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(value, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file-key", required=True)
    parser.add_argument("--session-dir", type=Path, required=True)
    parser.add_argument("--ttl", type=int, default=3600)
    args = parser.parse_args()
    phone_token, plugin_token = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    state = State(args.file_key, phone_token, plugin_token, args.ttl)
    # The directory is new and private. Existing sessions are never replaced.
    args.session_dir.mkdir(mode=0o700)
    servers = []
    try:
        for port, role in ((PHONE_PORT, "phone"), (PLUGIN_PORT, "plugin")):
            server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
            server.state, server.role = state, role
            servers.append(server)
        for role, port in (("phone", PHONE_PORT), ("plugin", PLUGIN_PORT)):
            write_private(args.session_dir / (role + ".json"), {
                "endpoint": "http://127.0.0.1:" + str(port),
                "fileKey": args.file_key, "token": state.tokens[role]})
        log = args.session_dir / "receipts.ndjson"
        write_private(log, {})
        log.write_text("")
        state.log = log
        def shutdown():
            for server in servers:
                server.shutdown()
        threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in servers]
        for thread in threads:
            thread.start()
        timer = threading.Timer(args.ttl, shutdown)
        timer.daemon = True
        timer.start()
        print("Loopback Figma relay ready; private session files created. TTL:", args.ttl, flush=True)
        try:
            for thread in threads:
                thread.join()
        except KeyboardInterrupt:
            shutdown()
        finally:
            timer.cancel()
    finally:
        for server in servers:
            server.server_close()


if __name__ == "__main__":
    main()
