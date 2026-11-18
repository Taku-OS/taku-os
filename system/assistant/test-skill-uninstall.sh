#!/bin/bash
# Destructive fixture test: use only the dedicated disposable AVD, never a physical device.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
serial=${1:?Provide the dedicated emulator serial}
[[ "$serial" == emulator-* ]] || { echo 'Emulator serial required' >&2; exit 1; }
[[ $("$ADB" -s "$serial" emu avd name | head -1 | tr -d '\r') == Taku_SkillsLifecycle_20261030 ]] || { echo 'Wrong AVD' >&2; exit 1; }
[[ $("$ADB" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r') == 36 ]] || exit 1
[[ $("$ADB" -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r') == arm64-v8a ]] || exit 1
out=out/skill-uninstall-tests
mkdir -p "$out/classes" "$out/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:system/assistant/out/broker-classes" -d "$out/classes" \
    system/assistant/tests/org/agentos/lab/SkillUninstallTest.java \
    system/assistant/tests/org/agentos/lab/SkillUninstallRecoveryTest.java \
    system/assistant/tests/org/agentos/lab/SkillCommunityUninstallTest.java \
    system/assistant/tests/org/agentos/lab/SkillVersionTest.java \
    system/assistant/tests/org/agentos/lab/SkillCredentialsTest.java
"$JDK/bin/jar" cf "$out/classes.jar" -C "$out/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$out/dex" "$out/classes.jar"
"$JDK/bin/jar" cf "$out/test.jar" -C "$out/dex" classes.dex
"$ADB" -s "$serial" push "$out/test.jar" /data/local/tmp/skill-uninstall-tests.jar
for test in SkillUninstallRecoveryTest SkillUninstallTest SkillCommunityUninstallTest SkillVersionTest SkillCredentialsTest; do
    "$ADB" -s "$serial" shell "CLASSPATH=/system_ext/framework/agentos-task-broker.jar:/data/local/tmp/skill-uninstall-tests.jar app_process /system/bin org.agentos.lab.$test" | tee "$out/$test.log"
    grep -q '^PASS:' "$out/$test.log"
done
