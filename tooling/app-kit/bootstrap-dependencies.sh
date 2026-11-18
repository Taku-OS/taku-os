#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
mkdir -p downloads runtime
# Pinned public package artifacts, not a copy of a user's runtime/cache.
curl -fL --retry 2 https://registry.npmjs.org/react/-/react-18.3.1.tgz -o downloads/react.tgz
curl -fL --retry 2 https://registry.npmjs.org/react-dom/-/react-dom-18.3.1.tgz -o downloads/react-dom.tgz
curl -fL --retry 2 https://registry.npmjs.org/@esbuild/android-arm64/-/android-arm64-0.25.10.tgz -o downloads/esbuild.tgz
tar -xOf downloads/react.tgz package/umd/react.production.min.js > runtime/react.js
tar -xOf downloads/react-dom.tgz package/umd/react-dom.production.min.js > runtime/react-dom.js
tar -xOf downloads/esbuild.tgz package/bin/esbuild > runtime/esbuild
chmod 755 runtime/esbuild
(cd api && npm ci --ignore-scripts --no-audit --no-fund)
# No host npm cache, downloaded programs or generated app data is committed.
