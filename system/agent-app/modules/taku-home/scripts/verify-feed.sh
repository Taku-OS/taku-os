#!/usr/bin/env bash
# Scoped acceptance: preserves existing cards, pins, desktop layout and widget data.
set -euo pipefail
SERIAL=${1:?usage: verify-feed.sh SERIAL [APK]}
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
source "$ROOT/../../scripts/android-env.sh"
if [ -n "${2:-}" ]; then "$ADB" -s "$SERIAL" install --no-incremental -r "$2"; fi
python3 "$(dirname "$0")/verify-feed.py" --serial "$SERIAL" --adb "$ADB" \
  --output "$ROOT/out/home-feed-verify/$(date +%Y%m%d-%H%M%S)"
