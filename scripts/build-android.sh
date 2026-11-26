#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.dirname(os.path.realpath(sys.argv[1]))))' "$0")"
if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != --with-diagnostics ]; }; then
  echo 'Usage: bash scripts/build-android.sh [--with-diagnostics]' >&2; exit 2
fi
for script in system/audio-engine/build.sh system/assistant/build.sh system/display/build.sh system/overlay/build.sh system/surfaces/build.sh system/proactivity/build.sh system/memory/build.sh system/notebook/build.sh tooling/app-kit/build-shell.sh; do
  bash "$script"
done
if [ "${1:-}" = --with-diagnostics ]; then bash tooling/diagnostics/build.sh; fi
printf '%s\n' 'Built Android development components. No install, flash, login or ROM build performed.'
