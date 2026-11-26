#!/usr/bin/env python3
"""Install or build a complete boot/module distribution under a per-key lock."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import subprocess
import sys
import tempfile
import time

IMAGE = Path(__file__).resolve().parent
KEY_FILES = (
    'kernel-source-lock.json', 'manifests/kernel.xml', 'build-kernel.sh',
    'install-kernel.py', 'kernel_cache.py', 'build.sh', 'build-config.json',
    'builder/Dockerfile', 'taku.mk', 'source-lock.json', 'manifests/devices.xml',
    'manifests/platform.xml',
)


def digest(path):
    with path.open('rb') as stream:
        sha = hashlib.sha256()
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            sha.update(chunk)
    return sha.hexdigest()


def module_lists(directory):
    return {path.name: path for path in directory.glob('*.modules.load*')}


def distribution(dist, dest):
    files = {path.name: path for path in dist.iterdir() if path.is_file()}
    required = {'boot.img'}
    # Preserve the destination contract and verify any new distribution lists.
    for listing in (*module_lists(dest).values(), *module_lists(dist).values()):
        for line in listing.read_text().splitlines():
            item = line.strip()
            if item and not item.startswith('#'):
                required.add(PurePosixPath(item).name)
    missing = sorted(required - files.keys())
    if missing:
        raise ValueError(f'Incomplete kernel distribution: {missing}')
    return files, sorted(required)


def inventory(dist, dest):
    files, required = distribution(dist, dest)
    return {
        'required': required,
        'files': {name: digest(path) for name, path in sorted(files.items())},
    }


def kernel_cache_key(product, target, command, image, manifest, dest, toolchain):
    inputs = {
        'schema': 1,
        'product': product,
        'target': target,
        'command': command,
        'config': {name: digest(image / name) for name in KEY_FILES},
        'resolvedManifest': digest(manifest),
        'toolchain': toolchain,
        # The pinned device prebuilt tree binds the boot image and module set
        # before install-kernel.py overwrites distribution files in this checkout.
        'devicePrebuilts': subprocess.check_output(
            ['git', 'ls-tree', '-r', 'HEAD', '--', '.'], cwd=dest, text=True),
        'moduleLists': {name: digest(path) for name, path in module_lists(dest).items()},
    }
    return hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()


def valid(entry, key, dest):
    try:
        dist = entry / 'dist'
        manifest = entry / 'manifest.json'
        if any(path.is_symlink() for path in (entry, dist, manifest)):
            return False
        if any(path.is_symlink() or not path.is_file() for path in dist.iterdir()):
            return False
        return json.loads(manifest.read_text()) == {'key': key, **inventory(dist, dest)}
    except (OSError, ValueError):
        return False


def publish(entry, key, dist, dest):
    with tempfile.TemporaryDirectory(prefix='.publish-', dir=entry.parent) as temp:
        staging = Path(temp) / 'entry'
        target = staging / 'dist'
        target.mkdir(parents=True)
        files, _ = distribution(dist, dest)
        for name, path in files.items():
            shutil.copy2(path, target / name)
        (staging / 'manifest.json').write_text(
            json.dumps({'key': key, **inventory(target, dest)}, sort_keys=True) + '\n')
        # Readers and writers share the lock. Replace a damaged entry only
        # after the new distribution has been staged completely.
        old = Path(temp) / 'old'
        if os.path.lexists(entry):
            entry.rename(old)
        try:
            staging.rename(entry)
        except OSError:
            if os.path.lexists(old):
                old.rename(entry)
            raise


def install_or_build(cache, key, dist, dest, build, install):
    timing = {'cache': 'disabled', 'kernel-cache-key': key, 'bazel_seconds': None,
              'cache_verify_install_seconds': None, 'cache_publish_seconds': None}
    if cache is not None:
        cache.mkdir(parents=True, exist_ok=True)
        with (cache / f'{key}.lock').open('a') as guard:
            fcntl.flock(guard, fcntl.LOCK_EX)
            entry = cache / key
            started = time.monotonic()
            if valid(entry, key, dest):
                install(entry / 'dist')
                timing.update(cache='hit', cache_verify_install_seconds=time.monotonic() - started)
                return timing
            timing.update(cache='miss', cache_verify_install_seconds=time.monotonic() - started)
            # A new entry must never ingest files left by an earlier dist run.
            dist.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(prefix='.dist-', dir=dist.parent) as temp:
                if os.path.lexists(dist):
                    dist.rename(Path(temp) / 'previous')
                started = time.monotonic()
                build()
                timing['bazel_seconds'] = time.monotonic() - started
                started = time.monotonic()
                publish(entry, key, dist, dest)
                timing['cache_publish_seconds'] = time.monotonic() - started
                install(dist)
            return timing
    started = time.monotonic()
    build()
    timing['bazel_seconds'] = time.monotonic() - started
    install(dist)
    return timing


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kernel-cache-dir', type=Path)
    parser.add_argument('--product', required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('dist', type=Path)
    parser.add_argument('dest', type=Path)
    parser.add_argument('results', type=Path)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    cache = args.kernel_cache_dir
    if cache is None and os.environ.get('TAKU_KERNEL_CACHE_DIR'):
        cache = Path(os.environ['TAKU_KERNEL_CACHE_DIR'])
    key = None
    if cache is not None:
        # The resolved manifest pins Kleaf, Clang, Bazel, JDK and GKI.
        # Host tools are supplied by the builder image and its installed packages.
        packages = subprocess.check_output(
            ['dpkg-query', '-W', '-f=${binary:Package}=${Version}\n'], text=True)
        toolchain = {'image': os.environ.get('TAKU_KERNEL_BUILDER_IMAGE_ID'),
                     'machine': platform.machine(), 'packages': sorted(packages.splitlines()),
                     'buildEnvironment': {name: value for name, value in os.environ.items()
                                          if name.startswith(('BAZEL', 'KBUILD', 'KLEAF', 'BUILD_', 'LLVM'))
                                          or name in ('CC', 'CXX', 'LD', 'CROSS_COMPILE', 'ARCH',
                                                      'PATH', 'SOURCE_DATE_EPOCH')}}
        key = kernel_cache_key(args.product, args.target, args.command, IMAGE,
                               args.results / 'resolved-kernel-manifest.xml', args.dest, toolchain)

    def build():
        subprocess.run(args.command, check=True)

    def install(dist):
        subprocess.run([sys.executable, str(IMAGE / 'install-kernel.py'), str(dist),
                        str(args.dest), str(args.results)], check=True)

    timing = install_or_build(cache, key, args.dist, args.dest, build, install)
    report = json.dumps(timing, indent=2) + '\n'
    (args.results / 'kernel-timing.json').write_text(report)
    print(report, end='', flush=True)
    if timing['cache'] != 'hit':
        # Release the persistent JVM before the memory-heavy Soong bootstrap.
        subprocess.run(['./tools/bazel', 'shutdown'], check=True)


if __name__ == '__main__':
    main()
