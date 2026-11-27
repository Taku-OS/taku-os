"""Synthetic protocol tests. No sockets, devices, or real session files."""
import importlib.util
import io
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest

spec = importlib.util.spec_from_file_location("relay", Path(__file__).resolve().parents[1] / "relay.py")
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.now = 1.0
        self.state = relay.State("SyntheticFile", "p" * 40, "d" * 40, 60, clock=lambda: self.now)
        self.request = {"requestId": "r1", "job": "sample-job", "body": {"action": "inspect"}}
        self.ack = {"requestId": "r1", "job": "sample-job", "fileKey": "SyntheticFile",
                    "action": "inspect", "executedBy": "desktop-plugin", "executedAt": "2030-01-01T00:00:00Z"}

    def test_roles_expiry_and_target(self):
        self.assertTrue(self.state.auth("phone", "Bearer " + "p" * 40))
        self.assertFalse(self.state.auth("plugin", "Bearer " + "p" * 40))
        self.assertTrue(self.state.auth("plugin", "Bearer " + "d" * 40))
        self.now = 61.0
        self.assertFalse(self.state.auth("phone", "Bearer " + "p" * 40))
        self.assertEqual(self.state.read()["target"], {"fileKey": "SyntheticFile"})

    def test_single_operation_and_exact_duplicate(self):
        self.assertTrue(self.state.submit(self.request)["accepted"])
        self.assertTrue(self.state.submit(self.request)["duplicate"])
        with self.assertRaises(ValueError):
            self.state.submit({**self.request, "requestId": "r2"})
        with self.assertRaises(ValueError):
            self.state.submit({**self.request, "job": "different"})
        self.state.finish(self.ack)
        self.state.finish(self.ack)  # Lost HTTP response can be safely retried.
        self.assertEqual(self.state.submit(self.request)["ack"], self.ack)
        self.assertTrue(self.state.submit({**self.request, "requestId": "r2"})["accepted"])

    def test_stale_wrong_file_and_conflicting_receipts(self):
        self.state.submit(self.request)
        for field, value in (("requestId", "other"), ("job", "other"), ("fileKey", "OtherFile"),
                             ("action", "apply"), ("executedBy", "phone"), ("executedAt", "")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.state.finish({**self.ack, field: value})
        self.state.finish(self.ack)
        with self.assertRaises(ValueError):
            self.state.finish({"requestId": "r1", "error": "late failure"}, error=True)

    def test_error_retained_and_exact_retry(self):
        self.state.submit(self.request)
        error = {"requestId": "r1", "error": "Open the configured Figma file."}
        self.state.finish(error, True)
        self.state.finish(error, True)
        self.assertEqual(self.state.read()["error"], error["error"])
        with self.assertRaises(ValueError):
            self.state.finish(self.ack)

    def test_validation_and_read_isolation(self):
        for body in ({"action": "eval", "code": "bad"}, {"action": "inspect", "code": "bad"},
                     {"action": "apply", "frame": "Start", "nodes": [{}] * 51}):
            with self.assertRaises(ValueError):
                self.state.submit({**self.request, "body": body})
        self.state.submit(self.request)
        view = self.state.read()
        view["request"]["body"]["action"] = "eval"
        self.assertEqual(self.state.read()["request"]["body"]["action"], "inspect")

    def test_endpoint_roles(self):
        self.assertTrue(relay.permitted("phone", "POST", "/request", None))
        self.assertFalse(relay.permitted("phone", "POST", "/request", "null"))
        self.assertFalse(relay.permitted("phone", "POST", "/ack", None))
        self.assertFalse(relay.permitted("plugin", "POST", "/request", "null"))
        self.assertTrue(relay.permitted("plugin", "POST", "/ack", "null"))

    def test_closed_nested_schema_before_queueing(self):
        for node in ({"key": "Card", "eval": "bad"}, {"key": "Card", "opacity": 2},
                     {"key": "Card", "width": 0}, {"key": "Card", "visible": "yes"},
                     {"key": "Card", "x": float("nan")}, {"key": "Card", "x": 10 ** 400}):
            with self.subTest(node=node), self.assertRaises(ValueError):
                self.state.submit({**self.request, "body": {"action": "apply", "frame": "Start", "nodes": [node]}})
        for body in ({"action": "begin", "width": True, "height": 720},
                     {"action": "link", "edges": [{"frame": "Start", "key": "$frame", "to": "Done", "delayMs": 0}]},
                     {"action": "clone", "frame": "Done", "from": "Start", "overrides": [{}] * 51}):
            with self.subTest(body=body), self.assertRaises(ValueError):
                self.state.submit({**self.request, "body": body})
        self.assertIsNone(self.state.read()["request"])
        self.state.submit({**self.request, "body": {"action": "apply", "frame": "Start", "nodes": [
            {"key": "Card", "type": "RECTANGLE", "width": 100, "height": 150, "opacity": 0.5}]}})

    def test_private_config_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "synthetic.json"
            relay.write_private(path, {"example": True})
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                relay.write_private(path, {"example": False})

    def handler(self, body, declared=None):
        handler = object.__new__(relay.Handler)
        handler.server = SimpleNamespace(state=self.state, role="phone")
        handler.path, handler.command = "/request", "POST"
        handler.headers = {"Authorization": "Bearer " + "p" * 40,
                           "Content-Length": str(len(body) if declared is None else declared)}
        handler.rfile = io.BytesIO(body)
        handler.timeouts, handler.responses = [], []
        handler.connection = SimpleNamespace(settimeout=handler.timeouts.append)
        handler.send = lambda status, value: handler.responses.append((status, value))
        return handler

    def test_partial_body_and_timeout_fail_closed(self):
        for timed_out in (False, True):
            handler = self.handler(b"{}", declared=10)
            if timed_out:
                class SlowBody:
                    def read(self, _):
                        raise TimeoutError("synthetic stalled body")
                handler.rfile = SlowBody()
            handler.do_POST()
            self.assertEqual(handler.responses[-1][0], 408 if timed_out else 400)
            self.assertTrue(handler.close_connection)
            self.assertEqual(handler.timeouts, [relay.READ_TIMEOUT_SECONDS])
            self.assertIsNone(self.state.read()["request"])

    def test_post_expiry_after_body_never_mutates_state(self):
        body = relay.json.dumps(self.request).encode()
        self.now = 60.5
        handler = self.handler(body)
        outer = self
        class DelayedBody:
            def read(self, length):
                outer.now = 61.0
                return body[:length]
        handler.rfile = DelayedBody()
        handler.do_POST()
        self.assertEqual(handler.responses[-1][0], 401)
        self.assertEqual(handler.timeouts, [0.5], "read timeout is clipped to remaining TTL")
        self.assertTrue(handler.close_connection)
        self.assertIsNone(self.state.read()["request"])
        with self.assertRaises(relay.SessionExpired):
            self.state.submit(self.request)

    def test_expiry_at_commit_guard_rejects_receipts(self):
        self.state.submit(self.request)
        self.now = 61.0
        with self.assertRaises(relay.SessionExpired):
            self.state.finish(self.ack)
        self.assertIsNone(self.state.read()["ack"])

    def test_server_close_does_not_wait_for_a_stalled_worker(self):
        # Exercise ThreadingMixIn without constructing or binding a socket.
        server = object.__new__(relay.RelayServer)
        entered, release = threading.Event(), threading.Event()
        daemons = []
        def stalled(*_):
            daemons.append(threading.current_thread().daemon)
            entered.set()
            release.wait(2)
        server.finish_request = stalled
        server.shutdown_request = lambda *_: None
        closed = []
        server.socket = SimpleNamespace(close=lambda: closed.append(True))
        try:
            server.process_request(None, None)
            self.assertTrue(entered.wait(1))
            server.server_close()
            self.assertEqual(closed, [True])
            self.assertEqual(daemons, [True])
            self.assertFalse(release.is_set(), "close returned while the worker was still waiting")
        finally:
            release.set()


if __name__ == "__main__":
    unittest.main()
