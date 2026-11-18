#!/bin/bash
# Component installer for the explicitly selected, already configured development AVD.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
export ADB_SERVER_SOCKET="${ADB_SERVER_SOCKET:-tcp:5037}"
: "${TAKU_SERIAL:?Select the development emulator with TAKU_SERIAL}"
[[ "$("$ADB" -s "$TAKU_SERIAL" emu avd name | head -1 | tr -d '\r')" == Taku_ImageIntegration_20261010 ]] || exit 1
bash adapters/magpie-runtime/bootstrap.sh
"$ADB" -s "$TAKU_SERIAL" root
"$ADB" -s "$TAKU_SERIAL" wait-for-device
"$ADB" -s "$TAKU_SERIAL" remount
"$ADB" -s "$TAKU_SERIAL" push adapters/magpie-runtime/downloads/magpie-arm64 /system_ext/bin/magpie-runtime
"$ADB" -s "$TAKU_SERIAL" push adapters/magpie-runtime/magpie /system_ext/bin/magpie
"$ADB" -s "$TAKU_SERIAL" push adapters/magpie-runtime/taku-magpie.rc /system_ext/etc/init/taku-magpie.rc
"$ADB" -s "$TAKU_SERIAL" push adapters/magpie-runtime/LICENSE /system_ext/etc/magpie-LICENSE
"$ADB" -s "$TAKU_SERIAL" shell 'chmod 755 /system_ext/bin/magpie /system_ext/bin/magpie-runtime; chmod 644 /system_ext/etc/init/taku-magpie.rc /system_ext/etc/magpie-LICENSE; restorecon /system_ext/bin/magpie /system_ext/bin/magpie-runtime /system_ext/etc/init/taku-magpie.rc; magpie --version'
echo 'Installed public gateway only. Reboot loads its init service. Device settings and accounts are preserved.'
