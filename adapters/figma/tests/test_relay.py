"""Synthetic protocol tests. No sockets, devices, or real session files."""
import importlib.util
from pathlib import Path
import tempfile
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


if __name__ == "__main__":
    unittest.main()
