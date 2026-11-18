#!/bin/bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd -P)
cd "$root"
python3 scripts/prepare-image-assets.py \
  --asset adapters/codex-runtime/downloads/codex.tar.gz \
  --asset adapters/codex-runtime/downloads/code-mode-host.tar.gz
printf '%s\n' 'Verified locked ARM64 programs. No authentication imported.'
