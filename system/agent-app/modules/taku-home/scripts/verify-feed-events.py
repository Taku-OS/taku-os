#!/usr/bin/env python3
"""Exercise feed publication, durable replay and live delivery on an isolated AVD."""
import argparse
import json
import os
from pathlib import Path
import re
import selectors
import shlex
import subprocess
import threading
import time
from urllib.parse import urlencode
import uuid
import xml.etree.ElementTree as ET

AUTHORITY = "content://org.agentos.agent.home-feed"
PACKAGE = "org.agentos.agent"


class ProviderError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


class Device:
    def __init__(self, adb, serial):
        self.command = [adb, "-s", serial]

    def run(self, *args, timeout=45):
        return subprocess.run(self.command + list(args), capture_output=True, check=True,
                              timeout=timeout).stdout

    def call(self, method, payload=None):
        program = ('IFS= read -r feed_request || exit 1\nexec content call --uri ' + AUTHORITY
                   + ' --method ' + shlex.quote(method) + ' --arg "$feed_request"')
        reply = subprocess.run(self.command + ["shell", "-T", program],
                               input=(json.dumps(payload or {}, ensure_ascii=False) + "\n").encode(),
                               capture_output=True, check=True, timeout=120).stdout.decode()
        match = re.search(r"Result: Bundle\[\{result=(.*)\}\]", reply)
        if not match:
            raise AssertionError(f"{method} did not return a JSON receipt: {reply[:300]}")
        envelope = json.loads(match.group(1))
        if envelope.get("ok") is not True:
            raise ProviderError(envelope.get("code"))
        return envelope["result"]

    def stream(self, after="0", follow=False, **filters):
        parameters = {"after": after, "follow": "1" if follow else "0", **filters}
        uri = AUTHORITY + "/events?" + urlencode(parameters, doseq=True)
        program = 'IFS= read -r feed_uri || exit 1\nexec content read --uri "$feed_uri"'
        child = subprocess.Popen(self.command + ["shell", "-T", program], stdin=subprocess.PIPE,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        child.stdin.write((uri + "\n").encode())
        child.stdin.close()
        return EventStream(child, follow=follow)

    def replay(self, after="0", **filters):
        frames = []
        with self.stream(after, **filters) as stream:
            deadline = time.monotonic() + 45
            try:
                while True:
                    frames.append(stream.next(max(0.01, deadline - time.monotonic())))
            except EOFError:
                pass
            stream.require_clean_exit()
        assert frames and frames[0]["type"] == "ready", "Replay must start with ready"
        assert frames[0]["follow"] is False, "Finite replay must not follow"
        assert frames[-1]["type"] == "checkpoint", "Replay ended before its checkpoint"
        return frames

    def tap_text(self, label):
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            self.run("shell", "uiautomator", "dump", "/sdcard/feed-delivery-ui.xml", timeout=15)
            tree = ET.fromstring(self.run("shell", "cat", "/sdcard/feed-delivery-ui.xml"))
            for node in tree.iter("node"):
                if label not in (node.get("text", ""), node.get("content-desc", "")):
                    continue
                points = [int(value) for value in re.findall(r"\d+", node.get("bounds", ""))]
                if len(points) == 4 and points[2] > points[0] and points[3] > points[1]:
                    self.run("shell", "input", "tap", str((points[0] + points[2]) // 2),
                             str((points[1] + points[3]) // 2))
                    return
        raise AssertionError(f"The actual feed did not show {label!r}")


class EventStream:
    def __init__(self, child, follow=False):
        self.child = child
        self.follow = follow
        self.selector = selectors.DefaultSelector()
        self.selector.register(child.stdout, selectors.EVENT_READ)
        self.buffer = bytearray()
        self.stderr_prefix = bytearray()
        self.stderr_truncated = False
        self.stderr_reader = threading.Thread(target=self.drain_stderr, daemon=True)
        self.stderr_reader.start()

    def drain_stderr(self):
        while data := self.child.stderr.read(4096):
            remaining = 8192 - len(self.stderr_prefix)
            self.stderr_prefix.extend(data[:remaining])
            self.stderr_truncated |= len(data) > remaining

    def require_clean_exit(self):
        self.child.wait(timeout=5)
        self.stderr_reader.join(timeout=5)
        assert not self.stderr_reader.is_alive(), "Transport diagnostics did not close"
        startup = re.compile(r"^\* daemon (?:not running; starting now at tcp:\d+|started successfully)(?: \*)?$")
        diagnostics = [line for line in self.stderr_prefix.decode(errors="replace").splitlines()
                       if line.strip() and not startup.fullmatch(line)]
        assert self.child.returncode == 0, f"Feed reader exited {self.child.returncode}"
        assert not diagnostics and not self.stderr_truncated, "Feed reader reported transport diagnostics"
        assert not self.follow, "Follow stream ended; treat EOF as a disconnect"

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def next(self, timeout=25):
        deadline = time.monotonic() + timeout
        while True:
            newline = self.buffer.find(b"\n")
            if newline >= 0:
                line = bytes(self.buffer[:newline])
                del self.buffer[:newline + 1]
                return json.loads(line)
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not self.selector.select(remaining):
                raise TimeoutError("No complete feed event frame arrived")
            data = os.read(self.child.stdout.fileno(), 65536)
            if not data:
                self.child.wait(timeout=5)
                assert not self.buffer, "Stream ended inside a JSON frame"
                raise EOFError
            self.buffer.extend(data)
            assert len(self.buffer) <= 2 * 1024 * 1024, "Verification frame budget exceeded"

    def close(self):
        self.selector.close()
        if self.child.poll() is None:
            self.child.terminate()
            try:
                self.child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.child.kill()
                self.child.wait(timeout=5)
        self.stderr_reader.join(timeout=5)
        for pipe in (self.child.stdout, self.child.stderr):
            pipe.close()


def card(card_id, rank=1_000_000, counter=0):
    return {
        "schema": "taku.feed-card/1", "id": card_id,
        "source": {"id": "agent", "app": "Feed acceptance"},
        "summary": f"Stored acceptance card {counter}", "rank": rank,
        "pin": {"label": "Feed acceptance", "icon": {"kind": "pixel", "glyph": "chat"}},
        "ui": {
            "catalog": "taku-feed-1", "state": {"ui": {"choice": "first"}},
            "spec": {"root": "body", "elements": {
                "body": {"type": "Column", "props": {"gap": 12},
                         "children": ["label", "choice", "confirm"]},
                "label": {"type": "Text", "props": {"text": "Feed acceptance"}, "children": []},
                "choice": {"type": "Segmented", "props": {
                    "options": [{"id": "first", "label": "First"},
                                {"id": "second", "label": "Second"}],
                    "value": {"$bindState": "/ui/choice"}}, "children": []},
                "confirm": {"type": "Button", "props": {
                    "label": "Confirm feed choice", "variant": "primary"}, "children": [],
                    "on": {"press": {"action": "dispatch", "params": {
                        "actionId": "confirm", "select": ["/ui/choice"]}}}},
            }},
        },
        "actions": {"confirm": {"label": "Confirm", "intent": {"type": "none"}}},
    }


def events(frames):
    return [frame for frame in frames if frame["type"] == "event"]


def require_event(stream, cursor):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        frame = stream.next(deadline - time.monotonic())
        if frame["type"] == "event" and frame["cursor"] == cursor:
            return frame
    raise AssertionError(f"Live delivery missed {cursor}")


def verify_gui(device, prefix, args, record):
    cli = Path(__file__).with_name("read-feed-events.mjs")
    device.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    device.run("shell", "wm", "dismiss-keyguard")
    gui_id = prefix + ".gui"
    device.call("feed/publish", {"card": card(gui_id, rank=9_000_000_000_000 + time.time())})
    gui_after = device.call("feed/list")["eventCursor"]
    gui_command = ["node", str(cli), "--serial", args.serial, "--adb", args.adb,
                   "--after", gui_after, "--follow", "--card-id", gui_id,
                   "--origin", "gui", "--op", "action"]
    child = subprocess.Popen(gui_command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    with EventStream(child) as stream:
        assert stream.next()["type"] == "ready"
        device.run("shell", "am", "start", "-W", "-n", PACKAGE + "/.home.HomeActivity")
        device.run("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(1)
        width, height = map(int, re.search(r"(\d+)x(\d+)", device.run("shell", "wm", "size").decode()).groups())
        device.run("shell", "input", "swipe", str(width // 5), str(height // 2),
                   str(width * 4 // 5), str(height // 2), "400")
        device.tap_text("Second")
        device.tap_text("Confirm feed choice")
        deadline = time.monotonic() + 30
        while True:
            frame = stream.next(max(0.01, deadline - time.monotonic()))
            if frame["type"] == "event":
                break
        assert frame["origin"] == "gui" and frame["cardId"] == gui_id
        assert frame["actionId"] == "confirm" and frame["values"]["/ui/choice"] == "second"
        (args.out / "gui-event.json").write_text(json.dumps(frame, ensure_ascii=False, indent=2))
        (args.out / "gui-choice.png").write_bytes(device.run("exec-out", "screencap", "-p"))
    record("actual-gui-choice", cardId=gui_id, cursor=frame["cursor"], values=frame["values"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--count", type=int, default=105)
    parser.add_argument("--gui", action="store_true")
    parser.add_argument("--gui-only", action="store_true", help="Repeat GUI checks without repeating capacity/replay checks")
    parser.add_argument("--legacy-id")
    parser.add_argument("--legacy-large-id")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use the explicit serial of an isolated test AVD")
    if args.count <= 100:
        parser.error("--count must cross the former 100-card limit")
    args.out.mkdir(parents=True, exist_ok=True)
    device = Device(args.adb, args.serial)
    prefix = "feed-proof-" + uuid.uuid4().hex[:10]
    report = {"serial": args.serial, "prefix": prefix, "checks": []}

    def record(name, **evidence):
        report["checks"].append({"check": name, **evidence})
        (args.out / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps({"passed": name, **evidence}, ensure_ascii=False), flush=True)

    if args.gui_only:
        verify_gui(device, prefix, args, record)
        return

    legacy_before = None
    if args.legacy_id:
        legacy_before = device.call("feed/get", {"id": args.legacy_id})
        assert legacy_before["pinned"] is True, "Upgrade lost the legacy pin"
        legacy_events = events(device.replay(cardId=args.legacy_id, op="action"))
        assert len(legacy_events) == 1, "Legacy action import was lost or duplicated"
        assert legacy_events[0]["phase"] == "legacy-recorded"
        assert legacy_events[0]["values"]["/ui/choice"] == "legacy-preserved"
        record("legacy-upgrade", cursor=legacy_events[0]["cursor"], cardId=args.legacy_id)

    if args.legacy_large_id:
        legacy_large = events(device.replay(cardId=args.legacy_large_id, op="action"))
        assert len(legacy_large) == 1
        assert legacy_large[0]["phase"] == "legacy-recorded"
        assert legacy_large[0]["values"]["/ui/legacyLarge"] == "x" * 90000
        assert legacy_large[0]["legacy"]["detail"]["values"]["/ui/legacyLarge"] == "x" * 90000
        record("large-legacy-upgrade", valueBytes=90000, cursor=legacy_large[0]["cursor"])

    published = []
    for index in range(args.count):
        card_id = f"{prefix}.{index}"
        result = device.call("feed/publish", {"card": card(card_id, counter=index)})
        assert result["id"] == card_id and result["replaced"] is False
        published.append(card_id)
        if index == 0:
            assert device.call("feed/pin", {"id": card_id})["pinned"] is True
    for index, card_id in enumerate(published):
        saved = device.call("feed/get", {"id": card_id})
        assert saved["card"]["summary"] == f"Stored acceptance card {index}"
        if index == 0:
            assert saved["pinned"] is True
    record("uncapped-publish", retainedIds=published)

    # The target branch already supports unlimited pins and visible-row ordering.
    for card_id in published[:15]:
        device.call("feed/pin", {"id": card_id})
    pins = [row["id"] for row in device.call("feed/list")["pins"]]
    assert set(published[:15]).issubset(pins)
    order = list(reversed(pins))
    device.call("feed/reorder-pins", {"ids": order})
    assert [row["id"] for row in device.call("feed/list")["pins"]] == order
    record("unlimited-pins-and-visible-reorder", count=len(order))
    for card_id in published[1:15]:
        device.call("feed/unpin", {"id": card_id})

    generated = []
    for index in range(args.count):
        result = device.call("feed/generate", {"card": card(f"{prefix}.input", counter=index)})
        assert result["replaced"] is False
        generated.append(result["id"])
    assert len(set(generated)) == args.count, "Generation reused a card ID"
    for index, card_id in enumerate(generated):
        saved = device.call("feed/get", {"id": card_id})
        assert saved["card"]["summary"] == f"Stored acceptance card {index}"
    assert device.call("feed/get", {"id": published[0]})["pinned"] is True
    record("uncapped-generate", retainedIds=generated)

    probe = published[-1]
    start = device.call("feed/list")["eventCursor"]
    receipts = []
    for index in range(75):
        values = {"/ui/counter": index}
        if index in (1, 60):
            values["/ui/unicode"] = "汉" * 15000
        receipt = device.call("feed/action", {"id": probe, "actionId": "confirm", "values": values})
        receipts.append(receipt["eventCursor"])
    replay = events(device.replay(start, cardId=probe, origin="agent", op="action"))
    assert [row["cursor"] for row in replay] == receipts, "Replay lost, duplicated or reordered actions"
    assert [row["values"]["/ui/counter"] for row in replay] == list(range(75))
    assert all(row["phase"] == "accepted" and row["actionId"] == "confirm" for row in replay)
    assert replay[1]["values"]["/ui/unicode"] == "汉" * 15000
    (args.out / "actions.ndjson").write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in replay) + "\n")
    record("durable-actions-over-50", count=len(replay), start=start, last=receipts[-1])

    with device.stream(receipts[-1], follow=True, cardId=probe, origin="agent", op="action") as live:
        assert live.next()["type"] == "ready"
        cursor = device.call("feed/action", {"id": probe, "actionId": "confirm",
                             "values": {"/ui/choice": "live"}})["eventCursor"]
        assert require_event(live, cursor)["values"]["/ui/choice"] == "live"
    missed = device.call("feed/action", {"id": probe, "actionId": "confirm",
                         "values": {"/ui/choice": "offline"}})["eventCursor"]
    resumed = events(device.replay(cursor, cardId=probe, origin="agent", op="action"))
    assert [row["cursor"] for row in resumed] == [missed]
    assert resumed[0]["values"]["/ui/choice"] == "offline"
    record("live-and-offline-replay", live=cursor, resumed=missed)

    empty = device.replay("0", cardId=prefix + ".absent", op="action")
    assert not events(empty)
    empty_head = empty[-1]["through"]
    receipt = device.call("feed/action", {"id": probe, "actionId": "confirm",
                          "values": {"/ui/choice": "after-empty-window"}})
    assert events(device.replay(empty_head, cardId=probe, op="action"))[0]["cursor"] == receipt["eventCursor"]
    record("filtered-checkpoint-resume", checkpoint=empty_head)

    literal_id, other_id = prefix + ".a_b", prefix + ".axb"
    for card_id in (literal_id, other_id):
        device.call("feed/publish", {"card": card(card_id)})
        device.call("feed/action", {"id": card_id, "actionId": "confirm", "values": {}})
    filtered = events(device.replay(cardIdPrefix=literal_id, op="action"))
    assert [row["cardId"] for row in filtered] == [literal_id]
    both = events(device.replay(cardId=[literal_id, other_id], op="action"))
    assert [row["cardId"] for row in both] == [literal_id, other_id]
    record("literal-prefix-and-multiple-ids", matched=literal_id, excluded=other_id)

    unchanged = device.call("feed/list")["eventCursor"]
    try:
        device.call("feed/action", {"id": probe, "actionId": "confirm",
                    "values": {"/ui/oversize": "x" * 70000}})
    except ProviderError as error:
        assert error.code == "EVENT_TOO_LARGE"
    else:
        raise AssertionError("Oversized values were accepted")
    assert not events(device.replay(unchanged, cardId=probe, op="action"))
    record("oversize-choice-rejected-before-acceptance")

    failed_id = prefix + ".missing-app"
    missing_app = card(failed_id)
    missing_app["actions"]["confirm"]["intent"] = {
        "type": "open-app", "package": "org.example.feed.proof.missing"}
    device.call("feed/publish", {"card": missing_app})
    before_effect = device.call("feed/list")["eventCursor"]
    try:
        device.call("feed/action", {"id": failed_id, "actionId": "confirm",
                    "values": {"/ui/choice": "stored-despite-local-failure"}})
    except ProviderError as error:
        assert error.code == "APP_NOT_INSTALLED"
    else:
        raise AssertionError("A missing app was reported as launched")
    accepted = events(device.replay(before_effect, cardId=failed_id, op="action"))
    assert len(accepted) == 1 and accepted[0]["phase"] == "accepted"
    assert accepted[0]["intentType"] == "open-app"
    assert accepted[0]["values"]["/ui/choice"] == "stored-despite-local-failure"
    record("accepted-choice-survives-local-effect-failure", cursor=accepted[0]["cursor"])

    cli = Path(__file__).with_name("read-feed-events.mjs")
    command = ["node", str(cli), "--serial", args.serial, "--adb", args.adb,
               "--after", start, "--card-id", probe, "--origin", "agent", "--op", "action"]
    cli_frames = [json.loads(line) for line in subprocess.run(command, capture_output=True,
                  check=True, timeout=45).stdout.decode().splitlines()]
    assert [row["cursor"] for row in events(cli_frames)][:75] == receipts
    record("actual-host-cli", eventCount=len(events(cli_frames)))

    if args.legacy_large_id:
        large_command = ["node", str(cli), "--serial", args.serial, "--adb", args.adb,
                         "--card-id", args.legacy_large_id, "--op", "action"]
        large_frames = [json.loads(line) for line in subprocess.run(large_command,
                        capture_output=True, check=True, timeout=45).stdout.decode().splitlines()]
        large_rows = events(large_frames)
        assert len(large_rows) == 1
        assert large_rows[0]["values"]["/ui/legacyLarge"] == "x" * 90000
        record("actual-host-cli-large-legacy", valueBytes=90000)

    boundaries = []
    for index in range(8):
        before = device.call("feed/list")["eventCursor"]
        with device.stream(before, cardId=probe, origin="agent", op="action") as finite:
            header = finite.next()
            assert header["type"] == "ready"
            committed = device.call("feed/action", {"id": probe, "actionId": "confirm",
                                    "values": {"/ui/window": index}})["eventCursor"]
            window = []
            try:
                while True:
                    window.append(finite.next())
            except EOFError:
                pass
            finite.require_clean_exit()
            assert window[-1]["type"] == "checkpoint"
            assert window[-1]["through"] == header["through"]
            assert committed not in [row["cursor"] for row in events(window)]
        caught_up = events(device.replay(header["through"], cardId=probe, op="action"))
        assert caught_up[0]["cursor"] == committed
        boundaries.append({"checkpoint": header["through"], "recovered": committed})
    record("fixed-high-water-replay", windows=boundaries)

    device.run("shell", "am", "force-stop", PACKAGE)
    after_restart = events(device.replay(start, cardId=probe, origin="agent", op="action"))
    assert [row["cursor"] for row in after_restart][:75] == receipts
    for card_id in published + generated:
        assert device.call("feed/get", {"id": card_id})["card"]["id"] == card_id
    assert device.call("feed/get", {"id": published[0]})["pinned"] is True
    if legacy_before:
        assert device.call("feed/get", {"id": args.legacy_id}) == legacy_before
        assert len(events(device.replay(cardId=args.legacy_id, op="action"))) == 1
    record("process-restart", retainedCards=len(published) + len(generated), actionCount=len(after_restart))

    if args.gui:
        verify_gui(device, prefix, args, record)
    print(json.dumps({"ok": True, "artifact": str(args.out / "result.json")}), flush=True)


if __name__ == "__main__":
    main()
