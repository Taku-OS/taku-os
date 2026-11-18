#!/usr/bin/env python3
"""Synthetic binary/clock tests; no device access or modification of footage."""
import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location("timing", Path(__file__).with_name("audit-screenrecord-timing.py"))
timing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(timing)


class TimingTests(unittest.TestCase):
    def metadata(self):
        binary = b"synthetic-prefix" + timing.MAGIC + struct.pack("<IqIQQ", 2, 1_000_000_000_000, 2, 1_000_000_000, 2_000_000_000)
        return binary, timing.parse_winscope(binary)

    def test_real_layout_uses_four_byte_frame_count_and_rejects_ambiguity(self):
        binary, metadata = self.metadata()
        self.assertEqual(metadata["elapsedNs"], [1_000_000_000, 2_000_000_000])
        for bad in (binary[:-1], binary + timing.MAGIC, b"no timestamp metadata"):
            with self.assertRaises(ValueError):
                timing.parse_winscope(bad)

    def test_every_decoded_pts_and_frame_count_must_match(self):
        _, metadata = self.metadata()
        probe = {"frames": [{"best_effort_timestamp_time": "0.000000"}, {"best_effort_timestamp_time": "1.000100"}]}
        _, error = timing.check_video_pts(metadata, probe, 1_000_000)
        self.assertEqual(error, 100_000)
        with self.assertRaises(ValueError):
            timing.check_video_pts(metadata, probe, 50_000)
        probe["frames"].pop()
        with self.assertRaises(ValueError):
            timing.check_video_pts(metadata, probe, 1_000_000)

    def test_elapsed_mapping_is_bracketed_and_does_not_depend_on_device_wall_clock(self):
        _, metadata = self.metadata()
        anchors = {"schema": "taku.clock-anchors/1", "samples": [
            {"hostBeforeUnixNs": 1_000_000_000_000, "hostAfterUnixNs": 1_000_020_000_000,
             "deviceElapsedRealtimeNs": 0, "elapsedResolutionNs": 10_000_000, "deviceUnixNs": 20},
            {"hostBeforeUnixNs": 1_003_000_000_000, "hostAfterUnixNs": 1_003_010_000_000,
             "deviceElapsedRealtimeNs": 3_000_000_000, "elapsedResolutionNs": 10_000_000, "deviceUnixNs": 30}]}
        result = timing.calibrate(anchors, metadata)
        self.assertEqual(result["firstFrameHostUnixNs"], 1_001_000_000_000)
        self.assertEqual(result["uncertaintyHalfWidthNs"], 10_000_000)
        self.assertTrue(result["probesBracketVerifiedFrames"])
        self.assertTrue(result["warnings"])

    def test_disjoint_clock_probes_fail_instead_of_inventing_sync(self):
        _, metadata = self.metadata()
        anchors = {"schema": "taku.clock-anchors/1", "samples": [
            {"hostBeforeUnixNs": 10_000, "hostAfterUnixNs": 10_100, "deviceUnixNs": 5_000},
            {"hostBeforeUnixNs": 20_000, "hostAfterUnixNs": 20_100, "deviceUnixNs": 10_000}]}
        with self.assertRaisesRegex(ValueError, "do not intersect"):
            timing.calibrate(anchors, metadata)

    def test_float_epoch_nanoseconds_are_rejected(self):
        with self.assertRaises(ValueError):
            timing.integer(1.793e18, "time")
        self.assertEqual(timing.integer("1793000000000000001", "time"), 1793000000000000001)


if __name__ == "__main__":
    unittest.main()
