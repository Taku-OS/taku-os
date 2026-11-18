#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../../../.."
source scripts/android-env.sh
source scripts/kotlin-env.sh
MODULE=system/agent-app/modules/taku-home
OUT=system/agent-app/out/voice-session-probe
HOME_CLASSES="$MODULE/android/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar"
[ -f "$HOME_CLASSES" ] || { echo 'Run :taku-home:testDebugUnitTest first.' >&2; exit 1; }
COROUTINES=$(python3 - <<'PY'
from pathlib import Path
p = Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2'
files = list(p.glob('*/*.jar'))
if len(files) != 1: raise SystemExit('Expected the resolved coroutines 1.10.2 JAR')
print(files[0])
PY
)
mkdir -p "$OUT/dex"
"$KOTLINC" "$MODULE/tests/VoiceSessionProbe.kt" "$MODULE/tests/VoiceBackgroundProbe.kt" "$MODULE/tests/ReviewRegressionProbe.kt" "$MODULE/tests/AppBuildIslandProbe.kt" "$MODULE/tests/NativeIslandProbe.kt" "$MODULE/tests/VoiceReplyProbe.kt" "$MODULE/tests/RecentsReviewProbe.kt" -jvm-target 17 -classpath "$ANDROID_JAR:$HOME_CLASSES:$COROUTINES" -d "$OUT/classes.jar"
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --classpath "$HOME_CLASSES" --classpath "$COROUTINES" --classpath "$KOTLIN_LIB/kotlin-stdlib.jar" --output "$OUT/dex" "$OUT/classes.jar"
"$TOOLS/aapt2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" --manifest "$MODULE/tests/AndroidManifest.xml"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" sign --key out/development-signing/platform.pk8 --cert out/development-signing/platform.x509.pem --out "$OUT/VoiceSessionTests.apk" "$OUT/aligned.apk"
echo "Built $OUT/VoiceSessionTests.apk; no device changed."
