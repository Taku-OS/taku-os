#!/usr/bin/env python3
"""Exercise installed surface fixtures on an explicitly selected test device."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
parser.add_argument("--adb", default="adb")
parser.add_argument("--out", type=Path, required=True)
parser.add_argument("--foreground", default="com.android.deskclock/.DeskClock")
parser.add_argument("--position", action="store_true", help="Also change/reset layout preferences on a disposable emulator")
args = parser.parse_args()
if args.position and not re.fullmatch(r"emulator-\d+", args.serial):
    parser.error("--position requires an explicitly selected disposable emulator")
args.out.mkdir(parents=True, exist_ok=True)
prefix = [args.adb, "-s", args.serial]


def adb(*arguments):
    return subprocess.check_output(prefix + list(arguments), text=True, stderr=subprocess.STDOUT, timeout=40)


def wait_for(check, message):
    until = time.monotonic() + 12
    while time.monotonic() < until:
        try:
            if check():
                return
        except (AssertionError, StopIteration):
            pass
        time.sleep(.2)
    raise AssertionError(message)


def frame(name=None):
    dump = adb("shell", "dumpsys", "window", "windows")
    windows = [block for block in re.split(r"(?m)^  Window #", dump)
               if "u0 Taku System Surface}" in block.split("\n")[0]]
    assert len(windows) == 1 and "isOnScreen=true" in windows[0], "Expected one visible surface"
    if name:
        (args.out / (name + "-window.txt")).write_text(windows[0])
    return [int(value) for value in re.search(r" frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", windows[0]).groups()]


def shot(name):
    data = subprocess.check_output(prefix + ["exec-out", "screencap", "-p"], timeout=20)
    assert data.startswith(b"\x89PNG")
    (args.out / (name + ".png")).write_bytes(data)


def tap(x, y):
    adb("shell", "input", "tap", str(round(x)), str(round(y)))
    time.sleep(.35)


def center(bounds):
    return (bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2


def log():
    return adb("logcat", "-d", "-v", "brief", "SurfaceDemo:I", "*:S")


def action_count(action):
    return sum("typed-action | preview | " + action + " | " in line for line in log().splitlines())


def show(kind, background=True, compact=False):
    adb("shell", "am", "force-stop", "org.agentos.surfacedemo")
    result = adb("shell", "am", "start", "-W", "-n", "org.agentos.surfacedemo/.PreviewActivity",
                 "--es", "scenario", kind, "--ez", "bottom", "true", "--el", "ttlMs", "600000",
                 "--ez", "compact", str(compact).lower())
    assert "Status: ok" in result, result
    wait_for(lambda: bool(frame()), "Surface did not appear")
    if background:
        result = adb("shell", "am", "start", "-W", "-n", args.foreground)
        assert "Status: ok" in result, result
        time.sleep(.3)


def ui_node(label=None, class_name=None, package="org.agentos.surfacedemo"):
    path = "/data/local/tmp/taku-surface-api.xml"
    adb("shell", "uiautomator", "dump", path)
    value = ET.fromstring(adb("exec-out", "cat", path))
    nodes = [node for node in value.iter("node") if node.get("package") == package
             and (label is None or node.get("text") == label)
             and (class_name is None or node.get("class") == class_name)]
    assert len(nodes) == 1, "Could not find the preview control"
    return [int(value) for value in re.findall(r"\d+", nodes[0].get("bounds"))]


def reset_position():
    bounds = frame()
    x, y = (bounds[0] + dp(85), bounds[1] + dp(30)) if bounds[3] - bounds[1] > dp(60) else center(bounds)
    adb("shell", "input", "swipe", str(round(x)), str(round(y)), str(round(x)), str(round(y)), "700")
    tap(*center(ui_node(label="恢复默认位置", package="org.agentos.surfaces")))


def drag(dx, dy, header=False, arrow=False):
    bounds = frame()
    x, y = (bounds[0] + dp(85), bounds[1] + dp(30)) if header else center(bounds)
    if arrow:
        x = bounds[2] - dp(26)
    adb("shell", "input", "swipe", str(round(x)), str(round(y)), str(round(x + dx)), str(round(y + dy)), "350")
    time.sleep(.5)
    return frame()


def near(first, second):
    return all(abs(a-b) <= 2 for a, b in zip(first, second))


def verify_position():
    adb("shell", "input", "keyevent", "BACK")  # Dismiss the API fixture's IME.
    show("hint")
    initial = frame()
    before = action_count("accept")
    moved = drag(dp(4), -dp(400))
    assert moved[1] < initial[1] - dp(300), "Capsule did not follow the drag"
    assert action_count("accept") == before, "Dragging accidentally accepted a hint"
    moved = drag(-dp(4), -dp(30), arrow=True)
    assert moved[3] - moved[1] == initial[3] - initial[1], "Dragging from the arrow accidentally expanded"
    assert action_count("accept") == before
    frame("dragged-capsule"); shot("dragged-capsule")
    tap(moved[2] - dp(26), center(moved)[1])
    card = frame("expanded-at-position"); shot("expanded-at-position")
    assert near(center(card), center(moved)), "Expansion lost the saved center"
    tap(card[2] - dp(86), card[1] + dp(30))
    assert near(frame(), moved), "Collapse did not restore the capsule position"
    tap(*center(frame()))
    wait_for(lambda: action_count("accept") == before + 1, "Tap after dragging stopped working")

    adb("shell", "am", "force-stop", "org.agentos.surfacedemo")
    adb("shell", "am", "force-stop", "org.agentos.surfaces")
    show("hint")
    assert near(frame(), moved), "Host process restart lost the position"
    show("choice")
    assert near(center(frame()), center(moved)), "New content did not inherit the user position"
    old_card = frame()
    choices_before = action_count("option-a")
    shifted = drag(-dp(10), -dp(100), header=True)
    assert shifted[1] < old_card[1] - dp(80), "Card header did not drag"
    frame("dragged-card"); shot("dragged-card")
    assert action_count("option-a") == choices_before, "Header drag selected an option"

    # Rotate while the producer remains alive, then restore portrait. Preference must not be rewritten.
    portrait_width, portrait_height = map(int, re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
    rotation = adb("shell", "settings", "get", "system", "user_rotation").strip()
    auto = adb("shell", "settings", "get", "system", "accelerometer_rotation").strip()
    try:
        adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb("shell", "settings", "put", "system", "user_rotation", "1")
        wait_for(lambda: frame()[2] > portrait_width or frame()[3] <= portrait_width, "Rotation did not change surface bounds")
        time.sleep(1)  # Let the system rotation animation finish before retaining the screenshot.
        landscape = frame("landscape"); shot("landscape")
        assert landscape[0] >= 0 and landscape[1] >= 0 and landscape[2] <= portrait_height and landscape[3] <= portrait_width, "Card left the rotated display"
        x = round(center(landscape)[0])
        adb("shell", "input", "swipe", str(x), str(landscape[3] - dp(40)), str(x), str(landscape[1] + dp(90)), "350")
        assert near(frame(), landscape), "Scrolling the body dragged the card"
        shot("landscape-scrolled")
        adb("shell", "settings", "put", "system", "user_rotation", "0")
        wait_for(lambda: near(frame(), shifted), "Returning to portrait lost the position")
    finally:
        for key, value in [("user_rotation", rotation), ("accelerometer_rotation", auto)]:
            adb("shell", "settings", "delete", "system", key) if value == "null" else adb("shell", "settings", "put", "system", key, value)

    # Put the card low enough that IME avoidance is necessary; transient movement must not persist.
    low = drag(0, dp(320), header=True)
    show("choice", background=False)
    assert near(frame(), low), "Same-size publish changed the user position"
    tap(*center(ui_node(class_name="android.widget.EditText")))
    wait_for(lambda: frame()[3] < low[3] - dp(60), "Custom-position card did not avoid IME")
    keyboard = frame("custom-keyboard"); shot("custom-keyboard")
    adb("shell", "input", "keyevent", "BACK")
    wait_for(lambda: near(frame(), low), "Keyboard dismissal did not restore the saved position")
    edge = drag(-dp(1000), -dp(2000), header=True)
    assert edge[0] >= dp(16) and edge[1] >= dp(12), "Drag escaped the safe area"
    assert edge[2] <= portrait_width - dp(16) and edge[3] <= portrait_height - dp(12)
    frame("screen-edge"); shot("screen-edge")
    reset_position()
    wait_for(lambda: frame()[3] > low[3] + dp(50), "Reset did not restore default placement")
    frame("reset-position"); shot("reset-position")
    return {"capsuleDragged": True, "dragDidNotTriggerAction": True, "tapAfterDrag": True,
            "dragFromButtonDoesNotClick": True, "bodyScrollDoesNotMoveCard": True,
            "expandCollapseShareCenter": True, "hostRestartRetainsPosition": True,
            "newContentInheritsPosition": True, "cardHeaderDragged": True,
            "rotationClampedAndRestored": True, "imeTemporaryAndRestored": True, "screenEdgeClamped": True,
            "resetDefaultViaLongPress": True, "capsuleBounds": moved, "cardBounds": shifted,
            "landscapeBounds": landscape, "imeBeforeBounds": low, "imeAfterBounds": keyboard, "edgeBounds": edge}


record = {"checkedAt": datetime.now(timezone.utc).isoformat(), "device": args.serial,
          "platform": int(adb("shell", "getprop", "ro.build.version.sdk")), "foreground": args.foreground}
assert record["platform"] >= 36
density = int(re.findall(r"(?:Physical|Override) density: (\d+)", adb("shell", "wm", "density"))[-1]) / 160
dp = lambda value: round(value * density)

try:
    installed = {}
    for package, filename in [("org.agentos.surfaces", "TakuSurfaces.apk"),
                              ("org.agentos.surfacedemo", "TakuSurfaceDemo.apk"),
                              ("org.agentos.surfacestests", "TakuSurfaceApiTests.apk")]:
        path = adb("shell", "pm", "path", package).strip().split(":", 1)[1]
        actual = adb("shell", "sha256sum", path).split()[0]
        expected = hashlib.sha256((ROOT / "system/surfaces/out" / filename).read_bytes()).hexdigest()
        assert actual == expected, "Install the current build before testing: " + package
        installed[filename] = actual
    record["apkSha256"] = installed
    wire = adb("shell", "am", "instrument", "-w", "-r", "org.agentos.surfacestests/.SurfaceApiTest")
    (args.out / "wire-tests.txt").write_text(wire)
    assert "INSTRUMENTATION_CODE: -1" in wire and "failure=" not in wire, wire
    record["androidWireAssertions"] = int(re.search(r"assertions=(\d+)", wire).group(1))

    if args.position:
        show("hint")
        reset_position()

    show("hint")
    hint = frame("hint"); shot("hint")
    before = action_count("accept")
    tap(*center(hint))
    wait_for(lambda: action_count("accept") == before + 1, "Hint body did not return its action")
    assert frame()[3] - frame()[1] == hint[3] - hint[1], "Hint body unexpectedly expanded"
    tap(*center(frame()))  # A selected revision can expand but cannot emit the same action again.
    assert action_count("accept") == before + 1
    record["hintDirectActionOnce"] = True

    show("choice")
    decision = frame("decision"); shot("decision")
    assert decision[3] - decision[1] > hint[3] - hint[1] + dp(80), "Choice did not start expanded"
    before = action_count("option-a")
    tap(decision[0] + dp(95), decision[3] - dp(42))
    wait_for(lambda: action_count("option-a") == before + 1, "Choice did not return its option")
    record["choiceInitiallyExpanded"] = True

    show("result")
    result = frame("result")
    before = action_count("open-details")
    tap(result[2] - dp(26), (result[1] + result[3]) / 2)  # Explicit arrow only expands.
    expanded = frame("result-expanded"); shot("result-expanded")
    assert expanded[3] - expanded[1] > result[3] - result[1] and action_count("open-details") == before
    tap(expanded[2] - dp(86), expanded[1] + dp(30))
    tap(*center(frame()))
    wait_for(lambda: action_count("open-details") == before + 1, "Result body did not return its details action")
    assert "typed-action | preview | open-details | demo:weekend-guide | text/markdown | Weekend ideas.md" in log()
    record["resultArtifactCallback"] = {"ref": "demo:weekend-guide", "mediaType": "text/markdown", "title": "Weekend ideas.md"}
    record["arrowExpandsWithoutAction"] = True

    show("legacy")
    old = frame(); assert old[3] - old[1] <= dp(60)
    tap(*center(old)); assert frame()[3] - frame()[1] > dp(100)
    record["legacyV1ExpandBehavior"] = True

    show("hint", background=False)
    tap(*center(ui_node(label="选择示例")))
    updated = frame(); assert updated[3] - updated[1] > dp(100)
    record["sameIdRevisionAppliesNewPresentation"] = True
    before_keyboard = updated
    tap(*center(ui_node(class_name="android.widget.EditText")))
    wait_for(lambda: frame()[3] < before_keyboard[3] - dp(100), "IME did not move the decision card")
    assert "mInputShown=true" in adb("shell", "dumpsys", "input_method")
    adb("shell", "input", "text", "surface_api_ok")
    after_keyboard = frame("keyboard"); shot("keyboard")
    record["keyboardBeforeBounds"] = before_keyboard
    record["keyboardAfterBounds"] = after_keyboard
    record["keyboardInsetApplied"] = True
    record["hintBounds"] = hint
    record["decisionBounds"] = decision
    if args.position:
        record["position"] = verify_position()
    (args.out / "demo-events.txt").write_text(log())
    print(json.dumps(record, ensure_ascii=False))
    (args.out / "device-checks.json").write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n")
finally:
    adb("shell", "am", "force-stop", "org.agentos.surfacedemo")
    adb("shell", "rm", "-f", "/data/local/tmp/taku-surface-api.xml")
