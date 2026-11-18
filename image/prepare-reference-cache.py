#!/usr/bin/env python3
"""Prepare shallow project caches before repo's first fetch; never edit the cache."""
import argparse
import collections
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def git(gitdir, *args, **kwargs):
    return subprocess.run(['git', '--git-dir=' + str(gitdir), *args],
                          check=True, capture_output=True, text=True, **kwargs)


def relative(value):
    path = Path(value)
    if not value or path.is_absolute() or '..' in path.parts:
        raise ValueError('Unsafe manifest/cache path: ' + value)
    return path


def owned(path, root):
    if not path.resolve().is_relative_to(root):
        raise ValueError('Destination escapes generated checkout: ' + str(path))
    return path


def boundaries(path):
    if not path.is_file():
        return set()
    values = set(path.read_text().splitlines())
    if any(not re.fullmatch('[0-9a-f]{40}', value) for value in values):
        raise ValueError('Invalid shallow boundary: ' + str(path))
    return values


def merge_boundaries(path, values, root):
    if not values:
        return False
    owned(path, root)
    content = ''.join(value + '\n' for value in sorted(boundaries(path) | values))
    if path.is_file() and path.read_text() == content:
        return False
    path.write_text(content)
    return True


def read_manifest(root):
    # Public repo command: includes local_manifests and works before checkout.
    output = subprocess.run(['repo', 'manifest', '-o', '-'], cwd=root,
                            check=True, capture_output=True, text=True).stdout
    projects = []
    for item in ET.fromstring(output).findall('project'):
        name = relative(item.attrib['name'])
        path = relative(item.get('path', item.attrib['name']))
        revision = item.get('revision', '')
        if not re.fullmatch('[0-9a-f]{40}', revision):
            raise ValueError('Expected an immutable manifest revision: ' + str(path))
        projects.append({'name': str(name), 'path': str(path), 'revision': revision})
    if not projects:
        raise ValueError('Merged repo manifest has no projects')
    return projects


def cache_boundaries(reference, projects):
    """Union boundaries for every old checkout sharing the same object store."""
    paths = {item['path'] for item in projects}
    listing = reference / '.repo/project.list'
    if listing.is_file():
        paths.update(str(relative(line)) for line in listing.read_text().splitlines() if line)
    result = collections.defaultdict(set)
    for path in sorted(paths):
        gitdir = reference / '.repo/projects' / (path + '.git')
        values = boundaries(gitdir / 'shallow')
        if not values:
            continue
        objects = gitdir / 'objects'
        if objects.is_symlink():
            result[objects.resolve()].update(values)
        else:
            # repo's newer layout gives each gitdir an alternate to its objdir.
            alternates = objects / 'info/alternates'
            if alternates.is_file():
                for alternate in alternates.read_text().splitlines():
                    result[(objects / alternate).resolve()].update(values)
    return result


def prepare(source_root, reference_root, empty_reference_parent=None):
    root, reference = Path(source_root).resolve(), Path(reference_root).resolve()
    if root == reference or root.is_relative_to(reference) or reference.is_relative_to(root):
        raise ValueError('Source and read-only reference must be separate checkouts')
    if not (root / '.repo/manifests.git/config').is_file():
        raise ValueError('Initialize the pinned manifest without --reference first')
    if not (reference / '.repo').is_dir():
        parent = Path(empty_reference_parent).resolve() if empty_reference_parent else None
        if (parent is None or not parent.is_dir() or any(parent.iterdir())
                or not (reference == parent or reference.is_relative_to(parent))):
            raise ValueError('Missing read-only repo reference: ' + str(reference))
        report = {'sourceRoot': str(root), 'referenceRoot': str(reference),
                  'emptyReferenceParent': str(parent), 'referenceModified': False,
                  'cacheAvailable': False}
        (root / '.repo/taku-reference-cache.json').write_text(json.dumps(report, indent=2) + '\n')
        return report
    projects = read_manifest(root)
    shared = cache_boundaries(reference, projects)
    recovery = root / 'metadata-repair' / ('reference-' + str(time.time_ns()))
    index_recoveries = set()
    recovered = 0
    for area in ('projects', 'project-objects'):
        base = root / '.repo' / area
        for lock in base.rglob('*.lock'):
            owned(lock, root)
            if not lock.is_file():
                continue
            target = recovery / lock.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            lock.rename(target)
            recovered += 1
            if area == 'projects' and lock.name == 'index.lock':
                index_recoveries.add(str(lock.parent.relative_to(base))[:-4])

    seeded, inherited, updated, partial = set(), 0, 0, 0
    for item in projects:
        name, path = item['name'], item['path']
        old = reference / '.repo/project-objects' / (name + '.git')
        obj = owned(root / '.repo/project-objects' / (name + '.git'), root)
        gitdir = owned(root / '.repo/projects' / (path + '.git'), root)
        values = shared[(old / 'objects').resolve()] | boundaries(old / 'shallow')
        worktree_git = root / path / '.git'
        if (gitdir.is_dir() and not values and not (gitdir / 'shallow').exists()
                and not worktree_git.exists() and not worktree_git.is_symlink()):
            head = subprocess.run(['git', '--git-dir=' + str(gitdir), 'rev-parse',
                                   '--verify', 'HEAD^{commit}'], capture_output=True)
            if head.returncode:
                # An interrupted first fetch must not turn into a full-history
                # fetch. Keep its metadata and shared object store, but let repo
                # initialize a new gitdir (is_new=True) with the configured depth.
                target = recovery / 'uninitialized' / gitdir.relative_to(root)
                target.parent.mkdir(parents=True, exist_ok=True)
                gitdir.rename(target)
                partial += 1
        if not (old / 'objects').is_dir():
            continue
        if name not in seeded:
            # Keep project gitdirs absent so repo retains is_new=True/depth=1.
            # Its copy_all initialization inherits this objdir's shallow file
            # before _RemoteFetch imports the old reference's refs.
            obj.parent.mkdir(parents=True, exist_ok=True)
            if not (obj / 'config').is_file():
                git(obj, 'init', '--bare')
            info = owned(obj / 'objects/info', root)
            info.mkdir(parents=True, exist_ok=True)
            (obj / 'objects/pack').mkdir(parents=True, exist_ok=True)
            alternate = info / 'alternates'
            owned(alternate, root)
            if not alternate.exists():
                alternate.write_text(str(old / 'objects') + '\n')
            if values:
                output = git(obj, 'cat-file', '--batch-check',
                             input=''.join(value + '\n' for value in sorted(values))).stdout
                if any(len(line.split()) != 3 or line.split()[1] != 'commit'
                       for line in output.splitlines()):
                    raise ValueError('Reference shallow boundary objects are unavailable: ' + name)
                updated += int(merge_boundaries(obj / 'shallow', values, root))
            seeded.add(name)
        if values:
            inherited += 1
            if gitdir.is_dir():
                updated += int(merge_boundaries(gitdir / 'shallow', values, root))

    # Manifest bootstrap has no reference; enable it only after cache metadata.
    git(root / '.repo/manifests.git', 'config', 'repo.reference', str(reference))
    for item in projects:
        if item['path'] not in index_recoveries or not (root / item['path'] / '.git').exists():
            continue
        gitdir = root / '.repo/projects' / (item['path'] + '.git')
        check = subprocess.run(['git', '--git-dir=' + str(gitdir), 'cat-file', '-e',
                                item['revision'] + '^{commit}'], capture_output=True)
        if check.returncode == 0:
            subprocess.run(['git', '-C', str(root / item['path']), 'read-tree', '--reset',
                            '-u', item['revision']], check=True, capture_output=True)
    report = {'sourceRoot': str(root), 'referenceRoot': str(reference),
              'manifestProjects': len(projects), 'seededObjectStores': len(seeded),
              'projectsWithInheritedShallow': inherited, 'updatedShallowFiles': updated,
              'recoveredLocks': recovered, 'recoveredUninitializedGitdirs': partial,
              'referenceModified': False}
    (root / '.repo/taku-reference-cache.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=Path('/src'))
    parser.add_argument('--reference-root', type=Path, default=Path('/previous'))
    parser.add_argument('--empty-reference-parent', type=Path,
                        help='Allow no cache only when this entire mounted parent is empty')
    args = parser.parse_args()
    try:
        print(json.dumps(prepare(args.source_root, args.reference_root, args.empty_reference_parent), indent=2))
    except (OSError, ValueError, subprocess.CalledProcessError, ET.ParseError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
