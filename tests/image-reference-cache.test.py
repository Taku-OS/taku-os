#!/usr/bin/env python3
"""Real shallow Git fetch regression; fixtures never access a build volume."""
import hashlib
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('cache', ROOT / 'image/prepare-reference-cache.py')
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


def command(*args, check=True):
    return subprocess.run(list(args), check=check, capture_output=True, text=True)


def tree_state(root):
    return {str(p.relative_to(root)): ('link:' + str(p.readlink()) if p.is_symlink()
            else hashlib.sha256(p.read_bytes()).hexdigest())
            for p in root.rglob('*') if p.is_symlink() or p.is_file()}


class ShallowReference(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.source, self.reference = self.base / 'source', self.base / 'reference'
        self.origin = self.base / 'origin'
        command('git', 'init', '-q', str(self.origin))
        for value in ['parent absent from shallow cache', 'old Android base', 'new Android base']:
            (self.origin / 'input.txt').write_text(value)
            command('git', '-C', str(self.origin), 'add', 'input.txt')
            command('git', '-C', str(self.origin), '-c', 'user.name=Fixture',
                    '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false',
                    'commit', '-qm', value)
            if value == 'old Android base':
                self.old = command('git', '-C', str(self.origin), 'rev-parse', 'HEAD').stdout.strip()
                command('git', '-C', str(self.origin), 'branch', 'old-base')
        self.new = command('git', '-C', str(self.origin), 'rev-parse', 'HEAD').stdout.strip()
        self.projects = [{'name': 'example/app', 'path': 'app', 'revision': self.new}]
        command('git', 'init', '--bare', str(self.source / '.repo/manifests.git'))
        self.add_cache('example/app', 'app')

    def add_cache(self, name, path):
        obj = self.reference / '.repo/project-objects' / (name + '.git')
        command('git', 'clone', '--bare', '--depth=1', '--branch=old-base',
                self.origin.as_uri(), str(obj))
        gitdir = self.reference / '.repo/projects' / (path + '.git')
        gitdir.mkdir(parents=True)
        # Repo stores shallow in each project gitdir, not its shared objdir.
        (obj / 'shallow').rename(gitdir / 'shallow')
        (gitdir / 'objects').symlink_to(obj / 'objects', target_is_directory=True)
        listing = self.reference / '.repo/project.list'
        with listing.open('a') as stream:
            stream.write(path + '\n')

    def prepare(self):
        with mock.patch.object(cache, 'read_manifest', return_value=self.projects):
            return cache.prepare(self.source, self.reference)

    def initialize_project_gitdir(self, copy_shallow=True):
        obj = self.source / '.repo/project-objects/example/app.git'
        gitdir = self.source / '.repo/projects/app.git'
        command('git', 'init', '--bare', str(gitdir))
        shutil.rmtree(gitdir / 'objects')
        (gitdir / 'objects').symlink_to(obj / 'objects', target_is_directory=True)
        if copy_shallow:
            shutil.copy2(obj / 'shallow', gitdir / 'shallow')
        command('git', '--git-dir=' + str(gitdir), 'remote', 'add', 'github', self.origin.as_uri())
        # repo's first fetch imports the reference's old refs before fetching.
        command('git', '--git-dir=' + str(gitdir), 'update-ref', 'refs/_alt/old-base', self.old)
        return gitdir

    def fetch(self, gitdir, check=True):
        return command('git', '--git-dir=' + str(gitdir), 'fetch', '--depth=1',
                       '--no-tags', 'github', self.new, check=check)

    def test_first_fetch_inherits_old_boundary_even_when_target_sha_changes(self):
        before = tree_state(self.reference)
        report = self.prepare()
        self.assertNotEqual(self.old, self.new)
        self.assertEqual(report['projectsWithInheritedShallow'], 1)
        self.assertFalse((self.source / '.repo/projects/app.git').exists(),
                         'repo must still recognize a new checkout and keep depth=1')
        gitdir = self.initialize_project_gitdir()
        self.fetch(gitdir)
        self.assertEqual(command('git', '--git-dir=' + str(gitdir), 'rev-parse', 'FETCH_HEAD').stdout.strip(), self.new)
        self.assertEqual(tree_state(self.reference), before)

    def test_missing_boundary_reproduces_fetch_failure_and_retry_repairs_it(self):
        self.prepare()
        gitdir = self.initialize_project_gitdir(copy_shallow=False)
        failed = self.fetch(gitdir, check=False)
        self.assertNotEqual(failed.returncode, 0, failed.stdout + failed.stderr)
        self.assertTrue('Could not read' in failed.stderr or 'Failed to traverse' in failed.stderr,
                        failed.stderr)
        self.prepare()
        self.assertIn(self.old, cache.boundaries(gitdir / 'shallow'))
        self.fetch(gitdir)

    def test_retry_preserves_new_boundaries_and_is_idempotent(self):
        self.prepare()
        gitdir = self.initialize_project_gitdir()
        self.fetch(gitdir)
        before = cache.boundaries(gitdir / 'shallow')
        self.assertIn(self.new, before)
        self.prepare()
        self.assertEqual(cache.boundaries(gitdir / 'shallow'), before)
        self.assertEqual(self.prepare()['updatedShallowFiles'], 0)

    def test_merged_device_projects_receive_the_same_preparation(self):
        self.add_cache('example/device', 'device/google/tokay')
        self.projects.append({'name': 'example/device', 'path': 'device/google/tokay', 'revision': self.new})
        report = self.prepare()
        self.assertEqual(report['seededObjectStores'], 2)
        self.assertEqual(cache.boundaries(self.source / '.repo/project-objects/example/device.git/shallow'), {self.old})

    def test_kernel_root_uses_the_same_contract(self):
        source = self.source / 'out-kernel/google/gs-6.1'
        reference = self.reference / 'out-kernel/google/gs-6.1'
        command('git', 'init', '--bare', str(source / '.repo/manifests.git'))
        # A separate reference checkout can have arbitrary nested root paths.
        shutil.copytree(self.reference / '.repo', reference / '.repo', symlinks=True)
        objects = reference / '.repo/projects/app.git/objects'
        objects.unlink()
        objects.symlink_to(reference / '.repo/project-objects/example/app.git/objects', target_is_directory=True)
        with mock.patch.object(cache, 'read_manifest', return_value=self.projects):
            report = cache.prepare(source, reference)
        self.assertEqual(report['manifestProjects'], 1)
        self.assertEqual(report['projectsWithInheritedShallow'], 1)
        self.assertEqual(cache.boundaries(source / '.repo/project-objects/example/app.git/shallow'), {self.old})

    def test_empty_reference_is_explicit_and_never_masks_partial_cache(self):
        empty = self.base / 'empty-reference'
        empty.mkdir()
        report = cache.prepare(self.source, empty, empty)
        self.assertFalse(report['cacheAvailable'])
        self.assertFalse(report['referenceModified'])
        self.assertEqual(cache.prepare(self.source, empty / 'out-kernel', empty)['emptyReferenceParent'], str(empty.resolve()))
        (empty / 'partial').mkdir()
        with self.assertRaisesRegex(ValueError, 'Missing read-only repo reference'):
            cache.prepare(self.source, empty, empty)
        with self.assertRaisesRegex(ValueError, 'Missing read-only repo reference'):
            cache.prepare(self.source, empty / 'out-kernel', empty)

    def test_project_without_cache_remains_new_for_repo_depth_one(self):
        self.projects.append({'name': 'new/project', 'path': 'new-project', 'revision': self.new})
        report = self.prepare()
        self.assertEqual(report['manifestProjects'], 2)
        self.assertEqual(report['seededObjectStores'], 1)
        self.assertFalse((self.source / '.repo/projects/new-project.git').exists())
        self.assertFalse((self.source / '.repo/project-objects/new/project.git').exists())

    def test_interrupted_uncached_project_is_preserved_then_reinitialized(self):
        self.projects.append({'name': 'new/project', 'path': 'new-project', 'revision': self.new})
        gitdir = self.source / '.repo/projects/new-project.git'
        obj = self.source / '.repo/project-objects/new/project.git'
        command('git', 'init', '--bare', str(gitdir))
        command('git', 'init', '--bare', str(obj))
        (gitdir / 'interruption-evidence').write_text('preserve this failed attempt')
        report = self.prepare()
        self.assertEqual(report['recoveredUninitializedGitdirs'], 1)
        self.assertFalse(gitdir.exists())
        self.assertTrue((obj / 'config').exists())
        preserved = list((self.source / 'metadata-repair').rglob('interruption-evidence'))
        self.assertEqual(len(preserved), 1)
        self.assertEqual(preserved[0].read_text(), 'preserve this failed attempt')

    def test_public_manifest_command_covers_local_manifests(self):
        xml = '<manifest><project name="example/app" path="app" revision="' + self.new + '"/>'
        xml += '<project name="example/device" path="device/google/tokay" revision="' + self.new + '"/></manifest>'
        with mock.patch.object(cache.subprocess, 'run', return_value=mock.Mock(stdout=xml)) as run:
            projects = cache.read_manifest(self.source)
        self.assertEqual(len(projects), 2)
        self.assertEqual(run.call_args.args[0], ['repo', 'manifest', '-o', '-'])
        self.assertEqual(run.call_args.kwargs['cwd'], self.source)


if __name__ == '__main__':
    unittest.main()
