#!/bin/bash
# Opt-in Android mksh regression. Only creates/removes its own temporary lock file.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
[ "$#" -eq 1 ] || { echo 'Usage: bash system/display/test-lifecycle-lock-device.sh <adb-serial>' >&2; exit 2; }
"$ADB" -s "$1" shell sh -s <<'SH'
probe=/data/local/tmp/taku-flock-probe-$$
trap 'rm -f "$probe"' EXIT
exec 9>"$probe"
# Persistent descriptors opened by Android mksh require explicit inheritance.
/system/bin/flock -x -n 9 9>&9 || exit 10
/system/bin/sh -c 'exec 8>"$1"; /system/bin/flock -x -n 8 8>&8' probe-contender "$probe"
held_result=$?
echo "contender_while_held=$held_result"
[ "$held_result" -eq 1 ] || exit 11
exec 9>&-
/system/bin/sh -c 'exec 8>"$1"; /system/bin/flock -x -n 8 8>&8' probe-contender "$probe"
released_result=$?
echo "contender_after_release=$released_result"
[ "$released_result" -eq 0 ] || exit 12
echo 'Android mksh lifecycle lock probe passed'
SH
