#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Lab-only harness. Core app source/build/install execute inside Android.
set -euo pipefail
ADB="${ANDROID_ADB:-adb}"
SERIAL=emulator-5582
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
EVIDENCE="${TAKU_EVIDENCE_DIR:-$HERE/../../out/scenarios/$(date -u +%Y%m%dT%H%M%SZ)}"
DEVICE=/data/local/tmp/taku-os-scenarios-20261010
test "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = AgentOS_ImageCheck
mkdir -p "$EVIDENCE"
"$ADB" -s "$SERIAL" shell mkdir -p "$DEVICE"
"$ADB" -s "$SERIAL" push "$HERE/phone-codex-prompt.txt" "$DEVICE/phone-codex-prompt.txt"
date -u +%Y-%m-%dT%H:%M:%SZ > "$EVIDENCE/generation-start.utc"
# The only uploaded input is the test request. The phone model writes app code.
"$ADB" -s "$SERIAL" shell 'cd /data/local/tmp/taku-os-scenarios-20261010 && /system_ext/bin/codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -C /data/local/tmp/taku-os-scenarios-20261010 --json -o model-final.txt - < phone-codex-prompt.txt > phone-model-events.jsonl 2>&1; rc=$?; echo "$rc" > phone-model.exit; exit "$rc"'
date -u +%Y-%m-%dT%H:%M:%SZ > "$EVIDENCE/generation-finish.utc"
"$ADB" -s "$SERIAL" pull "$DEVICE/phone-model-events.jsonl" "$EVIDENCE/phone-model-events.jsonl"
"$ADB" -s "$SERIAL" pull "$DEVICE/model-final.txt" "$EVIDENCE/model-final.txt"
"$ADB" -s "$SERIAL" pull "$DEVICE/phone-model.exit" "$EVIDENCE/phone-model.exit"
