#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
cache=${TAKU_GO2_CACHE:-"$HOME/Library/Caches/taku-go2"}
mkdir -p "$cache"
if [ ! -x "$cache/venv/bin/python" ]; then uv venv "$cache/venv" --python 3.12; fi
uv pip install --python "$cache/venv/bin/python" -r requirements.txt
uv pip install --python "$cache/venv/bin/python" --no-deps 'unitree_webrtc_connect @ git+https://github.com/legion1581/unitree_webrtc_connect.git@e0abc5780761539eff89a382a79319e5cf6ad1f4'
