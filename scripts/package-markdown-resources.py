#!/usr/bin/env python3
"""Keep parser classpath resources when the SDK-only build converts jars to DEX."""
import json
import pathlib
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
assets = json.loads((ROOT / 'system/assistant/markdown-dependencies.json').read_text())['assets']
with zipfile.ZipFile(sys.argv[1], 'a', compression=zipfile.ZIP_DEFLATED) as apk:
    for item in assets:
        with zipfile.ZipFile(ROOT / item['path']) as jar:
            for name in jar.namelist():
                if name.endswith(('/', '.class')) or name.startswith('META-INF/'):
                    continue
                data = jar.read(name)
                if name in apk.namelist():
                    if apk.read(name) != data:
                        raise SystemExit('Conflicting Java resource: ' + name)
                else:
                    apk.writestr(name, data)
