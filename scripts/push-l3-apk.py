#!/usr/bin/env python3
"""Build and replace a standalone L3 APK, preserving app data."""
import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPONENTS = {
    "agent-app": ("org.agentos.agent", ("system/agent-app/scripts/build-android.sh",),
                  "system/agent-app/out/TakuAgent.apk"),
    "assistant": ("org.agentos.assistant", ("system/assistant/build.sh", "--apk-only"),
                  "system/assistant/out/AgentOSAssistant.apk"),
}


def run(*args, capture=True):
    return subprocess.run(args, cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def android_tools():
    output = run("bash", "-ec", 'source scripts/android-env.sh; printf "%s\\n" "$ADB" "$TOOLS" "$JDK"')
    adb, tools, jdk = output.splitlines()
    os.environ["JAVA_HOME"] = jdk
    return adb, Path(tools)


def checksum(apk):
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect(apk, package, tools):
    badging = run(tools / "aapt2", "dump", "badging", apk)
    identity = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", badging, re.M)
    if not identity or identity[1] != package or int(identity[2]) < 1:
        raise ValueError(f"Invalid package or versionCode in {apk}; expected {package}")
    report = run(tools / "apksigner", "verify", "--print-certs", apk)
    signers = sorted(re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$",
                                report, re.M))
    if not signers:
        raise ValueError(f"No verified signing certificate in {apk}")
    return {"versionCode": int(identity[2]), "signers": signers, "sha256": checksum(apk)}


def pull(adb, package, target):
    paths = run(*adb, "shell", "pm", "path", "--user", "0", package).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:/"):
        raise ValueError(f"{package} must already be installed as a single APK for user 0")
    run(*adb, "pull", paths[0].removeprefix("package:"), target)


def deploy(args, started):
    package, builder, artifact = COMPONENTS[args.component]
    backup = args.backup_dir.resolve()
    saved = backup / "previous.apk"
    manifest = backup / "backup.json"
    if args.dry_run:
        if args.rollback:
            print(f"Would verify {saved} for {package} and restore it on {args.serial} with adb install -r")
        else:
            print(f"Would build with {' '.join(builder)}, verify {artifact}, back up {package} from "
                  f"{args.serial} to {backup}, and replace it with adb install -r")
        return

    adb_path, tools = android_tools()
    adb = (adb_path, "-s", args.serial)
    if args.rollback:
        candidate = saved
        metadata = json.loads(manifest.read_text())
        expected = inspect(candidate, package, tools)
        if metadata != {"serial": args.serial, "package": package, **expected}:
            raise ValueError("Backup metadata, APK, or target does not match")
    else:
        if backup.exists():
            raise ValueError(f"Backup directory already exists: {backup}")
        run("bash", *builder, capture=False)
        candidate = ROOT / artifact
        expected = inspect(candidate, package, tools)

    with tempfile.TemporaryDirectory(prefix="taku-l3-") as directory:
        current = Path(directory) / "current.apk"
        pull(adb, package, current)
        installed = inspect(current, package, tools)
        if expected["signers"] != installed["signers"]:
            raise ValueError("Candidate and installed APK signing certificates differ")
        downgrade = expected["versionCode"] < installed["versionCode"]
        if downgrade and not args.rollback:
            raise ValueError("Push would downgrade versionCode; use a newer build")
        if not args.rollback:
            backup.mkdir(mode=0o700, parents=True, exist_ok=False)
            with saved.open("xb") as output, current.open("rb") as source:
                shutil.copyfileobj(source, output)
            if checksum(saved) != installed["sha256"]:
                raise ValueError("Backup checksum mismatch")
            manifest.write_text(json.dumps({"serial": args.serial, "package": package,
                                            **installed}, indent=2) + "\n")
            print(f"Backup saved to {backup}", flush=True)

        flags = ["-r", "--user", "0"]
        if downgrade:
            flags.append("-d")
        run(*adb, "install", *flags, candidate, capture=False)
        visible = Path(directory) / "visible.apk"
        pull(adb, package, visible)
        if checksum(visible) != expected["sha256"]:
            raise ValueError("Installed APK readback differs from the requested APK")
        elapsed = time.monotonic() - started
        print(f"{package} versionCode={expected['versionCode']} visible to package manager "
              f"after {elapsed:.2f}s (including installed APK readback)")


def main():
    started = time.monotonic()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("component", choices=COMPONENTS)
    parser.add_argument("--serial", required=True, help="explicit adb serial")
    parser.add_argument("--backup-dir", required=True, type=Path)
    parser.add_argument("--rollback", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.:\[\]-]*", args.serial):
        parser.error("invalid adb serial")
    try:
        if args.dry_run:
            deploy(args, started)
        else:
            lock_path = ROOT / "out/l3-apk.lock"
            lock_path.parent.mkdir(exist_ok=True)
            with lock_path.open("a") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                deploy(args, started)
    except BlockingIOError:
        parser.exit(1, "Another L3 APK operation is running in this checkout\n")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
