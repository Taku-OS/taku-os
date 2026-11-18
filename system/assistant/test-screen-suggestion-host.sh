#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
out="$PWD/out/screen-suggestion-host"
mkdir -p "$out/classes"
cp="$PWD/out/ime-replies-host/json-20240303.jar"
if [ ! -f "$cp" ]; then bash system/assistant/test-ime-replies-host.sh; fi
"$JDK/bin/javac" --release 17 -cp "$cp" -d "$out/classes" \
 system/assistant/src/org/agentos/assistant/ScreenSuggestionJson.java \
 system/assistant/tests/org/agentos/assistant/ScreenSuggestionJsonTest.java \
 system/assistant/broker/org/agentos/lab/CleanupWork.java \
 system/assistant/broker/org/agentos/lab/PhoneAskUser.java \
 system/assistant/tests/org/agentos/lab/PhoneAskUserTest.java \
 system/assistant/broker/org/agentos/lab/PhoneGestureSession.java \
 system/assistant/tests/org/agentos/lab/PhoneGestureSessionTest.java
"$JDK/bin/java" -cp "$out/classes:$cp" org.agentos.assistant.ScreenSuggestionJsonTest
"$JDK/bin/java" -cp "$out/classes:$cp" org.agentos.lab.PhoneAskUserTest
"$JDK/bin/java" -cp "$out/classes:$cp" org.agentos.lab.PhoneGestureSessionTest
