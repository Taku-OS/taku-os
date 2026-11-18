#!/usr/bin/env python3
"""Check interrupted manifest recovery with local Git; no network or Docker."""
import importlib.util
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('manifest_init', ROOT / 'image/init-source-manifest.py')
manifest_init = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest_init)
REAL_RUN = subprocess.run


class ManifestInitialization(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = pathlib.Path(self.temporary.name)
        self.source = self.base / 'generated'
        self.upstream = self.base / 'upstream'
        self.reference = self.base / 'old-reference'
        self.manifest = self.base / 'frozen/platform.xml'
        self.manifest.parent.mkdir()
        self.manifest.write_text('<manifest><!-- locked fixture --></manifest>\n')
        self.git('init', '-q', str(self.upstream))
        self.git('-C', str(self.upstream), 'config', 'user.name', 'Fixture')
        self.git('-C', str(self.upstream), 'config', 'user.email', 'fixture@example.invalid')
        for number in range(3):
            (self.upstream / 'default.xml').write_text(f'<manifest><!-- {number} --></manifest>\n')
            self.git('-C', str(self.upstream), 'add', 'default.xml')
            self.git('-C', str(self.upstream), 'commit', '-qm', f'fixture {number}')
            if number == 1:
                self.old_commit = self.git('-C', str(self.upstream), 'rev-parse', 'HEAD').stdout.strip()
                self.git('-C', str(self.upstream), 'branch', 'old', self.old_commit)
        self.commit = self.git('-C', str(self.upstream), 'rev-parse', 'HEAD').stdout.strip()
        self.repo_calls = []
        self.checkout_commit = self.commit
        self.fail_first_init = False

    def git(self, *args):
        return REAL_RUN(['git', *args], check=True, text=True,
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def repo_driver(self, args, **kwargs):
        if args[0] != 'repo':
            return REAL_RUN(args, **kwargs)
        self.repo_calls.append(list(args))
        root = pathlib.Path(kwargs['cwd'])
        # Match the real launcher: an empty .repo is NOT a discovery boundary.
        selected = next((candidate for candidate in (root, *root.parents)
                         if (candidate / '.repo/repo/main.py').is_file()), root)
        self.assertEqual(selected, root, 'repo launcher would mutate the ancestor checkout')
        worktree = root / '.repo/manifests'
        gitdir = root / '.repo/manifests.git'
        if '-u' in args:
            self.assertNotIn('--reference', args)
            self.assertEqual(args[args.index('-b') + 1], self.commit)
            self.assertEqual(args[args.index('-m') + 1], 'default.xml')
            if self.fail_first_init:
                raise subprocess.CalledProcessError(1, args)
            worktree.parent.mkdir(parents=True, exist_ok=True)
            if not (gitdir / 'config').is_file():
                self.git('init', '-q', '--separate-git-dir=' + str(gitdir), str(worktree))
            else:
                worktree.mkdir(exist_ok=True)
                (worktree / '.git').write_text('gitdir: ' + str(gitdir) + '\n')
                self.git('--git-dir=' + str(gitdir), 'config', 'core.bare', 'false')
            # Real Git exercises imported shallow refs and missing-parent errors;
            # this minimal driver replaces only repo's manifest orchestration.
            self.git('--git-dir=' + str(gitdir), '--work-tree=' + str(worktree),
                     'fetch', '--depth=1', '--no-tags', self.upstream.as_uri(), self.commit)
            self.git('--git-dir=' + str(gitdir), '--work-tree=' + str(worktree),
                     'checkout', '--detach', '--force', self.checkout_commit)
        else:
            selected = args[args.index('-m') + 1]
            self.assertEqual((worktree / selected).read_bytes(), self.manifest.read_bytes())
            link = root / '.repo/manifest.xml'
            if link.exists() or link.is_symlink():
                link.unlink()
            link.symlink_to(pathlib.Path('manifests') / selected)
        return subprocess.CompletedProcess(args, 0)

    def initialize(self):
        with mock.patch.object(manifest_init.subprocess, 'run', side_effect=self.repo_driver):
            manifest_init.initialize(self.source, self.upstream.as_uri(), self.commit, self.manifest)

    def backups(self, name):
        return list((self.source / 'metadata-repair').glob('*/.repo/' + name))

    def test_partial_repo_is_initialized_at_exact_commit_and_copies_frozen_manifest(self):
        (self.source / '.repo/repo').mkdir(parents=True)
        trace = self.source / '.repo/TRACE_FILE'
        trace.write_text('previous failed init')
        frozen = self.manifest.read_bytes()
        self.initialize()
        self.assertEqual(self.git('-C', str(self.source / '.repo/manifests'),
                                  'rev-parse', 'HEAD').stdout.strip(), self.commit)
        self.assertEqual((self.source / '.repo/manifest.xml').read_bytes(), frozen)
        self.assertEqual(self.manifest.read_bytes(), frozen)
        self.assertEqual(trace.read_text(), 'previous failed init')
        self.assertEqual(len(self.repo_calls), 2)

    def make_shallow_reference_dependency(self, include_config=True):
        self.git('clone', '-q', '--depth=1', '--branch', 'old',
                 self.upstream.as_uri(), str(self.reference))
        gitdir = self.source / '.repo/manifests.git'
        self.git('init', '-q', '--bare', str(gitdir))
        if include_config:
            self.git('--git-dir=' + str(gitdir), 'config', 'repo.reference', str(self.reference))
        alternates = gitdir / 'objects/info/alternates'
        alternates.write_text(str(self.reference / '.git/objects') + '\n')
        imported = gitdir / 'refs/remotes/origin/old'
        imported.parent.mkdir(parents=True)
        imported.write_text(self.old_commit + '\n')
        (gitdir / 'index.lock').write_text('interrupted manifest lock')
        (self.source / '.repo/manifests').mkdir()
        (self.source / '.repo/manifests/partial.xml').write_text('preserve partial worktree')
        return gitdir

    def test_imported_shallow_refs_are_preserved_then_fetched_independently(self):
        gitdir = self.make_shallow_reference_dependency()
        old_shallow = (self.reference / '.git/shallow').read_bytes()
        unrepaired = REAL_RUN(['git', '--git-dir=' + str(gitdir), 'fetch', '--depth=1',
                               '--no-tags', self.upstream.as_uri(), self.commit],
                              capture_output=True, text=True)
        self.assertNotEqual(unrepaired.returncode, 0,
                            'Fixture must reproduce the interrupted shallow-ref failure')
        self.assertIn('Failed to traverse parents', unrepaired.stderr)
        self.initialize()
        saved = self.backups('manifests.git')
        self.assertEqual(len(saved), 1)
        self.assertEqual((saved[0] / 'refs/remotes/origin/old').read_text().strip(), self.old_commit)
        self.assertEqual((saved[0] / 'index.lock').read_text(), 'interrupted manifest lock')
        self.assertEqual(self.backups('manifests')[0].joinpath('partial.xml').read_text(),
                         'preserve partial worktree')
        self.assertEqual((self.reference / '.git/shallow').read_bytes(), old_shallow)
        self.assertFalse((self.source / '.repo/manifests.git/objects/info/alternates').exists())
        self.assertEqual(self.git('-C', str(self.reference), 'rev-parse', 'HEAD').stdout.strip(),
                         self.old_commit)

    def test_alternates_alone_after_interrupted_config_cleanup_are_recovered(self):
        self.make_shallow_reference_dependency(include_config=False)
        self.initialize()
        self.assertEqual(len(self.backups('manifests.git')), 1)

    def test_successful_retry_reuses_independent_manifest_and_preserves_only_lock(self):
        self.initialize()
        gitdir = self.source / '.repo/manifests.git'
        marker = gitdir / 'fixture-history'
        marker.write_text('independent manifest must remain')
        lock = gitdir / 'index.lock'
        lock.write_text('interrupted checkout lock')
        self.initialize()
        self.assertEqual(marker.read_text(), 'independent manifest must remain')
        self.assertFalse(lock.exists())
        saved_locks = self.backups('manifests.git/index.lock')
        self.assertEqual(len(saved_locks), 1)
        self.assertEqual(saved_locks[0].read_text(), 'interrupted checkout lock')
        self.assertFalse(self.backups('manifests.git/fixture-history'))

    def test_project_reference_without_manifest_alternates_does_not_quarantine(self):
        self.initialize()
        gitdir = self.source / '.repo/manifests.git'
        marker = gitdir / 'fixture-history'
        marker.write_text('healthy independent manifest')
        self.git('--git-dir=' + str(gitdir), 'config', 'repo.reference', '/previous')
        self.assertFalse((gitdir / 'objects/info/alternates').exists())
        self.initialize()
        self.assertEqual(marker.read_text(), 'healthy independent manifest')
        self.assertFalse(self.backups('manifests.git'))
        reference = REAL_RUN(['git', '--git-dir=' + str(gitdir), 'config',
                              '--get-all', 'repo.reference'], capture_output=True, text=True)
        self.assertEqual(reference.returncode, 1)

    def test_caller_worktree_with_unmounted_gitdir_does_not_affect_config_reads(self):
        self.initialize()
        gitdir = self.source / '.repo/manifests.git'
        self.git('--git-dir=' + str(gitdir), 'config', 'repo.reference', '/previous')
        caller = self.base / 'readonly-mounted-worktree'
        caller.mkdir()
        (caller / '.git').write_text('gitdir: /unmounted-host/git/worktrees/fixture\n')
        original_cwd = pathlib.Path.cwd()
        try:
            os.chdir(caller)
            self.initialize()
        finally:
            os.chdir(original_cwd)
        self.assertFalse(self.backups('manifests.git'))
        self.assertEqual((self.source / '.repo/manifest.xml').read_bytes(), self.manifest.read_bytes())

    def test_wrong_checkout_commit_cannot_replace_frozen_manifest(self):
        self.checkout_commit = self.old_commit
        # The old commit is fetched in advance so the fake repo can return a
        # valid but incorrect checkout; initialize must still reject it.
        self.git('clone', '-q', '--separate-git-dir=' + str(self.source / '.repo/manifests.git'),
                 str(self.upstream), str(self.source / '.repo/manifests'))
        destination = self.source / '.repo/manifests/platform.xml'
        destination.write_text('previous pinned manifest')
        with self.assertRaisesRegex(RuntimeError, 'differs from its pinned commit'):
            self.initialize()
        self.assertEqual(destination.read_text(), 'previous pinned manifest')
        self.assertEqual(len(self.repo_calls), 1)

    def test_init_failure_does_not_install_manifest_or_hide_failure(self):
        self.fail_first_init = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.initialize()
        self.assertFalse((self.source / '.repo/manifests/platform.xml').exists())
        self.assertEqual(len(self.repo_calls), 1)

    def test_floating_commit_is_rejected_before_source_is_created(self):
        with self.assertRaisesRegex(ValueError, 'must be pinned'):
            manifest_init.initialize(self.source, self.upstream.as_uri(),
                                     'lineage-24.0', self.manifest)
        self.assertFalse(self.source.exists())

    def make_parent_repo(self):
        parent = self.base / 'platform'
        tooling = parent / '.repo/repo'
        self.git('init', '-q', str(tooling))
        self.git('-C', str(tooling), 'config', 'user.name', 'Fixture')
        self.git('-C', str(tooling), 'config', 'user.email', 'fixture@example.invalid')
        (tooling / 'main.py').write_text('import sys\nprint(next(a for a in sys.argv if a.startswith("--repo-dir=")))\n')
        self.git('-C', str(tooling), 'add', 'main.py')
        self.git('-C', str(tooling), 'commit', '-qm', 'repo discovery fixture')
        (parent / '.repo/manifest.xml').write_text('preserve parent platform manifest\n')
        self.source = parent / 'out-kernel/google/gs-6.1'
        return parent, tooling

    def test_nested_init_creates_independent_tool_anchor_and_preserves_parent(self):
        parent, tooling = self.make_parent_repo()
        original_head = self.git('-C', str(tooling), 'rev-parse', 'HEAD').stdout
        self.initialize()
        local = self.source / '.repo/repo'
        self.assertTrue((local / 'main.py').is_file())
        self.assertNotEqual((local / 'main.py').stat().st_ino, (tooling / 'main.py').stat().st_ino)
        self.assertNotEqual((local / '.git/config').stat().st_ino, (tooling / '.git/config').stat().st_ino)
        self.assertEqual(self.git('-C', str(local), 'rev-parse', 'HEAD').stdout, original_head)
        self.assertEqual((parent / '.repo/manifest.xml').read_text(), 'preserve parent platform manifest\n')
        self.assertFalse((parent / '.repo/manifests.git').exists())
        self.assertEqual((self.source / '.repo/manifest.xml').read_bytes(), self.manifest.read_bytes())
        marker = local / 'local-tooling-marker'
        marker.write_text('reuse independent nested tooling')
        self.initialize()
        self.assertEqual(marker.read_text(), 'reuse independent nested tooling')

    def test_interrupted_nested_tooling_is_preserved_before_independent_bootstrap(self):
        parent, _ = self.make_parent_repo()
        partial = self.source / '.repo/repo'
        partial.mkdir(parents=True)
        (partial / 'interrupted').write_text('unfinished tool bootstrap')
        self.initialize()
        backups = self.backups('repo/interrupted')
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_text(), 'unfinished tool bootstrap')
        self.assertTrue((self.source / '.repo/repo/main.py').exists())
        self.assertEqual((parent / '.repo/manifest.xml').read_text(), 'preserve parent platform manifest\n')

    @unittest.skipUnless(shutil.which('repo'), 'Real repo launcher is available in the Linux builder')
    def test_real_launcher_discovery_is_anchored_to_nested_root(self):
        parent, _ = self.make_parent_repo()
        # Reproduce the actual failure: mkdir .repo does not hide the parent.
        (self.source / '.repo').mkdir(parents=True)
        def discovered():
            return REAL_RUN(['repo', 'version'], cwd=self.source, capture_output=True, text=True, check=True).stdout.strip()
        self.assertEqual(discovered(), '--repo-dir=' + str(parent / '.repo'))
        self.initialize()
        self.assertEqual(discovered(), '--repo-dir=' + str(self.source / '.repo'))

    @unittest.skipUnless(os.environ.get('TAKU_TEST_REPO_INSTALL'), 'Optional complete installed repo integration fixture')
    def test_real_repo_nested_init_after_parent_manifest_restore(self):
        # This fixture uses the builder's actual installed git-repo copied into
        # scratch space. It never writes the supplied installation/source volume.
        parent = self.base / 'real-platform'
        shutil.copytree(os.environ['TAKU_TEST_REPO_INSTALL'], parent / '.repo/repo', symlinks=False,
                        ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
        config = self.base / 'test-gitconfig'
        config.write_text('[user]\n name = Fixture\n email = fixture@example.invalid\n')
        with mock.patch.dict(os.environ, {'GIT_CONFIG_GLOBAL': str(config), 'PYTHONDONTWRITEBYTECODE': '1'}):
            # Simulate a parent manifest having been switched to the wrong pin,
            # then restore it before bootstrapping the nested kernel checkout.
            manifest_init.initialize(parent, self.upstream.as_uri(), self.old_commit, self.manifest)
            manifest_init.initialize(parent, self.upstream.as_uri(), self.commit, self.manifest)
            head = self.git('-C', str(parent / '.repo/manifests'), 'rev-parse', 'HEAD').stdout
            selected = (parent / '.repo/manifest.xml').read_bytes()
            manifest_config = (parent / '.repo/manifests.git/config').read_bytes()
            child = parent / 'out-kernel/google/gs-6.1'
            manifest_init.initialize(child, self.upstream.as_uri(), self.old_commit, self.manifest)
            self.assertEqual(self.git('-C', str(child / '.repo/manifests'), 'rev-parse', 'HEAD').stdout.strip(), self.old_commit)
            self.assertEqual(self.git('-C', str(parent / '.repo/manifests'), 'rev-parse', 'HEAD').stdout, head)
            self.assertEqual((parent / '.repo/manifest.xml').read_bytes(), selected)
            self.assertEqual((parent / '.repo/manifests.git/config').read_bytes(), manifest_config)


if __name__ == '__main__':
    unittest.main()
