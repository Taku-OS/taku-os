#!/usr/bin/env python3
"""Device acceptance for display-scoped Unicode input; uses a disposable fixture, never a model turn."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "out/phone-input-tests"
PACKAGE = "org.agentos.phoneinput.tests"
REMOTE = "/data/local/tmp/taku-phone-input-tests.jar"
SAMPLE = '后台中文，标点「测试」 🌱👩‍💻\n第二行：引号"与反斜杠\\。'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--display", required=True, type=int, help="existing Taku background display ID")
    parser.add_argument("--installed-broker", action="store_true", help="call the installed broker's bounded parent entry point, not the standalone helper")
    args = parser.parse_args()
    if args.display <= 0:
        parser.error("--display must be the existing nonzero background display")
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or Path.home() / "Library/Android/sdk")
    adb = str(sdk / "platform-tools/adb")
    env = dict(os.environ, ADB_SERVER_SOCKET="tcp:5037")
    env.pop("ANDROID_ADB_SERVER_ADDRESS", None)
    env.pop("ANDROID_ADB_SERVER_PORT", None)

    def device(*command, check=True, input=None, timeout=30):
        return subprocess.run([adb, "-s", args.serial, *command], input=input, capture_output=True,
                              text=True, timeout=timeout, check=check, env=env)

    assert device("emu", "avd", "name").stdout.splitlines()[0].strip() == "Taku_ImageIntegration_20261010"
    assert device("shell", "id", "-u").stdout.strip() == "0", "Root ADB is required"
    for file in ("fixture.apk", "helper.jar"):
        assert (OUTPUT / file).is_file(), "Run system/assistant/build-phone-input-tests.sh first"
    installed_jar = "/system_ext/framework/agentos-task-broker.jar"
    installed_sha = None
    if args.installed_broker:
        production = ROOT / "system/assistant/out/task-broker.jar"
        assert production.is_file(), "Build the production broker first"
        installed_sha = device("shell", "sha256sum", installed_jar).stdout.split()[0]
        assert installed_sha == hashlib.sha256(production.read_bytes()).hexdigest(), "Installed broker differs from the local production build"
    # Refuse to replace an unrelated or previously installed copy of this test package.
    assert not device("shell", "pm", "path", PACKAGE, check=False).stdout.strip(), "Fixture package already exists; inspect/remove it first"
    report = {"serial": args.serial, "display": args.display, "cases": [],
              "entry_point": "installed-broker-parent" if args.installed_broker else "standalone-parent",
              "installed_broker_sha256": installed_sha,
              "helper_sha256": hashlib.sha256((OUTPUT / "helper.jar").read_bytes()).hexdigest(),
              "fixture_sha256": hashlib.sha256((OUTPUT / "fixture.apk").read_bytes()).hexdigest()}
    installed = False

    def state(side, token=None):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            result = device("shell", "run-as", PACKAGE, "cat", f"files/{side}.json", check=False)
            try:
                value = json.loads(result.stdout)
                if token is None or value["token"] == token:
                    return value
            except (ValueError, KeyError):
                pass
            time.sleep(.15)
        raise AssertionError(f"No fresh fixture state for {side}")

    def launch(side, mode):
        token = uuid.uuid4().hex
        display = 0 if side == "front" else args.display
        name = "ForegroundActivity" if side == "front" else "BackgroundActivity"
        device("shell", "am", "start", "-W", "--display", str(display), "-n", f"{PACKAGE}/.{name}",
               "--es", "mode", mode, "--es", "token", token)
        actual = state(side, token)
        assert actual["display"] == display, actual
        return actual

    def prepare(mode):
        # A previous voice interaction session can stay above an explicitly started Activity.
        device("shell", "input", "-d", "0", "keyevent", "KEYCODE_HOME")
        launch("back", mode)
        launch("front", "front")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            front, back = state("front"), state("back")
            if (front["windowFocus"] and front["focused"] == [True]
                    and all(surface.get("drawn") and all(surface.get("visible", []))
                            for surface in (front, back))):
                return front, back
            time.sleep(.1)
        raise AssertionError({"fixture_not_visible": {"front": front, "back": back}})

    def enter(display, text):
        started = time.monotonic()
        classpath = f"{installed_jar}:{REMOTE}" if args.installed_broker else REMOTE
        result = device("shell", f"CLASSPATH={classpath} timeout 20 app_process /system/bin org.agentos.lab.PhoneTextInputAcceptanceProbe",
                        input=json.dumps({"displayId": display, "text": text}, ensure_ascii=False), check=False, timeout=25)
        return {"exit": result.returncode, "stdout": result.stdout.strip(),
                "stderr": result.stderr.strip(), "seconds": round(time.monotonic() - started, 3)}

    def rejected(result, code):
        assert result["exit"] == 1 and (result["stdout"] == "ERROR: " + code or
                "（" + code + "）" in result["stdout"]), result

    def unchanged_front(before):
        after = state("front")
        assert after == before, {"foreground_before": before, "foreground_after": after}

    try:
        device("install", "--no-incremental", str(OUTPUT / "fixture.apk"))
        installed = True
        device("push", str(OUTPUT / "helper.jar"), REMOTE)
        for mode in ("focused", "single"):
            front, back = prepare(mode)
            assert back["focused"] == ([False, True] if mode == "focused" else [False]), back
            result = enter(args.display, SAMPLE)
            assert result["exit"] == 0 and result["stdout"] == "OK", result
            after = state("back")
            expected = ["后台甲", SAMPLE] if mode == "focused" else [SAMPLE]
            assert after["texts"] == expected, after
            assert after["focused"] == back["focused"], "Input changed focus"
            unchanged_front(front)
            case = {"name": mode, "result": result, "before": back, "after": after, "foreground_unchanged": True}
            report["cases"].append(case)
            if mode == "focused":
                # Preserve the original PNGs while both displays still contain only the fixture.
                capture_id = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
                foreground_png = OUTPUT / f"focused-{capture_id}-foreground.png"
                background_png = OUTPUT / f"focused-{capture_id}-background.png"
                png = subprocess.run([adb, "-s", args.serial, "exec-out", "screencap", "-p"],
                                     capture_output=True, check=True, timeout=20, env=env).stdout
                assert png.startswith(b"\x89PNG\r\n\x1a\n"), "Foreground capture is not a PNG"
                foreground_png.write_bytes(png)
                assert device("shell", "/system_ext/bin/phone-display", "id").stdout.strip() == str(args.display)
                capture = device("shell", "/system_ext/bin/phone-display", "capture")
                device("pull", "/data/local/agentos/virtual-display/screen.png", str(background_png))
                assert background_png.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"), "Background capture is not a PNG"
                case["screenshots"] = {"foreground": str(foreground_png), "background": str(background_png)}
                case["background_capture"] = capture.stdout.strip()
        for mode in ("ambiguous", "two-windows"):
            front, back = prepare(mode)
            assert back["focused"] == [False, False], back
            result = enter(args.display, SAMPLE)
            rejected(result, "AMBIGUOUS_BACKGROUND_INPUT")
            assert state("back") == back, "Ambiguous input changed a widget"
            unchanged_front(front)
            report["cases"].append({"name": mode, "result": result, "unchanged": True, "foreground_unchanged": True})
        front, back = prepare("focused")
        result = enter(0, SAMPLE)
        rejected(result, "INVALID_INPUT")
        assert state("back") == back
        unchanged_front(front)
        report["cases"].append({"name": "reject-display-zero", "result": result, "unchanged": True})
        front, back = prepare("filtered")
        result = enter(args.display, SAMPLE)
        rejected(result, "TEXT_READBACK_MISMATCH")
        assert state("back")["texts"] != [SAMPLE]
        unchanged_front(front)
        report["cases"].append({"name": "detect-input-filter-truncation", "result": result, "foreground_unchanged": True})
        report["status"] = "passed"
    except BaseException as error:
        report["status"] = "failed"
        report["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        if installed:
            device("shell", "am", "force-stop", PACKAGE, check=False)
            report["fixture_uninstall"] = device("uninstall", PACKAGE, check=False).stdout.strip()
        device("shell", "rm", "-f", REMOTE, check=False)
        if installed:
            # Internal activities such as document pickers require their original intent.
            # This dedicated fixture returns to Home instead of guessing how to recreate them.
            report["foreground_cleanup"] = {"action": "HOME", "display": 0,
                "exit": device("shell", "input", "-d", "0", "keyevent", "KEYCODE_HOME", check=False).returncode}
        report_path = OUTPUT / "acceptance.json"
        if report_path.exists():
            report_path.rename(OUTPUT / ("acceptance-" + time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6] + ".json"))
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "cases": len(report["cases"]), "report": str(OUTPUT / "acceptance.json")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
