#!/usr/bin/env python3
"""Explicit device acceptance using a synthetic fixture; no real chat, account, booking or send."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "out/phone-input-tests"
PACKAGE = "org.agentos.phoneinput.tests"
TITLE = "Taku draft fixture (3)"
REMOTE = "/data/local/tmp/taku-phone-draft-acceptance.jar"
SAMPLE = "TEST ONLY — dinner at 20:00 for 3.\nSeafood allergy noted. 手动发送。"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", help="ADB executable; existing ADB environment is preserved")
    parser.add_argument("--remote-broker", default="/system_ext/framework/agentos-task-broker.jar")
    parser.add_argument("--local-broker", type=Path, default=ROOT / "system/assistant/out/task-broker.jar")
    parser.add_argument("--keep-fixture", action="store_true", help="keep the visibly synthetic fixture open for inspection")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or Path.home() / "Library/Android/sdk")
    adb = args.adb or os.environ.get("ADB") or str(sdk / "platform-tools/adb")

    def device(*command, check=True, input=None, timeout=30):
        return subprocess.run([adb, "-s", args.serial, *command], input=input, capture_output=True,
                              text=True, timeout=timeout, check=check)

    direct_root = device("shell", "id", "-u").stdout.strip() == "0"

    def shell(command, root=False, **kwargs):
        if root and not direct_root:
            command = "su 0 sh -c " + shlex.quote(command)
        return device("shell", "-T", command, **kwargs)

    assert shell("id -u", root=True).stdout.strip() == "0", "Root ADB or existing su access is required"
    for file in ("fixture.apk", "helper.jar"):
        assert (OUTPUT / file).is_file(), "Run system/assistant/build-phone-input-tests.sh first"
    assert args.local_broker.is_file(), "Build the production broker first"
    installed_sha = shell(shlex.join(["sha256sum", args.remote_broker]), root=True).stdout.split()[0]
    assert installed_sha == hashlib.sha256(args.local_broker.read_bytes()).hexdigest(), "Remote broker differs from the specified local production build"
    assert not shell(shlex.join(["pm", "path", PACKAGE]), check=False).stdout.strip(), "Fixture already exists; inspect/remove this test package first"
    report = {"synthetic_only": True, "serial": args.serial, "display": 0,
              "entry_point": "installed-broker-parent", "broker_sha256": installed_sha,
              "fixture_sha256": hashlib.sha256((OUTPUT / "fixture.apk").read_bytes()).hexdigest(),
              "helper_sha256": hashlib.sha256((OUTPUT / "helper.jar").read_bytes()).hexdigest(), "cases": []}
    installed = False

    def state(token=None, ready=False):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            response = shell(shlex.join(["run-as", PACKAGE, "cat", "files/draft.json"]), check=False)
            try:
                value = json.loads(response.stdout)
                if token is not None and value.get("token") != token:
                    continue
                if ready and not all(value.get(k) for k in ("synthetic", "windowFocus", "drawn", "visible")):
                    continue
                return value
            except (ValueError, KeyError):
                pass
            finally:
                time.sleep(.1)
        raise AssertionError("Fresh visible synthetic fixture state unavailable")

    def launch(initial=""):
        token = uuid.uuid4().hex
        command = ["am", "start", "-W", "--display", "0", "-n", PACKAGE + "/.DraftActivity",
                   "--es", "token", token, "--es", "draft", initial]
        shell(shlex.join(command))
        result = state(token, ready=True)
        assert result["display"] == 0 and result["title"] == TITLE and result["text"] == initial, result
        assert result["setTextActionCount"] == 0 and result["textChangeCount"] == 0, result
        return result

    def prepare(title=TITLE):
        classpath = args.remote_broker + ":" + REMOTE
        command = "CLASSPATH=" + shlex.quote(classpath) + " timeout 20 app_process /system/bin org.agentos.lab.PhoneDraftHandoffAcceptanceProbe"
        started = time.monotonic()
        result = shell(command, root=True, input=json.dumps({"packageName": PACKAGE,
            "conversationTitle": title, "text": SAMPLE}, ensure_ascii=False), check=False, timeout=25)
        return {"exit": result.returncode, "stdout": result.stdout.strip(), "stderr": result.stderr.strip(),
                "seconds": round(time.monotonic() - started, 3)}

    def no_send(actual):
        assert all(actual[k] == 0 for k in ("buttonSendCount", "imeActionCount", "enterKeyCount", "otherEditorActionCount")), actual
        assert not actual["editorFocused"], "Draft helper changed editor focus"

    def success(result, already):
        assert result["exit"] == 0, result
        value = json.loads(result["stdout"])
        assert value["draftPrepared"] is True and value["sent"] is False and value["alreadyPrepared"] is already, value

    try:
        device("install", "--no-incremental", str(OUTPUT / "fixture.apk"))
        installed = True
        device("push", str(OUTPUT / "helper.jar"), REMOTE)
        before = launch()
        result = prepare()
        success(result, False)
        after = state()
        assert after["text"] == SAMPLE and after["setTextActionCount"] == 1 and after["textChangeCount"] == 1, after
        no_send(after)
        report["cases"].append({"name": "empty-draft-write-and-readback", "result": result, "before": before, "after": after})

        before = after
        result = prepare()
        success(result, True)
        after = state()
        assert after == before, {"before": before, "after": after}
        no_send(after)
        report["cases"].append({"name": "same-draft-idempotent", "result": result, "unchanged": True, "after": after})

        before = launch("TEST ONLY — preserve this existing user draft")
        result = prepare()
        assert result["exit"] == 1 and result["stdout"] == "ERROR: EXISTING_DRAFT_PRESERVED", result
        after = state()
        assert after == before, {"before": before, "after": after}
        no_send(after)
        report["cases"].append({"name": "different-draft-preserved", "result": result, "unchanged": True, "after": after})

        before = launch()
        result = prepare("Wrong synthetic conversation (3)")
        assert result["exit"] == 1 and result["stdout"] == "ERROR: CONVERSATION_TITLE_NOT_VERIFIED", result
        after = state()
        assert after == before, {"before": before, "after": after}
        no_send(after)
        report["cases"].append({"name": "wrong-title-rejected", "result": result, "unchanged": True, "after": after})
        report["status"] = "passed"
    except BaseException as error:
        report["status"] = "failed"
        report["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        if installed and not args.keep_fixture:
            shell(shlex.join(["am", "force-stop", PACKAGE]), check=False)
            report["fixture_uninstall"] = device("uninstall", PACKAGE, check=False).stdout.strip()
        shell(shlex.join(["rm", "-f", REMOTE]), check=False)
        report_path = OUTPUT / ("draft-acceptance-" + time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6] + ".json")
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps({"status": report["status"], "cases": len(report["cases"]), "report": str(report_path)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
