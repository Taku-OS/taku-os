#!/usr/bin/env bash
# Host-only policy regression; requires JDK 17+, Kotlin and cached JUnit 4.13.2.
set -euo pipefail
MODULE=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$MODULE/../../../.." && pwd)
source "$ROOT/scripts/android-env.sh"
source "$ROOT/scripts/compile-jvm.sh"
CP=$(python3 - <<'PYTHON'
import os
from pathlib import Path
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
jars = []
for module in ['junit/junit/4.13.2', 'org.hamcrest/hamcrest-core/1.3', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2', 'org.json/json/20250517']:
    matches = list((cache / module).glob('*/*.jar'))
    if not matches:
        raise SystemExit('Missing cached dependency: ' + module + '; run the Android build first.')
    jars.append(str(matches[0]))
print(':'.join(jars))
PYTHON
)
CLASSES="$ROOT/out/voice-interaction-tests"
compile_jvm "$CLASSES" "$CP:$ANDROID_JAR" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/assistant/VoiceGesture.kt" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/assistant/SystemAssistCapture.kt" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/assistant/VoiceInput.kt" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/assistant/EngineVoiceInput.kt" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/assistant/VoiceDelivery.kt" \
  "$ROOT/system/audio-engine/client/org/agentos/audio/AudioClient.kt" \
  "$ROOT/system/audio-engine/contract/org/agentos/audio/AudioContract.kt" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/assistant/LiveTalkEndpoint.kt" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/assistant/SessionThreadCreation.kt" \
  "$MODULE/android/src/test/java/org/agentos/agent/home/SessionThreadCreationTest.kt" \
  "$MODULE/android/src/test/java/org/agentos/agent/home/LiveTalkEndpointTest.kt" \
  "$MODULE/android/src/test/java/org/agentos/agent/home/VoiceGestureTest.kt" \
  "$MODULE/android/src/test/java/org/agentos/agent/home/VoiceDeliveryTest.kt" \
  "$MODULE/android/src/test/java/org/agentos/agent/home/SystemAssistCaptureTest.kt"
"$JDK/bin/java" -cp "$CLASSES:$CP:$ANDROID_JAR:$KOTLIN_LIB/kotlin-stdlib.jar" \
  org.junit.runner.JUnitCore org.agentos.agent.home.VoiceGestureTest org.agentos.agent.home.VoiceDeliveryTest org.agentos.agent.home.LiveTalkEndpointTest org.agentos.agent.home.SessionThreadCreationTest org.agentos.agent.home.SystemAssistCaptureTest
