#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Host checks for Live Talk provider defaults and the MOSS-Live key view; no device or network key.
set -euo pipefail
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
source ../../scripts/compile-jvm.sh
out="$PWD/out/live-provider-host"
rm -rf "$out/classes"; mkdir -p "$out/classes"
python3 - "$out" <<'PY'
import hashlib,pathlib,sys,urllib.request
p=pathlib.Path(sys.argv[1])/'json-20240303.jar'
digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=digest:
 b=urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
 assert hashlib.sha256(b).hexdigest()==digest
 p.write_bytes(b)
PY
compile_jvm "$out/classes" "$ANDROID_JAR" broker/org/agentos/lab/*.java broker/org/agentos/lab/*.kt \
 ../ai/client/org/agentos/ai/NotebookSocialSource.java ../../tooling/app-kit/apk-shell/src/org/agentos/runtime/AppApiClient.java \
 tests/live-provider/org/agentos/lab/LiveProviderDefaultsTest.java
# The pinned org.json jar precedes android.jar so JSON runs for real; Android classes stay unused stubs.
"$JDK/bin/java" -cp "$out/classes:$out/json-20240303.jar:$KOTLIN_LIB/kotlin-stdlib.jar:$ANDROID_JAR" org.agentos.lab.LiveProviderDefaultsTest
