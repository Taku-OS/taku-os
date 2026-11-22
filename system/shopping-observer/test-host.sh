#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out/host-tests
task_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
task_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$task_javac" --release 17 -d out/host-tests core/org/agentos/shopping/*.java src/org/agentos/shopping/VisionAnswer.java tests/host/org/agentos/shopping/*.java
for test in SnapshotParserTest ProductTypeAnswerTest ProductTypeCachePolicyTest ObservationPolicyTest VisionAnswerTest ScreenshotCropTest LiveFrameTargetTest LiveFrameImageCoreTest OutfitPickGeometryTest LiveFrameCapturePolicyTest LiveFrameContinuityTest LiveFrameReadThrottleTest WindowReadDiagnosticTest AdmissionPolicyTest ExplicitTryOnPolicyTest OfferOcclusionGuardTest; do
  "$task_java" -cp out/host-tests "org.agentos.shopping.$test"
done
bash tests/test-observation-history-host.sh
bash tests/test-window-snapshot-host.sh
bash tests/test-reminder-host.sh
python3 tests/test-live-progress-host.py
