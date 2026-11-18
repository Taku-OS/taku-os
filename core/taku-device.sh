#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# On-phone CLI facade: uses the same probes as the host SDK, no Node requirement.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
method=${1:-}
case "$method" in system.info|displays.status|tasks.status) :;; *) printf '{"protocol":"taku/0.1","type":"response","id":"device-cli","ok":false,"error":{"code":"METHOD_NOT_FOUND","message":"Unknown method","retryable":false}}\n'; exit 2;; esac
if result=$(sh "$root/device-probe.sh" "$method"); then
 printf '{"protocol":"taku/0.1","type":"response","id":"device-cli","ok":true,"result":%s}\n' "$result"
else
 rc=$?; printf '{"protocol":"taku/0.1","type":"response","id":"device-cli","ok":false,"error":%s}\n' "$result"; exit "$rc"
fi
