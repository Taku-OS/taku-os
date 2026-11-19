#!/usr/bin/env python3
"""Fetch locked runtime programs and CA bundle; never embed credentials."""
import argparse, hashlib, json, pathlib, subprocess, sys, tempfile, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
lock = json.loads((ROOT / 'image/runtime-assets.lock.json').read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--asset', action='append', choices=[item['path'] for item in lock['assets']])
args = parser.parse_args()
cache = ROOT / 'out/image-fetch'
cache.mkdir(parents=True, exist_ok=True)

def verified(path, item):
    if not path.is_file() or path.stat().st_size != item['bytes']:
        return False
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest() == item['sha256']

for item in lock['assets']:
    if args.asset and item['path'] not in args.asset:
        continue
    dest = ROOT / item['path']
    if verified(dest, item):
        print('Verified cached asset:', item['path'])
        continue
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as tmp:
        temporary = pathlib.Path(tmp.name)
    try:
        fetched = False
        if url := item.get('url'):
            try:
                with urllib.request.urlopen(url, timeout=60) as source, temporary.open('wb') as output:
                    while chunk := source.read(1024 * 1024):
                        output.write(chunk)
                fetched = True
            except OSError:
                if not item.get('githubRelease'):
                    raise
        if not fetched and (release := item.get('githubRelease')):
            subprocess.run(['gh', 'release', 'download', release['tag'], '--repo', release['repo'],
                            '--pattern', release['asset'], '--output', str(temporary), '--clobber'], check=True)
        elif not fetched:
            raise SystemExit('Locked asset has no url or githubRelease and is not cached: ' + item['path'])
        if not verified(temporary, item):
            raise SystemExit('Size or hash mismatch; keeping existing asset unchanged: ' + item['path'])
        dest.parent.mkdir(parents=True, exist_ok=True)
        temporary.replace(dest)
    finally:
        temporary.unlink(missing_ok=True)
    print('Fetched and verified asset:', item['path'])

if not args.asset:
    subprocess.run([sys.executable, str(ROOT / 'scripts/prepare-markdown.py')], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(['node', str(ROOT / 'system/agent-app/scripts/export-reply-contract.ts')], check=True)
