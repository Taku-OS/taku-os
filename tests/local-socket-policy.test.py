#!/usr/bin/env python3
"""Run the component-local policy methods on the JVM without Android services."""
import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def java_gate(path, binding):
    source = (ROOT / path).read_text()
    assert binding in source, f"Policy is no longer wired into {path}"
    return re.search(r"    static boolean isCallerAuthorized\(.*?\n    }", source, re.S)[0]


display = java_gate(
    "system/display/src/org/agentos/lab/VirtualDisplayHost.java",
    "if(!isCallerAuthorized(uid)){client.close();continue;}",
)
plugins = java_gate(
    "system/plugins/android/org/taku/plugins/host/AndroidServer.java",
    "if (!isCallerAuthorized(peerUid, AndroidServer::hasRootPermission))",
)
audio = (ROOT / "system/audio-engine/src/org/agentos/audio/AudioSocket.kt").read_text()
assert "val uid=socket.peerCredentials.uid" in audio
audio_gate = re.search(r"socket.use \{ local ->\s+if\(([^\n]+)\)return", audio)[1]

with tempfile.TemporaryDirectory(prefix="taku-socket-policy-") as directory:
    root = Path(directory)
    (root / "SocketPolicyTest.java").write_text("""
public class SocketPolicyTest {
    static class Display {
""" + display + """
    }
    static class Plugins {
""" + plugins + """
    }
    public static void main(String[] args) {
        for (int uid : new int[]{0, 1000, 2000}) {
            if (!Display.isCallerAuthorized(uid)) throw new AssertionError("Display " + uid);
            if (!Plugins.isCallerAuthorized(uid, ignored -> {
                throw new AssertionError("Privileged Plugins caller queried provider");
            })) throw new AssertionError("Plugins " + uid);
        }
        for (int uid : new int[]{-1, 1001, 9999, 10000, 110042}) {
            if (Display.isCallerAuthorized(uid)) throw new AssertionError("Display " + uid);
            if (Plugins.isCallerAuthorized(uid, ignored -> false)) throw new AssertionError("Plugins " + uid);
        }
        if (Plugins.isCallerAuthorized(9999, ignored -> {
            throw new AssertionError("Non-app Plugins caller queried provider");
        })) throw new AssertionError("Unexpected system caller");
        if (!Plugins.isCallerAuthorized(10000, uid -> uid == 10000)) {
            throw new AssertionError("Plugins denied granted app");
        }
        System.out.println("Display and Plugins socket policy tests passed");
    }
}
""")
    (root / "AudioSocketPolicyTest.kt").write_text("fun isCallerAuthorized(uid: Int): Boolean = !(" + audio_gate + ")\n" + """

fun main() {
    for (uid in listOf(0, 2000)) check(isCallerAuthorized(uid)) { "Audio denied UID $uid" }
    for (uid in listOf(-1, 1000, 1001, 10000)) check(!isCallerAuthorized(uid)) {
        "Audio allowed UID $uid"
    }
    println("AudioSocket policy tests passed")
}
""")
    subprocess.run(
        ["bash", "-ec", '''
source scripts/android-env.sh
source scripts/compile-jvm.sh
compile_jvm "$1/classes" "" "$1/SocketPolicyTest.java" "$1/AudioSocketPolicyTest.kt"
"$JDK/bin/java" -cp "$1/classes" SocketPolicyTest
"$JDK/bin/java" -cp "$1/classes:$KOTLIN_LIB/kotlin-stdlib.jar" AudioSocketPolicyTestKt
''', "socket-policy", directory],
        cwd=ROOT, env=os.environ, check=True,
    )
