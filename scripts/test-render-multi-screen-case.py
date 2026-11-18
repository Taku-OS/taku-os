#!/usr/bin/env python3
"""Synthetic-only renderer checks. No device, account or real App footage.

Run with the bundled Python that contains Pillow. Optionally preserve the fixture,
MP4 and overview/focus stills using --preview-dir /an/empty/path.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import struct
import sys
import tempfile
import unittest

from PIL import Image, ImageDraw, ImageFont

spec = importlib.util.spec_from_file_location("renderer", Path(__file__).with_name("render-multi-screen-case.py"))
renderer = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = renderer
spec.loader.exec_module(renderer)
timing_spec = importlib.util.spec_from_file_location("timing", Path(__file__).with_name("audit-screenrecord-timing.py"))
timing = importlib.util.module_from_spec(timing_spec)
timing_spec.loader.exec_module(timing)
PREVIEW = None


def fixture(root):
    recording = root / "recording"
    recording.mkdir(parents=True)
    font = ImageFont.truetype(renderer.find_font(), 25)
    bindings = []
    colors = [(184, 54, 76), (23, 119, 164), (204, 139, 11)]
    for lane_index, key in enumerate(("synthetic-xhs", "synthetic-maps", "synthetic-meituan")):
        binding = {"screenKey": key, "displayId": lane_index + 2, "generation": f"SYNTHETIC-GENERATION-{lane_index}"}
        bindings.append(binding)
        folder = recording / key
        (folder / "frames").mkdir(parents=True)
        rows = []
        for index in range(25):
            timestamp = index / 5 + .02
            image = Image.new("RGB", (360, 640), colors[lane_index])
            draw = ImageDraw.Draw(image)
            draw.text((20, 30), "SYNTHETIC TEST", font=font, fill="white")
            draw.text((20, 90), f"LANE {lane_index + 1}", font=font, fill="white")
            draw.text((20, 140), f"capture +{timestamp:.2f}s", font=font, fill="white")
            draw.rectangle((20, 240 + index * 8, 340, 280 + index * 8), fill="white")
            file = f"frames/{index:06d}.jpg"
            image.save(folder / file, quality=85)
            status = dict(binding, frameSequence=index + 1, frameAt=1_000_000 + round(timestamp * 1000))
            rows.append({"relativeReadStartSeconds": timestamp - .01, "relativeReadEndSeconds": timestamp,
                         "statusBefore": status, "statusAfter": status, "stableFrameMetadata": True,
                         "file": file, "sha256": hashlib.sha256((folder / file).read_bytes()).hexdigest()})
        (folder / "frames.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
    (recording / "recording.json").write_text(json.dumps({"schema": "taku.multi-screen-recording/1",
        "startedAtUnixMs": 1_000_000, "deviceSerial": "SYNTHETIC-TEST-NO-DEVICE", "captureComplete": True,
        "bindings": bindings}))
    main_frames = root / "main-frames"
    main_frames.mkdir()
    for index in range(50):
        image = Image.new("RGB", (360, 640), (27, 132, 85))
        draw = ImageDraw.Draw(image)
        draw.text((20, 30), "SYNTHETIC MAIN", font=font, fill="white")
        draw.text((20, 90), f"video +{index / 10:.2f}s", font=font, fill="white")
        draw.ellipse((20 + index * 4, 300, 95 + index * 4, 375), fill="white")
        image.save(main_frames / f"{index:04d}.png")
    video = root / "synthetic-main.mp4"
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-framerate", "10", "-i", str(main_frames / "%04d.png"),
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", str(video)], check=True)
    edit = {"schema": "taku.multi-screen-edit/1", "reviewed": True, "synthetic": True,
            "title": "Four-screen synthetic test · Synthetic footage only", "reviewNote": "Synthetic fixture, not device or Agent acceptance.",
            "labels": {"main": "WeChat · Synthetic main display", "synthetic-xhs": "Xiaohongshu · Synthetic display A", "synthetic-maps": "Amap · Synthetic display B", "synthetic-meituan": "Meituan · Synthetic display C"},
            "cuts": [{"start": .4, "end": 4.4}], "segments": []}
    focuses = ["overview", "synthetic-xhs", "synthetic-maps", "synthetic-meituan", "overview"]
    captions = ["Edit layout for three parallel queries\nSynthetic test footage only", "Focus on Xiaohongshu\nThe other three screens stay visible", "Switch to the Amap route\nAll streams share cuts and speed", "Switch to Meituan reservation information\nNo actual reservation was made", "Return to the four-screen overview\nReal Agent concurrency still needs tool-event evidence"]
    for index, (focus, caption) in enumerate(zip(focuses, captions)):
        edit["segments"].append({"start": round(.4 + index * .8, 2), "end": round(1.2 + index * .8, 2),
                                 "focus": focus, "caption": caption, "evidence": [f"synthetic-fixture:stage-{index}"]})
    edit_path = root / "synthetic-edit.json"
    edit_path.write_text(json.dumps(edit, ensure_ascii=False, indent=2))
    return recording, video, edit_path


class RendererTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = None
        if PREVIEW:
            cls.root = Path(PREVIEW).resolve()
            cls.root.mkdir(parents=True, exist_ok=False)
        else:
            cls.temporary = tempfile.TemporaryDirectory(prefix="taku-render-test-")
            cls.root = Path(cls.temporary.name)
        cls.recording, cls.video, cls.edit_path = fixture(cls.root)

    @classmethod
    def tearDownClass(cls):
        if cls.temporary:
            cls.temporary.cleanup()

    def test_01_shared_cuts_speed_and_gap_boundaries(self):
        edit = renderer.read_json(self.edit_path)
        edit["cuts"] = [{"start": .4, "end": 2.4}, {"start": 2.8, "end": 4.4}]
        plan, speed = renderer.make_schedule(edit, 2, speed=2)
        self.assertEqual(speed, 2)
        self.assertEqual([round(t, 2) for t, _ in plan], [.4, 1.4, 2.8, 3.8])
        lane = renderer.Lane("test", [renderer.Frame(.1, Path("a")), renderer.Frame(.3, Path("b")), renderer.Frame(.9, Path("c"))])
        self.assertIsNotNone(lane.at(.5, .3))
        self.assertIsNone(lane.at(.7, .3))
        self.assertIsNone(lane.at(.95, .3))  # Never extend the final captured frame.

    def test_02_review_and_coverage_are_required(self):
        _, lanes = renderer.load_recording(self.recording)
        edit = renderer.read_json(self.edit_path)
        edit["segments"][0]["evidence"] = []
        with self.assertRaisesRegex(ValueError, "evidence"):
            renderer.validate_edit(edit, ["main", *lanes])
        edit = renderer.read_json(self.edit_path)
        edit["segments"][1]["start"] += .1
        with self.assertRaisesRegex(ValueError, "reviewed segment"):
            renderer.validate_edit(edit, ["main", *lanes])

    def test_03_corrupt_or_missing_capture_is_rejected(self):
        path = self.recording / "synthetic-xhs/frames/000000.jpg"
        original = path.read_bytes()
        try:
            path.write_bytes(b"not a JPEG")
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                renderer.load_recording(self.recording)
            path.unlink()
            with self.assertRaisesRegex(ValueError, "missing"):
                renderer.load_recording(self.recording)
        finally:
            path.write_bytes(original)

    def test_04_render_focus_overview_and_visible_gaps(self):
        output = self.root / "synthetic-four-screen-preview.mp4"
        summary = renderer.render(self.recording, self.video, 1_000_000, self.edit_path, output, width=1080, fps=6)
        self.assertEqual(summary["durationSeconds"], 4)
        self.assertTrue(summary["synthetic"])
        self.assertEqual(summary["gapOutputFrames"], {})
        probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "stream=width,height:format=duration", "-of", "json", str(output)]))
        self.assertEqual((probe["streams"][0]["width"], probe["streams"][0]["height"]), (1080, 750))
        self.assertLess(float(probe["format"]["duration"]), 60)
        for label, timestamp in (("overview", .2), ("focus", 1.2)):
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-ss", str(timestamp), "-i", str(output), "-frames:v", "1", str(self.root / f"synthetic-{label}.png")], check=True)
        with self.assertRaisesRegex(ValueError, "already exists"):
            renderer.render(self.recording, self.video, 1_000_000, self.edit_path, output, width=1080, fps=6)
        gap_edit = renderer.read_json(self.edit_path)
        gap_edit["cuts"] = [{"start": 4.4, "end": 5.4}]
        gap_edit["segments"] = [{"start": 4.4, "end": 5.4, "focus": "overview", "caption": "Gap test: never freeze a frame to disguise ended footage", "evidence": ["synthetic-fixture:gap"]}]
        gap_path = self.root / "synthetic-gap-edit.json"
        gap_path.write_text(json.dumps(gap_edit))
        with self.assertRaisesRegex(ValueError, "capture gap"):
            renderer.render(self.recording, self.video, 1_000_000, gap_path, self.root / "must-not-exist.mp4", width=1080, fps=4)
        self.assertFalse((self.root / "must-not-exist.mp4").exists())
        marked = renderer.render(self.recording, self.video, 1_000_000, gap_path, self.root / "synthetic-gap-preview.mp4", width=1080, fps=4, gaps="mark")
        self.assertTrue(marked["gapOutputFrames"])

    def test_05_audited_sparse_video_holds_only_between_verified_pts(self):
        directory = self.root / "sparse-main-frames"
        directory.mkdir()
        for index, source_index in enumerate((0, 20, 40)):
            (directory / f"{index:04d}.png").write_bytes((self.root / "main-frames" / f"{source_index:04d}.png").read_bytes())
        video = self.root / "synthetic-sparse-main.mp4"
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-framerate", "1/2", "-i", str(directory / "%04d.png"),
                        "-c:v", "libx264", "-bf", "0", "-pix_fmt", "yuv420p", str(video)], check=True)
        # Explicitly synthetic metadata in a free atom. The actual three decoded
        # video frames still have to pass the real audit's complete PTS checks.
        payload = timing.MAGIC + struct.pack("<IqIQQQ", 2, 999_000_000_000, 3, 1_000_000_000, 3_000_000_000, 5_000_000_000)
        with video.open("ab") as stream:
            stream.write(struct.pack(">I4s", len(payload) + 8, b"free") + payload)
        anchors = {"schema": "taku.clock-anchors/1", "samples": [
            {"hostBeforeUnixNs": 999_000_000_000, "hostAfterUnixNs": 999_000_000_000, "deviceElapsedRealtimeNs": 0, "elapsedResolutionNs": 1},
            {"hostBeforeUnixNs": 1_006_000_000_000, "hostAfterUnixNs": 1_006_000_000_000, "deviceElapsedRealtimeNs": 7_000_000_000, "elapsedResolutionNs": 1}]}
        audit = timing.audit(video, anchors)
        self.assertEqual(audit["frameCount"], 3)
        self.assertEqual(audit["containerTailAfterLastVerifiedFrameSeconds"], 2)
        audit_path = self.root / "synthetic-sparse-timing.json"
        audit_path.write_text(json.dumps(audit))
        renderer.load_main_audit(audit_path, video)
        extracted = self.root / "sparse-extracted"
        extracted.mkdir()
        lane = renderer.extract_main(video, extracted, 0, 4.5, timing=audit, requested_times=[.5, 1.5, 2.5, 3.5, 4.25])
        self.assertEqual(lane.at(.5, .8).path, lane.at(1.5, .8).path)
        self.assertEqual(lane.at(2.5, .8).path, lane.at(3.5, .8).path)
        self.assertIsNone(lane.at(4.25, .8), "do not extend through inferred two-second container tail")
        background = renderer.Lane("background", lane.frames)
        self.assertIsNone(background.at(1.5, .8), "background capture gaps must not inherit video hold semantics")
        edit = renderer.read_json(self.edit_path)
        edit["cuts"] = [{"start": .4, "end": 3.8}]
        edit_path = self.root / "synthetic-sparse-edit.json"
        edit_path.write_text(json.dumps(edit))
        result = renderer.render(self.recording, video, None, edit_path, self.root / "synthetic-sparse-preview.mp4",
                                 width=1080, fps=4, main_timing_audit=audit_path)
        self.assertEqual(result["gapOutputFrames"], {})
        self.assertIsNotNone(result["mainTimingAudit"])
        original_digest = audit["sha256"]
        audit["sha256"] = "wrong-video"
        audit_path.write_text(json.dumps(audit))
        with self.assertRaisesRegex(ValueError, "SHA256"):
            renderer.load_main_audit(audit_path, video)
        audit["sha256"] = original_digest
        audit_path.write_text(json.dumps(audit))

    def test_06_large_exact_frame_selection_uses_bounded_expression_depth(self):
        selection = renderer.frame_index_select(range(0, 3000, 3))
        result = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                                 "color=size=16x16:rate=8:duration=1", "-vf", f"select='{selection}'",
                                 "-fps_mode", "vfr", "-f", "framehash", "-"],
                                check=True, capture_output=True, text=True)
        rows = [line for line in result.stdout.splitlines() if line and not line.startswith("#")]
        self.assertEqual(len(rows), 3, "only original frame indices 0, 3 and 6 are selected")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preview-dir")
    args, remaining = parser.parse_known_args()
    PREVIEW = args.preview_dir
    unittest.main(argv=[sys.argv[0], *remaining])
