#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Production protocol, YUV/lease/renderer and geometry with fake data/time only.
# No SDK, dependency download, Android runtime, camera, credentials or cloud.
set -euo pipefail
MODULE_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$MODULE_ROOT/../.." && pwd)"
JDK="${JAVA_HOME:-}"
if [ -z "$JDK" ] && [ -x /usr/libexec/java_home ]; then JDK=$(/usr/libexec/java_home -v 17+ 2>/dev/null || true); fi
if [ -z "$JDK" ]; then JDK="$(dirname "$(dirname "$(command -v javac)")")"; fi
OUT="$REPO_ROOT/out/tryon/host-tests"
mkdir -p "$OUT"
SRC="$MODULE_ROOT/app/src/org/taku/tryon/realtime"
SOURCES=("$MODULE_ROOT/app/src/org/taku/tryon/api/ApiArguments.java" "$MODULE_ROOT/app/src/org/taku/tryon/api/CredentialWriteGate.java" "$MODULE_ROOT/contract/org/taku/cosplay/api/CosplayContract.java")
for name in CosplayPolicy CosplayRequestStore LegacyWire StartAdmission AttemptOwner FalRealtimeProfile FalSignalCodec FalSignalPolicy FalModelUpdate RtcPixelConversion CallTryOnBridge TryOnFrameBridge LocalShoppingFrameBridge DirectPreviewRenderer YuvPlanePacking CameraFrameLease TakuPreviewGeometry LucyCharacterReference ShoppingIslandGeometry ShoppingPreviewGeometry ShoppingPreviewLayout ShoppingPreviewMotion ShoppingPreviewReveal XmaxStartupDeadlines; do SOURCES+=("$SRC/$name.java"); done
find "$MODULE_ROOT/tests/host" -name '*.java' > "$OUT/fixtures.txt"
"$JDK/bin/javac" --release 17 -encoding UTF-8 -d "$OUT" "${SOURCES[@]}" @"$OUT/fixtures.txt"
for name in CosplayPolicyStoreTest LegacyWireTest StartAdmissionTest AttemptOwnerTest RtcProtocolTest FalModelUpdateTest DirectShoppingBridgeTest DirectCameraFramesTest DirectPreviewRendererTest TakuPreviewGeometryTest LucyCharacterReferenceTest ShoppingPreviewGeometryTest ShoppingPreviewLayoutTest ShoppingPreviewMotionTest ShoppingPreviewRevealTest XmaxStartupDeadlinesTest; do
  "$JDK/bin/java" -cp "$OUT" "org.taku.tryon.realtime.$name"
done

"$JDK/bin/java" -cp "$OUT" org.taku.tryon.api.ApiArgumentsTest
"$JDK/bin/java" -cp "$OUT" org.taku.tryon.api.CredentialWriteGateTest
python3 "$MODULE_ROOT/tests/check-boundaries.py"

python3 "$MODULE_ROOT/tests/presentation/test-service-control.py"
python3 "$MODULE_ROOT/tests/presentation/test-move-api.py"
python3 "$MODULE_ROOT/tests/presentation/test-result-island.py"
python3 "$MODULE_ROOT/tests/api-pipes/test-credential-pipes.py"
python3 "$MODULE_ROOT/tests/character/test-direct-cosplay-presentation.py"
python3 "$MODULE_ROOT/tests/character/test-reference-deadline.py"
python3 "$MODULE_ROOT/tests/character/test-character-import-binding.py"
