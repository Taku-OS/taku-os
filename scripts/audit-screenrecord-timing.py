#!/usr/bin/env python3
"""Audit original Android screenrecord MP4 timing without contacting a device.

Reads Winscope v2 metadata and checks EVERY timestamp against decoded ffprobe
presentation timestamps. No adb-launch/MP4 creation-time fallback is permitted.

Usage:
  python3 scripts/audit-screenrecord-timing.py --video main.mp4 \
    --anchors clock-anchors.json --recording background/recording.json \
    --output main-timing-audit.json

Clock anchors (all ns fields must be JSON integers or integer strings):
  {"schema":"taku.clock-anchors/1","samples":[
    {"hostBeforeUnixNs":1793000000000000000,
     "hostAfterUnixNs":1793000000010000000,
     "deviceUnixNs":1793000000004000000,
     "deviceElapsedRealtimeNs":123450120000000,
     "elapsedResolutionNs":10000000}]}

Capture host before/after around the same shell call reading device date and
/proc/uptime. Convert the FIRST uptime column with Decimal(seconds)*1e9, never a
float. /proc/uptime is floored to 10 ms, hence elapsedResolutionNs=10000000. Use
multiple probes before and after recording. Actual nanosecond elapsedRealtime
probes can set resolution 1. deviceUnixNs is optional when elapsed is supplied.

Preferred mapping: host Unix = Winscope elapsed + the intersected host/elapsed
anchor offset. This avoids relying on the device's wall clock. If only device
date anchors are supplied, the tool reports the less robust wall-clock mapping.
Without anchors it reports DEVICE Unix only, never silently calls it host time.

The interval assumes a constant clock offset over the measured span. Disjoint
anchor intervals fail instead of averaging away a clock step. Keep the reported
uncertainty; the rounded renderer argument is an estimate, not sub-frame proof.
"""

import argparse
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import mmap
from pathlib import Path
import struct
import subprocess


MAGIC = b"#VV1NSC0PET1ME2#"
SOURCE = "https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/cmds/screenrecord/screenrecord.cpp"


def integer(value, name):
    if type(value) is int:
        return value
    if isinstance(value, str) and value.lstrip("-").isdigit():
        return int(value)
    raise ValueError(f"{name} must be an integer or integer string, not a float")


def parse_winscope(data):
    position = data.find(MAGIC)
    if position < 0 or data.find(MAGIC, position + len(MAGIC)) >= 0:
        raise ValueError("expected exactly one original Winscope v2 magic; no guessed start-time fallback")
    header = position + len(MAGIC)
    if len(data) < header + 16:
        raise ValueError("truncated Winscope header")
    version, offset, count = struct.unpack_from("<IqI", data, header)
    if version != 2 or not 1 <= count <= 2_000_000:
        raise ValueError("unsupported Winscope version or invalid frame count")
    start = header + 16
    if len(data) < start + count * 8:
        raise ValueError("truncated Winscope frame array")
    timestamps = [struct.unpack_from("<Q", data, start + index * 8)[0] for index in range(count)]
    if any(b <= a for a, b in zip(timestamps, timestamps[1:])):
        raise ValueError("Winscope timestamps are not strictly increasing; unsupported/reordered source")
    return {"magicFileByteOffset": position, "version": version,
            "realtimeMinusElapsedNs": offset, "frameCount": count, "elapsedNs": timestamps}


def check_video_pts(metadata, probe, tolerance_ns):
    frames = probe.get("frames", [])
    if len(frames) != metadata["frameCount"]:
        raise ValueError(f"decoded frame count {len(frames)} != Winscope count {metadata['frameCount']}")
    values = []
    for frame in frames:
        timestamp = frame.get("best_effort_timestamp_time", frame.get("pts_time"))
        if timestamp is None:
            raise ValueError("decoded frame is missing a presentation timestamp")
        value = Decimal(str(timestamp)) * 1_000_000_000
        if not value.is_finite():
            raise ValueError("non-finite video timestamp")
        values.append(int(value))
    if any(b <= a for a, b in zip(values, values[1:])):
        raise ValueError("decoded video timestamps are not strictly increasing")
    first_elapsed = metadata["elapsedNs"][0]
    error = max(abs((elapsed - first_elapsed) - (pts - values[0]))
                for elapsed, pts in zip(metadata["elapsedNs"], values))
    if error > tolerance_ns:
        raise ValueError(f"Winscope/video PTS disagreement {error} ns exceeds {tolerance_ns} ns")
    return values, error


def calibrate(anchors, metadata):
    if not isinstance(anchors, dict) or anchors.get("schema") != "taku.clock-anchors/1":
        raise ValueError("unsupported clock-anchor schema")
    samples = anchors.get("samples")
    if not isinstance(samples, list) or not samples:
        raise ValueError("provide at least one bracketed device clock sample")
    mode = "elapsed" if all("deviceElapsedRealtimeNs" in sample for sample in samples) else "deviceUnix"
    bounds, mids, warnings = [], [], []
    for sample in samples:
        before = integer(sample["hostBeforeUnixNs"], "hostBeforeUnixNs")
        after = integer(sample["hostAfterUnixNs"], "hostAfterUnixNs")
        if before <= 0 or after < before:
            raise ValueError("host clock bracket is reversed or invalid")
        mids.append((before + after) // 2)
        if mode == "elapsed":
            elapsed = integer(sample["deviceElapsedRealtimeNs"], "deviceElapsedRealtimeNs")
            resolution = integer(sample.get("elapsedResolutionNs", 10_000_000), "elapsedResolutionNs")
            if elapsed < 0 or not 1 <= resolution <= 1_000_000_000:
                raise ValueError("invalid elapsed value or resolution")
            # A floored elapsed reading represents [elapsed, elapsed + resolution).
            low, high = before - elapsed - resolution, after - elapsed
            if "deviceUnixNs" in sample:
                device_unix = integer(sample["deviceUnixNs"], "deviceUnixNs")
                date_resolution = integer(sample.get("deviceDateResolutionNs", 1), "deviceDateResolutionNs")
                # date and uptime may be separate commands within the host bracket.
                difference = device_unix - elapsed - metadata["realtimeMinusElapsedNs"]
                if abs(difference) > (after - before) + resolution + date_resolution:
                    warnings.append("Device date/elapsed anchor disagrees with mux-time realtime offset; direct elapsed mapping avoids depending on that wall clock.")
        else:
            if "deviceUnixNs" not in sample:
                raise ValueError("all samples need elapsed values, or all need deviceUnixNs")
            device_unix = integer(sample["deviceUnixNs"], "deviceUnixNs")
            resolution = integer(sample.get("deviceDateResolutionNs", 1), "deviceDateResolutionNs")
            if not 1 <= resolution <= 1_000_000_000:
                raise ValueError("invalid device date resolution")
            low, high = before - device_unix - resolution, after - device_unix
        bounds.append((low, high))
    lower, upper = max(low for low, _ in bounds), min(high for _, high in bounds)
    if lower > upper:
        raise ValueError("clock-anchor offset intervals do not intersect; clock drift/step or wrong clock domain: refuse a guessed alignment")
    midpoint = (lower + upper) // 2
    base_first = metadata["elapsedNs"][0]
    base_last = metadata["elapsedNs"][-1]
    if mode == "deviceUnix":
        base_first += metadata["realtimeMinusElapsedNs"]
        base_last += metadata["realtimeMinusElapsedNs"]
        warnings.append("Wall-clock mapping requires the device realtime/elapsed relation to remain stable throughout recording; prefer elapsed anchors.")
    first, last = base_first + midpoint, base_last + midpoint
    bracketed = min(mids) <= first and max(mids) >= last
    if not bracketed:
        warnings.append("Clock probes do not bracket the full verified video span; long-span constant-offset stability is not established.")
    return {"method": "host-minus-device-" + mode + "-bracket-intersection",
            "anchorCount": len(samples), "offsetLowerNs": lower, "offsetUpperNs": upper,
            "offsetEstimateNs": midpoint, "uncertaintyHalfWidthNs": (upper - lower + 1) // 2,
            "firstFrameHostUnixNs": first, "firstFrameHostUnixLowerNs": base_first + lower,
            "firstFrameHostUnixUpperNs": base_first + upper, "lastVerifiedFrameHostUnixNs": last,
            "probesBracketVerifiedFrames": bracketed, "warnings": sorted(set(warnings))}


def iso_ns(timestamp):
    return datetime.fromtimestamp(timestamp // 1_000_000_000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S") + f".{timestamp % 1_000_000_000:09d}Z"


def audit(video, anchors=None, background_t0_ms=None, ffprobe="ffprobe", tolerance_ns=1_000_000):
    video = Path(video).resolve()
    with video.open("rb") as stream:
        if video.stat().st_size == 0:
            raise ValueError("empty video")
        with mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
            metadata = parse_winscope(data)
    command = [ffprobe, "-v", "error", "-select_streams", "v:0", "-show_frames", "-show_entries",
               "frame=best_effort_timestamp_time,pts_time:stream=time_base,start_time,duration:format=duration:format_tags=creation_time",
               "-of", "json", str(video)]
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    probe = json.loads(result.stdout)
    values, error = check_video_pts(metadata, probe, tolerance_ns)
    offset = metadata["realtimeMinusElapsedNs"]
    first, last = metadata["elapsedNs"][0], metadata["elapsedNs"][-1]
    hasher = hashlib.sha256()
    with video.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    duration = Decimal(str(probe.get("format", {}).get("duration", "0")))
    verified_span = Decimal(last - first) / 1_000_000_000
    output = {"schema": "taku.screenrecord-timing-audit/1", "video": str(video), "sha256": hasher.hexdigest(),
              "officialSource": SOURCE, "winscopeMagicFileByteOffset": metadata["magicFileByteOffset"],
              "winscopeVersion": metadata["version"], "realtimeMinusElapsedNs": offset,
              "frameCount": metadata["frameCount"], "firstFrameElapsedNs": first, "lastFrameElapsedNs": last,
              "firstFrameDeviceUnixNs": first + offset, "lastFrameDeviceUnixNs": last + offset,
              "firstFrameDeviceUTC": iso_ns(first + offset), "lastFrameDeviceUTC": iso_ns(last + offset),
              "firstVideoPtsNs": values[0], "lastVideoPtsNs": values[-1],
              "verifiedVideoPtsNs": values,
              "verifiedSpanSeconds": float(verified_span), "containerDurationSeconds": float(duration),
              "containerTailAfterLastVerifiedFrameSeconds": float(duration - verified_span),
              "maxWinscopeVsVideoPtsErrorNs": error, "maxAcceptedPtsErrorNs": tolerance_ns,
              "containerCreationTimeNotUsed": probe.get("format", {}).get("tags", {}).get("creation_time"),
              "coveragePolicy": "Use first through last verified frame; do not extend final frame across an inferred container tail. Sparse PTS between verified frames may represent a static screen, not a recording gap.",
              "hostCalibration": None, "rendererMainStartUnixMs": None,
              "sourceModified": False}
    if anchors is not None:
        mapping = calibrate(anchors, metadata)
        output["hostCalibration"] = mapping
        output["rendererMainStartUnixMs"] = (mapping["firstFrameHostUnixNs"] + 500_000) // 1_000_000
        if background_t0_ms is not None:
            t0_ns = integer(background_t0_ms, "background start Unix milliseconds") * 1_000_000
            output["firstFrameRelativeToBackgroundSeconds"] = (mapping["firstFrameHostUnixNs"] - t0_ns) / 1e9
            output["lastVerifiedFrameRelativeToBackgroundSeconds"] = (mapping["lastVerifiedFrameHostUnixNs"] - t0_ns) / 1e9
    else:
        output["warning"] = "No host/device clock calibration supplied; DEVICE Unix time must not be assumed equal to host time."
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--anchors", type=Path)
    parser.add_argument("--recording", type=Path, help="recording directory or recording.json for relative host time")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--max-pts-error-ms", type=Decimal, default=Decimal("1"))
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists; timing evidence is never overwritten")
    if not Decimal("0") < args.max_pts_error_ms <= Decimal("10"):
        parser.error("max PTS error must be >0 and <=10 ms")
    anchors = json.loads(args.anchors.read_text()) if args.anchors else None
    t0 = None
    if args.recording:
        path = args.recording / "recording.json" if args.recording.is_dir() else args.recording
        t0 = json.loads(path.read_text())["startedAtUnixMs"]
    report = audit(args.video, anchors, t0, args.ffprobe, int(args.max_pts_error_ms * 1_000_000))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"output": str(args.output), "frameCount": report["frameCount"],
                      "rendererMainStartUnixMs": report["rendererMainStartUnixMs"],
                      "hostCalibration": report["hostCalibration"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
