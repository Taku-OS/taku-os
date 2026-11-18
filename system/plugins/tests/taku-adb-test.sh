#!/bin/bash
set -euo pipefail
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
ln -s /bin/sh "$tmp/sh"
ln -s /bin/cp "$tmp/cp"
export TAKU_ADB_BIN_DIR="$tmp"
shim=system/plugins/taku-adb
bash "$shim" version | grep -q 'Version 1.0.0'
bash "$shim" devices | grep -q local
[ "$(bash "$shim" -s local shell printf local)" = local ]
printf example > "$tmp/source"
bash "$shim" pull "$tmp/source" "$tmp/result"
cmp "$tmp/source" "$tmp/result"
if bash "$shim" -s remote devices >/dev/null 2>&1; then exit 1; fi
if bash "$shim" start-server >/dev/null 2>&1; then exit 1; fi
echo 'PASS: optional taku-adb compatibility shim; no server or remote target'
