#!/usr/bin/env python3
"""Check the country plugin through an already running, isolated Android host."""

import argparse
import json
from pathlib import Path, PurePosixPath
import re
import shlex
import socket
import subprocess
import sys
import time
import uuid


PLUGIN_ID = "org.taku.environment.country"
PROVIDER_CLASS = "org.agentos.environment.plugin.CountryProvider"
TOOLS = {"country_read", "country_subscribe", "country_poll", "country_unsubscribe"}
GROUPS = (
    "install_and_discovery",
    "snapshot_and_independent_subscriptions",
    "refresh_and_independent_cursors",
    "unsubscribe_isolation",
    "strict_arguments",
    "bounded_history_and_exact_dropped_count",
    "disable_enable_and_process_release",
    "reload_remove_and_process_release",
)


class CheckFailed(Exception):
    pass


def require(condition, message):
    if not condition:
        raise CheckFailed(message)


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def error_code(value):
    require(isinstance(value, dict), "error must be an object")
    code = value.get("code")
    require(isinstance(code, str) and code.strip(), "error.code must be nonempty")
    return code


class Verification:
    def __init__(self, args):
        self.args = args
        self.port = None
        self.generation = None
        self.subscription_a = None
        self.subscription_b = None
        self.subscription_c = None
        self.b_cursor = None
        self.installed = False
        self.current = None
        self.report = {
            "pluginId": PLUGIN_ID,
            "serial": args.serial,
            "expectedAvd": args.expected_avd,
            "socket": args.socket,
            "pluginDirectory": args.plugin_dir,
            "startedAtMillis": int(time.time() * 1000),
            "passed": False,
            "preflight": {"status": "not_run"},
            "groups": [{"name": name, "status": "not_run"} for name in GROUPS],
            "observedAvailabilities": [],
            "unknown": [
                "Real cross-border country changes and arrival behavior are unverified.",
                "Unavailable source states are not injected; only the actual cellular source is observed.",
                "Ten-minute subscription expiry is not exercised by this bounded lifecycle check.",
            ],
            "cleanup": {"forwardRemoved": None, "hostLeftRunning": True},
        }

    def save(self):
        self.args.out.write_text(
            json.dumps(self.report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    def note(self, **values):
        self.current.update(values)

    def adb(self, *arguments):
        try:
            result = subprocess.run(
                [self.args.adb, "-s", self.args.serial, *arguments],
                check=False, capture_output=True, text=True, timeout=20,
            )
        except subprocess.TimeoutExpired as error:
            raise CheckFailed("adb command timed out") from error
        except OSError as error:
            raise CheckFailed("adb could not be executed") from error
        require(result.returncode == 0, "adb command failed (exit %s)" % result.returncode)
        return result.stdout.strip()

    def shell(self, *arguments):
        return self.adb("shell", shlex.join(arguments))

    def request(self, operation, argument=None, **values):
        request = {"operation": operation, **values}
        if argument is not None:
            request["argument"] = argument
        payload = (json.dumps(request, separators=(",", ":")) + "\n").encode("utf-8")
        try:
            with socket.create_connection(("127.0.0.1", self.port), timeout=20) as connection:
                connection.sendall(payload)
                with connection.makefile("rb") as stream:
                    line = stream.readline(2 * 1024 * 1024 + 1)
            require(line.endswith(b"\n") and len(line) <= 2 * 1024 * 1024,
                    "host response is missing, oversized, or not newline framed")
            response = json.loads(line)
        except (OSError, ValueError) as error:
            raise CheckFailed("host transport or JSON decoding failed") from error
        require(isinstance(response, dict) and type(response.get("ok")) is bool,
                "host response must contain boolean ok")
        if response["ok"]:
            require("result" in response, "host success response is missing result")
        else:
            error_code(response.get("error"))
        return response

    def host(self, operation, argument=None, **values):
        response = self.request(operation, argument, **values)
        require(response["ok"], "host rejected %s: %s" % (
            operation, response.get("error", {}).get("code", "unknown")))
        return response["result"]

    def host_error(self, operation, expected, argument=PLUGIN_ID, **values):
        response = self.request(operation, argument, **values)
        require(not response["ok"], "host unexpectedly accepted %s" % operation)
        code = error_code(response["error"])
        require(code == expected, "%s expected host error %s, got %s" % (operation, expected, code))
        return code

    def call(self, name, arguments=None, expect_error=False):
        result = self.host("call-tool", PLUGIN_ID, name=name, arguments=arguments or {})
        require(isinstance(result, dict), "MCP tool result must be an object")
        content = result.get("content")
        require(isinstance(content, list) and content, "MCP content must be a nonempty array")
        texts = [item.get("text") for item in content
                 if isinstance(item, dict) and item.get("type") == "text"]
        require(texts and all(isinstance(text, str) for text in texts),
                "MCP content must include text")
        structured = result.get("structuredContent")
        require(isinstance(structured, dict), "MCP structuredContent must be an object")
        require(type(result.get("isError")) is bool, "MCP isError must be boolean")
        require(result["isError"] is expect_error, "%s returned unexpected isError" % name)
        if expect_error:
            return {"code": error_code(structured.get("error"))}
        return structured

    def snapshot(self, value, generation=None):
        require(isinstance(value, dict), "snapshot must be an object")
        require(value.get("source") == "cellular_network", "snapshot source must be cellular_network")
        require(isinstance(value.get("sourceDescription"), str) and value["sourceDescription"].strip(),
                "snapshot sourceDescription must be nonempty")
        require(isinstance(value.get("reason"), str) and value["reason"].strip(),
                "snapshot reason must be nonempty")
        availability = value.get("availability")
        country = value.get("countryIso")
        require(availability in ("AVAILABLE", "UNKNOWN"), "invalid availability")
        require(isinstance(country, str), "countryIso must be a string")
        require((availability == "AVAILABLE" and re.fullmatch(r"[A-Z]{2}", country))
                or (availability == "UNKNOWN" and country == ""), "countryIso and availability disagree")
        for field in ("observedAtMillis", "observedElapsedRealtimeMillis", "sequence"):
            require(integer(value.get(field), 1), "%s must be a positive integer" % field)
        since = value.get("continuousSinceElapsedRealtimeMillis")
        require(type(since) is int, "continuousSinceElapsedRealtimeMillis must be an integer")
        if availability == "AVAILABLE":
            require(0 < since <= value["observedElapsedRealtimeMillis"], "invalid continuous since time")
        else:
            require(since == -1, "UNKNOWN continuous since time must be -1")
        require(type(value.get("stable")) is bool, "stable must be boolean")
        actual_generation = value.get("providerGeneration")
        require(isinstance(actual_generation, str), "providerGeneration must be a UUID string")
        try:
            uuid.UUID(actual_generation)
        except ValueError as error:
            raise CheckFailed("providerGeneration must be a UUID string") from error
        if generation is not None:
            require(actual_generation == generation, "providerGeneration changed without a lifecycle action")
        if availability not in self.report["observedAvailabilities"]:
            self.report["observedAvailabilities"].append(availability)
        return value

    def read(self, refresh=False, generation=None):
        return self.snapshot(self.call("country_read", {"refresh": refresh}), generation)

    def subscribe(self):
        result = self.call("country_subscribe")
        token = result.get("subscriptionId")
        require(isinstance(token, str) and token.strip(), "subscriptionId must be nonempty")
        require(type(result.get("ttlMs")) is int and result["ttlMs"] == 600000,
                "subscription TTL must be ten minutes")
        return token, self.snapshot(result.get("snapshot"), self.generation)

    def poll(self, token):
        result = self.call("country_poll", {"subscriptionId": token})
        events = result.get("events")
        require(isinstance(events, list), "poll events must be an array")
        require(integer(result.get("dropped")), "poll dropped must be a nonnegative integer")
        previous = None
        for event in events:
            self.snapshot(event, self.generation)
            require(previous is None or event["sequence"] == previous + 1,
                    "poll events must have consecutive increasing sequences")
            previous = event["sequence"]
        if token == self.subscription_b and events:
            self.b_cursor = events[-1]["sequence"]
        return result

    def provider_pids(self):
        output = self.shell("ps", "-A", "-o", "PID,ARGS")
        lines = output.splitlines()
        require(lines and lines[0].split()[0] == "PID", "ps PID,ARGS output is invalid")
        pids = []
        for line in lines[1:]:
            fields = line.split(None, 1)
            require(len(fields) == 2 and fields[0].isdigit(), "invalid ps process row")
            if PROVIDER_CLASS in fields[1].split():
                pids.append(int(fields[0]))
        return sorted(pids)

    def expect_processes(self, count, retired=()):
        deadline = time.monotonic() + 5
        while True:
            pids = self.provider_pids()
            require(len(pids) <= 1, "more than one exact CountryProvider process is running")
            if len(pids) == count and set(pids).isdisjoint(retired):
                return pids
            require(time.monotonic() < deadline, "CountryProvider process count or old PID retirement failed")
            time.sleep(0.1)

    def discovered_tools(self):
        catalog = self.host("tools")
        require(isinstance(catalog, list), "host tools must be an array")
        matches = [entry for entry in catalog
                   if isinstance(entry, dict) and entry.get("pluginId") == PLUGIN_ID]
        if not matches:
            return set()
        require(len(matches) == 1, "duplicate country plugin groups in tool discovery")
        tools = matches[0].get("tools")
        require(isinstance(tools, list), "country plugin tools must be an array")
        names = [tool.get("id") if isinstance(tool, dict) else None for tool in tools]
        require(all(isinstance(name, str) for name in names) and len(set(names)) == len(names),
                "tool discovery contains invalid or duplicate IDs")
        return set(names)

    def started(self, result):
        require(isinstance(result, dict) and result.get("id") == PLUGIN_ID,
                "host lifecycle result has the wrong plugin ID")
        require(result.get("state") == "started" and result.get("enabled") is True,
                "plugin must be enabled and started")

    def preflight(self):
        self.current = self.report["preflight"]
        require(self.adb("get-state") == "device", "target emulator is not online")
        require(self.shell("getprop", "ro.kernel.qemu") == "1", "target is not a qemu emulator")
        avd_lines = [line.strip() for line in self.adb("emu", "avd", "name").splitlines() if line.strip()]
        require(avd_lines in ([self.args.expected_avd], [self.args.expected_avd, "OK"]),
                "actual AVD name does not exactly match --expected-avd")
        self.note(qemu="1", avd=self.args.expected_avd)
        manifest_path = str(PurePosixPath(self.args.plugin_dir) / "manifest.json")
        try:
            manifest = json.loads(self.shell("cat", manifest_path))
        except ValueError as error:
            raise CheckFailed("remote plugin manifest is not JSON") from error
        require(isinstance(manifest, dict) and manifest.get("id") == PLUGIN_ID,
                "remote directory is not the authorized country plugin")
        require(self.provider_pids() == [], "a CountryProvider already exists; refusing shared-device interference")
        port = self.adb("forward", "tcp:0", "localabstract:" + self.args.socket)
        require(port.isdigit() and 0 < int(port) < 65536, "adb forward did not return an allocated port")
        self.port = int(port)
        self.note(forwardPort=self.port)
        self.host_error("show", "NOT_FOUND")
        self.note(pluginAbsentBeforeInstall=True)

    def install_and_discovery(self):
        self.installed = None
        installed = self.host("install", self.args.plugin_dir)
        self.installed = True
        self.started(installed)
        self.started(self.host("show", PLUGIN_ID))
        require(self.discovered_tools() == TOOLS, "country plugin must expose exactly the four contracted tools")
        self.note(state="started", tools=sorted(TOOLS), providerPids=self.expect_processes(1))

    def snapshot_and_independent_subscriptions(self):
        value = self.read()
        self.generation = value["providerGeneration"]
        self.subscription_a, snapshot_a = self.subscribe()
        self.subscription_b, snapshot_b = self.subscribe()
        require(self.subscription_a != self.subscription_b, "independent subscriptions must have distinct IDs")
        self.b_cursor = snapshot_b["sequence"]
        self.note(providerGeneration=self.generation, sequence=value["sequence"],
                  countryIso=value["countryIso"], availability=value["availability"], reason=value["reason"],
                  observedAtMillis=value["observedAtMillis"],
                  observedElapsedRealtimeMillis=value["observedElapsedRealtimeMillis"],
                  subscriptionInitialSequences=[snapshot_a["sequence"], snapshot_b["sequence"]],
                  distinctSubscriptions=True, ttlMs=600000)

    def refresh_and_independent_cursors(self):
        before = self.read(generation=self.generation)
        refreshed = self.read(refresh=True, generation=self.generation)
        sequence = refreshed["sequence"]
        require(sequence > before["sequence"], "refresh must create a new sequence")
        poll_a = self.poll(self.subscription_a)
        again_a = self.poll(self.subscription_a)
        poll_b = self.poll(self.subscription_b)
        require(again_a["events"] == [] and again_a["dropped"] == 0,
                "a second immediate A poll must be empty")
        for label, result in (("A", poll_a), ("B", poll_b)):
            require(result["dropped"] == 0, "%s unexpectedly dropped events" % label)
            same = [event for event in result["events"] if event["sequence"] == sequence]
            require(same == [refreshed], "%s must observe the exact refreshed snapshot" % label)
        self.note(providerGeneration=self.generation, refreshedSequence=sequence,
                  eventCounts={"A": len(poll_a["events"]), "B": len(poll_b["events"])},
                  sameEvent=True, secondAPollEmpty=True)

    def unsubscribe_isolation(self):
        first = self.call("country_unsubscribe", {"subscriptionId": self.subscription_a})
        second = self.call("country_unsubscribe", {"subscriptionId": self.subscription_a})
        require(first.get("removed") is True and second.get("removed") is False,
                "unsubscribe must remove once and be idempotent")
        code = self.call("country_poll", {"subscriptionId": self.subscription_a}, expect_error=True)["code"]
        refreshed = self.read(refresh=True, generation=self.generation)
        result = self.poll(self.subscription_b)
        require(result["dropped"] == 0 and refreshed in result["events"],
                "unsubscribing A must not affect B")
        self.note(unsubscribeRemoved=[True, False], cancelledPollErrorCode=code,
                  bLatestSequence=self.b_cursor)

    def strict_arguments(self):
        self.note(errorCodes={})
        for label, arguments in (("refresh_string", {"refresh": "true"}),
                                 ("extra_country", {"country": "US"})):
            code = self.call("country_read", arguments, expect_error=True)["code"]
            self.current["errorCodes"][label] = code

    def bounded_history_and_exact_dropped_count(self):
        self.poll(self.subscription_b)
        cursor = self.b_cursor
        last_sequence = cursor
        self.note(cursorBeforePause=cursor, refreshCount=0)
        for count in range(65):
            refreshed = self.read(refresh=True, generation=self.generation)
            require(refreshed["sequence"] > last_sequence, "each refresh must produce a new sequence")
            last_sequence = refreshed["sequence"]
            self.note(refreshCount=count + 1, lastRefreshSequence=last_sequence)
        result = self.poll(self.subscription_b)
        events, dropped = result["events"], result["dropped"]
        require(0 < len(events) <= 64, "poll must return between one and 64 retained events")
        first, last = events[0]["sequence"], events[-1]["sequence"]
        self.note(eventCount=len(events), firstSequence=first, lastSequence=last, dropped=dropped)
        require(dropped >= 1, "65 paused refreshes must overflow a 64-event history")
        require(first > cursor and dropped == first - cursor - 1,
                "dropped must equal the exact missing sequence count before the first event")
        require(dropped + len(events) == last - cursor and last >= last_sequence,
                "retained events and dropped count must cover the entire cursor-to-last sequence span")

    def disable_enable_and_process_release(self):
        prior_generation = self.generation
        prior_pids = self.expect_processes(1)
        self.note(generationBefore=prior_generation, pidsBefore=prior_pids)
        disabled = self.host("disable", PLUGIN_ID)
        require(disabled.get("state") == "stopped" and disabled.get("enabled") is False,
                "disabled provider must be stopped")
        require(not self.discovered_tools(), "disabled provider must disappear from tools")
        code = self.host_error("call-tool", "PROVIDER_STOPPED", name="country_read", arguments={})
        self.note(disabledCallErrorCode=code, disabledPids=self.expect_processes(0))
        self.started(self.host("enable", PLUGIN_ID))
        enabled = self.read()
        self.generation = enabled["providerGeneration"]
        require(self.generation != prior_generation, "enable must create a new providerGeneration")
        self.note(generationAfter=self.generation, pidsAfter=self.expect_processes(1, prior_pids))
        require(self.discovered_tools() == TOOLS, "enable must restore the contracted tools")
        code = self.call("country_poll", {"subscriptionId": self.subscription_b}, expect_error=True)["code"]
        self.subscription_c, _ = self.subscribe()
        self.note(oldSubscriptionErrorCode=code, newSubscriptionUsable=True)

    def reload_remove_and_process_release(self):
        prior_generation = self.generation
        prior_pids = self.expect_processes(1)
        self.note(generationBefore=prior_generation, pidsBefore=prior_pids)
        self.started(self.host("reload", PLUGIN_ID))
        reloaded = self.read()
        self.generation = reloaded["providerGeneration"]
        require(self.generation != prior_generation, "reload must create a new providerGeneration")
        self.note(generationAfter=self.generation, pidsAfter=self.expect_processes(1, prior_pids))
        require(self.discovered_tools() == TOOLS, "reload must retain the contracted tools")
        code = self.call("country_poll", {"subscriptionId": self.subscription_c}, expect_error=True)["code"]
        self.note(oldSubscriptionErrorCode=code)
        self.installed = None
        self.host("remove", PLUGIN_ID)
        self.installed = False
        self.note(showAfterRemove=self.host_error("show", "NOT_FOUND"),
                  callAfterRemove=self.host_error("call-tool", "NOT_FOUND", name="country_read", arguments={}),
                  removedPids=self.expect_processes(0))
        require(not self.discovered_tools(), "removed provider must disappear from tools")

    def run(self):
        try:
            self.preflight()
            self.current["status"] = "passed"
            self.save()
            for result in self.report["groups"]:
                self.current = result
                getattr(self, result["name"])()
                result["status"] = "passed"
                self.save()
            self.report["passed"] = True
        except (Exception, KeyboardInterrupt) as error:
            if self.current is not None:
                self.current.update(status="failed", failureType=type(error).__name__)
                if isinstance(error, CheckFailed):
                    self.current["failure"] = str(error)
            self.report["failureType"] = type(error).__name__
            self.report["scenePreservedOnFailure"] = True
        finally:
            self.report["pluginInstalledByThisRunAtExit"] = self.installed
            if self.port is not None:
                try:
                    self.adb("forward", "--remove", "tcp:%s" % self.port)
                    self.report["cleanup"]["forwardRemoved"] = True
                except CheckFailed:
                    self.report["cleanup"]["forwardRemoved"] = False
                    self.report["passed"] = False
            self.report["finishedAtMillis"] = int(time.time() * 1000)
            self.save()
        return 0 if self.report["passed"] else 1


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        "The caller must start AndroidServer with a dedicated root and socket, and push the "
        "installable plugin directory first. This script never starts/stops the host, installs "
        "an APK, or alters cellular state. Failure preserves plugin state; only its own ADB "
        "forward is removed. Use a new JSON output path for each run."))
    parser.add_argument("--serial", required=True, help="explicit emulator-NNNN serial")
    parser.add_argument("--expected-avd", required=True, help="exact expected emulator AVD name")
    parser.add_argument("--socket", required=True,
                        help="dedicated country-test-*, sony-test-*, sony-country-test-* or taku-country-test-* socket")
    parser.add_argument("--plugin-dir", required=True, help="already pushed absolute remote plugin directory")
    parser.add_argument("--out", required=True, type=Path, help="new absolute JSON evidence path")
    parser.add_argument("--adb", default="adb", help="ADB executable (default: adb)")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("--serial must explicitly identify an emulator-NNNN")
    if not args.expected_avd.strip() or "\n" in args.expected_avd or "\r" in args.expected_avd:
        parser.error("--expected-avd must be one nonempty exact AVD name")
    if not re.fullmatch(r"(?:country|sony|sony-country|taku-country)-test-[A-Za-z0-9][A-Za-z0-9_.-]{0,60}", args.socket):
        parser.error("--socket must use a dedicated country/Sony test prefix")
    remote = PurePosixPath(args.plugin_dir)
    if not remote.is_absolute() or ".." in remote.parts or str(remote) == "/" or "\x00" in args.plugin_dir:
        parser.error("--plugin-dir must be an absolute remote directory without '..'")
    args.plugin_dir = str(remote)
    if not args.out.is_absolute() or args.out.suffix.lower() != ".json":
        parser.error("--out must be an absolute .json path")
    if not args.out.parent.is_dir():
        parser.error("--out parent directory must already exist")
    try:
        with args.out.open("x", encoding="utf-8") as output:
            output.write("{}\n")
    except OSError:
        parser.error("--out must be a writable new file; existing evidence is never overwritten")
    return args


def main():
    args = parse_args()
    verification = Verification(args)
    verification.save()
    status = verification.run()
    print(json.dumps({"passed": verification.report["passed"], "evidence": str(args.out)}))
    return status


if __name__ == "__main__":
    sys.exit(main())
