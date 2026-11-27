#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if [ "$#" -ne 0 ]; then
  echo 'Simulation accepts no robot address or other arguments.' >&2
  exit 1
fi
cache=${TAKU_GO2_CACHE:-"$HOME/Library/Caches/taku-go2"}
exec "$cache/venv/bin/python" -u simulator.py
