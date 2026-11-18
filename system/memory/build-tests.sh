#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
MARKDOWN_CP=$(python3 ../../scripts/prepare-markdown.py)
IFS=: read -r -a MARKDOWN_JARS <<< "$MARKDOWN_CP"
OUTPUT=out/tests
rm -rf "$OUTPUT/classes" "$OUTPUT/dex"
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex" "$OUTPUT/generated" "$OUTPUT/ort"
"$TOOLS/aapt2" compile --dir res -o "$OUTPUT/resources.zip"
"$TOOLS/aapt2" link -o "$OUTPUT/resources.apk" -I "$ANDROID_JAR" --manifest tests/AndroidManifest.xml --custom-package org.agentos.memory --java "$OUTPUT/generated" "$OUTPUT/resources.zip"
# Storage tests compile real store, delta, retrieval, organization, privacy, and presentation code; observation/analysis jobs use fixture stand-ins.
ORT_AAR=${TAKU_ORT_AAR:-downloads/onnxruntime-android-1.29.0.aar}
unzip -q -o -j "$ORT_AAR" classes.jar -d "$OUTPUT/ort"
DEBUG_HOOKS=false; [ "${TAKU_DEBUG_HOOKS:-0}" = 1 ] && DEBUG_HOOKS=true
printf 'package org.agentos.memory;\nfinal class BuildFlags{static final boolean DEBUG=%s;}\n' "$DEBUG_HOOKS" > "$OUTPUT/generated/BuildFlags.java"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$MARKDOWN_CP:$OUTPUT/ort/classes.jar" -d "$OUTPUT/classes" "$OUTPUT/generated/org/agentos/memory/R.java" "$OUTPUT/generated/BuildFlags.java" src/org/agentos/memory/{MemoryStore,MemoryResult,MemoryContent,MemoryCopy,Delta,Recall,Embedder,Organizer,Privacy,BrokerClient,MemoryMaintenance,Proactive,FeedCards,FeedCheck,FeedFeedback}.java ../ai/client/org/agentos/ai/AiClient.java tests/fixture/*.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar" "${MARKDOWN_JARS[@]}" "$OUTPUT/ort/classes.jar"
cp "$OUTPUT/resources.apk" "$OUTPUT/unsigned.apk"
(cd "$OUTPUT/dex" && zip -q ../unsigned.apk classes.dex)
python3 ../../scripts/package-markdown-resources.py "$OUTPUT/unsigned.apk"
"$TOOLS/zipalign" -f 4 "$OUTPUT/unsigned.apk" "$OUTPUT/aligned.apk"
if [ ! -f "$OUTPUT/untrusted.jks" ]; then "$JDK/bin/keytool" -genkeypair -keystore "$OUTPUT/untrusted.jks" -storepass development-only -keypass development-only -alias test -dname CN=TakuSyntheticUntrusted -keyalg RSA -validity 3650 -noprompt >/dev/null 2>&1; fi
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --ks "$OUTPUT/untrusted.jks" --ks-pass pass:development-only --out "$OUTPUT/TakuMemoryTests.apk" "$OUTPUT/aligned.apk"
# Home Feed stand-in shares the real authority, only for emulators without Home. Uninstall it before installing real Home.
FEED=$OUTPUT/homefeed
rm -rf "$FEED" && mkdir -p "$FEED/classes" "$FEED/dex"
"$TOOLS/aapt2" link -o "$FEED/unsigned.apk" -I "$ANDROID_JAR" --manifest tests/homefeed/AndroidManifest.xml
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$FEED/classes" tests/homefeed/HomeFeedStub.java
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$FEED/dex" $(find "$FEED/classes" -name '*.class')
(cd "$FEED/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$FEED/unsigned.apk" "$FEED/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --ks "$OUTPUT/untrusted.jks" --ks-pass pass:development-only --out "$OUTPUT/TakuHomeFeedStub.apk" "$FEED/aligned.apk"
printf '%s\n' 'Built isolated SQLite tests, untrusted synthetic UI fixture and home feed stand-in; not part of the product APK.'
