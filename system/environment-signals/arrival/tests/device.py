#!/usr/bin/env python3
"""Exercise two ordinary Android apps against a prepared, disposable AOSP emulator."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time


ARRIVAL = "org.agentos.environment.arrival"
PERMISSIONS = "org.agentos.assistant.permissions"
THROTTLE_ALLOWLIST = "location_background_throttle_package_whitelist"
CLIENTS = {name: f"org.agentos.arrivalfixture.{name}" for name in ("notebook", "itinerary")}


def verify(adb, serial, avd):
    if not re.fullmatch(r"emulator-\d+", serial):
        raise SystemExit("Use an isolated emulator serial")

    def device(*command):
        return subprocess.check_output([adb, "-s", serial, *command], text=True, timeout=60).strip()

    def shell(*command):
        return device("shell", shlex.join(command))

    def call(authority, method, **args):
        bundle = shell("content", "call", "--uri", "content://" + authority,
                       "--method", method, "--arg", json.dumps(args, separators=(",", ":")))
        prefix = "Result: Bundle[{result="
        if not bundle.startswith(prefix) or not bundle.endswith("}]"):
            raise AssertionError(bundle)
        return json.loads(bundle[len(prefix):-2])

    def result(authority, method, **args):
        response = call(authority, method, **args)
        assert response.get("ok"), response
        return response["result"]

    def scope(package, allowed):
        result(PERMISSIONS, "setScope", packageName=package, scope="taku.control", allowed=allowed)

    def wait_for(read, predicate, timeout=60):
        deadline = time.monotonic() + timeout
        while True:
            value = read()
            if predicate(value):
                return value
            assert time.monotonic() < deadline, value
            time.sleep(2)

    def locked():
        policy = shell("dumpsys", "window", "policy").split("KeyguardServiceDelegate", 1)[1]
        return "showing=true" in policy and "screenState=SCREEN_STATE_OFF" in policy

    def gps(longitude, latitude, repetitions):
        for _ in range(repetitions):
            device("emu", "geo", "fix", longitude, latitude)
            time.sleep(11)

    def watch(authority, watch_id, lifetime, **overrides):
        data = dict(id=watch_id, placeId="waterfront", latitude=37.7931, longitude=-122.3932,
                    radiusMeters=200, expiresAtMs=int(time.time() * 1000) + lifetime * 1000)
        return call(authority, "register", **(data | overrides))

    if shell("getprop", "ro.kernel.qemu") != "1" or shell("getprop", "ro.boot.qemu.avd_name") != avd:
        raise SystemExit("The selected serial does not match the isolated AVD")

    fixtures = Path(__file__).resolve().parents[1] / "out" / "fixtures"
    authorities = {name: package + ".control" for name, package in CLIENTS.items()}
    before = result(PERMISSIONS, "read")
    assert before["mode"] in {"deny", "auto"}, before["mode"]
    previous_allowlist = shell("settings", "get", "global", THROTTLE_ALLOWLIST)
    previous_lock = shell("locksettings", "get-disabled")
    try:
        result(PERMISSIONS, "setMode", mode="deny")
        for name, package in CLIENTS.items():
            device("install", "--no-incremental", "-r", str(fixtures / f"{name}.apk"))
            assert shell("pm", "clear", package) == "Success"
            shell("cmd", "package", "unstop", package)
            for permission in ("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION",
                               "ACCESS_BACKGROUND_LOCATION", "POST_NOTIFICATIONS"):
                shell("pm", "grant", package, "android.permission." + permission)
            denial = watch(authorities[name], "same-watch", 600)
            assert denial.get("code") == "PERMISSION_DENIED", denial
            scope(package, True)
        result(PERMISSIONS, "setMode", mode="auto")
        shell("settings", "put", "global", THROTTLE_ALLOWLIST, ARRIVAL)
        shell("locksettings", "set-disabled", "false")
        shell("input", "keyevent", "KEYCODE_SLEEP")
        wait_for(locked, bool, timeout=15)
        for authority in authorities.values():
            response = watch(authority, "same-watch", 600)
            assert response.get("ok"), response
        assert watch(authorities["notebook"], "canceled", 600)["ok"]
        assert result(authorities["notebook"], "cancel", id="canceled")["cancelled"]
        assert watch(authorities["itinerary"], "expired", 15)["ok"]
        assert result(ARRIVAL, "list")["watches"] == []
        gps("-122.4132", "37.8131", 2)
        gps("-122.3932", "37.7931", 3)
        assert locked(), "The screen woke during the GPS run"

        states = {name: wait_for(lambda: call(authority, "status"),
                                 lambda s: len(s["active"]) == len(s["posted"]) == 1)
                  for name, authority in authorities.items()}
        assert len({status["uid"] for status in states.values()}) == 2, states
        ids = {}
        for name, status in states.items():
            authority = authorities[name]
            pending = result(authority, "pending")["events"]
            assert len(pending) == 1 and pending[0]["id"] == "same-watch", pending
            ids[name] = pending[0]["eventId"]
            assert status["active"] == [ids[name]] and status["posted"] == pending, status
            assert [watch["id"] for watch in result(authority, "list")["watches"]] == ["same-watch"]
        assert ids["notebook"] != ids["itinerary"], ids
        assert not result(authorities["itinerary"], "ack", eventId=ids["notebook"])["acknowledged"]
        print(json.dumps({"locked": locked(), "notifications": states}), flush=True)

        for name, authority in authorities.items():
            initial = states[name]
            retry = wait_for(lambda: call(authority, "status"),
                             lambda s: s["wakeCount"] > initial["wakeCount"], timeout=150)
            assert retry["active"] == [ids[name]] and retry["posted"] == initial["posted"], retry
            assert retry["lastPull"]["result"]["events"] == initial["posted"], retry
            print(f"{name}: wake {initial['wakeCount']} to {retry['wakeCount']}; one notification {ids[name]}")
            assert result(authority, "ack", eventId=ids[name])["acknowledged"]
            assert result(authority, "pending")["events"] == []
            assert result(authority, "cancel", id="same-watch")["cancelled"]
        scope(CLIENTS["itinerary"], False)
        assert watch(authorities["itinerary"], "blocked", 60).get("code") == "PERMISSION_DENIED"
        print("PASS: locked-screen GPS, two app UIDs, isolation, cancel, expiry, retries, ack, and revocation")
    finally:
        if previous_allowlist == "null":
            shell("settings", "delete", "global", THROTTLE_ALLOWLIST)
        else:
            shell("settings", "put", "global", THROTTLE_ALLOWLIST, previous_allowlist)
        shell("locksettings", "set-disabled", previous_lock)
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        for package in CLIENTS.values():
            result(PERMISSIONS, "reset", packageName=package)
        result(PERMISSIONS, "setMode", mode=before["mode"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("avd")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    args = parser.parse_args()
    verify(args.adb, args.serial, args.avd)
