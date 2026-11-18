#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/phone-draft-host"
mkdir -p "$task_out/classes" "$task_out/android-classes"
"$JDK/bin/javac" --release 17 -d "$task_out/classes" \
  system/assistant/broker/org/agentos/lab/PhoneDraftTarget.java \
  system/assistant/tests/org/agentos/lab/PhoneDraftTargetTest.java
"$JDK/bin/java" -cp "$task_out/classes" org.agentos.lab.PhoneDraftTargetTest
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$task_out/android-classes" \
  system/assistant/broker/org/agentos/lab/PhoneDraftTarget.java \
  system/assistant/broker/org/agentos/lab/PhoneDraftHandoff.java \
  system/assistant/tests/org/agentos/lab/PhoneDraftRootAvailabilityTest.java
"$JDK/bin/java" -cp "$task_out/android-classes:$ANDROID_JAR" org.agentos.lab.PhoneDraftRootAvailabilityTest
echo 'Foreground draft host tests and Android adapter compilation passed; no device was changed.'
