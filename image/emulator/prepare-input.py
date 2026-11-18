#!/usr/bin/env python3
"""Seal a development image input without publishing or rewriting release locks."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Clean committed project checkout')
    parser.add_argument('--agent-build', type=Path, required=True, help='Checkout where npm run build:android completed')
    parser.add_argument('--cache', type=Path, action='append', default=[], help='Additional roots with locked dependencies')
    parser.add_argument('--tools', type=Path, required=True, help='Android build-tools directory')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source, agent, output = args.source.resolve(), args.agent_build.resolve(), args.output.resolve()
    if git(source, 'status', '--porcelain', '--untracked-files=no'):
        raise ValueError('Use a clean source worktree to avoid concurrent edits')
    source_commit = git(source, 'rev-parse', 'HEAD')
    if output.exists():
        raise ValueError('Output already exists; use a new snapshot directory')
    agent_code = runpy.run_path(str(source / 'image/agent_ui.py'))
    expected = agent_code['source_inputs'](source)
    if expected != agent_code['source_inputs'](agent):
        raise ValueError('Agent build sources differ from the image sources')
    apk = agent / 'system/agent-app/out/TakuAgent.apk'
    identity = agent_code['apk_identity'](apk, args.tools / 'aapt2', args.tools / 'apksigner')
    identity.update(sha256=sha(apk), sourceInputs=expected, sourceCommit=git(agent, 'rev-parse', 'HEAD'))
    assets = []
    for name in ('image/runtime-assets.lock.json', 'system/assistant/markdown-dependencies.json',
                 'system/audio-engine/rom-input-lock.json', 'system/memory/vector-dependencies.json'):
        assets += json.loads((source / name).read_text())['assets']
    ime = json.loads((source / 'system/inputmethod/rom-input-lock.json').read_text())
    assets += [{'path': ime['path'], 'sha256': ime['sha256']}, *ime['jni']]
    assets = [a for a in assets if a['path'] != 'system/agent-app/out/TakuAgent.apk']
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=output.name + '.', dir=output.parent) as temp:
        root = Path(temp) / 'snapshot'
        root.mkdir()
        for name in git(source, 'ls-files', '-z').split('\0'):
            if not name:
                continue
            if not (name == 'Android.bp' or name.split('/')[0] in ('system', 'image', 'adapters', 'core', 'spec', 'scripts')
                    or name == 'tooling/app-kit/apk-shell/src/org/agentos/runtime/AppApiClient.java'):
                continue
            p = source / name
            if p.is_symlink() or not p.is_file():
                raise ValueError('Expected ordinary source: ' + name)
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, target)
        for item in assets:
            name = item['path']
            if Path(name).is_absolute() or '..' in Path(name).parts:
                raise ValueError('Unsafe asset path')
            candidates = [r / name for r in (source, *args.cache)]
            p = next((p for p in candidates if p.is_file() and not p.is_symlink() and sha(p) == item['sha256']), None)
            if p is None:
                raise ValueError('Missing locked dependency: ' + name)
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, target)
        target = root / 'system/agent-app/out/TakuAgent.apk'
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(apk, target)
        # The verified build checkout has npm dependencies; snapshots deliberately do not.
        subprocess.run(['node', str(agent / 'system/agent-app/scripts/export-reply-contract.ts'),
                        str(root / 'system/assistant/out/reply-assets/native/reply-contract.json')], check=True)
        subprocess.run(['python3', str(root / 'scripts/prepare-memory-vectors.py')], check=True)
        subprocess.run(['python3', str(root / 'system/inputmethod/prepare-rom.py'), '--verify-only'], check=True)
        audio = runpy.run_path(str(root / 'system/audio-engine/prepare-rom.py'))
        model_manifest = json.loads((root / 'system/audio-engine/prebuilt/models/manifest.json').read_text())
        names = sorted([a['path'] for a in model_manifest['files']] + ['manifest.json'])
        (root / 'system/audio-engine/models/rom-files.mk').write_text(audio['model_makefile'](names))
        (root / 'system/audio-engine/prebuilt/Android.bp').write_text(audio['voice_blueprint'](names))
        audio['validate_inputs'](root, json.loads((root / 'system/audio-engine/rom-input-lock.json').read_text()))
        files = [{'path': str(p.relative_to(root)), 'sha256': sha(p)} for p in sorted(root.rglob('*')) if p.is_file()]
        value = {'schema': 'taku.emulator-input/1', 'source': {'commit': source_commit,
                 'credentialsIncluded': False}, 'agent': identity, 'files': files,
                 'digest': hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()}
        (root / 'emulator-input.json').write_text(json.dumps(value, indent=2) + '\n')
        # Catch source changes during input preparation, including edits in the build checkout.
        if (git(source, 'rev-parse', 'HEAD') != source_commit
                or git(source, 'status', '--porcelain', '--untracked-files=no')
                or expected != agent_code['source_inputs'](agent)):
            raise ValueError('Sources changed during snapshot preparation')
        root.rename(output)
    print(output)


if __name__ == '__main__':
    main()
