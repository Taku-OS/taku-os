#!/usr/bin/env python3
"""Fetch or verify the pinned public native dependency; never touch a device."""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('google_services', ROOT / 'image/google_services.py')
GMS = importlib.util.module_from_spec(spec); spec.loader.exec_module(GMS)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('fetch', 'verify'))
    args = parser.parse_args()
    lock, checksum = GMS.load_lock()
    destination = GMS.cache_path(ROOT, lock)
    if args.action == 'fetch' and not destination.exists():
        # Exclusive directory: preserve failed downloads for diagnosis. Never
        # reset an existing checkout or silently change an in-use dependency.
        destination.mkdir(parents=True, exist_ok=False)
        def git(*arguments):
            return subprocess.run(['git', '-c', 'credential.helper=', '-c', 'http.version=HTTP/1.1', *arguments],
                                  cwd=destination, env=dict(os.environ, GIT_TERMINAL_PROMPT='0'), check=True)
        git('init'); git('remote', 'add', 'origin', lock['repository'])
        git('sparse-checkout', 'init', '--cone')
        git('sparse-checkout', 'set', *lock['sparseDirectories'])
        git('fetch', '--depth=1', '--filter=blob:none', 'origin', lock['commit'])
        git('checkout', '--detach', 'FETCH_HEAD')
    print(GMS.verify_source(destination, lock) | {'lockSha256': checksum})


if __name__ == '__main__':
    main()
