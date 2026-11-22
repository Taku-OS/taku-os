#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Host-only production island projection/actions tests; no Android UI, device or cloud calls.

Reuse the owner fixture's actual state/member extraction and Android boundary doubles.
No APK or previously compiled Home module is required. Dependencies must already be cached.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import runpy
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
OWNER = runpy.run_path(str(HERE / 'test-character-owner.py'))
ROOT = OWNER['SOURCE_ROOT']
MODULE = HERE.parent
BUILD = ROOT / 'out/assistant-card-host'


def main():
    jdk, compiler, library, coroutines, json_jar = OWNER['toolchain']()
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
    jars = []
    for name in ['junit/junit/4.13.2', 'org.hamcrest/hamcrest-core/1.3']:
        candidates = list((cache / name).glob('*/*.jar'))
        if not candidates:
            raise SystemExit('Missing cached test dependency: ' + name)
        jars.append(candidates[0])
    for directory in ['sources', 'contract-classes', 'classes']:
        path = BUILD / directory
        if path.exists():
            shutil.rmtree(path)
        path.mkdir(parents=True)
    sources = BUILD / 'sources'
    presentation = MODULE / 'android/src/main/java/org/agentos/agent/home/island/AssistantCardPresentation.kt'
    test = MODULE / 'android/src/test/java/org/agentos/agent/home/AssistantCardPresentationTest.kt'
    production = [*OWNER['SOURCES'], OWNER['CONTRACT'], OWNER['ENGINE'], OWNER['SESSION'], presentation, test]
    hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in production}
    copies = []
    for original in [*OWNER['SOURCES'], presentation, test]:
        copied = sources / original.name
        shutil.copyfile(original, copied)
        copies.append(copied)
    transcript = sources / 'DictationTranscript.kt'
    transcript.write_text('package org.agentos.agent.home.assistant\nclass DictationTranscript {' +
                          OWNER['ENGINE'].read_text().split('class DictationTranscript {', 1)[1])
    copies.append(transcript)
    state = OWNER['session_fixture_source']()
    key = re.search(r'const val VOICE_FAL_KEY_MISSING = ("[^"]+")', OWNER['SESSION'].read_text())
    assert key, 'Missing actual voice error constant'
    state = state.replace(' companion object {', ' companion object {const val VOICE_FAL_KEY_MISSING=' + key[1] + ';')
    session = sources / 'SessionStateFixture.kt'
    session.write_text(state)
    copies.append(session)
    for name, content in OWNER['STUBS'].items():
        copied = sources / name
        copied.write_text(content)
        copies.append(copied)
    env = {**os.environ, 'JAVA_HOME': str(jdk)}
    contract = BUILD / 'contract-classes'
    classes = BUILD / 'classes'
    cp = ':'.join(map(str, [contract, coroutines, json_jar, *jars]))
    commands = [
        [jdk / 'bin/javac', '--release', '17', '-d', contract, OWNER['CONTRACT']],
        [compiler, '-jvm-target', '17', '-cp', cp, '-d', classes, *copies],
        [jdk / 'bin/java', '-cp', str(classes) + ':' + cp + ':' + str(library / 'kotlin-stdlib.jar'),
         'org.junit.runner.JUnitCore', 'org.agentos.agent.home.AssistantCardPresentationTest'],
    ]
    output = []
    for command in commands:
        result = subprocess.run(list(map(str, command)), cwd=HERE, env=env, capture_output=True, text=True, timeout=90)
        output.append(result.stdout + result.stderr)
        (BUILD / 'test.log').write_text('\n'.join(output))
        if result.returncode:
            raise SystemExit(output[-1])
    assert hashes == {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in production}, 'Sources changed while testing'
    result = re.search(r'OK \((\d+) tests\)', output[-1])
    assert result, output[-1]
    receipt = {'passed': True, 'tests': int(result[1]), 'sourceSha256': hashes,
               'commands': [list(map(str, command)) for command in commands],
               'boundary': 'Production presentation/payload/action admission and source state; Android boundary doubles. No Android renderer, notification delivery, touch, ASR, camera or cloud execution.'}
    (BUILD / 'report.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(output[-1].strip())
    print('Receipt: ' + str(BUILD / 'report.json'))


if __name__ == '__main__':
    main()
