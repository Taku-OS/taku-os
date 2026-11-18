#!/usr/bin/env python3
"""Render reviewed four-screen footage on the recorder's shared host timeline.

Requires ffmpeg, ffprobe and Pillow. Never contacts a device or uploads footage.
Example:
  python3 scripts/render-multi-screen-case.py --recording /path/to/recording \
    --main-video /path/to/main.mp4 --main-start-unix-ms 1793000000000 \
    --edit /path/to/reviewed-edit.json --output /path/to/case.mp4

For audited Android screenrecord footage, replace --main-start-unix-ms with
--main-timing-audit main-timing-audit.json from audit-screenrecord-timing.py.
Only this audited main-video path permits normal presentation of a static frame
until the next verified PTS; it stops at the final verified frame, never at an
inferred container tail. Background JPEG lanes always retain receipt-gap checks.

Edit JSON (times are seconds relative to recording.json.startedAtUnixMs):
  {"schema":"taku.multi-screen-edit/1", "reviewed":true, "synthetic":false,
   "title":"One dinner plan, four views", "reviewNote":"Reviewed against tool events",
   "labels":{"main":"WeChat · Main display","slot-a":"Xiaohongshu","slot-b":"Amap","slot-c":"Meituan"},
   "cuts":[{"start":2,"end":12}],
   "segments":[{"start":2,"end":6,"focus":"overview","caption":"Three independent queries",
                "evidence":["tool-events.jsonl:12-18"]},
               {"start":6,"end":12,"focus":"slot-b","caption":"Check the route to the venue",
                "evidence":["tool-events.jsonl:19-24"]}]}

All four lanes use the same cuts and speed. Focus changes layout only. JPEGs are
placed at host receipt completion, not claimed device presentation times. The
provided main start must be the host timestamp of its first video frame; an adb
launch timestamp alone is only an estimate and should be described in reviewNote.
Missing/stale samples fail by default; --gaps mark shows a conspicuous gap panel.
No gap is filled by indefinitely freezing the last image. Static screens with
fresh recorder polls are valid captures, not evidence of ongoing Agent work.
"""

import argparse
import bisect
from collections import Counter
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from PIL import Image, ImageDraw, ImageFont, ImageOps


def number(value, label):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{label} must be finite")
    return float(value)


def read_json(path):
    return json.loads(Path(path).read_text())


@dataclass(frozen=True)
class Frame:
    time: float
    path: Path


class Lane:
    def __init__(self, key, frames, end_time=None):
        self.key, self.frames = key, frames
        self.times = [frame.time for frame in frames]
        if not frames or any(b <= a for a, b in zip(self.times, self.times[1:])):
            raise ValueError(f"{key}: frames must have strictly increasing receipt timestamps")
        self.end_time = self.times[-1] if end_time is None else end_time

    def at(self, timestamp, max_gap):
        index = bisect.bisect_right(self.times, timestamp + 1e-7) - 1
        if index < 0 or timestamp > self.end_time + 1e-7 or timestamp - self.times[index] > max_gap:
            return None
        return self.frames[index]


class VerifiedVideoLane(Lane):
    """Original video presentation semantics within a separately verified span."""
    def at(self, timestamp, max_gap):
        index = bisect.bisect_right(self.times, timestamp + 1e-7) - 1
        if index < 0 or timestamp > self.end_time + 1e-7:
            return None
        return self.frames[index]


def load_main_audit(path, video):
    report = read_json(path)
    if report.get("schema") != "taku.screenrecord-timing-audit/1":
        raise ValueError("unsupported main timing audit")
    digest = hashlib.sha256()
    with Path(video).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if report.get("sha256") != digest.hexdigest():
        raise ValueError("main timing audit does not match this video SHA256")
    pts = report.get("verifiedVideoPtsNs", [])
    if not pts or len(pts) != report.get("frameCount") or any(type(t) is not int for t in pts):
        raise ValueError("main audit must include every verified integer video PTS")
    if any(b <= a for a, b in zip(pts, pts[1:])) or pts[0] != report.get("firstVideoPtsNs") or pts[-1] != report.get("lastVideoPtsNs"):
        raise ValueError("inconsistent verified main PTS")
    mapping = report.get("hostCalibration")
    if not isinstance(mapping, dict) or type(mapping.get("firstFrameHostUnixNs")) is not int:
        raise ValueError("main timing audit needs host clock calibration")
    if type(report.get("maxWinscopeVsVideoPtsErrorNs")) is not int or not 0 <= report["maxWinscopeVsVideoPtsErrorNs"] <= 10_000_000:
        raise ValueError("main timing audit PTS verification is invalid")
    return report


def load_recording(directory):
    directory = Path(directory).resolve()
    manifest = read_json(directory / "recording.json")
    if manifest.get("schema") != "taku.multi-screen-recording/1":
        raise ValueError("unsupported recording schema")
    number(manifest.get("startedAtUnixMs"), "recording start")
    bindings = manifest.get("bindings", [])
    if len(bindings) != 3:
        raise ValueError("four-screen render requires exactly three recorded background lanes")
    lanes, seen_displays, seen_generations = {}, set(), set()
    for binding in bindings:
        key = binding.get("screenKey", "")
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,31}", key) or key == "main" or key in lanes:
            raise ValueError("invalid or duplicate background screen key")
        display, generation = binding.get("displayId"), binding.get("generation")
        if type(display) is not int or display <= 0 or display in seen_displays:
            raise ValueError("background display IDs must be positive and distinct")
        if not isinstance(generation, str) or not generation or generation in seen_generations:
            raise ValueError("background generations must be distinct")
        seen_displays.add(display)
        seen_generations.add(generation)
        folder = directory / key
        frames = []
        for line in (folder / "frames.jsonl").read_text().splitlines():
            row = json.loads(line)
            for status_name in ("statusBefore", "statusAfter"):
                status = row[status_name]
                if any(status.get(field) != binding[field] for field in ("screenKey", "displayId", "generation")):
                    raise ValueError(f"{key}: {status_name} does not match recorded binding")
            begin = number(row["relativeReadStartSeconds"], "sample start")
            end = number(row["relativeReadEndSeconds"], "sample end")
            if end < begin or begin < 0:
                raise ValueError(f"{key}: invalid sample interval")
            if "file" not in row:
                continue  # Empty polls never become an invented picture.
            path = (folder / row["file"]).resolve()
            if not path.is_relative_to(folder.resolve()) or not path.is_file():
                raise ValueError(f"{key}: missing or out-of-folder JPEG: {row['file']}")
            if hashlib.sha256(path.read_bytes()).hexdigest() != row.get("sha256"):
                raise ValueError(f"{key}: JPEG hash mismatch: {path.name}")
            with Image.open(path) as image:
                if image.format != "JPEG":
                    raise ValueError(f"{key}: expected a JPEG")
                image.verify()
            frames.append(Frame(end, path))
        lanes[key] = Lane(key, frames)
    return manifest, lanes


def validate_edit(edit, keys):
    if not isinstance(edit, dict) or edit.get("schema") != "taku.multi-screen-edit/1":
        raise ValueError("unsupported edit schema")
    if edit.get("reviewed") is not True or not str(edit.get("reviewNote", "")).strip():
        raise ValueError("an explicit reviewed edit and reviewNote are required")
    if type(edit.get("synthetic")) is not bool:
        raise ValueError("edit must explicitly declare synthetic true or false")
    cuts, segments = edit.get("cuts", []), edit.get("segments", [])
    if not cuts or not segments:
        raise ValueError("provide source cuts and reviewed focus/caption segments")
    for name, rows in (("cuts", cuts), ("segments", segments)):
        previous = -math.inf
        for row in rows:
            begin, end = number(row.get("start"), name), number(row.get("end"), name)
            if begin < 0 or end <= begin or begin < previous:
                raise ValueError(f"{name} must be ordered, non-overlapping positive intervals")
            previous = end
    for segment in segments:
        if segment.get("focus") not in {"overview", *keys}:
            raise ValueError("focus must be overview, main or a recorded screenKey")
        if not isinstance(segment.get("caption"), str) or not segment["caption"].strip():
            raise ValueError("each segment needs a reviewed caption")
        if len(segment["caption"]) > 100:
            raise ValueError("keep each large caption within 100 characters")
        if not isinstance(segment.get("evidence"), list) or not segment["evidence"] or not all(isinstance(x, str) and x.strip() for x in segment["evidence"]):
            raise ValueError("each segment must cite reviewed tool-event evidence")
    for cut in cuts:
        covered = cut["start"]
        for segment in segments:
            if segment["end"] <= covered:
                continue
            if segment["start"] > covered + 1e-7:
                break
            covered = max(covered, segment["end"])
            if covered >= cut["end"]:
                break
        if covered < cut["end"]:
            raise ValueError("every retained source interval must have a reviewed segment")
    return edit


def make_schedule(edit, fps, speed=None, max_duration=58):
    if not 1 <= fps <= 30 or not 0 < max_duration < 60:
        raise ValueError("fps must be 1..30 and max-duration must be below 60 seconds")
    duration = sum(cut["end"] - cut["start"] for cut in edit["cuts"])
    speed = max(1, duration / max_duration) if speed is None else number(speed, "speed")
    if speed < 1 or duration / speed > max_duration + 1e-7:
        raise ValueError("use one speed >= 1 that fits the requested duration")
    count = max(1, math.ceil(duration / speed * fps - 1e-7))
    if count / fps >= 60:
        raise ValueError("rounded output duration must remain below 60 seconds")
    ends, total = [], 0
    for cut in edit["cuts"]:
        total += cut["end"] - cut["start"]
        ends.append(total)
    schedule = []
    for index in range(count):
        position = min(index / fps * speed, duration - 1e-7)
        cut_index = bisect.bisect_right(ends, position)
        cut = edit["cuts"][cut_index]
        source = cut["start"] + position - (ends[cut_index - 1] if cut_index else 0)
        segment = next(s for s in edit["segments"] if s["start"] <= source < s["end"])
        schedule.append((source, segment))
    return schedule, speed


def frame_index_select(indices):
    # FFmpeg's expression parser has a finite recursion depth. A flat sum is
    # left-associative and fails for long real captures; a balanced tree keeps
    # the depth logarithmic without changing which exact audited frames select.
    terms = [f"eq(n,{index})" for index in indices]
    while len(terms) > 1:
        terms = [f"({terms[index]}+{terms[index + 1]})" if index + 1 < len(terms) else terms[index]
                 for index in range(0, len(terms), 2)]
    return terms[0] if terms else "0"


def extract_main(video, destination, start_offset, last_source, ffmpeg="ffmpeg", ffprobe="ffprobe", timing=None, requested_times=None):
    probe = subprocess.run([ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries",
                            "stream=width,height,duration:format=duration", "-of", "json", str(video)],
                           check=True, capture_output=True, text=True)
    metadata = json.loads(probe.stdout)
    if not metadata.get("streams"):
        raise ValueError("main video has no video stream")
    # select keeps original sampled PTS; unlike an fps filter it never duplicates
    # a source frame across a missing timestamp interval. setpts defines first frame=0.
    selected = None
    if timing is not None:
        all_pts = [(value - timing["verifiedVideoPtsNs"][0]) / 1e9 for value in timing["verifiedVideoPtsNs"]]
        # Select by original frame index, not a cadence filter that can drop the
        # final changed frame just before a long static interval.
        indices = {0}
        for timestamp in requested_times or []:
            index = bisect.bisect_right(all_pts, timestamp - start_offset + 1e-7) - 1
            if index >= 0:
                indices.add(index)
        selected = sorted(indices)
        selection = frame_index_select(selected)
    else:
        selection = "isnan(prev_selected_t)+gte(t-prev_selected_t,0.08)"
    filters = f"setpts=PTS-STARTPTS,select='{selection}',scale=440:800:force_original_aspect_ratio=decrease,showinfo"
    command = [ffmpeg, "-hide_banner", "-nostdin", "-i", str(video), "-map", "0:v:0", "-an",
               "-t", str(max(0.1, last_source - start_offset + 1)), "-vf", filters,
               "-fps_mode", "vfr", "-q:v", "3", str(destination / "%06d.jpg")]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise ValueError("main video decode failed: " + result.stderr[-1500:])
    timestamps = [float(value) for value in re.findall(r"\bn:\s*\d+\s+pts:\s*\S+\s+pts_time:([\d.eE+-]+)", result.stderr)]
    paths = sorted(destination.glob("*.jpg"))
    # showinfo may see one frame at/after -t that the encoder does not emit.
    if len(timestamps) < len(paths) or not paths:
        raise ValueError("could not establish decoded main-frame timestamps")
    if selected is not None:
        expected = [all_pts[index] for index in selected]
        if len(paths) != len(expected) or any(abs(a - b) > .002 for a, b in zip(timestamps, expected)):
            raise ValueError("decoded main frames do not match the audited PTS selection")
        return VerifiedVideoLane("main", [Frame(start_offset + timestamp, path) for timestamp, path in zip(expected, paths)],
                                 end_time=start_offset + all_pts[-1])
    duration = metadata["streams"][0].get("duration", metadata.get("format", {}).get("duration"))
    end_time = start_offset + float(duration) if duration is not None else None
    return Lane("main", [Frame(start_offset + timestamp, path) for timestamp, path in zip(timestamps, paths)], end_time)


def find_font(explicit=None):
    candidates = [explicit] if explicit else [
        "/System/Library/Fonts/STHeiti Medium.ttc",
        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise ValueError("provide --font with a CJK-capable TTF/TTC/OTF file")


class Composer:
    def __init__(self, width, font, edit, keys, speed):
        self.width, self.height = width, width * 25 // 36
        self.scale, self.font_path = width / 1440, font
        self.edit, self.keys, self.speed = edit, keys, speed
        self.fonts, self.cache = {}, {}

    def font(self, size):
        size = max(9, round(size * self.scale))
        if size not in self.fonts:
            self.fonts[size] = ImageFont.truetype(self.font_path, size)
        return self.fonts[size]

    def box(self, rectangle):
        return tuple(round(value * self.scale) for value in rectangle)

    def text(self, draw, text, xy, size, color, width=None):
        font = self.font(size)
        if width is not None:
            while draw.textlength(text, font=font) > width * self.scale and size > 15:
                size -= 1
                font = self.font(size)
            if draw.textlength(text, font=font) > width * self.scale:
                while text and draw.textlength(text + "…", font=font) > width * self.scale:
                    text = text[:-1]
                text += "…"
        draw.text(self.box(xy), text, font=font, fill=color)

    def paragraph(self, draw, text, rectangle, size=44):
        x, y, width, height = rectangle
        for candidate in range(size, 23, -1):
            font, lines, current = self.font(candidate), [], ""
            for char in text:
                if char == "\n" or (current and draw.textlength(current + char, font=font) > width * self.scale):
                    lines.append(current)
                    current = "" if char == "\n" else char
                else:
                    current += char
            if current:
                lines.append(current)
            step = candidate * 1.4
            if step * len(lines) <= height:
                for index, line in enumerate(lines):
                    self.text(draw, line, (x, y + step * index), candidate, "#122133")
                return
        raise ValueError("caption cannot fit; shorten the reviewed caption")

    def tile(self, canvas, key, frame, rectangle, focused):
        draw = ImageDraw.Draw(canvas)
        x, y, width, height = rectangle
        draw.rounded_rectangle(self.box((x, y, x + width, y + height)), radius=round(18 * self.scale),
                               fill="#ffffff", outline="#4a68ed" if focused else "#dce2e9", width=max(1, round(2 * self.scale)))
        label = self.edit.get("labels", {}).get(key, "Main display" if key == "main" else key)
        self.text(draw, label, (x + 15, y + 11), 24 if width > 280 else 21, "#26364a", width - 30)
        content = self.box((x + 10, y + 48, x + width - 10, y + height - 12))
        draw.rectangle(content, fill="#101820")
        if frame is None:
            self.text(draw, "Missing footage", (x + 22, y + height / 2 - 20), 30, "#ffffff", width - 44)
            self.text(draw, "NO CAPTURE", (x + 22, y + height / 2 + 25), 18, "#ffd174", width - 44)
            return
        cache_key = (str(frame.path), content[2] - content[0], content[3] - content[1])
        if cache_key not in self.cache:
            with Image.open(frame.path) as image:
                resized = ImageOps.contain(image.convert("RGB"), (cache_key[1], cache_key[2]), Image.Resampling.LANCZOS)
            if len(self.cache) > 16:
                self.cache.clear()
            self.cache[cache_key] = resized
        resized = self.cache[cache_key]
        canvas.paste(resized, (content[0] + (cache_key[1] - resized.width) // 2,
                               content[1] + (cache_key[2] - resized.height) // 2))

    def render(self, frames, source_time, segment, index, count):
        canvas = Image.new("RGB", (self.width, self.height), "#eff3f8")
        draw = ImageDraw.Draw(canvas)
        self.text(draw, "Taku OS", (28, 24), 36, "#14253a")
        self.text(draw, self.edit.get("title", "One task, four views"), (210, 30), 27, "#3c5068", 690)
        badge = "SYNTHETIC TEST · NOT DEVICE FOOTAGE" if self.edit["synthetic"] else "Original recordings · Synchronized edit"
        self.text(draw, badge, (910, 31), 23, "#9a5400" if self.edit["synthetic"] else "#415b73", 502)
        focus = segment["focus"]
        if focus == "overview":
            self.text(draw, "Four-screen overview · Shared capture timeline", (28, 82), 22, "#526b83")
            for index_key, key in enumerate(self.keys):
                self.tile(canvas, key, frames[key], (28 + 353 * index_key, 124, 325, 642), False)
            self.paragraph(draw, segment["caption"], (36, 795, 1360, 140), 46)
        else:
            self.text(draw, "Focus on the current action · Other screens remain visible", (28, 82), 22, "#526b83")
            self.tile(canvas, focus, frames[focus], (28, 124, 433, 808), True)
            others = [key for key in self.keys if key != focus]
            for index_key, key in enumerate(others):
                self.tile(canvas, key, frames[key], (490 + 315 * index_key, 124, 292, 560), False)
            self.paragraph(draw, segment["caption"], (500, 716, 900, 205), 48)
        self.text(draw, f"Uniform {self.speed:.2f}× speed · Capture +{source_time:.1f}s · Thumbnails change size only", (28, 959), 20, "#52677d", 1384)
        draw.rectangle(self.box((28, 991, 28 + 1384 * (index + 1) / count, 995)), fill="#4a68ed")
        return canvas


def render(recording, main_video, main_start_unix_ms, edit_path, output, *, width=1440, fps=15,
           max_duration=58, speed=None, max_gap=0.8, gaps="error", font=None, ffmpeg="ffmpeg", ffprobe="ffprobe", main_timing_audit=None):
    if width not in (1080, 1440) or gaps not in ("error", "mark") or not 0 < max_gap <= 2:
        raise ValueError("width must be 1080/1440, gaps error/mark, max-gap in (0,2]")
    if not shutil.which(ffmpeg) or not shutil.which(ffprobe):
        raise ValueError("ffmpeg and ffprobe must be installed")
    manifest, backgrounds = load_recording(recording)
    keys = ["main", *backgrounds]
    edit = validate_edit(read_json(edit_path), keys)
    if "SYNTHETIC" in str(manifest.get("deviceSerial", "")).upper() and not edit["synthetic"]:
        raise ValueError("synthetic input cannot be labelled real footage")
    schedule, actual_speed = make_schedule(edit, fps, speed, max_duration)
    output = Path(output).resolve()
    sidecar = output.with_suffix(".render.json")
    if output.exists() or sidecar.exists():
        raise ValueError("output or its render manifest already exists; evidence is never overwritten")
    output.parent.mkdir(parents=True, exist_ok=True)
    timing = load_main_audit(main_timing_audit, main_video) if main_timing_audit else None
    if timing is not None:
        first_ns = timing["hostCalibration"]["firstFrameHostUnixNs"]
        if main_start_unix_ms is not None and abs(main_start_unix_ms * 1_000_000 - first_ns) > 1_000_000:
            raise ValueError("supplied main start disagrees with the timing audit")
        main_start_unix_ms = (first_ns + 500_000) // 1_000_000
        start_offset = (first_ns - manifest["startedAtUnixMs"] * 1_000_000) / 1e9
    else:
        start_offset = (number(main_start_unix_ms, "main start") - manifest["startedAtUnixMs"]) / 1000
    composer = Composer(width, find_font(font), edit, keys, actual_speed)
    with tempfile.TemporaryDirectory(prefix="taku-four-screen-") as temporary:
        temporary = Path(temporary)
        main_directory = temporary / "main"
        main_directory.mkdir()
        main = extract_main(Path(main_video).resolve(), main_directory, start_offset, schedule[-1][0], ffmpeg, ffprobe,
                            timing=timing, requested_times=[timestamp for timestamp, _ in schedule])
        lanes = {"main": main, **backgrounds}
        missing, frame_plan = Counter(), []
        for timestamp, segment in schedule:
            row = {key: lane.at(timestamp, max_gap) for key, lane in lanes.items()}
            absent = [key for key, frame in row.items() if frame is None]
            if absent and gaps == "error":
                raise ValueError(f"capture gap at source +{timestamp:.3f}s: {', '.join(absent)}; trim cuts or use --gaps mark")
            missing.update(absent)
            frame_plan.append(row)
        encoded = temporary / "case.mp4"
        command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-f", "rawvideo",
                   "-pix_fmt", "rgb24", "-video_size", f"{composer.width}x{composer.height}",
                   "-framerate", str(fps), "-i", "pipe:0", "-an", "-c:v", "libx264", "-preset", "veryfast",
                   "-crf", "19", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(encoded)]
        with (temporary / "encoder.log").open("wb") as log:
            process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=log)
            try:
                for index, ((timestamp, segment), row) in enumerate(zip(schedule, frame_plan)):
                    process.stdin.write(composer.render(row, timestamp, segment, index, len(schedule)).tobytes())
                process.stdin.close()
                code = process.wait()
                if code:
                    raise ValueError("video encoder failed: " + (temporary / "encoder.log").read_text()[-1500:])
            except BaseException:
                process.kill()
                process.wait()
                raise
        summary = {
            "schema": "taku.multi-screen-render/1", "synthetic": edit["synthetic"],
            "recording": str(Path(recording).resolve()), "sourceCaptureComplete": manifest.get("captureComplete"),
            "mainVideo": str(Path(main_video).resolve()), "mainStartUnixMs": main_start_unix_ms,
            "durationSeconds": len(schedule) / fps, "fps": fps, "speed": actual_speed,
            "dimensions": [composer.width, composer.height], "edit": edit, "gapPolicy": gaps,
            "maxReceiptGapSeconds": max_gap, "gapOutputFrames": dict(missing),
            "alignment": "Shared host timeline, background frames placed at receipt completion. Main host clock alignment comes from the timing audit." if timing else "Shared host timeline, background frames placed at receipt completion. Main first-frame host timestamp supplied by operator; no device clock calibration.",
            "mainTimingAudit": {"path": str(Path(main_timing_audit).resolve()), "sourceSha256": timing["sha256"], "hostCalibration": timing["hostCalibration"], "lastVerifiedRelativePtsSeconds": (timing["lastVideoPtsNs"] - timing["firstVideoPtsNs"]) / 1e9} if timing else None,
            "mainFramePolicy": "Original presentation between verified PTS; stop at final verified frame." if timing else "Conservative receipt-gap limit; sparse PTS need a timing audit.",
            "interpretation": "Four simultaneous views are not proof of concurrent Agent work; reviewed event evidence must establish that separately.",
            "audio": "omitted", "outputSha256": hashlib.sha256(encoded.read_bytes()).hexdigest(),
        }
        # Copy with exclusive creation so even a competing renderer cannot overwrite evidence.
        with encoded.open("rb") as source, output.open("xb") as target:
            shutil.copyfileobj(source, target)
        with sidecar.open("x") as target:
            json.dump(summary, target, ensure_ascii=False, indent=2)
            target.write("\n")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--recording", required=True, type=Path)
    parser.add_argument("--main-video", required=True, type=Path)
    parser.add_argument("--main-start-unix-ms", type=int)
    parser.add_argument("--main-timing-audit", type=Path)
    parser.add_argument("--edit", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--width", type=int, choices=(1080, 1440), default=1440)
    parser.add_argument("--fps", type=int, default=15)
    parser.add_argument("--max-duration", type=float, default=58)
    parser.add_argument("--speed", type=float)
    parser.add_argument("--max-gap", type=float, default=.8)
    parser.add_argument("--gaps", choices=("error", "mark"), default="error")
    parser.add_argument("--font")
    args = parser.parse_args()
    if args.main_start_unix_ms is None and args.main_timing_audit is None:
        parser.error("provide --main-timing-audit or --main-start-unix-ms")
    result = render(args.recording, args.main_video, args.main_start_unix_ms, args.edit, args.output,
                    width=args.width, fps=args.fps, max_duration=args.max_duration, speed=args.speed,
                    max_gap=args.max_gap, gaps=args.gaps, font=args.font, main_timing_audit=args.main_timing_audit)
    print(json.dumps({"output": str(args.output), "durationSeconds": result["durationSeconds"],
                      "synthetic": result["synthetic"], "gapOutputFrames": result["gapOutputFrames"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
