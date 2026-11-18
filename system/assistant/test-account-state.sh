#!/bin/bash
# Protocol-only guards and real IPC permissions; this never completes OAuth or a model turn.
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
SERIAL=emulator-5634
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = Taku_ImageIntegration_20261010 ] || exit 1
OUTPUT=../../out/agent-account-tests
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/classes" broker/org/agentos/lab/AgentAccount.java tests/org/agentos/lab/AccountStateTest.java tests/org/agentos/tests/AccountBridgeProbe.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
"$ADB" -s "$SERIAL" push "$OUTPUT/test.jar" /data/local/tmp/taku-account-tests.jar
"$ADB" -s "$SERIAL" shell 'CLASSPATH=/data/local/tmp/taku-account-tests.jar app_process /system/bin org.agentos.lab.AccountStateTest' | tee "$OUTPUT/protocol.txt"
"$ADB" -s "$SERIAL" shell 'CLASSPATH=/data/local/tmp/taku-account-tests.jar app_process /system/bin org.agentos.tests.AccountBridgeProbe' > "$OUTPUT/ipc-root.json"
if "$ADB" -s "$SERIAL" shell 'su 2000 /system/bin/sh -c "CLASSPATH=/data/local/tmp/taku-account-tests.jar app_process /system/bin org.agentos.tests.AccountBridgeProbe"' > "$OUTPUT/ipc-shell.txt"; then
 echo 'FAIL: ordinary shell must not read account challenge'; exit 1
fi
rg --threads 2 -q 'Account endpoint unavailable or access denied.' "$OUTPUT/ipc-shell.txt"
echo 'PASS: real root endpoint verified; UID2000 denied. No login artifacts exported.'
