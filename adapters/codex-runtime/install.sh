#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
SERIAL=${TAKU_SERIAL:?Set TAKU_SERIAL to the authorized experimental AVD serial}
AVD_NAME=$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')
[ "$AVD_NAME" = AgentOS_ImageCheck ] || { echo 'Wrong emulator'; exit 1; }
test -s downloads/codex.tar.gz && test -s downloads/code-mode-host.tar.gz || { echo "Run bootstrap.sh first" >&2; exit 1; }
CA_BUNDLE=${TAKU_CA_BUNDLE:-/etc/ssl/cert.pem}
test -s "$CA_BUNDLE" || { echo "Set TAKU_CA_BUNDLE to a trusted CA bundle" >&2; exit 1; }
"$ADB" -s "$SERIAL" root
"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" remount
"$ADB" -s "$SERIAL" shell 'mkdir -p /system_ext/bin /system_ext/etc/codex /data/local/agentos/home/.codex /data/local/agentos/work; chmod 700 /data/local/agentos/home /data/local/agentos/home/.codex'
"$ADB" -s "$SERIAL" push codex /system_ext/bin/codex
"$ADB" -s "$SERIAL" push downloads/codex.tar.gz /system_ext/etc/codex/codex.tar.gz
"$ADB" -s "$SERIAL" push downloads/code-mode-host.tar.gz /system_ext/etc/codex/code-mode-host.tar.gz
"$ADB" -s "$SERIAL" push "$CA_BUNDLE" /system_ext/etc/codex/cacert.pem
"$ADB" -s "$SERIAL" shell 'chmod 755 /system_ext/bin/codex; chmod 644 /system_ext/etc/codex/*; printf "nameserver 10.0.2.3\n" > /system/etc/resolv.conf; chmod 644 /system/etc/resolv.conf; restorecon -R /system_ext/bin/codex /system_ext/etc/codex /system/etc/resolv.conf; codex --version'
echo 'Program installed. Credentials are intentionally not part of this installer.'
