#!/usr/bin/env python3
"""Initialize a pinned manifest independently of shallow project caches.

The caller owns the generated source volume's exclusive builder lock.
"""
import argparse
import datetime
import pathlib
import re
import shutil
import subprocess


def anchor_nested_repo(root, backup):
    """Install independent repo tooling before the launcher can find a parent.

    The installed launcher (git-repo a0bc2b14...) walks upward looking for
    .repo/repo/main.py, not merely .repo. REPO_DIR is not an override. Copy the
    existing ancestor's tooling, including its version/remote configuration,
    so both init and later ordinary `repo sync` use the intended nested root.
    """
    repodir = root / '.repo'
    if repodir.is_symlink():
        raise RuntimeError('Source .repo directory must not be a symlink')
    local = repodir / 'repo'
    if local.is_symlink():
        raise RuntimeError('Repo tooling must not share a symlink with another checkout')
    if (local / 'main.py').is_file():
        return
    ancestor = next((parent / '.repo/repo' for parent in root.parents
                     if (parent / '.repo/repo/main.py').is_file()), None)
    if ancestor is None:
        return  # Top-level bootstrap remains the launcher's responsibility.
    if not (ancestor / '.git').is_dir() or (ancestor / '.git').is_symlink():
        raise RuntimeError('Ancestor repo tooling must have independent Git metadata')
    repodir.mkdir(parents=True, exist_ok=True)
    if local.exists():
        saved = backup / '.repo/repo'
        saved.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(local), str(saved))
    staging = repodir / ('repo-bootstrap-' + backup.name)
    # copytree copies regular files instead of creating Git-object hardlinks.
    # Dereference any tooling symlinks so no later self-update changes a parent.
    shutil.copytree(ancestor, staging, symlinks=False,
                    ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    alternates = staging / '.git/objects/info/alternates'
    if alternates.is_file() and alternates.read_text().strip():
        subprocess.run(['git', '-C', str(staging), 'repack', '-a', '-d'], check=True)
        alternates.unlink()
    if not (staging / 'main.py').is_file():
        raise RuntimeError('Copied repo tooling has no launcher entry point')
    staging.rename(local)


def preserve_reference_manifest(root, gitdir, backup):
    """Quarantine only manifest metadata that still depends on an old cache."""
    config = gitdir / 'config'
    has_reference = False
    if config.is_file():
        # Even --file can discover the caller's Git worktree. A read-only host
        # worktree mount may reference an unavailable .git directory in Docker.
        result = subprocess.run(['git', 'config', '--file', str(config),
                                 '--get-all', 'repo.reference'],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, cwd=root)
        if result.returncode not in (0, 1):
            raise RuntimeError('Could not read manifest reference configuration')
        has_reference = result.returncode == 0
    alternates = gitdir / 'objects/info/alternates'
    has_alternates = alternates.is_file() and bool(alternates.read_text().strip())
    if not has_alternates:
        # Project-cache preparation also sets repo.reference for later project
        # fetches. Without manifest object alternates this is a healthy,
        # independent manifest repository; clear only the project-cache option
        # before init and let project-cache preparation restore it afterwards.
        if has_reference:
            result = subprocess.run(['git', 'config', '--file', str(config),
                                     '--unset-all', 'repo.reference'], cwd=root)
            if result.returncode not in (0, 5):
                raise RuntimeError('Could not clear manifest reference configuration')
        return
    # Clearing repo.reference alone does not repair refs imported without their
    # shallow boundaries. Preserve this small generated manifest checkout and
    # fetch it independently. Successful retries have no reference dependency,
    # so they reuse their manifest repository without repeated quarantine.
    for name in ('manifests.git', 'manifests', 'manifest.xml'):
        path = root / '.repo' / name
        if path.exists() or path.is_symlink():
            saved = backup / path.relative_to(root)
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(path), str(saved))


def initialize(root, url, commit, manifest):
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Manifest commit must be pinned')
    root = root.resolve()
    manifest = manifest.resolve()
    root.mkdir(parents=True, exist_ok=True)
    gitdir = root / '.repo/manifests.git'
    # A previous interrupted init must not prevent the recovery code from running.
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    backup = root / 'metadata-repair' / stamp
    anchor_nested_repo(root, backup)
    preserve_reference_manifest(root, gitdir, backup)
    for lock in gitdir.rglob('*.lock'):
        if lock.is_file():
            saved = backup / lock.relative_to(root)
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(lock), str(saved))
    # repo imports a reference repository's refs without its shallow boundaries.
    # Manifest repositories are small; fetch them directly and handle project
    # object reuse separately after loading the complete pinned manifest.
    subprocess.run(['repo', 'init', '-v', '-u', url, '-b', commit, '-m', 'default.xml',
                    '--depth=1', '--git-lfs', '--no-clone-bundle'], cwd=root, check=True)
    actual = subprocess.check_output(['git', '-C', str(root / '.repo/manifests'),
                                      'rev-parse', 'HEAD'], text=True).strip()
    if actual != commit:
        raise RuntimeError('Initialized manifest differs from its pinned commit')
    destination = root / '.repo/manifests' / manifest.name
    shutil.copy2(manifest, destination)
    subprocess.run(['repo', 'init', '-m', manifest.name], cwd=root, check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=pathlib.Path, required=True)
    parser.add_argument('--url', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--manifest', type=pathlib.Path, required=True)
    args = parser.parse_args()
    initialize(args.source_root.resolve(), args.url, args.commit, args.manifest.resolve())
