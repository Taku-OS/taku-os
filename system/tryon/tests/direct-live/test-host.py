#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compile the current LIVE Activity and service startup methods against deterministic doubles.

No Android SDK/runtime, dependency download, camera, credential, socket or model call.
The service harness supplies only framework/window/lifecycle fields; startupState and
cancelStartup are copied verbatim from the current production source at test time.
"""
from pathlib import Path
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
MODULE = HERE.parents[1]
SOURCE = MODULE / "app/src/org/taku/tryon/realtime"
CONTRACT = MODULE / "contract/org/taku/tryon/contract/LiveTryOnContract.java"
JAVA_HOME = os.environ.get("JAVA_HOME")
JAVAC = str(Path(JAVA_HOME) / "bin/javac") if JAVA_HOME else "javac"
JAVA = str(Path(JAVA_HOME) / "bin/java") if JAVA_HOME else "java"


def compile_and_run(directory, sources, name):
    subprocess.run([JAVAC, "--release", "17", "-encoding", "UTF-8", "-d", str(directory),
                    *(str(path) for path in sources)], check=True)
    subprocess.run([JAVA, "-cp", str(directory), "org.taku.tryon.realtime." + name],
                   check=True, timeout=20)


with tempfile.TemporaryDirectory(prefix="taku-live-entry-host-") as temporary:
    compile_and_run(Path(temporary), [
        *sorted((HERE / "activity-stubs").rglob("*.java")),
        HERE / "LiveEntryTest.java", SOURCE / "DirectLiveTryOnActivity.java", CONTRACT,
    ], "LiveEntryTest")

service = (SOURCE / "CallTryOnService.java").read_text()
start = service.index("    static final int STARTUP_PENDING=")
end = service.index("    static boolean control(", start)
with tempfile.TemporaryDirectory(prefix="taku-startup-ack-host-") as temporary:
    directory = Path(temporary)
    harness = directory / "CallTryOnService.java"
    harness.write_text((HERE / "ServiceHost.java.template").read_text()
                       .replace("@@STARTUP_METHODS@@", service[start:end]))
    compile_and_run(directory, [
        *sorted((HERE / "service-stubs").rglob("*.java")),
        SOURCE / "StartAdmission.java", harness, HERE / "StartupAckTest.java",
    ], "StartupAckTest")

# The manifest prevents the opaque starting window before Activity.onCreate runs.
android = "{http://schemas.android.com/apk/res/android}"
application = ET.parse(MODULE / "app/AndroidManifest.xml").getroot().find("application")
activities = [activity for activity in application.findall("activity")
              if activity.get(android + "name", "").endswith(".DirectLiveTryOnActivity")]
assert len(activities) == 1, "Exactly one LIVE entry is required"
activity = activities[0]
assert activity.get(android + "theme") == "@android:style/Theme.Translucent.NoTitleBar"
assert activity.get(android + "excludeFromRecents") == "true"
assert activity.get(android + "exported") == "true"
print("LIVE entry manifest starting-window checks: 4")
