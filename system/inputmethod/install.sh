#!/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later
set -euo pipefail
cd "$(dirname "$0")/../.."
export TAKU_BUILD_TOOLS="${TAKU_BUILD_TOOLS:-36.1.0}"
source scripts/android-env.sh
[ "$#" -eq 1 ] || { echo 'Usage: install.sh DEVICE_SERIAL' >&2; exit 2; }
serial="$1"
abi="$("$ADB" -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in arm64-v8a|x86_64) ;; *) echo "Unsupported input method ABI: $abi" >&2; exit 2;; esac
"$ADB" -s "$serial" install -r "out/inputmethod/TakuInputMethod-$abi.apk"
# Installation does not silently replace a person's selected keyboard.
"$ADB" -s "$serial" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n org.agentos.inputmethod/org.fcitx.fcitx5.android.ui.main.MainActivity
