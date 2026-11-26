#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
cache=${TAKU_GO2_CACHE:-"$HOME/Library/Caches/taku-go2"}
python=${GO2_PYTHON:-"$cache/venv/bin/python"}
if [ ! -x "$python" ]; then
  echo 'Run tooling/go2-lab/setup.sh first, or set GO2_PYTHON to a Python with numpy.' >&2
  exit 1
fi
for test in tests/go2-*.test.py; do
  "$python" "$test"
done
node --test tests/go2-motion-ui.test.mjs tests/go2-geo-projection.test.mjs tests/go2-follow-tracker.test.mjs
for script in tooling/go2-lab/web/*.js tooling/go2-lab/test-phone-*.mjs; do
  node --check "$script"
done
