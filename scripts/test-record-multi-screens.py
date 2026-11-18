#!/usr/bin/env python3
"""Host transport tests with explicitly synthetic mirror sockets, no Android device."""
import importlib.util
import json
import socketserver
import struct
import tempfile
import threading
import time
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("recorder", Path(__file__).with_name("record-multi-screens.py"))
recorder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recorder)


class Mirror(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        frame_calls = 0
        try:
            while command := self.rfile.readline():
                self.server.commands.append(command)
                if command == b"status\n":
                    status = dict(self.server.binding, frameAt=12345, frameSequence=1)
                    if self.server.change_generation and frame_calls:
                        status["generation"] = "replacement-host"
                    data = json.dumps(status).encode()
                elif command == b"frame\n":
                    frame_calls += 1
                    time.sleep(self.server.delay)
                    data = b"\xff\xd8synthetic-test-frame\xff\xd9"
                else:
                    raise AssertionError("recorder sent a mutating command")
                self.wfile.write(struct.pack(">I", len(data)) + data)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


class RecordingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.servers = []
        self.addCleanup(self.close_servers)

    def close_servers(self):
        for server in self.servers:
            server.shutdown()
            server.server_close()

    def mirror(self, index, delay=0, change_generation=False):
        server = Mirror(("127.0.0.1", 0), Handler)
        server.binding = {"screenKey": f"screen-{index}", "port": server.server_address[1],
                          "displayId": index + 2, "generation": f"generation-{index}"}
        server.delay, server.change_generation, server.commands = delay, change_generation, []
        self.servers.append(server)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        return server.binding

    def output(self):
        return Path(self.temp.name) / "recording"

    def test_three_lanes_share_clock_without_slow_lane_blocking_others(self):
        bindings = {"deviceSerial": "SYNTHETIC-TEST", "screens": [self.mirror(1), self.mirror(2), self.mirror(3, delay=.45)]}
        result = recorder.record(bindings, self.output(), duration=.75, fps=5)
        self.assertTrue(result["captureComplete"])
        self.assertGreater(result["screens"]["screen-1"]["polls"], result["screens"]["screen-3"]["polls"])
        self.assertGreater(result["commonCaptureWindow"]["durationSeconds"], .5)
        for server in self.servers:
            self.assertEqual(set(server.commands), {b"frame\n", b"status\n"})
        rows = [json.loads(line) for line in (self.output() / "screen-1/frames.jsonl").read_text().splitlines()]
        self.assertLess(rows[0]["relativeReadStartSeconds"], .1)
        self.assertTrue(all(row["stableFrameMetadata"] for row in rows))
        self.assertNotIn("agentSucceeded", result)

    def test_generation_change_stops_and_never_saves_new_host_frame(self):
        bindings = {"deviceSerial": "SYNTHETIC-TEST", "screens": [self.mirror(1, change_generation=True)]}
        result = recorder.record(bindings, self.output(), duration=.2, fps=5)
        self.assertFalse(result["captureComplete"])
        self.assertEqual(result["screens"]["screen-1"]["framesSaved"], 0)
        self.assertIn("generation", result["screens"]["screen-1"]["error"])

    def test_duplicate_display_and_foreground_are_rejected_before_connect(self):
        one = {"screenKey": "screen-1", "port": 1, "displayId": 0, "generation": "a"}
        with self.assertRaises(ValueError):
            recorder.validate_bindings({"deviceSerial": "TEST", "screens": [one]})
        one["displayId"] = 3
        two = dict(one, screenKey="screen-2", port=2, generation="b")
        with self.assertRaises(ValueError):
            recorder.validate_bindings({"deviceSerial": "TEST", "screens": [one, two]})

    def test_existing_evidence_directory_is_never_overwritten(self):
        bindings = {"deviceSerial": "SYNTHETIC-TEST", "screens": [self.mirror(1)]}
        self.output().mkdir()
        marker = self.output() / "original.txt"
        marker.write_text("preserve")
        with self.assertRaises(FileExistsError):
            recorder.record(bindings, self.output(), duration=.1)
        self.assertEqual(marker.read_text(), "preserve")

    def test_malformed_binding_and_status_are_explicit_errors(self):
        for value in ([], {"deviceSerial": "TEST", "screens": [[]]},
                      {"deviceSerial": "TEST", "screens": [{"screenKey": []}]}):
            with self.assertRaises(ValueError):
                recorder.validate_bindings(value)
        with self.assertRaises(ValueError):
            recorder.check_identity([], {})


if __name__ == "__main__":
    unittest.main()
