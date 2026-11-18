#!/usr/bin/env python3
"""Record already forwarded Taku execution screens on one host timeline.

Read-only: sends only status/frame requests to loopback mirror sockets. It never
launches apps, sends input, creates adb forwards, or replaces existing evidence.
Each lane polls independently; capture overlap is not proof of Agent execution.
"""

import argparse
import hashlib
import json
import re
import signal
import socket
import struct
import threading
import time
from pathlib import Path


def validate_bindings(value):
    if not isinstance(value, dict):
        raise ValueError("bindings must be a JSON object")
    screens = value.get("screens", [])
    if not isinstance(value.get("deviceSerial"), str) or not value["deviceSerial"]:
        raise ValueError("deviceSerial is required for recording provenance")
    if not isinstance(screens, list) or not 1 <= len(screens) <= 3:
        raise ValueError("provide one to three bound background screens")
    for s in screens:
        if not isinstance(s, dict) or any(k not in s for k in ("screenKey", "port", "displayId", "generation")):
            raise ValueError("each screen must contain its key, port, displayId and generation")
        if not isinstance(s["screenKey"], str) or not re.fullmatch(r"[a-z][a-z0-9-]{0,31}", s["screenKey"]):
            raise ValueError("invalid screenKey")
        if type(s["port"]) is not int or not 1 <= s["port"] <= 65535:
            raise ValueError("invalid forwarded loopback port")
        if type(s["displayId"]) is not int or s["displayId"] <= 0:
            raise ValueError("display 0 is not a background execution screen")
        if not isinstance(s["generation"], str) or not s["generation"]:
            raise ValueError("a host generation is required")
    for field in ("screenKey", "port", "displayId", "generation"):
        values = [s[field] for s in screens]
        if len(set(values)) != len(values):
            raise ValueError(f"{field} must be unique per screen")
    return screens


def read_exact(sock, size):
    parts = []
    while size:
        chunk = sock.recv(size)
        if not chunk:
            raise ConnectionError("mirror disconnected")
        parts.append(chunk)
        size -= len(chunk)
    return b"".join(parts)


def mirror_read(sock, command):
    if command not in (b"status\n", b"frame\n"):
        raise ValueError("recorder permits only read commands")
    sock.sendall(command)
    size = struct.unpack(">I", read_exact(sock, 4))[0]
    if size > 4_000_000:
        raise ValueError("oversized mirror response")
    return read_exact(sock, size)


def check_identity(status, binding):
    if not isinstance(status, dict):
        raise ValueError("mirror status must be a JSON object")
    for field in ("screenKey", "displayId", "generation"):
        if status.get(field) != binding[field]:
            raise ValueError(f"{field} changed or does not match the bound screen")


def record(bindings, output, duration=600, fps=5, stop=None):
    screens = validate_bindings(bindings)
    if not 0 < duration <= 1200 or not 0 < fps <= 5:
        raise ValueError("duration must be (0,1200] seconds and fps (0,5]")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    stop = stop or threading.Event()
    start = threading.Event()
    ready = {s["screenKey"]: threading.Event() for s in screens}
    outcomes = {}
    clock = {}

    def lane(binding):
        key = binding["screenKey"]
        folder = output / key
        (folder / "frames").mkdir(parents=True)
        result = {"screenKey": key, "polls": 0, "framesSaved": 0, "exitReason": "duration"}
        outcomes[key] = result
        try:
            with socket.create_connection(("127.0.0.1", binding["port"]), timeout=3) as sock:
                sock.settimeout(3)
                status = json.loads(mirror_read(sock, b"status\n"))
                check_identity(status, binding)
                result["initialStatus"] = status
                ready[key].set()
                start.wait()
                if stop.is_set():
                    result["exitReason"] = "preflight_cancelled"
                    return
                next_poll = clock["monotonic"]
                with (folder / "frames.jsonl").open("x") as log:
                    while not stop.is_set() and time.monotonic() < clock["monotonic"] + duration:
                        before_ns = time.time_ns()
                        before_mono = time.monotonic()
                        before = json.loads(mirror_read(sock, b"status\n"))
                        jpeg = mirror_read(sock, b"frame\n")
                        after = json.loads(mirror_read(sock, b"status\n"))
                        check_identity(before, binding)
                        check_identity(after, binding)
                        result["polls"] += 1
                        row = {
                            "sample": result["polls"],
                            "relativeReadStartSeconds": before_mono - clock["monotonic"],
                            "relativeReadEndSeconds": time.monotonic() - clock["monotonic"],
                            "hostReadStartUnixMs": before_ns // 1_000_000,
                            "hostReadEndUnixMs": time.time_ns() // 1_000_000,
                            "statusBefore": before, "statusAfter": after,
                            "stableFrameMetadata": all(k in before and k in after and before[k] == after[k] for k in ("frameAt", "frameSequence")),
                        }
                        if jpeg:
                            if not jpeg.startswith(b"\xff\xd8") or not jpeg.endswith(b"\xff\xd9"):
                                raise ValueError("mirror response is not a complete JPEG")
                            filename = f"frames/{result['framesSaved']:06d}.jpg"
                            (folder / filename).write_bytes(jpeg)
                            row.update(file=filename, bytes=len(jpeg), sha256=hashlib.sha256(jpeg).hexdigest())
                            result["framesSaved"] += 1
                        else:
                            row["emptyFrame"] = True
                        log.write(json.dumps(row, ensure_ascii=False) + "\n")
                        log.flush()
                        result.setdefault("firstReadSeconds", row["relativeReadStartSeconds"])
                        result["lastReadSeconds"] = row["relativeReadEndSeconds"]
                        next_poll = max(next_poll + 1 / fps, time.monotonic())
                        stop.wait(max(0, min(next_poll, clock["monotonic"] + duration) - time.monotonic()))
                if stop.is_set():
                    result["exitReason"] = "stopped"
        except (OSError, ValueError, KeyError) as error:
            result.update(exitReason="capture_error", error=f"{type(error).__name__}: {error}")
            stop.set()
        finally:
            ready[key].set()

    workers = [threading.Thread(target=lane, args=(s,), name=f"record-{s['screenKey']}") for s in screens]
    for worker in workers:
        worker.start()
    for event in ready.values():
        if not event.wait(5):
            stop.set()
    clock.update(monotonic=time.monotonic(), unixMs=time.time_ns() // 1_000_000)
    start.set()
    for worker in workers:
        worker.join()
    complete = all(r.get("exitReason") == "duration" and r["framesSaved"] > 0 for r in outcomes.values())
    sampled = [r for r in outcomes.values() if "firstReadSeconds" in r]
    common = None
    if len(sampled) == len(screens):
        begin = max(r["firstReadSeconds"] for r in sampled)
        end = min(r["lastReadSeconds"] for r in sampled)
        common = {"startSeconds": begin, "endSeconds": end, "durationSeconds": max(0, end - begin)}
    summary = {
        "schema": "taku.multi-screen-recording/1", "deviceSerial": bindings["deviceSerial"],
        "startedAtUnixMs": clock["unixMs"], "endedAtUnixMs": time.time_ns() // 1_000_000,
        "requestedDurationSeconds": duration, "pollFpsPerScreen": fps,
        "bindings": screens, "screens": outcomes, "captureComplete": complete,
        "commonCaptureWindow": common,
        "timestampNote": "Shared host receipt timeline; device frame metadata is bracketed, not atomic. No host/device clock calibration is implied.",
        "interpretation": "Overlapping captures alone do not prove overlapping Agent work. Keep actual per-thread tool/turn events and distinct App/display evidence separately.",
        "privacy": "Private real-device footage; no upload or publication performed.",
    }
    (output / "recording.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bindings", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--duration", type=float, default=600)
    parser.add_argument("--fps", type=float, default=5)
    args = parser.parse_args()
    stop = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stop.set())
    result = record(json.loads(args.bindings.read_text()), args.output, args.duration, args.fps, stop)
    print(json.dumps({"captureComplete": result["captureComplete"], "output": str(args.output)}, ensure_ascii=False))
    return 0 if result["captureComplete"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
