#!/usr/bin/env python3
"""Apply reviewed host workarounds only to a generated Android repo checkout."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile


PATCH_NAME = 'taku-r8-serial-gc-v1'
ENVIRONMENT_FLAG = 'TAKU_R8_SERIAL_GC'
TARGET = Path('build/soong/java/config/config.go')
REPORT_NAME = 'build-workarounds.json'
SOONG_RUNTIME_PATCH_NAME = 'taku-soong-go-runtime-env-v2'
SOONG_RUNTIME_TARGET = Path('build/soong/ui/build/soong.go')
NINJA_RUNTIME_PATCH_NAME = 'taku-ninja-go-asyncpreemptoff-v1'
NINJA_RUNTIME_TARGET = Path('build/soong/ui/build/ninja.go')
BLUEPRINT_RUNTIME_PATCH_NAME = 'taku-blueprint-explicit-gomaxprocs-v1'
BLUEPRINT_RUNTIME_TARGET = Path('build/blueprint/bootstrap/command.go')
BLUEPRINT_LAUNCHER_TARGET = Path('build/blueprint/bootstrap/bootstrap.go')

# Verified in LineageOS lineage-24.0 and AOSP android17-release. Match both
# blocks so a new upstream collector option cannot silently conflict with ours.
DEXER_FLAGS = '''\tdexerJavaVmFlagsList = []string{
\t\t`-JXX:OnError="cat hs_err_pid%p.log"`,
\t\t"-JXX:CICompilerCount=6",
\t\t"-JXX:+UseDynamicNumberOfGCThreads",
\t}
'''
ORIGINAL_R8_FLAGS = '''\tpctx.VariableFunc("R8Flags", func(ctx android.PackageVarContext) string {
\t\tr8flags := []string{
\t\t\t"-JXmx4096M",
\t\t\t"-JDcom.android.tools.r8.emitRecordAnnotationsInDex",
\t\t\t"-JDcom.android.tools.r8.emitPermittedSubclassesAnnotationsInDex",
\t\t}
\t\tr8flags = append(r8flags, dexerJavaVmFlagsList...)
\t\treturn strings.Join(r8flags, " ")

\t})
'''
PATCHED_R8_FLAGS = ORIGINAL_R8_FLAGS.replace(
    '\t\treturn strings.Join(r8flags, " ")',
    '\t\t// taku-r8-serial-gc-v1: opt-in workaround for host G1 crashes.\n'
    '\t\tif ctx.Config().IsEnvTrue("TAKU_R8_SERIAL_GC") {\n'
    '\t\t\tr8flags = append(r8flags, "-JXX:+UseSerialGC")\n'
    '\t\t}\n'
    '\t\treturn strings.Join(r8flags, " ")',
)

# Verified against Soong 183e59578606687d66096cbce86cb50c27f4e9d7 and
# Blueprint 7f2058de8155b05ea3c3f8317d33bfe732cacf8b. The available-env JSON
# is a build configuration map, not the environment read by the Go runtime.
ORIGINAL_SOONG_RUNTIME = '''\tcommonArgs = append(commonArgs, "-l", filepath.Join(pb.config.FileListDir(), "Android.bp.list"))
\tinvocationEnv := make(map[string]string)
\tif pb.debugPort != "" {
'''
LEGACY_SOONG_RUNTIME = ORIGINAL_SOONG_RUNTIME.replace(
    '\tinvocationEnv := make(map[string]string)\n',
    '\tinvocationEnv := make(map[string]string)\n'
    '\t// taku-soong-go-runtime-env-v1: preserve only explicit Go resource limits across env -i.\n'
    '\tfor _, name := range []string{"GOMEMLIMIT", "GOMAXPROCS"} {\n'
    '\t\tif value, ok := config.Environment().Get(name); ok && value != "" {\n'
    '\t\t\tinvocationEnv[name] = value\n'
    '\t\t}\n'
    '\t}\n',
)
PATCHED_SOONG_RUNTIME = LEGACY_SOONG_RUNTIME.replace(
    'taku-soong-go-runtime-env-v1: preserve only explicit Go resource limits across env -i.',
    'taku-soong-go-runtime-env-v2: preserve explicit Go resource limits and fixed host compatibility.',
).replace(
    '\tif pb.debugPort != "" {\n',
    '\tif value, ok := config.Environment().Get("GODEBUG"); ok && value == "asyncpreemptoff=1" {\n'
    '\t\tinvocationEnv["GODEBUG"] = value\n'
    '\t}\n'
    '\tif pb.debugPort != "" {\n',
)
ORIGINAL_NINJA_RUNTIME = '''\tcmd.Environment.Set("DIST_DIR", config.DistDir())
\tcmd.Environment.Set("SHELL", "/bin/bash")
'''
PATCHED_NINJA_RUNTIME = ORIGINAL_NINJA_RUNTIME.replace(
    '\tcmd.Environment.Set("SHELL", "/bin/bash")\n',
    '\t// taku-ninja-go-asyncpreemptoff-v1: retain only the fixed host compatibility setting.\n'
    '\tif value, ok := config.Environment().Get("GODEBUG"); ok && value == "asyncpreemptoff=1" {\n'
    '\t\tcmd.Environment.Set("GODEBUG", value)\n'
    '\t}\n'
    '\tcmd.Environment.Set("SHELL", "/bin/bash")\n',
)
ORIGINAL_BLUEPRINT_RUNTIME = '''func RunBlueprint(args Args, stopBefore StopBefore, ctx *blueprint.Context, config interface{}) ([]string, error) {
\truntime.GOMAXPROCS(runtime.NumCPU())

\tif args.NoGC {
'''
PATCHED_BLUEPRINT_RUNTIME = ORIGINAL_BLUEPRINT_RUNTIME.replace(
    '\truntime.GOMAXPROCS(runtime.NumCPU())\n',
    '\t// taku-blueprint-explicit-gomaxprocs-v1: retain an explicitly configured runtime limit.\n'
    '\tif os.Getenv("GOMAXPROCS") == "" {\n'
    '\t\truntime.GOMAXPROCS(runtime.NumCPU())\n'
    '\t}\n',
)
ISOLATED_BUILDER_COMMAND = '`env -i $env "$$BUILDER" ` +'


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def atomic_write(path, content, mode=0o644):
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_source(source_root, relative_path):
    target = source_root / relative_path
    if not target.resolve().is_relative_to(source_root) or target.is_symlink():
        raise ValueError('Patch target must stay inside the generated source checkout: ' + str(relative_path))
    return target, target.read_bytes()


def prepare_patch(source_root, relative_path, name, original_block, patched_block,
                  label, unique_tokens, metadata, guards=(), legacy=None):
    target, before = read_source(source_root, relative_path)
    text = before.decode('utf-8')
    for guard_label, guard in guards:
        if text.count(guard) != 1:
            raise ValueError(f'Upstream {guard_label} changed; refusing to apply {name}')

    previous_hash = None
    if legacy is not None:
        legacy_name, legacy_block = legacy
        if legacy_name in text:
            if (text.count(legacy_name) != 1 or text.count(legacy_block) != 1 or
                    name in text or original_block in text or
                    any(text.count(token) != legacy_block.count(token) for token in unique_tokens)):
                raise ValueError(f'Upstream {label} changed or legacy patch drifted; refusing to apply {name}')
            previous_hash = sha256(before)
            text = text.replace(legacy_block, original_block, 1)

    if (text.count(original_block) == 1 and name not in text and
            all(token not in text for token in unique_tokens)):
        original = text.encode('utf-8')
        after = text.replace(original_block, patched_block, 1).encode('utf-8')
        status = 'upgraded' if previous_hash else 'applied'
    elif (text.count(patched_block) == 1 and text.count(name) == 1 and
            all(text.count(token) == patched_block.count(token) for token in unique_tokens) and
            original_block not in text):
        # Also recovers the audit record if a previous run stopped after the
        # atomic source replacement but before writing its report.
        original = text.replace(patched_block, original_block, 1).encode('utf-8')
        after = before
        status = 'already-applied'
    else:
        raise ValueError(f'Upstream {label} changed or patch drifted; refusing to apply {name}')

    return target, after, {
        'name': name,
        'path': relative_path.as_posix(),
        'status': status,
        'originalSha256': sha256(original),
        'appliedSha256': sha256(after),
        **({'previousAppliedSha256': previous_hash} if previous_hash else {}),
        **metadata,
    }


def apply(source_root, results_dir):
    source_root = Path(source_root).resolve()
    results_dir = Path(results_dir).resolve()
    if not (source_root / '.repo').is_dir():
        raise ValueError('Expected a generated Android repo checkout with .repo: ' + str(source_root))

    # Validate every upstream block before changing any file. A partial write
    # interrupted by the host is recoverable through each patch's exact match.
    _, launcher_bytes = read_source(source_root, BLUEPRINT_LAUNCHER_TARGET)
    if launcher_bytes.decode('utf-8').count(ISOLATED_BUILDER_COMMAND) != 1:
        raise ValueError('Upstream Blueprint env -i launcher changed; refusing runtime patches')
    plans = [
        prepare_patch(source_root, TARGET, PATCH_NAME, ORIGINAL_R8_FLAGS, PATCHED_R8_FLAGS,
                      'R8Flags', (ENVIRONMENT_FLAG,), {
                          'environmentFlag': ENVIRONMENT_FLAG,
                          'environmentValueAtApplyTime': os.environ.get(ENVIRONMENT_FLAG, ''),
                          'scope': 'R8 JVM only; D8 and other Java tools unchanged',
                      }, guards=(('dexer JVM flags', DEXER_FLAGS),)),
        prepare_patch(source_root, SOONG_RUNTIME_TARGET, SOONG_RUNTIME_PATCH_NAME,
                      ORIGINAL_SOONG_RUNTIME, PATCHED_SOONG_RUNTIME,
                      'Soong primary-builder environment', ('"GOMEMLIMIT"', '"GOMAXPROCS"'), {
                          'environmentVariables': ['GOMEMLIMIT', 'GOMAXPROCS', 'GODEBUG'],
                          'environmentValuesAtApplyTime': {
                              key: os.environ.get(key, '') for key in ('GOMEMLIMIT', 'GOMAXPROCS', 'GODEBUG')
                          },
                          'fixedGodebugValue': 'asyncpreemptoff=1',
                          'scope': 'Primary-builder Go limits and fixed compatibility; retain env -i isolation',
                      }, guards=(('Soong primary-builder Env field', '\t\tEnv:     invocationEnv,\n'),),
                      legacy=('taku-soong-go-runtime-env-v1', LEGACY_SOONG_RUNTIME)),
        prepare_patch(source_root, BLUEPRINT_RUNTIME_TARGET, BLUEPRINT_RUNTIME_PATCH_NAME,
                      ORIGINAL_BLUEPRINT_RUNTIME, PATCHED_BLUEPRINT_RUNTIME,
                      'Blueprint default concurrency', ('"GOMAXPROCS"',), {
                          'environmentVariables': ['GOMAXPROCS'],
                          'environmentValuesAtApplyTime': {'GOMAXPROCS': os.environ.get('GOMAXPROCS', '')},
                          'scope': 'Honor explicit Go concurrency; preserve NumCPU default when unset',
                      }),
        prepare_patch(source_root, NINJA_RUNTIME_TARGET, NINJA_RUNTIME_PATCH_NAME,
                      ORIGINAL_NINJA_RUNTIME, PATCHED_NINJA_RUNTIME,
                      'Ninja command environment', ('cmd.Environment.Set("GODEBUG", value)',), {
                          'environmentVariables': ['GODEBUG'],
                          'environmentValuesAtApplyTime': {'GODEBUG': os.environ.get('GODEBUG', '')},
                          'fixedGodebugValue': 'asyncpreemptoff=1',
                          'scope': 'Ninja child Go tools; only exact asyncpreemptoff=1 passes the filter',
                      }, guards=(('Ninja environment filter', '\t\tcmd.Environment.Allow(append([]string{\n'),)),
    ]

    results_dir.mkdir(parents=True, exist_ok=True)
    record = {
        'schemaVersion': 1,
        'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'sourceRoot': str(source_root),
        'patches': [patch for _, _, patch in plans],
        'sourceChecks': [{
            'path': BLUEPRINT_LAUNCHER_TARGET.as_posix(),
            'sha256': sha256(launcher_bytes),
            'check': 'Primary builder still executes through env -i',
        }],
    }
    for target, after, patch in plans:
        if patch['status'] in ('applied', 'upgraded'):
            atomic_write(target, after, stat.S_IMODE(target.stat().st_mode))
    atomic_write(results_dir / REPORT_NAME, (json.dumps(record, indent=2) + '\n').encode('utf-8'))
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=Path('/src'))
    parser.add_argument('--results-dir', type=Path, default=Path('/results'))
    args = parser.parse_args()
    try:
        record = apply(args.source_root, args.results_dir)
    except (OSError, UnicodeError, ValueError) as error:
        parser.exit(1, str(error) + '\n')
    for patch in record['patches']:
        print(f"{patch['name']}: {patch['status']}; audit: {args.results_dir / REPORT_NAME}")


if __name__ == '__main__':
    main()
