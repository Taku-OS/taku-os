#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Taku AI Inc.
# SPDX-License-Identifier: Apache-2.0
"""Prune stale build outputs on the Taku OS cloud builder while preserving caches and OTA baselines."""
from __future__ import annotations

import argparse
import dataclasses
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys

SCHEMA = 'taku.cloud-builder-prune/1'
BUILD_NUMBER_RE = re.compile(r'(20\d{12})')
BRANDED_OTA_RE = re.compile(r'^TakuOS-.*-tokay-(20\d{12})-full\.zip$')
LINEAGE_OTA_RE = re.compile(r'^lineage-.*-UNOFFICIAL-tokay\.zip$')
HOME_STAGING_RE = re.compile(
    r'^taku-(?:.*-input(?:-[A-Za-z0-9_.-]+)?|agent-build-[A-Za-z0-9_.-]+|'
    r'recents-\d{8}(?:-r\d+)?|.*-validation-\d{8})$'
)
SHA256_REF_RE = re.compile(r'sha256:([0-9a-f]{64})')
RECEIPT_FILE_NAMES = frozenset({
    'artifact-manifest.json', 'attempts.log', 'baseline-receipt.json',
    'branding-properties.json', 'build-workarounds.json', 'build.exit',
    'carrier-defaults-apk.json', 'carrier-defaults-source.json',
    'component-verification.json', 'emulator-input.json', 'exit-code',
    'export-receipt.json', 'google-apps-artifacts.json',
    'google-services-artifacts.json', 'incremental-check.json',
    'input-manifest.json', 'kernel-artifacts.json', 'kernel-source-lock.json',
    'launcher-integration.json', 'ota-plan.json', 'overlay-manifest.json',
    'prepared.json', 'product-branding-integration.json',
    'recents-integration.json', 'resolved-kernel-manifest.xml',
    'resolved-manifest.xml', 'resume-check.json', 'run-build.sh',
    'run-input.json', 'signature-verification.json', 'source-lock.json',
    'status.json', 'system-ai-rom.json', 'updater-apk-verification.json',
    'updater-integration.json',
})
MAX_RECEIPT_BYTES = 2 * 1024 * 1024
MAX_RUN_RECEIPTS_BYTES = 10 * 1024 * 1024


@dataclasses.dataclass(frozen=True)
class Policy:
    keep_full_runs: int = 3
    keep_scratch_runs: int = 5
    scratch_max_age_days: float = 5.0
    overlay_upper_max_age_hours: float = 12.0
    var_tmp_max_age_hours: float = 24.0
    keep_var_tmp: int = 3
    home_staging_max_age_days: float = 3.0
    keep_home_staging: int = 3
    container_max_age_hours: float = 24.0
    keep_containers: int = 3
    min_free_gib: float = 200.0
    min_root_free_gib: float = 12.0


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def fsync_dir(path: Path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _unescape_mount_token(token: str) -> str:
    return re.sub(r'\\([0-7]{3})', lambda m: chr(int(m.group(1), 8)), token)


def parse_proc_mounts(text: str) -> set[Path]:
    paths: set[Path] = set()
    for raw in text.splitlines():
        parts = raw.split()
        if len(parts) < 4:
            continue
        mountpoint = _unescape_mount_token(parts[1])
        if mountpoint.startswith('/'):
            paths.add(Path(mountpoint))
        for opt in parts[3].split(','):
            for key in ('lowerdir=', 'upperdir=', 'workdir='):
                if opt.startswith(key):
                    for item in opt[len(key):].split(':'):
                        unescaped = _unescape_mount_token(item)
                        if unescaped.startswith('/'):
                            paths.add(Path(unescaped))
    return paths


def has_active_mount(target: Path, mount_paths: set[Path]) -> bool:
    for mp in mount_paths:
        if mp == target or mp.is_relative_to(target):
            return True
    return False


def tree_usage(root: Path) -> tuple[int, dict[tuple[int, int], tuple[int, int]]]:
    """Return apparent unique bytes inside root and an inode map {(dev, ino): (size, count_in_tree)}.

    Never follows symlinks. Tracks hardlink counts so multi-linked files (such as
    Lineage OTA aliases) only count as reclaimable disk blocks when all links are removed.
    """
    inodes: dict[tuple[int, int], list[int]] = {}
    try:
        st = root.lstat()
    except OSError:
        return 0, {}
    if not stat.S_ISDIR(st.st_mode):
        key = (st.st_dev, st.st_ino)
        return st.st_size, {key: (st.st_size, 1)}
    stack = [root]
    dir_bytes = 0
    while stack:
        current = stack.pop()
        try:
            with os.scandir(current) as it:
                for entry in it:
                    try:
                        est = entry.stat(follow_symlinks=False)
                    except OSError:
                        continue
                    if stat.S_ISDIR(est.st_mode):
                        dir_bytes += est.st_size
                        stack.append(Path(entry.path))
                    else:
                        key = (est.st_dev, est.st_ino)
                        if key in inodes:
                            inodes[key][1] += 1
                        else:
                            inodes[key] = [est.st_size, 1]
        except OSError:
            continue
    inode_map = {k: (v[0], v[1]) for k, v in inodes.items()}
    total = dir_bytes + sum(size for size, _ in inode_map.values())
    return total, inode_map


def estimate_reclaimed_bytes(targets: list[Path]) -> int:
    """Estimate actual disk bytes freed when unlinking/removing all targets together."""
    removed_links: dict[tuple[int, int], int] = {}
    inode_meta: dict[tuple[int, int], tuple[int, int]] = {}
    dir_bytes = 0
    for target in targets:
        try:
            st = target.lstat()
        except OSError:
            continue
        if stat.S_ISDIR(st.st_mode):
            stack = [target]
            while stack:
                cur = stack.pop()
                try:
                    cst = cur.lstat()
                    dir_bytes += cst.st_size
                except OSError:
                    continue
                try:
                    with os.scandir(cur) as it:
                        for entry in it:
                            try:
                                est = entry.stat(follow_symlinks=False)
                            except OSError:
                                continue
                            if stat.S_ISDIR(est.st_mode):
                                stack.append(Path(entry.path))
                            else:
                                key = (est.st_dev, est.st_ino)
                                removed_links[key] = removed_links.get(key, 0) + 1
                                inode_meta[key] = (est.st_size, est.st_nlink)
                except OSError:
                    continue
        else:
            key = (st.st_dev, st.st_ino)
            removed_links[key] = removed_links.get(key, 0) + 1
            inode_meta[key] = (st.st_size, st.st_nlink)
    reclaimed = dir_bytes
    for key, count in removed_links.items():
        size, total_nlink = inode_meta[key]
        if count >= total_nlink:
            reclaimed += size
    return reclaimed


def rmtree_force(target: Path):
    def _onerror(func, path, _exc_info):
        p = Path(path)
        try:
            if not p.is_symlink():
                parent = p.parent
                if parent.exists() and not parent.is_symlink():
                    os.chmod(parent, stat.S_IRWXU)
                os.chmod(p, stat.S_IRWXU)
        except OSError:
            pass
        func(path)

    if target.is_symlink() or target.is_file():
        try:
            os.chmod(target.parent, stat.S_IRWXU)
        except OSError:
            pass
        target.unlink()
        return
    try:
        os.chmod(target, stat.S_IRWXU)
    except OSError:
        pass
    shutil.rmtree(target, onerror=_onerror)


def read_json_safe(path: Path):
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 5 * 1024 * 1024:
            return None
        value = json.loads(path.read_text(encoding='utf-8'))
        return value if isinstance(value, dict) else None
    except (OSError, UnicodeDecodeError, ValueError):
        return None


def discover_systemd_protected_runs(work: Path, systemd_dir: Path) -> dict[str, str]:
    protected: dict[str, str] = {}
    if not systemd_dir.is_dir():
        return protected
    enabled_units: set[Path] = set()
    for wants in systemd_dir.glob('*.wants'):
        if not wants.is_dir():
            continue
        for entry in wants.iterdir():
            if entry.name.endswith('.service'):
                try:
                    resolved = entry.resolve()
                except OSError:
                    continue
                if resolved.is_file():
                    enabled_units.add(resolved)
    runs_prefix = str(work / 'runs') + '/'
    pattern = re.compile(re.escape(runs_prefix) + r'([A-Za-z0-9_.-]+)')
    for unit in sorted(enabled_units):
        try:
            text = unit.read_text(encoding='utf-8', errors='replace')
        except OSError:
            continue
        for match in pattern.findall(text):
            protected.setdefault(match, f'systemd-enabled:{unit.name}')
    return protected


def discover_pinned_baselines(work: Path, releases_dirs: list[Path]) -> tuple[set[str], set[str]]:
    pinned_builds: set[str] = set()
    pinned_runs: set[str] = set()

    pin_file = work / 'pinned-baselines.json'
    pin_data = read_json_safe(pin_file)
    if pin_data:
        for b in pin_data.get('pinnedBuildNumbers', []):
            if isinstance(b, str) and BUILD_NUMBER_RE.fullmatch(b):
                pinned_builds.add(b)
        for r in pin_data.get('pinnedRuns', []):
            if isinstance(r, str) and r:
                pinned_runs.add(r)

    candidate_release_dirs = list(releases_dirs)
    for extra in [work / 'input/docs/releases', *(work / 'runs').glob('*/input/docs/releases')]:
        if extra.is_dir() and not extra.is_symlink():
            candidate_release_dirs.append(extra)

    for rel_dir in candidate_release_dirs:
        if not rel_dir.is_dir():
            continue
        for item in rel_dir.glob('*.json'):
            data = read_json_safe(item)
            if not data:
                continue
            for value in (
                data.get('build'),
                (data.get('verification') or {}).get('nativeUpdateSourceBuild') if isinstance(data.get('verification'), dict) else None,
                (data.get('verification') or {}).get('nativeUpdateTargetBuild') if isinstance(data.get('verification'), dict) else None,
            ):
                if isinstance(value, str) and BUILD_NUMBER_RE.fullmatch(value):
                    pinned_builds.add(value)

    top_input = read_json_safe(work / 'results/run-input.json')
    if top_input and (work / 'armed').exists() and not (work / 'results/build.exit').exists():
        b = str(top_input.get('buildNumber', ''))
        if BUILD_NUMBER_RE.fullmatch(b):
            pinned_builds.add(b)

    return pinned_builds, pinned_runs


@dataclasses.dataclass
class RunInfo:
    name: str
    path: Path
    mtime: float
    build_number: str | None
    is_full_export: bool
    is_overlay: bool
    is_finished: bool
    referenced_runs: set[str]
    image_digests: set[str]


def inspect_run(run_dir: Path, work: Path) -> RunInfo:
    mtimes = [run_dir.lstat().st_mtime]
    for sub in ('results', 'export', 'target-baseline-export'):
        p = run_dir / sub
        if p.exists() and not p.is_symlink():
            try:
                mtimes.append(p.lstat().st_mtime)
            except OSError:
                pass
    mtime = max(mtimes)

    is_full_export = any(
        (run_dir / sub / 'baseline-receipt.json').is_file()
        for sub in ('export', 'target-baseline-export')
    )
    is_overlay = (run_dir / 'upper').is_dir() or (run_dir / 'work').is_dir()

    build_number = None
    for candidate in (
        run_dir / 'export/baseline-receipt.json',
        run_dir / 'target-baseline-export/baseline-receipt.json',
        run_dir / 'export/run-input.json',
        run_dir / 'results/run-input.json',
        run_dir / 'run-input.json',
    ):
        data = read_json_safe(candidate)
        if not data:
            continue
        raw = (
            (data.get('buildIdentity') or {}).get('buildNumber')
            if isinstance(data.get('buildIdentity'), dict)
            else None
        ) or data.get('buildNumber')
        if isinstance(raw, str) and BUILD_NUMBER_RE.fullmatch(raw):
            build_number = raw
            break
    if not build_number:
        m = BUILD_NUMBER_RE.search(run_dir.name)
        if m:
            build_number = m.group(1)

    is_finished = (
        is_full_export
        or (run_dir / 'results/build.exit').is_file()
        or (run_dir / 'results/exit-code').is_file()
    )

    referenced_runs: set[str] = set()
    image_digests: set[str] = set()
    runs_prefix = re.escape(str(work / 'runs') + '/')
    ref_re = re.compile(runs_prefix + r'([A-Za-z0-9_.-]+)')
    scan_files = [
        *run_dir.glob('*.sh'),
        *run_dir.glob('*.json'),
        *(run_dir / 'results').glob('*.json'),
        *(run_dir / 'export').glob('*.json'),
    ]
    for f in scan_files:
        try:
            if f.is_symlink() or not f.is_file() or f.stat().st_size > 1024 * 1024:
                continue
            text = f.read_text(encoding='utf-8', errors='replace')
        except OSError:
            continue
        for ref in ref_re.findall(text):
            if ref != run_dir.name:
                referenced_runs.add(ref)
        for digest in SHA256_REF_RE.findall(text):
            image_digests.add('sha256:' + digest)

    return RunInfo(
        name=run_dir.name,
        path=run_dir,
        mtime=mtime,
        build_number=build_number,
        is_full_export=is_full_export,
        is_overlay=is_overlay,
        is_finished=is_finished,
        referenced_runs=referenced_runs,
        image_digests=image_digests,
    )


def archive_retired_run_receipts(run_dir: Path, retired_root: Path, reason: str, reclaimed_bytes: int):
    dest = retired_root / run_dir.name
    if dest.exists():
        return dest
    candidates: list[tuple[Path, Path]] = []
    copied_bytes = 0
    for rel_dir in (Path('.'), Path('results'), Path('export'), Path('export/signature-verification'),
                    Path('target-baseline-export')):
        base = run_dir / rel_dir
        if not base.is_dir() or base.is_symlink():
            continue
        try:
            entries = sorted(base.iterdir())
        except OSError:
            continue
        for item in entries:
            try:
                st = item.lstat()
            except OSError:
                continue
            if not stat.S_ISREG(st.st_mode):
                continue
            if (item.name in RECEIPT_FILE_NAMES or item.name.startswith('run-build')) and st.st_size <= MAX_RECEIPT_BYTES:
                if copied_bytes + st.st_size <= MAX_RUN_RECEIPTS_BYTES:
                    candidates.append((item, item.relative_to(run_dir)))
                    copied_bytes += st.st_size

    dest.mkdir(parents=True, exist_ok=True)
    saved_files = []
    for src, rel in candidates:
        out = dest / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, out)
        os.chmod(out, stat.S_IRUSR | stat.S_IWUSR | stat.S_IRGRP | stat.S_IROTH)
        h = hashlib.sha256(out.read_bytes()).hexdigest()
        saved_files.append({'path': str(rel), 'bytes': out.stat().st_size, 'sha256': h})
    manifest = {
        'schema': 'taku.retired-cloud-run/1',
        'run': run_dir.name,
        'retiredAt': utc_now().isoformat(),
        'reason': reason,
        'reclaimedBytes': reclaimed_bytes,
        'preservedFiles': saved_files,
    }
    manifest_path = dest / 'retired-manifest.json'
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    with manifest_path.open('rb') as f:
        os.fsync(f.fileno())
    fsync_dir(dest)
    fsync_dir(dest.parent)
    return dest


def inspect_docker():
    """Return container and image metadata from Docker if available."""
    if shutil.which('docker') is None:
        return [], []
    ps = subprocess.run(
        ['docker', 'ps', '-a', '--no-trunc', '--format', '{{.ID}}'],
        capture_output=True, text=True, check=False,
    )
    if ps.returncode != 0:
        return [], []
    ids = [line.strip() for line in ps.stdout.splitlines() if line.strip()]
    containers = []
    if ids:
        inspected = subprocess.run(['docker', 'inspect', *ids], capture_output=True, text=True, check=False)
        if inspected.returncode == 0:
            containers = json.loads(inspected.stdout)
    imgs = subprocess.run(
        ['docker', 'images', '-a', '--no-trunc', '--format', '{{json .}}'],
        capture_output=True, text=True, check=False,
    )
    images = []
    if imgs.returncode == 0:
        for line in imgs.stdout.splitlines():
            if line.strip():
                images.append(json.loads(line))
    return containers, images


def parse_iso_timestamp(value: str | None) -> float | None:
    if not value or value.startswith('0001-01-01'):
        return None
    cleaned = value.strip()
    if cleaned.endswith('Z'):
        cleaned = cleaned[:-1] + '+00:00'
    cleaned = re.sub(r'(\.\d{6})\d+', r'\1', cleaned)
    try:
        return datetime.datetime.fromisoformat(cleaned).timestamp()
    except ValueError:
        return None


def select_runs(
    runs: dict[str, RunInfo],
    *,
    now_ts: float,
    policy: Policy,
    mount_paths: set[Path],
    systemd_protected: dict[str, str],
    pinned_builds: set[str],
    pinned_runs: set[str],
    mounted_host_paths: set[Path],
):
    kept: dict[str, str] = {}
    for name, info in runs.items():
        if has_active_mount(info.path, mount_paths) or any(
            hp == info.path or hp.is_relative_to(info.path) for hp in mounted_host_paths
        ):
            kept[name] = 'protected:active-mount'
        elif name in systemd_protected:
            kept[name] = f'protected:{systemd_protected[name]}'
        elif (info.path / '.keep').exists():
            kept[name] = 'protected:dot-keep'
        elif name in pinned_runs:
            kept[name] = 'protected:pinned-run'
        elif info.build_number and info.build_number in pinned_builds:
            kept[name] = f'protected:pinned-build:{info.build_number}'
        elif not info.is_finished and (now_ts - info.mtime) < 24 * 3600:
            kept[name] = 'protected:pending-or-recent'

    full_runs = sorted(
        (r for r in runs.values() if r.is_full_export),
        key=lambda r: (r.build_number or '', r.mtime, r.name),
        reverse=True,
    )
    for idx, r in enumerate(full_runs):
        if idx < policy.keep_full_runs and r.name not in kept:
            kept[r.name] = f'keep:recent-full-export:{idx + 1}'

    scratch_runs = sorted(
        (r for r in runs.values() if not r.is_full_export),
        key=lambda r: (r.mtime, r.name),
        reverse=True,
    )
    max_scratch_age = policy.scratch_max_age_days * 86400
    for idx, r in enumerate(scratch_runs):
        if r.name in kept:
            continue
        if idx < policy.keep_scratch_runs:
            kept[r.name] = f'keep:recent-scratch:{idx + 1}'
        elif (now_ts - r.mtime) < max_scratch_age:
            kept[r.name] = 'keep:within-scratch-ttl'

    # Transitive closure of `base=` / input references from kept runs.
    queue = list(kept.keys())
    while queue:
        cur = queue.pop()
        info = runs.get(cur)
        if not info:
            continue
        for ref in sorted(info.referenced_runs):
            if ref in runs and ref not in kept:
                kept[ref] = f'protected:referenced-by:{cur}'
                queue.append(ref)

    retired = [r for name, r in sorted(runs.items()) if name not in kept]

    overlay_upper_prunes: list[RunInfo] = []
    upper_max_age = policy.overlay_upper_max_age_hours * 3600
    for name, reason in sorted(kept.items()):
        info = runs[name]
        if not info.is_overlay or not info.is_finished:
            continue
        if reason in ('protected:active-mount', 'protected:dot-keep'):
            continue
        if (now_ts - info.mtime) < upper_max_age:
            continue
        if (info.path / 'overlay-upper-pruned.json').is_file():
            continue
        if (info.path / 'upper').exists() or (info.path / 'work').exists():
            overlay_upper_prunes.append(info)

    return kept, retired, overlay_upper_prunes


def select_tokay_out_zips(product_dir: Path, *, now_ts: float, armed_build: str | None) -> list[dict]:
    if not product_dir.is_dir() or product_dir.is_symlink():
        return []
    branded: list[tuple[str, float, Path]] = []
    lineage: list[tuple[str, float, Path]] = []
    partials: list[Path] = []
    for entry in sorted(product_dir.iterdir()):
        try:
            st = entry.lstat()
        except OSError:
            continue
        if not stat.S_ISREG(st.st_mode):
            continue
        m = BRANDED_OTA_RE.match(entry.name)
        if m:
            branded.append((m.group(1), st.st_mtime, entry))
            continue
        if LINEAGE_OTA_RE.match(entry.name):
            lineage.append((entry.name, st.st_mtime, entry))
            continue
        if '.partial' in entry.name and (now_ts - st.st_mtime) >= 6 * 3600:
            partials.append(entry)

    branded.sort(key=lambda x: (x[0], x[1]), reverse=True)
    keep_branded = {branded[0][2]} if branded else set()
    for bnum, _, p in branded:
        if armed_build and bnum == armed_build:
            keep_branded.add(p)

    lineage.sort(key=lambda x: (x[0], x[1]), reverse=True)
    keep_lineage = {lineage[0][2]} if lineage else set()

    removals = []
    for bnum, _, p in branded:
        if p not in keep_branded:
            removals.append({'path': p, 'kind': 'stale-product-full-ota', 'buildNumber': bnum})
    for _, _, p in lineage:
        if p not in keep_lineage:
            removals.append({'path': p, 'kind': 'stale-product-lineage-ota-alias'})
    for p in partials:
        removals.append({'path': p, 'kind': 'stale-partial-artifact'})
    return removals


def select_staging_dirs(
    *,
    root_dir: Path,
    now_ts: float,
    var_tmp_max_age_hours: float,
    keep_var_tmp: int,
    home_staging_max_age_days: float,
    keep_home_staging: int,
    mount_paths: set[Path],
    mounted_host_paths: set[Path],
) -> list[dict]:
    actions: list[dict] = []

    def _is_busy(p: Path) -> bool:
        if has_active_mount(p, mount_paths):
            return True
        return any(hp == p or hp.is_relative_to(p) or p.is_relative_to(hp) for hp in mounted_host_paths)

    var_tmp = root_dir / 'var/tmp'
    if var_tmp.is_dir() and not var_tmp.is_symlink():
        candidates = []
        for entry in var_tmp.iterdir():
            try:
                st = entry.lstat()
            except OSError:
                continue
            if stat.S_ISDIR(st.st_mode) and entry.name.startswith('taku-') and not _is_busy(entry):
                candidates.append((st.st_mtime, entry))
        candidates.sort(key=lambda x: (x[0], x[1].name), reverse=True)
        cutoff = var_tmp_max_age_hours * 3600
        for idx, (mtime, entry) in enumerate(candidates):
            if idx >= keep_var_tmp and (now_ts - mtime) >= cutoff:
                actions.append({'path': entry, 'kind': 'stale-var-tmp-build'})

    home_root = root_dir / 'home'
    if home_root.is_dir() and not home_root.is_symlink():
        home_candidates = []
        for user_dir in sorted(home_root.iterdir()):
            try:
                ust = user_dir.lstat()
            except OSError:
                continue
            if not stat.S_ISDIR(ust.st_mode):
                continue
            try:
                children = list(user_dir.iterdir())
            except OSError:
                continue
            for entry in children:
                try:
                    est = entry.lstat()
                except OSError:
                    continue
                if not stat.S_ISDIR(est.st_mode):
                    continue
                if HOME_STAGING_RE.match(entry.name) and not (entry / '.keep').exists() and not _is_busy(entry):
                    home_candidates.append((est.st_mtime, entry))
        home_candidates.sort(key=lambda x: (x[0], x[1].name), reverse=True)
        cutoff = home_staging_max_age_days * 86400
        for idx, (mtime, entry) in enumerate(home_candidates):
            if idx >= keep_home_staging and (now_ts - mtime) >= cutoff:
                actions.append({'path': entry, 'kind': 'stale-home-staging'})

    return actions


def build_plan(
    *,
    work: Path,
    root_dir: Path,
    systemd_dir: Path,
    releases_dirs: list[Path],
    policy: Policy,
    now_ts: float,
    mount_paths: set[Path],
    containers: list[dict],
    images: list[dict],
):
    # 1. Check active containers first; never prune while a build container is active.
    for c in containers:
        name = (c.get('Name') or '').lstrip('/')
        state = c.get('State') or {}
        if state.get('Running') or state.get('Restarting') or state.get('Paused'):
            mounts = [Path(m['Source']) for m in (c.get('Mounts') or []) if m.get('Source', '').startswith('/')]
            if name.startswith('taku') or any(
                m == work or m.is_relative_to(work) or str(m).startswith('/var/tmp/taku-') or str(m).startswith('/home/')
                for m in mounts
            ):
                return {'schema': SCHEMA, 'status': 'skipped', 'reason': f'active-container:{name}',
                        'observedAt': utc_now().isoformat()}

    # 2. Plan exited `taku*` container cleanup first so host dirs of removed containers can be freed.
    exited_taku = []
    for c in containers:
        name = (c.get('Name') or '').lstrip('/')
        state = c.get('State') or {}
        status = (state.get('Status') or '').lower()
        if not name.startswith('taku') or status not in ('exited', 'dead'):
            continue
        ts = parse_iso_timestamp(state.get('FinishedAt')) or parse_iso_timestamp(c.get('Created')) or now_ts
        exited_taku.append((ts, name, c))
    exited_taku.sort(key=lambda x: (x[0], x[1]), reverse=True)
    container_cutoff = policy.container_max_age_hours * 3600
    containers_to_remove: list[dict] = []
    kept_containers: list[dict] = []
    for idx, (ts, name, c) in enumerate(exited_taku):
        if idx >= policy.keep_containers and (now_ts - ts) >= container_cutoff:
            containers_to_remove.append({'id': c['Id'], 'name': name, 'finishedAt': (c.get('State') or {}).get('FinishedAt')})
        else:
            kept_containers.append(c)

    removing_ids = {item['id'] for item in containers_to_remove}
    mounted_host_paths: set[Path] = set()
    used_image_ids: set[str] = set()
    for c in containers:
        if c.get('Id') in removing_ids:
            continue
        if c.get('Image'):
            used_image_ids.add(c['Image'])
        for m in c.get('Mounts') or []:
            src = m.get('Source', '')
            if src.startswith('/'):
                mounted_host_paths.add(Path(src))

    # 3. Discover pinned baselines & systemd-protected runs.
    systemd_protected = discover_systemd_protected_runs(work, systemd_dir)
    pinned_builds, pinned_runs = discover_pinned_baselines(work, releases_dirs)

    runs_dir = work / 'runs'
    runs: dict[str, RunInfo] = {}
    if runs_dir.is_dir() and not runs_dir.is_symlink():
        for entry in sorted(runs_dir.iterdir()):
            try:
                st = entry.lstat()
            except OSError:
                continue
            if stat.S_ISDIR(st.st_mode):
                runs[entry.name] = inspect_run(entry, work)

    def _compute_work_actions(active_policy: Policy):
        kept, retired, upper_prunes = select_runs(
            runs,
            now_ts=now_ts,
            policy=active_policy,
            mount_paths=mount_paths,
            systemd_protected=systemd_protected,
            pinned_builds=pinned_builds,
            pinned_runs=pinned_runs,
            mounted_host_paths=mounted_host_paths,
        )
        armed_build = None
        top_input = read_json_safe(work / 'results/run-input.json')
        if top_input and (work / 'armed').exists() and not (work / 'results/build.exit').exists():
            armed_build = str(top_input.get('buildNumber', '')) or None
        zip_removals = select_tokay_out_zips(
            work / 'source/out/target/product/tokay', now_ts=now_ts, armed_build=armed_build
        )
        work_targets: list[Path] = [r.path for r in retired]
        for info in upper_prunes:
            for sub in ('upper', 'work'):
                p = info.path / sub
                if p.exists():
                    work_targets.append(p)
        work_targets.extend(item['path'] for item in zip_removals)
        reclaimed = estimate_reclaimed_bytes(work_targets)
        return kept, retired, upper_prunes, zip_removals, reclaimed

    kept_runs, retired_runs, upper_prunes, zip_removals, work_reclaimed = _compute_work_actions(policy)

    work_usage = shutil.disk_usage(work)
    work_pressure = False
    if (work_usage.free + work_reclaimed) < int(policy.min_free_gib * 1024**3):
        work_pressure = True
        tighter = dataclasses.replace(
            policy,
            keep_full_runs=min(policy.keep_full_runs, 2),
            keep_scratch_runs=min(policy.keep_scratch_runs, 2),
            scratch_max_age_days=min(policy.scratch_max_age_days, 2.0),
            overlay_upper_max_age_hours=min(policy.overlay_upper_max_age_hours, 2.0),
        )
        kept_runs, retired_runs, upper_prunes, zip_removals, work_reclaimed = _compute_work_actions(tighter)

    # 4. Root disk staging cleanup (with pressure adaptation).
    staging_actions = select_staging_dirs(
        root_dir=root_dir,
        now_ts=now_ts,
        var_tmp_max_age_hours=policy.var_tmp_max_age_hours,
        keep_var_tmp=policy.keep_var_tmp,
        home_staging_max_age_days=policy.home_staging_max_age_days,
        keep_home_staging=policy.keep_home_staging,
        mount_paths=mount_paths,
        mounted_host_paths=mounted_host_paths,
    )
    root_reclaimed = estimate_reclaimed_bytes([item['path'] for item in staging_actions])
    root_usage = shutil.disk_usage(root_dir)
    root_pressure = False
    if (root_usage.free + root_reclaimed) < int(policy.min_root_free_gib * 1024**3):
        root_pressure = True
        staging_actions = select_staging_dirs(
            root_dir=root_dir,
            now_ts=now_ts,
            var_tmp_max_age_hours=min(policy.var_tmp_max_age_hours, 6.0),
            keep_var_tmp=min(policy.keep_var_tmp, 2),
            home_staging_max_age_days=min(policy.home_staging_max_age_days, 1.0),
            keep_home_staging=min(policy.keep_home_staging, 2),
            mount_paths=mount_paths,
            mounted_host_paths=mounted_host_paths,
        )
        root_reclaimed = estimate_reclaimed_bytes([item['path'] for item in staging_actions])

    # 5. Untagged dangling Docker images (strictly excluding builder digests).
    protected_images = set(used_image_ids)
    top_input = read_json_safe(work / 'results/run-input.json')
    if top_input and isinstance(top_input.get('builderImage'), str):
        protected_images.add(top_input['builderImage'])
    for name in kept_runs:
        protected_images.update(runs[name].image_digests)

    images_to_remove: list[dict] = []
    for img in images:
        img_id = img.get('ID') or ''
        repo = img.get('Repository') or ''
        tag = img.get('Tag') or ''
        if repo != '<none>' or tag != '<none>':
            continue
        if img_id in protected_images:
            continue
        created_at = img.get('CreatedAt') or ''
        created_ts = None
        if created_at:
            # Example: "2026-11-02 08:40:12 +0000 UTC"
            parts = created_at.split()
            if len(parts) >= 3:
                try:
                    created_ts = datetime.datetime.strptime(
                        ' '.join(parts[:3]), '%Y-%m-%d %H:%M:%S %z'
                    ).timestamp()
                except ValueError:
                    created_ts = None
        if created_ts is not None and (now_ts - created_ts) >= 72 * 3600:
            images_to_remove.append({'id': img_id, 'createdAt': created_at})

    fs_actions = []
    for r in retired_runs:
        size, _ = tree_usage(r.path)
        fs_actions.append({
            'action': 'retire-run',
            'run': r.name,
            'path': str(r.path),
            'buildNumber': r.build_number,
            'isFullExport': r.is_full_export,
            'apparentBytes': size,
        })
    for r in upper_prunes:
        targets = [r.path / s for s in ('upper', 'work') if (r.path / s).exists()]
        fs_actions.append({
            'action': 'prune-overlay-upper',
            'run': r.name,
            'paths': [str(p) for p in targets],
            'reclaimedBytes': estimate_reclaimed_bytes(targets),
        })
    for item in zip_removals:
        p = item['path']
        fs_actions.append({
            'action': 'remove-file',
            'kind': item['kind'],
            'path': str(p),
            'apparentBytes': p.lstat().st_size,
            'hardlinks': p.lstat().st_nlink,
        })
    for item in staging_actions:
        p = item['path']
        size, _ = tree_usage(p)
        fs_actions.append({
            'action': 'remove-staging-dir',
            'kind': item['kind'],
            'path': str(p),
            'reclaimedBytes': estimate_reclaimed_bytes([p]),
            'apparentBytes': size,
        })

    return {
        'schema': SCHEMA,
        'status': 'planned',
        'observedAt': utc_now().isoformat(),
        'workDir': str(work),
        'workPressureMode': work_pressure,
        'rootPressureMode': root_pressure,
        'pinnedBuildNumbers': sorted(pinned_builds),
        'pinnedRuns': sorted(pinned_runs),
        'systemdProtectedRuns': systemd_protected,
        'keptRuns': dict(sorted(kept_runs.items())),
        'filesystemActions': fs_actions,
        'containersToRemove': containers_to_remove,
        'imagesToRemove': images_to_remove,
        'estimatedWorkReclaimedBytes': work_reclaimed,
        'estimatedRootReclaimedBytes': root_reclaimed,
        'workDiskFreeBeforeBytes': work_usage.free,
        'rootDiskFreeBeforeBytes': root_usage.free,
    }


def apply_plan(plan: dict, *, work: Path, root_dir: Path) -> dict:
    retired_root = work / 'retired-runs'
    executed = []
    errors = []

    for item in plan['containersToRemove']:
        res = subprocess.run(['docker', 'rm', item['id']], capture_output=True, text=True, check=False)
        if res.returncode == 0:
            executed.append({'action': 'remove-container', 'name': item['name'], 'id': item['id']})
        else:
            errors.append({'action': 'remove-container', 'name': item['name'], 'error': res.stderr.strip()})

    for item in plan['imagesToRemove']:
        res = subprocess.run(['docker', 'rmi', item['id']], capture_output=True, text=True, check=False)
        if res.returncode == 0:
            executed.append({'action': 'remove-image', 'id': item['id']})
        else:
            errors.append({'action': 'remove-image', 'id': item['id'], 'error': res.stderr.strip()})

    # Re-read /proc/mounts immediately before touching any filesystem entry.
    live_mounts = parse_proc_mounts(Path('/proc/mounts').read_text(encoding='utf-8')) if Path('/proc/mounts').exists() else set()

    for act in plan['filesystemActions']:
        kind = act['action']
        if kind == 'retire-run':
            target = Path(act['path'])
            if not target.exists():
                continue
            if has_active_mount(target, live_mounts):
                errors.append({'action': kind, 'path': str(target), 'error': 'mount appeared before deletion'})
                continue
            reclaimed = estimate_reclaimed_bytes([target])
            receipt_dir = archive_retired_run_receipts(target, retired_root, 'expired-by-retention-policy', reclaimed)
            rmtree_force(target)
            executed.append({**act, 'receiptArchive': str(receipt_dir), 'reclaimedBytes': reclaimed})
        elif kind == 'prune-overlay-upper':
            run_path = work / 'runs' / act['run']
            if has_active_mount(run_path, live_mounts):
                errors.append({'action': kind, 'run': act['run'], 'error': 'mount appeared before deletion'})
                continue
            targets = [Path(p) for p in act['paths'] if Path(p).exists()]
            reclaimed = estimate_reclaimed_bytes(targets)
            for t in targets:
                rmtree_force(t)
                t.mkdir(mode=0o755, exist_ok=True)
            marker = run_path / 'overlay-upper-pruned.json'
            marker.write_text(json.dumps({
                'schema': 'taku.overlay-upper-pruned/1',
                'prunedAt': utc_now().isoformat(),
                'reclaimedBytes': reclaimed,
            }, indent=2) + '\n', encoding='utf-8')
            executed.append({**act, 'reclaimedBytes': reclaimed})
        elif kind == 'remove-file':
            target = Path(act['path'])
            if target.exists() or target.is_symlink():
                target.unlink()
                executed.append(act)
        elif kind == 'remove-staging-dir':
            target = Path(act['path'])
            if not target.exists():
                continue
            if has_active_mount(target, live_mounts):
                errors.append({'action': kind, 'path': str(target), 'error': 'mount appeared before deletion'})
                continue
            rmtree_force(target)
            executed.append(act)

    work_after = shutil.disk_usage(work)
    root_after = shutil.disk_usage(root_dir)
    result = {
        **plan,
        'status': 'applied' if not errors else 'partial-error',
        'appliedAt': utc_now().isoformat(),
        'executedCount': len(executed),
        'errors': errors,
        'workDiskFreeAfterBytes': work_after.free,
        'rootDiskFreeAfterBytes': root_after.free,
        'actualWorkFreedBytes': work_after.free - plan['workDiskFreeBeforeBytes'],
        'actualRootFreedBytes': root_after.free - plan['rootDiskFreeBeforeBytes'],
    }
    history = work / 'prune-history.jsonl'
    with history.open('a', encoding='utf-8') as log:
        log.write(json.dumps({
            'schema': SCHEMA,
            'appliedAt': result['appliedAt'],
            'status': result['status'],
            'executedCount': result['executedCount'],
            'actualWorkFreedBytes': result['actualWorkFreedBytes'],
            'actualRootFreedBytes': result['actualRootFreedBytes'],
            'workDiskFreeAfterBytes': result['workDiskFreeAfterBytes'],
            'rootDiskFreeAfterBytes': result['rootDiskFreeAfterBytes'],
            'errors': errors,
        }) + '\n')
        log.flush()
        os.fsync(log.fileno())
    return result


def install_systemd_units(work: Path, systemd_dir: Path):
    script_dest = work / 'cloud-builder-prune.py'
    src = Path(__file__).resolve()
    if src != script_dest.resolve():
        shutil.copy2(src, script_dest)
        os.chmod(script_dest, 0o755)

    service = f"""[Unit]
Description=Prune stale Taku OS build outputs on persistent work disk
RequiresMountsFor={work}
After=docker.service local-fs.target

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 {script_dest} --work-dir {work} --apply
Nice=10
IOSchedulingClass=best-effort
IOSchedulingPriority=7
"""
    timer = """[Unit]
Description=Periodic Taku OS cloud builder artifact cleanup

[Timer]
OnBootSec=5min
OnUnitActiveSec=2h
Persistent=true
RandomizedDelaySec=30s

[Install]
WantedBy=timers.target
"""
    (systemd_dir / 'taku-builder-prune.service').write_text(service, encoding='utf-8')
    (systemd_dir / 'taku-builder-prune.timer').write_text(timer, encoding='utf-8')
    subprocess.run(['systemctl', 'daemon-reload'], check=True)
    subprocess.run(['systemctl', 'enable', '--now', 'taku-builder-prune.timer'], check=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-dir', type=Path, default=Path('/mnt/taku-build'))
    parser.add_argument('--root-dir', type=Path, default=Path('/'))
    parser.add_argument('--systemd-dir', type=Path, default=Path('/etc/systemd/system'))
    parser.add_argument('--releases-dir', type=Path, action='append', default=[])
    parser.add_argument('--keep-full-runs', type=int, default=3)
    parser.add_argument('--keep-scratch-runs', type=int, default=5)
    parser.add_argument('--scratch-max-age-days', type=float, default=5.0)
    parser.add_argument('--overlay-upper-max-age-hours', type=float, default=12.0)
    parser.add_argument('--var-tmp-max-age-hours', type=float, default=24.0)
    parser.add_argument('--keep-var-tmp', type=int, default=3)
    parser.add_argument('--home-staging-max-age-days', type=float, default=3.0)
    parser.add_argument('--keep-home-staging', type=int, default=3)
    parser.add_argument('--container-max-age-hours', type=float, default=24.0)
    parser.add_argument('--keep-containers', type=int, default=3)
    parser.add_argument('--min-free-gib', type=float, default=200.0)
    parser.add_argument('--min-root-free-gib', type=float, default=12.0)
    parser.add_argument('--allow-unmounted-work', action='store_true')
    parser.add_argument('--skip-docker', action='store_true')
    parser.add_argument('--apply', action='store_true', help='Execute deletions (default is dry-run)')
    parser.add_argument('--install-systemd', action='store_true', help='Install and enable taku-builder-prune.timer')
    args = parser.parse_args(argv)

    work = args.work_dir.resolve()
    if not work.is_dir():
        raise SystemExit(f'Work directory does not exist: {work}')
    if not args.allow_unmounted_work and not os.path.ismount(work):
        raise SystemExit(f'Work directory is not a mounted filesystem: {work}')

    if args.install_systemd:
        install_systemd_units(work, args.systemd_dir.resolve())

    lock_path = work / 'worker.lock'
    with lock_path.open('a', encoding='utf-8') as lock_file:
        try:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(json.dumps({
                'schema': SCHEMA,
                'status': 'skipped',
                'reason': 'worker-lock-held',
                'observedAt': utc_now().isoformat(),
            }, indent=2))
            return 0

        mount_text = Path('/proc/mounts').read_text(encoding='utf-8') if Path('/proc/mounts').exists() else ''
        mount_paths = parse_proc_mounts(mount_text)
        containers, images = ([], []) if args.skip_docker else inspect_docker()

        policy = Policy(
            keep_full_runs=args.keep_full_runs,
            keep_scratch_runs=args.keep_scratch_runs,
            scratch_max_age_days=args.scratch_max_age_days,
            overlay_upper_max_age_hours=args.overlay_upper_max_age_hours,
            var_tmp_max_age_hours=args.var_tmp_max_age_hours,
            keep_var_tmp=args.keep_var_tmp,
            home_staging_max_age_days=args.home_staging_max_age_days,
            keep_home_staging=args.keep_home_staging,
            container_max_age_hours=args.container_max_age_hours,
            keep_containers=args.keep_containers,
            min_free_gib=args.min_free_gib,
            min_root_free_gib=args.min_root_free_gib,
        )
        plan = build_plan(
            work=work,
            root_dir=args.root_dir.resolve(),
            systemd_dir=args.systemd_dir.resolve(),
            releases_dirs=[p.resolve() for p in args.releases_dir],
            policy=policy,
            now_ts=utc_now().timestamp(),
            mount_paths=mount_paths,
            containers=containers,
            images=images,
        )
        if plan['status'] == 'skipped' or not args.apply:
            print(json.dumps(plan, indent=2))
            return 0

        result = apply_plan(plan, work=work, root_dir=args.root_dir.resolve())
        print(json.dumps(result, indent=2))
        return 0 if result['status'] == 'applied' else 1


if __name__ == '__main__':
    raise SystemExit(main())
