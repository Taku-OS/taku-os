#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export PATH="$PREFIX/bin:/system/bin"
export TMPDIR="$PREFIX/tmp"
unset LD_PRELOAD LD_LIBRARY_PATH HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy
rootfs="$HOME/taku-go2/rootfs"
mkdir -p "$HOME/taku-go2"
exec >>"$HOME/taku-go2/runtime.log" 2>&1
termux-wake-lock
trap 'termux-wake-unlock' EXIT
proot --link2symlink -0 -r "$rootfs" -b /dev -b /proc -b /sys \
  -w /opt/taku-go2/tooling/go2-lab /usr/bin/env -i \
  HOME=/root PATH=/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8 \
  /usr/local/bin/python -u phone/runtime.py "$@"
