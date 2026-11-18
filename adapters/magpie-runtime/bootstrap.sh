#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
python3 scripts/prepare-image-assets.py --asset adapters/magpie-runtime/downloads/magpie-arm64
