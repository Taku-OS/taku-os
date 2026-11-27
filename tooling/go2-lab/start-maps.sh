#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
cache=${TAKU_GO2_CACHE:-"$HOME/Library/Caches/taku-go2"}
exec "$cache/venv/bin/python" -u amap_service.py "$@"
