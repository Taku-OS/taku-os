#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.dirname(os.path.realpath(sys.argv[1]))))' "$0")"
with_diagnostics=false
with_tryon=false
for option in "$@"; do
  case "$option" in
    --with-diagnostics) with_diagnostics=true ;;
    --with-tryon) with_tryon=true ;;
    *) echo 'Usage: bash scripts/build-android.sh [--with-diagnostics] [--with-tryon]' >&2; exit 2 ;;
  esac
done
for script in system/audio-engine/build.sh system/assistant/build.sh system/display/build.sh system/overlay/build.sh system/surfaces/build.sh system/proactivity/build.sh system/memory/build.sh tooling/app-kit/build-shell.sh; do
  bash "$script"
done
if "$with_diagnostics"; then bash tooling/diagnostics/build.sh; fi
if "$with_tryon"; then bash system/tryon/app/build.sh; fi
printf '%s\n' 'Built Android development components. No install, flash, login or ROM build performed.'
