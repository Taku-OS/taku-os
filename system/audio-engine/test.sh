#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/kotlin-env.sh
mkdir -p system/audio-engine/out/tests
"$KOTLINC" system/audio-engine/contract/org/agentos/audio/*.kt system/audio-engine/client/org/agentos/audio/SpeechChunks.kt system/audio-engine/src/org/agentos/audio/FileSpeechSegments.kt system/audio-engine/src/org/agentos/audio/RouteGate.kt system/audio-engine/src/org/agentos/audio/FeedGate.kt system/audio-engine/src/org/agentos/audio/Uninterruptible.kt system/audio-engine/src/org/agentos/audio/DictationPolicy.kt system/audio-engine/src/org/agentos/audio/PcmLevelMeter.kt system/audio-engine/src/org/agentos/audio/WhisperPcmRanges.kt system/audio-engine/src/org/agentos/audio/WhisperSignalGate.kt system/audio-engine/tests/RuntimeTest.kt system/audio-engine/tests/ConcurrencyTest.kt system/audio-engine/tests/DictationRuntimeTest.kt system/audio-engine/tests/DictationPolicyTest.kt system/audio-engine/tests/FalDictationRealtimeTest.kt -include-runtime -jvm-target 17 -d system/audio-engine/out/tests/runtime.jar
"$JDK/bin/java" -cp system/audio-engine/out/tests/runtime.jar org.agentos.audio.RuntimeTestKt
"$JDK/bin/java" -cp system/audio-engine/out/tests/runtime.jar org.agentos.audio.ConcurrencyTestKt
"$JDK/bin/java" -cp system/audio-engine/out/tests/runtime.jar org.agentos.audio.DictationRuntimeTestKt
"$JDK/bin/java" -cp system/audio-engine/out/tests/runtime.jar org.agentos.audio.DictationPolicyTestKt
"$JDK/bin/java" -cp system/audio-engine/out/tests/runtime.jar org.agentos.audio.FalDictationRealtimeTestKt
