#!/bin/bash
# Source from host-side component scripts. SDK and JDK remain external tools.
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -d "$candidate" ]; then SDK="$candidate"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || { echo 'Set ANDROID_SDK_ROOT to an Android SDK.' >&2; return 1; }
JDK="${JAVA_HOME:-}"
if [ -z "$JDK" ] && [ -x /usr/libexec/java_home ]; then JDK=$(/usr/libexec/java_home -v 17+ 2>/dev/null || true); fi
if [ -z "$JDK" ] && command -v brew >/dev/null 2>&1; then
  candidate="$(brew --prefix openjdk@21 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home"
  [ ! -x "$candidate/bin/javac" ] || JDK="$candidate"
fi
if [ -z "$JDK" ] && command -v javac >/dev/null 2>&1; then
  JDK=$(python3 -c 'import os,shutil;print(os.path.dirname(os.path.dirname(os.path.realpath(shutil.which("javac")))))')
fi
[ -x "$JDK/bin/javac" ] || { echo 'Set JAVA_HOME to JDK 17 or newer.' >&2; return 1; }
JAVA="$JDK"; export JAVA_HOME="$JDK"
TOOLS="$SDK/build-tools/${TAKU_BUILD_TOOLS:-36.0.0}"
ANDROID_JAR="$SDK/platforms/android-${TAKU_ANDROID_API:-36}/android.jar"
ADB="${ADB:-$SDK/platform-tools/adb}"
[ -f "$ANDROID_JAR" ] && [ -x "$TOOLS/aapt2" ] || { echo 'Install SDK platform android-36 and build-tools 36.0.0 (or set TAKU_ANDROID_API/TAKU_BUILD_TOOLS).' >&2; return 1; }
