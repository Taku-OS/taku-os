#!/usr/bin/env python3
"""Start one existing development AVD with host keyboard input enabled."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess


def enable_keyboard(config):
    text = config.read_text()
    updated, count = re.subn(r"(?m)^\s*hw\.keyboard\s*=.*$", "hw.keyboard=yes", text)
    if not count:
        updated = text.rstrip("\n") + "\nhw.keyboard=yes\n"
    if updated != text:
        backup = config.with_name("config.ini.before-host-keyboard")
        if not backup.exists():
            shutil.copy2(config, backup)
        config.write_text(updated)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--avd", required=True, help="Existing AVD name; other AVDs are untouched")
    parser.add_argument("--port", type=int, required=True, help="Unused even emulator console port")
    parser.add_argument("--configure-only", action="store_true", help="Prepare keyboard for the next cold start")
    parser.add_argument("--writable-system", action="store_true", help="Use a temporary writable system image for development")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.avd) or args.avd in (".", ".."):
        parser.error("Invalid AVD name")
    if args.port % 2 or not 5554 <= args.port <= 5682:
        parser.error("Port must be even and between 5554 and 5682")

    android_home = Path(os.environ.get("ANDROID_USER_HOME", str(Path.home() / ".android")))
    avd_home = Path(os.environ.get("ANDROID_AVD_HOME", str(android_home / "avd")))
    ini = avd_home / (args.avd + ".ini")
    if not ini.is_file():
        parser.error(f"AVD not found: {ini}")
    settings = dict(line.split("=", 1) for line in ini.read_text().splitlines() if "=" in line)
    directory = Path(settings.get("path", str(android_home / settings.get("path.rel", ""))))
    config = directory / "config.ini"
    if not config.is_file():
        parser.error("AVD config.ini not found")

    if args.configure_only:
        enable_keyboard(config)
        print(f"Prepared {args.avd}; cold restart required. No running device was stopped.")
        return

    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        sdk = next((str(p) for p in (Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk") if p.is_dir()), "")
    adb, emulator = Path(sdk) / "platform-tools/adb", Path(sdk) / "emulator/emulator"
    if not adb.is_file() or not emulator.is_file():
        parser.error("Set ANDROID_SDK_ROOT to an SDK with adb and emulator installed")
    devices = subprocess.check_output([str(adb), "devices"], text=True)
    for line in devices.splitlines()[1:]:
        fields = line.split()
        if not fields:
            continue
        serial = fields[0]
        if serial == f"emulator-{args.port}":
            parser.error("Selected port is already in use; no device was changed")
        if serial.startswith("emulator-") and fields[1:] == ["device"]:
            name = subprocess.check_output([str(adb), "-s", serial, "emu", "avd", "name"], text=True).splitlines()[0]
            if name == args.avd:
                parser.error(f"{args.avd} is already running on {serial}; close it before restarting")

    enable_keyboard(config)
    command = [str(emulator), "-avd", args.avd, "-port", str(args.port), "-gpu", "host", "-no-snapshot"]
    if args.writable_system:
        command.append("-writable-system")
    print(f"Starting {args.avd} on emulator-{args.port} with host keyboard enabled", flush=True)
    os.execv(command[0], command)


if __name__ == "__main__":
    main()
