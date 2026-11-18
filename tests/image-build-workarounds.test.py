#!/usr/bin/env python3
"""Exercise patch failure gates on fixtures, without touching a ROM checkout."""
import hashlib
import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('workarounds', ROOT / 'image/apply-build-workarounds.py')
workarounds = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workarounds)

# Relevant upstream config.go blocks, including D8 to ensure it stays unchanged.
UPSTREAM = '''package config

var (
\tdexerJavaVmFlagsList = []string{
\t\t`-JXX:OnError="cat hs_err_pid%p.log"`,
\t\t"-JXX:CICompilerCount=6",
\t\t"-JXX:+UseDynamicNumberOfGCThreads",
\t}
)

func init() {
\tpctx.StaticVariable("D8Flags", strings.Join(append([]string{
\t\t"-JXmx4096M",
\t\t"-JXX:+TieredCompilation",
\t\t"-JXX:TieredStopAtLevel=1",
\t\t"-JDcom.android.tools.r8.emitRecordAnnotationsInDex",
\t\t"-JDcom.android.tools.r8.emitPermittedSubclassesAnnotationsInDex",
\t}, dexerJavaVmFlagsList...), " "))

\tpctx.VariableFunc("R8Flags", func(ctx android.PackageVarContext) string {
\t\tr8flags := []string{
\t\t\t"-JXmx4096M",
\t\t\t"-JDcom.android.tools.r8.emitRecordAnnotationsInDex",
\t\t\t"-JDcom.android.tools.r8.emitPermittedSubclassesAnnotationsInDex",
\t\t}
\t\tr8flags = append(r8flags, dexerJavaVmFlagsList...)
\t\treturn strings.Join(r8flags, " ")

\t})
}
'''

SOONG_UPSTREAM = '''package build

// Other upstream helpers may inspect runtime.GOMAXPROCS without an env lookup.
func (pb PrimaryBuilderFactory) primaryBuilderInvocation(config Config) bootstrap.PrimaryBuilderInvocation {
\tcommonArgs = append(commonArgs, "-l", filepath.Join(pb.config.FileListDir(), "Android.bp.list"))
\tinvocationEnv := make(map[string]string)
\tif pb.debugPort != "" {
\t\tinvocationEnv["GODEBUG"] = "asyncpreemptoff=1"
\t}
\treturn bootstrap.PrimaryBuilderInvocation{
\t\tEnv:     invocationEnv,
\t}
}
'''
BLUEPRINT_UPSTREAM = '''package bootstrap

func RunBlueprint(args Args, stopBefore StopBefore, ctx *blueprint.Context, config interface{}) ([]string, error) {
\truntime.GOMAXPROCS(runtime.NumCPU())

\tif args.NoGC {
\t\tdebug.SetGCPercent(-1)
\t}
\treturn nil, nil
}
'''
LAUNCHER_UPSTREAM = '''package bootstrap
var command = `cd / && ` +
\t`env -i $env "$$BUILDER" ` +
\t` --top "$$TOP" `
'''
NINJA_UPSTREAM = '''package build

func runNinja(ctx Context, config Config) {
\tif cmd.Environment.IsEnvTrue("ALLOW_NINJA_ENV") {
\t\tctx.Println("Allowing all environment variables during ninja; incremental builds may be unsafe.")
\t} else {
\t\tcmd.Environment.Allow(append([]string{
\t\t\t"HOME",
\t\t\t"PATH",
\t\t}, config.BuildBrokenNinjaUsesEnvVars()...)...)
\t}
\tcmd.Environment.Set("DIST_DIR", config.DistDir())
\tcmd.Environment.Set("SHELL", "/bin/bash")
}
'''


class BuildWorkarounds(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = pathlib.Path(self.temp.name)
        self.source = root / 'generated'
        self.results = root / 'results'
        (self.source / '.repo').mkdir(parents=True)
        self.target = self.source / workarounds.TARGET
        self.target.parent.mkdir(parents=True)
        self.target.write_text(UPSTREAM)
        self.soong = self.source / workarounds.SOONG_RUNTIME_TARGET
        self.blueprint = self.source / workarounds.BLUEPRINT_RUNTIME_TARGET
        self.launcher = self.source / workarounds.BLUEPRINT_LAUNCHER_TARGET
        self.ninja = self.source / workarounds.NINJA_RUNTIME_TARGET
        for path, text in ((self.soong, SOONG_UPSTREAM), (self.blueprint, BLUEPRINT_UPSTREAM),
                           (self.launcher, LAUNCHER_UPSTREAM), (self.ninja, NINJA_UPSTREAM)):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)

    def apply(self):
        return workarounds.apply(self.source, self.results)['patches'][0]

    def assert_rejected_without_changes(self, expected_error):
        before = {path: path.read_bytes() for path in (self.target, self.soong, self.blueprint, self.launcher, self.ninja)}
        with self.assertRaisesRegex(ValueError, expected_error):
            self.apply()
        for path, content in before.items():
            self.assertEqual(path.read_bytes(), content)
        self.assertFalse((self.results / workarounds.REPORT_NAME).exists())

    def test_applies_only_to_r8_and_records_hashes(self):
        original = self.target.read_bytes()
        self.target.chmod(0o640)
        report = self.apply()
        patched = self.target.read_text()
        prefix = UPSTREAM.split('\tpctx.VariableFunc("R8Flags"')[0]
        self.assertTrue(patched.startswith(prefix), 'D8 and shared JVM flags must stay unchanged')
        self.assertIn('if ctx.Config().IsEnvTrue("TAKU_R8_SERIAL_GC") {', patched)
        self.assertEqual(patched.count('-JXX:+UseSerialGC'), 1)
        self.assertEqual(report['originalSha256'], hashlib.sha256(original).hexdigest())
        self.assertEqual(report['appliedSha256'], hashlib.sha256(self.target.read_bytes()).hexdigest())
        self.assertEqual(report['status'], 'applied')
        self.assertEqual(self.target.stat().st_mode & 0o777, 0o640)
        saved = json.loads((self.results / workarounds.REPORT_NAME).read_text())
        self.assertEqual(saved['patches'][0], report)

    def test_repeat_does_not_rewrite_source_or_lose_original_hash(self):
        first = self.apply()
        content = self.target.read_bytes()
        modified = self.target.stat().st_mtime_ns
        second = self.apply()
        self.assertEqual(second['status'], 'already-applied')
        self.assertEqual(second['originalSha256'], first['originalSha256'])
        self.assertEqual(second['appliedSha256'], first['appliedSha256'])
        self.assertEqual(self.target.read_bytes(), content)
        self.assertEqual(self.target.stat().st_mtime_ns, modified)

    def test_report_can_be_recovered_after_interrupted_application(self):
        first = self.apply()
        (self.results / workarounds.REPORT_NAME).unlink()
        second = self.apply()
        self.assertEqual(second['originalSha256'], first['originalSha256'])
        self.assertTrue((self.results / workarounds.REPORT_NAME).is_file())

    def test_runtime_patches_are_allowlisted_and_audited(self):
        paths = [self.target, self.soong, self.blueprint, self.ninja]
        originals = {path: path.read_bytes() for path in paths}
        with mock.patch.dict(os.environ, {'GOMEMLIMIT': '24GiB', 'GOMAXPROCS': '4',
                                          'GODEBUG': 'asyncpreemptoff=1',
                                          'TAKU_R8_SERIAL_GC': '1', 'SECRET_NOT_FOR_GO': 'hidden'}):
            report = workarounds.apply(self.source, self.results)
        self.assertEqual(len(report['patches']), 4)
        for path, patch in zip(paths, report['patches']):
            self.assertEqual(patch['path'], path.relative_to(self.source).as_posix())
            self.assertEqual(patch['originalSha256'], hashlib.sha256(originals[path]).hexdigest())
            self.assertEqual(patch['appliedSha256'], hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertEqual(report['patches'][1]['environmentVariables'], ['GOMEMLIMIT', 'GOMAXPROCS', 'GODEBUG'])
        self.assertEqual(report['patches'][1]['environmentValuesAtApplyTime'],
                         {'GOMEMLIMIT': '24GiB', 'GOMAXPROCS': '4', 'GODEBUG': 'asyncpreemptoff=1'})
        self.assertEqual(report['patches'][3]['fixedGodebugValue'], 'asyncpreemptoff=1')
        self.assertNotIn('hidden', json.dumps(report))
        self.assertIn('[]string{"GOMEMLIMIT", "GOMAXPROCS"}', self.soong.read_text())
        self.assertEqual(self.launcher.read_text(), LAUNCHER_UPSTREAM)
        self.assertEqual(report['sourceChecks'][0]['sha256'], hashlib.sha256(self.launcher.read_bytes()).hexdigest())
        self.assertIn('if os.Getenv("GOMAXPROCS") == "" {', self.blueprint.read_text())

    def test_all_patches_are_idempotent(self):
        first = workarounds.apply(self.source, self.results)
        states = {path: (path.read_bytes(), path.stat().st_mtime_ns)
                  for path in (self.target, self.soong, self.blueprint, self.launcher, self.ninja)}
        second = workarounds.apply(self.source, self.results)
        for old, new in zip(first['patches'], second['patches']):
            self.assertEqual(new['status'], 'already-applied')
            self.assertEqual(new['originalSha256'], old['originalSha256'])
            self.assertEqual(new['appliedSha256'], old['appliedSha256'])
        for path, state in states.items():
            self.assertEqual((path.read_bytes(), path.stat().st_mtime_ns), state)

    def test_partial_write_is_recovered_without_repatching_r8(self):
        self.target.write_text(UPSTREAM.replace(workarounds.ORIGINAL_R8_FLAGS, workarounds.PATCHED_R8_FLAGS))
        modified = self.target.stat().st_mtime_ns
        report = workarounds.apply(self.source, self.results)
        self.assertEqual([p['status'] for p in report['patches']], ['already-applied', 'applied', 'applied', 'applied'])
        self.assertEqual(self.target.stat().st_mtime_ns, modified)
        self.assertEqual(report['patches'][0]['originalSha256'], hashlib.sha256(UPSTREAM.encode()).hexdigest())

    def test_changed_soong_block_rejects_all_writes(self):
        self.soong.write_text(SOONG_UPSTREAM.replace('make(map[string]string)', 'map[string]string{}'))
        self.assert_rejected_without_changes('Soong primary-builder environment changed')

    def test_changed_soong_env_field_rejects_all_writes(self):
        self.soong.write_text(SOONG_UPSTREAM.replace('Env:     invocationEnv,', 'Env:     otherEnv,'))
        self.assert_rejected_without_changes('Soong primary-builder Env field changed')

    def test_changed_blueprint_block_rejects_all_writes(self):
        self.blueprint.write_text(BLUEPRINT_UPSTREAM.replace('runtime.NumCPU()', '8'))
        self.assert_rejected_without_changes('Blueprint default concurrency changed')

    def test_changed_env_isolation_rejects_all_writes(self):
        self.launcher.write_text(LAUNCHER_UPSTREAM.replace('env -i ', 'env '))
        self.assert_rejected_without_changes('env -i launcher changed')

    def test_duplicate_runtime_blocks_reject_all_writes(self):
        for path, original in ((self.soong, SOONG_UPSTREAM), (self.blueprint, BLUEPRINT_UPSTREAM)):
            with self.subTest(path=path):
                path.write_text(original + original)
                self.assert_rejected_without_changes('Upstream')
                path.write_text(original)

    def test_modified_runtime_patch_rejects_all_writes(self):
        for path, old, new in ((self.soong, '"GOMEMLIMIT", "GOMAXPROCS"', '"GOMEMLIMIT", "GOMAXPROCS", "HOME"'),
                               (self.blueprint, 'os.Getenv("GOMAXPROCS") == ""', 'os.Getenv("GOMAXPROCS") != ""')):
            with self.subTest(path=path):
                self.soong.write_text(SOONG_UPSTREAM)
                self.blueprint.write_text(BLUEPRINT_UPSTREAM)
                self.apply()
                (self.results / workarounds.REPORT_NAME).unlink()
                path.write_text(path.read_text().replace(old, new))
                self.assert_rejected_without_changes('patch drifted')

    def test_new_runtime_target_symlink_is_rejected(self):
        external = pathlib.Path(self.temp.name) / 'external-runtime.go'
        external.write_text(SOONG_UPSTREAM)
        self.soong.unlink()
        self.soong.symlink_to(external)
        self.assert_rejected_without_changes('inside the generated source checkout')

    def test_exact_legacy_runtime_patch_is_upgraded_with_original_hash(self):
        legacy = SOONG_UPSTREAM.replace(workarounds.ORIGINAL_SOONG_RUNTIME, workarounds.LEGACY_SOONG_RUNTIME)
        self.soong.write_text(legacy)
        report = workarounds.apply(self.source, self.results)['patches'][1]
        self.assertEqual(report['status'], 'upgraded')
        self.assertEqual(report['originalSha256'], hashlib.sha256(SOONG_UPSTREAM.encode()).hexdigest())
        self.assertEqual(report['previousAppliedSha256'], hashlib.sha256(legacy.encode()).hexdigest())
        self.assertIn(workarounds.PATCHED_SOONG_RUNTIME, self.soong.read_text())
        again = workarounds.apply(self.source, self.results)['patches'][1]
        self.assertEqual(again['status'], 'already-applied')
        self.assertEqual(again['originalSha256'], report['originalSha256'])

    def test_modified_legacy_patch_is_rejected_before_any_write(self):
        legacy = workarounds.LEGACY_SOONG_RUNTIME.replace('"GOMEMLIMIT", "GOMAXPROCS"',
                                                         '"GOMEMLIMIT", "GOMAXPROCS", "HOME"')
        self.soong.write_text(SOONG_UPSTREAM.replace(workarounds.ORIGINAL_SOONG_RUNTIME, legacy))
        self.assert_rejected_without_changes('legacy patch drifted')

    def test_ninja_environment_drift_rejects_all_writes(self):
        for old, new in (('config.DistDir()', 'otherDir'),
                         ('cmd.Environment.Allow(append([]string{', 'cmd.Environment.Allow([]string{')):
            with self.subTest(old=old):
                self.ninja.write_text(NINJA_UPSTREAM.replace(old, new))
                self.assert_rejected_without_changes('Ninja')

    def test_modified_ninja_patch_is_rejected(self):
        self.apply()
        (self.results / workarounds.REPORT_NAME).unlink()
        self.ninja.write_text(self.ninja.read_text().replace('value == "asyncpreemptoff=1"', 'value != ""'))
        self.assert_rejected_without_changes('patch drifted')

    def test_compiled_go_preserves_explicit_limits_and_unset_default(self):
        go = os.environ.get('TAKU_TEST_GO') or shutil.which('go')
        if not go:
            self.skipTest('Set TAKU_TEST_GO to the pinned Linux Go toolchain for runtime validation')
        self.apply()
        # Compile the actual inserted Go statements, then start a child with
        # only the generated Env map, just as Blueprint's env -i launcher does.
        env_code = self.soong.read_text().split('\tinvocationEnv :=', 1)[1].split('\tif pb.debugPort', 1)[0]
        env_code = '\tinvocationEnv :=' + env_code
        runtime_code = self.blueprint.read_text().split('([]string, error) {\n', 1)[1].split('\tif args.NoGC', 1)[0]
        ninja_code = self.ninja.read_text().split('\t// taku-ninja-go-asyncpreemptoff-v1:', 1)[1]
        ninja_code = ninja_code[ninja_code.index('\tif value, ok :='):].split('\tcmd.Environment.Set("SHELL"', 1)[0]
        program = '''package main
import ("encoding/json"; "fmt"; "os"; "os/exec"; "runtime"; "runtime/debug")
type Environment map[string]string
func (e Environment) Get(key string) (string, bool) { value, ok := e[key]; return value, ok }
func (e Environment) Set(key string, value string) { e[key] = value }
func (e Environment) Allow(keys ...string) {
  allowed := map[string]bool{}; for _, key := range keys { allowed[key] = true }
  for key := range e { if !allowed[key] { delete(e, key) } }
}
type Config struct { env Environment }
func (c Config) Environment() Environment { return c.env }
type Command struct { Environment Environment }
func main() {
  if len(os.Args) > 1 && os.Args[1] == "ninja" {
    config := Config{Environment{"GODEBUG":os.Args[2]}}
    cmd := Command{Environment{"PATH":"/usr/bin", "GODEBUG":os.Args[2], "SECRET_NOT_FOR_GO":"hidden"}}
    cmd.Environment.Allow("PATH")
NINJA_CODE
    _ = json.NewEncoder(os.Stdout).Encode(cmd.Environment)
    return
  }
  if len(os.Args) > 1 && os.Args[1] == "child" {
RUNTIME_CODE
    _ = json.NewEncoder(os.Stdout).Encode(map[string]interface{}{
      "maxprocs":runtime.GOMAXPROCS(0), "cpus":runtime.NumCPU(), "memoryLimit":debug.SetMemoryLimit(-1),
      "other":os.Getenv("SECRET_NOT_FOR_GO"), "godebug":os.Getenv("GODEBUG")})
    return
  }
  config := Config{Environment{"GOMEMLIMIT":os.Args[1], "GOMAXPROCS":os.Args[2],
    "SECRET_NOT_FOR_GO":"hidden", "GODEBUG":os.Args[3]}}
ENV_CODE
  cmd := exec.Command(os.Args[0], "child")
  cmd.Env = []string{}
  for name, value := range invocationEnv { cmd.Env = append(cmd.Env, name+"="+value) }
  output, err := cmd.CombinedOutput(); if err != nil { panic(fmt.Sprintf("%s: %v", output, err)) }
  fmt.Print(string(output))
}
'''.replace('NINJA_CODE', ninja_code).replace('ENV_CODE', env_code).replace('RUNTIME_CODE', runtime_code)
        directory = pathlib.Path(self.temp.name) / 'go-smoke'
        directory.mkdir()
        source = directory / 'main.go'
        source.write_text(program)
        executable = directory / 'probe'
        env = {key: value for key, value in os.environ.items()
               if key not in ('GOMEMLIMIT', 'GOMAXPROCS', 'GODEBUG', 'GOGC', 'GOROOT')}
        env.update({'GOCACHE': str(directory / 'cache'), 'GOTMPDIR': str(directory),
                    'GOENV': 'off', 'GOWORK': 'off', 'GO111MODULE': 'off', 'GOTOOLCHAIN': 'local',
                    'CGO_ENABLED': '0'})
        compiled = subprocess.run([go, 'build', '-o', str(executable), str(source)], env=env,
                                  capture_output=True, text=True, timeout=180)
        self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
        for memory, concurrency, godebug in (('', '', ''), ('24GiB', '4', 'asyncpreemptoff=1'),
                                             ('24GiB', '', 'gctrace=1'),
                                             ('24GiB', '4', 'asyncpreemptoff=1,gctrace=1')):
            with self.subTest(memory=memory, concurrency=concurrency, godebug=godebug):
                result = subprocess.run([str(executable), memory, concurrency, godebug], capture_output=True,
                                        text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                report = json.loads(result.stdout)
                self.assertEqual(report['maxprocs'], int(concurrency) if concurrency else report['cpus'])
                self.assertEqual(report['memoryLimit'], 24 * 1024**3 if memory else 2**63 - 1)
                self.assertEqual(report['other'], '')
                self.assertEqual(report['godebug'], 'asyncpreemptoff=1' if godebug == 'asyncpreemptoff=1' else '')
        for godebug in ('', 'gctrace=1', 'asyncpreemptoff=1,gctrace=1', 'asyncpreemptoff=1'):
            with self.subTest(ninja_godebug=godebug):
                result = subprocess.run([str(executable), 'ninja', godebug], capture_output=True,
                                        text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                report = json.loads(result.stdout)
                self.assertEqual(report.get('GODEBUG', ''), 'asyncpreemptoff=1' if godebug == 'asyncpreemptoff=1' else '')
                self.assertEqual(report['PATH'], '/usr/bin')
                self.assertNotIn('SECRET_NOT_FOR_GO', report)

    def test_changed_upstream_r8_block_is_rejected(self):
        self.target.write_text(UPSTREAM.replace('"-JXmx4096M"', '"-JXmx8192M"'))
        self.assert_rejected_without_changes('Upstream R8Flags changed')

    def test_changed_shared_gc_flags_are_rejected(self):
        self.target.write_text(UPSTREAM.replace('"-JXX:+UseDynamicNumberOfGCThreads"', '"-JXX:+UseG1GC"'))
        self.assert_rejected_without_changes('Upstream dexer JVM flags changed')

    def test_duplicate_r8_definition_is_rejected(self):
        self.target.write_text(UPSTREAM + workarounds.ORIGINAL_R8_FLAGS)
        self.assert_rejected_without_changes('Upstream R8Flags changed')

    def test_modified_installed_patch_is_rejected(self):
        self.apply()
        (self.results / workarounds.REPORT_NAME).unlink()
        self.target.write_text(self.target.read_text().replace('UseSerialGC', 'UseParallelGC'))
        self.assert_rejected_without_changes('patch drifted')

    def test_non_repo_checkout_is_rejected(self):
        (self.source / '.repo').rmdir()
        self.assert_rejected_without_changes('generated Android repo checkout')

    def test_symlink_outside_checkout_is_rejected(self):
        external = pathlib.Path(self.temp.name) / 'external.go'
        external.write_text(UPSTREAM)
        self.target.unlink()
        self.target.symlink_to(external)
        self.assert_rejected_without_changes('inside the generated source checkout')


if __name__ == '__main__':
    unittest.main()
