#!/usr/bin/env python3
"""Offline settings/default-model probes on a disposable AVD with no real fal credentials."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--adb', default=str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
p.add_argument('--output', required=True, type=Path)
p.add_argument('--settings-only', action='store_true', help='Skip synthetic translation sessions; exercise settings and GUI only')
a = p.parse_args()
a.output = a.output.resolve()
a.output.mkdir(parents=True, exist_ok=True)


def adb(*command):
    return subprocess.check_output([a.adb, '-s', a.serial, *command], text=True).strip()


assert a.serial.startswith('emulator-') and adb('emu', 'avd', 'name').splitlines()[0].startswith('Taku_Audio_ROM_')
assert adb('shell', 'id', '-u') == '0'
assert adb('shell', 'su', '2000', 'id', '-u') == '2000'
base = '/data/local/agentos/ai/credentials/'
for name in ['fal.json', 'fal-model.json']:
    assert adb('shell', f'test ! -e {base + name} && echo absent') == 'absent', 'Refuse to overwrite existing fal configuration'
wifi = adb('shell', 'settings get global wifi_on')
mobile = adb('shell', 'settings get global mobile_data')
assert wifi in ('0', '1') and mobile in ('0', '1')
voice = adb('shell', 'settings get global taku_ai_audio_enabled')
assert voice in ('null', '0', '1')
synthetic_key = 'local-validation-only-not-a-real-key'


def write(name, text):
    path = a.output / name
    path.write_text(text)
    adb('push', str(path), base + name)
    adb('shell', 'chmod', '600', base + name)


# AudioConfigBridge stores this beside SystemAi.FILE, under SystemAi.ROOT.
SETTINGS_PATH = '/data/local/agentos/ai/audio-settings.json'


def settings_bytes():
    return subprocess.check_output([a.adb, '-s', a.serial, 'exec-out', 'cat', SETTINGS_PATH])


def push_settings(data):
    path = a.output / 'audio-settings-raw.json'
    path.write_bytes(data)
    adb('push', str(path), SETTINGS_PATH)


def probe(name, **params):
    command = ['shell', 'am', 'instrument', '-w', '-e', 'mode', 'config']
    for key, value in params.items():
        command += ['-e', key, str(value).lower() if isinstance(value, bool) else str(value)]
    result = adb(*command, 'org.agentos.audio.tests/.ReviewRegressionProbe')
    (a.output / (name + '.txt')).write_text(result + '\n')
    report = json.loads(next(line.split('report=', 1)[1] for line in result.splitlines() if line.startswith('INSTRUMENTATION_RESULT: report=')))
    assert report['passed'], report
    return report


def public(request=None, *, method='settings', raw=None, code=None):
    command = ['su', '2000', 'content', 'call', '--user', '0', '--uri', 'content://org.agentos.audio.open-access', '--method', method]
    if raw is not None or request is not None:
        command += ['--arg', raw if raw is not None else json.dumps(request, separators=(',', ':'))]
    output = adb('shell', shlex.join(command))
    assert synthetic_key not in output, 'A settings response exposed the submitted key'
    envelope, _ = json.JSONDecoder().raw_decode(output[output.index('result=') + 7:])
    if code is not None:
        assert envelope['ok'] is False and envelope['code'] == code, envelope
        return envelope
    assert envelope['ok'] is True, envelope
    result = envelope['result']
    assert set(result) == {'enabled', 'audioEnabled', 'systemRevision', 'audioRevision', 'reading', 'fal', 'dictation'}, result
    assert set(result['fal']) == {'configured', 'masked', 'model', 'models', 'realtimeAvailable', 'error'}, result
    assert result['fal']['realtimeAvailable'] is False
    view = result['dictation'].get('assistant-entry')
    assert isinstance(view, dict), result
    assert set(view) == {'primaryProfileId', 'fallbackProfileId', 'language', 'resolvedLanguage', 'isDefault', 'error', 'backends'}, view
    for row in view['backends']:
        assert set(row) == {'profileId', 'languages', 'processing', 'available', 'unavailableReasons', 'maxCaptureMs', 'drainTimeoutMs'}, row
    return result


def capture(name):
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/review-config.xml')
    path = a.output / (name + '.xml')
    adb('pull', '/data/local/tmp/review-config.xml', str(path))
    return list(ET.parse(path).iter('node'))


def control(name, resource, *, enabled=True):
    for attempt in range(12):
        nodes = capture(name)
        node = next((node for node in nodes if node.get('resource-id') == resource and node.get('enabled') == str(enabled).lower()), None)
        if node is not None:
            return node
        bounds = list(map(int, re.findall(r'\d+', nodes[0].get('bounds'))))
        x, height = (bounds[0] + bounds[2]) // 2, bounds[3] - bounds[1]
        adb('shell', 'input', 'swipe', str(x), str(bounds[1] + height * 4 // 5), str(x), str(bounds[1] + height // 3), '200')
        time.sleep(.2)
    raise AssertionError(f'{name}: enabled={enabled} control {resource} unavailable')


def tap(node):
    left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))


def wait_state(predicate):
    for _ in range(30):
        state = public({'action': 'read'})
        if predicate(state):
            return state
        time.sleep(.1)
    raise AssertionError('Settings did not reach the requested state')


def home(page=None):
    adb('shell', 'am', 'force-stop', 'org.agentos.audio')
    command = ['shell', 'am', 'start', '-W', '-n', 'org.agentos.audio/.AudioHomeActivity']
    if page is not None:
        command += ['--es', 'page', page]
    adb(*command)


def public_roundtrip():
    before = public({'action': 'read'})
    for request, code in [
        ({'action': 'falCredential'}, 'UNSUPPORTED_OPERATION'),
        ({'action': 'read', 'op': 'falCredential'}, 'INVALID_ARGUMENT'),
        ({'action': 'saveFalKey', 'key': ''}, 'INVALID_ARGUMENT'),
        ({'action': 'saveFalKey', 'key': 'invalid key'}, 'INVALID_ARGUMENT'),
        ({'action': 'saveFalKey', 'key': 17}, 'INVALID_ARGUMENT'),
        ({'action': 'selectFalModel', 'model': 'unknown'}, 'INVALID_ARGUMENT'),
        ({'action': 'setAudioEnabled', 'enabled': 'false'}, 'INVALID_ARGUMENT'),
        ({'action': 'setAudioEnabled', 'enabled': False, 'provider': 'fal'}, 'INVALID_ARGUMENT'),
        ({'action': 'configureReading', 'language': 'en-US', 'voiceId': 'zh-CN-local-0', 'speed': 1}, 'INVALID_ARGUMENT'),
        ({'action': 'configureReading', 'language': 'en-US', 'voiceId': 'en-US-local-0', 'speed': 0}, 'INVALID_ARGUMENT'),
        ({'action': 'resetReading'}, 'INVALID_ARGUMENT'),
        ({'action': 'configureDictation'}, 'INVALID_ARGUMENT'),
        ({'action': 'configureDictation', 'scope': 'other', 'primaryProfileId': 'zipformer', 'fallbackProfileId': None, 'language': 'system'}, 'INVALID_ARGUMENT'),
        ({'action': 'configureDictation', 'scope': 'assistant-entry', 'primaryProfileId': 'unknown', 'fallbackProfileId': None, 'language': 'system'}, 'INVALID_ARGUMENT'),
        ({'action': 'configureDictation', 'scope': 'assistant-entry', 'primaryProfileId': 'zipformer', 'fallbackProfileId': None, 'language': 'ja'}, 'INVALID_ARGUMENT'),
        ({'action': 'configureDictation', 'scope': 'assistant-entry', 'primaryProfileId': 'zipformer', 'fallbackProfileId': 'zipformer', 'language': 'system'}, 'INVALID_ARGUMENT'),
        ({'action': 'configureDictation', 'scope': 'assistant-entry', 'primaryProfileId': 'zipformer', 'fallbackProfileId': None, 'language': 'system', 'resolvedLanguage': 'zh-CN'}, 'INVALID_ARGUMENT'),
        ({'action': 'resetDictation'}, 'INVALID_ARGUMENT'),
        ({'action': 'resetDictation', 'scope': 'other'}, 'INVALID_ARGUMENT'),
    ]:
        public(request, code=code)
        assert public({'action': 'read'}) == before, 'Rejected settings changed authoritative state'
    public({'action': 'read'}, method='falCredential', code='UNSUPPORTED_OPERATION')
    for raw in ['[]', '{', '{"action":"read"} trailing']:
        public(raw=raw, code='INVALID_ARGUMENT')
    public(code='INVALID_ARGUMENT')
    assert public({'action': 'read'}) == before

    saved = public({'action': 'saveFalKey', 'key': synthetic_key})
    assert saved['fal']['configured'] and saved['fal']['masked'] and saved['fal']['error'] is None
    selected = public({'action': 'selectFalModel', 'model': 'grok'})
    assert selected['fal']['model'] == 'grok'
    assert public({'action': 'saveFalKey', 'key': synthetic_key})['fal']['model'] == 'grok'
    removed = public({'action': 'removeFalKey'})
    assert not removed['fal']['configured'] and removed['fal']['masked'] is None and removed['fal']['model'] == 'grok'
    public({'action': 'selectFalModel', 'model': 'kotoba'}, code='PROVIDER_NOT_CONFIGURED')
    public({'action': 'saveFalKey', 'key': synthetic_key})
    public({'action': 'selectFalModel', 'model': 'kotoba'})

    paused = public({'action': 'setAudioEnabled', 'enabled': False})
    assert not paused['audioEnabled'] and paused['enabled'] == before['enabled']
    home()
    switch = control('voice-agent-to-gui', 'org.agentos.audio:id/audio_enabled')
    assert switch.get('checked') == 'false'
    tap(switch)
    resumed = wait_state(lambda state: state['audioEnabled'])
    assert resumed['enabled'] == before['enabled']
    assert control('voice-gui-to-agent', 'org.agentos.audio:id/audio_enabled').get('checked') == 'true'

    gui_key = synthetic_key + '-gui'
    tap(control('key-dialog-open', 'org.agentos.audio:id/audio_save_fal_key'))
    before_empty_key = public({'action': 'read'})
    tap(control('key-dialog-empty-save', 'android:id/button1'))
    assert any(node.get('resource-id') == 'org.agentos.audio:id/audio_fal_key_input' for node in capture('key-dialog-empty'))
    assert public({'action': 'read'}) == before_empty_key
    tap(control('key-dialog-input', 'org.agentos.audio:id/audio_fal_key_input'))
    adb('shell', 'input', 'text', gui_key)
    filled = next(node for node in capture('key-dialog-filled') if node.get('resource-id') == 'org.agentos.audio:id/audio_fal_key_input')
    assert filled.get('password') == 'true' and gui_key not in filled.get('text', ''), 'Submitted key must stay masked in its input'
    tap(control('key-dialog-save', 'android:id/button1'))
    gui_saved = wait_state(lambda state: state['fal']['configured'] and state['fal']['masked'].endswith('-gui'))
    assert gui_saved['fal']['model'] == resumed['fal']['model'], 'GUI key replacement must preserve the selected model'

    public({'action': 'configureReading', 'language': 'en-US', 'voiceId': 'en-US-local-999', 'speed': .5})
    home('reading')
    tap(control('reading-language', 'org.agentos.audio:id/reading_language_en'))
    tap(control('reading-speed', 'org.agentos.audio:id/reading_speed'))
    choices = capture('reading-speed-picker')
    tap(next(node for node in choices if node.get('text') == '1.25×'))
    reading = wait_state(lambda state: state['reading'].get('en-US', {}).get('speed') == 1.25)
    assert reading['reading']['en-US']['voiceId'] == 'en-US-local-999'
    tap(control('reading-reset', 'org.agentos.audio:id/reading_reset'))
    wait_state(lambda state: 'en-US' not in state['reading'])

    dictation_before = before['dictation']['assistant-entry']
    baseline = public({'action': 'read'})
    saved_dictation = public({'action': 'configureDictation', 'scope': 'assistant-entry',
                              'primaryProfileId': 'zipformer', 'fallbackProfileId': 'fal-whisper', 'language': 'zh-CN'})
    view = saved_dictation['dictation']['assistant-entry']
    assert saved_dictation['audioRevision'] == baseline['audioRevision'] + 1, 'one dictation save advances revision once'
    assert view['primaryProfileId'] == 'zipformer' and view['fallbackProfileId'] == 'fal-whisper'
    assert view['language'] == 'zh-CN' and view['resolvedLanguage'] == 'zh-CN'
    assert view['isDefault'] is False and view['error'] is None
    assert {row['profileId']: row['processing'] for row in view['backends']} == {'zipformer': 'LOCAL', 'fal-whisper': 'CLOUD_AUDIO'}
    assert saved_dictation['reading'] == baseline['reading'], 'dictation save preserves reading profiles'

    home('dictation')
    tap(control('dictation-language', 'org.agentos.audio:id/dictation_language_en'))
    gui_dictation = wait_state(lambda state: state['dictation']['assistant-entry']['language'] == 'en-US')
    assert gui_dictation['dictation']['assistant-entry']['resolvedLanguage'] == 'en-US'
    tap(control('dictation-primary', 'org.agentos.audio:id/dictation_primary_whisper'))
    gui_cloud = wait_state(lambda state: state['dictation']['assistant-entry']['primaryProfileId'] == 'fal-whisper')
    cloud_row = next(row for row in gui_cloud['dictation']['assistant-entry']['backends'] if row['profileId'] == 'fal-whisper')
    assert cloud_row['processing'] == 'CLOUD_AUDIO' and cloud_row['available'] is True
    tap(control('dictation-reset', 'org.agentos.audio:id/dictation_reset'))
    wait_state(lambda state: state['dictation']['assistant-entry']['isDefault'])

    if dictation_before['isDefault']:
        public({'action': 'resetDictation', 'scope': 'assistant-entry'})
    else:
        public({'action': 'configureDictation', 'scope': 'assistant-entry',
                'primaryProfileId': dictation_before['primaryProfileId'],
                'fallbackProfileId': dictation_before['fallbackProfileId'],
                'language': dictation_before['language']})
    return {'publicSettingsRoundtrip': True, 'rejectedSettingsPreservedState': True, 'maskedKeyAndModelRetention': True,
            'voiceGuiAgentRoundtrip': True, 'guiKeyInputMasked': True, 'guiKeySaveAndModelRetention': True,
            'readingGuiAgentRoundtrip': True, 'unknownSavedVoicePreserved': True,
            'dictationGuiAgentRoundtrip': True, 'dictationRevisionAtomicity': True, 'callerUid': 2000}


reports = []
original = None
try:
    adb('shell', 'svc wifi disable')
    adb('shell', 'svc data disable')
    adb('shell', 'mkdir', '-p', base)
    original = public({'action': 'read'})
    assert original['enabled'], 'Default-model fixture requires global AI enabled'
    adb('shell', 'settings put global taku_ai_audio_enabled 0')
    reports.append(probe('policy-revoked', configured=False, credentialError='POLICY_REVOKED'))
    adb('shell', 'settings put global taku_ai_audio_enabled 1')
    reports.append(probe('missing-key', configured=False, credentialError='PROVIDER_NOT_CONFIGURED'))
    reports.append(public_roundtrip())
    raw_settings = settings_bytes()
    try:
        parsed_settings = json.loads(raw_settings)
        parsed_settings['dictation'] = {'assistant-entry': 'broken'}
        push_settings(json.dumps(parsed_settings).encode())
        broken_view = public({'action': 'read'})['dictation']['assistant-entry']
        assert broken_view['isDefault'] is False and broken_view['error']['code'] == 'SETTINGS_UNREADABLE', broken_view
        assert broken_view['primaryProfileId'] is None and broken_view['language'] is None, broken_view
        reports.append({'malformedDictationExplicit': True})
        push_settings(b'{')
        public({'action': 'configureDictation', 'scope': 'assistant-entry', 'primaryProfileId': 'zipformer',
                'fallbackProfileId': None, 'language': 'system'}, code='SETTINGS_UNREADABLE')
        assert settings_bytes() == b'{', 'Unreadable settings must never be overwritten'
        reports.append({'unreadableFilePreserved': True})
    finally:
        push_settings(raw_settings)
    write('fal.json', '{')
    reports.append(probe('corrupt-key', error='KEY_UNREADABLE', configured=False, credentialError='PROVIDER_NOT_CONFIGURED'))
    corrupt = public({'action': 'read'})
    assert corrupt['fal']['error'] == 'KEY_UNREADABLE' and not corrupt['fal']['configured']
    home()
    control('corrupt-key-replace', 'org.agentos.audio:id/audio_save_fal_key')
    tap(control('corrupt-key-remove', 'org.agentos.audio:id/audio_remove_fal_key'))
    tap(control('corrupt-key-confirm', 'android:id/button1'))
    wait_state(lambda state: state['fal']['error'] is None and not state['fal']['configured'])
    public({'action': 'saveFalKey', 'key': synthetic_key})
    write('fal-model.json', '{')
    reports.append(probe('corrupt-model', error='MODEL_UNREADABLE', configured=True, model='kotoba'))
    corrupt = public({'action': 'read'})
    assert corrupt['fal']['error'] == 'MODEL_UNREADABLE' and corrupt['fal']['configured']
    fallback = next(model['name'] for model in corrupt['fal']['models'] if model['id'] == corrupt['fal']['model'])
    home()
    tap(control('corrupt-model-select', 'org.agentos.audio:id/audio_model'))
    tap(next(node for node in capture('corrupt-model-picker') if node.get('text') == fallback))
    wait_state(lambda state: state['fal']['error'] is None and state['fal']['model'] == 'kotoba')
    reports.append({'corruptKeyGuiRemoval': True, 'corruptModelGuiRepair': True})
    for model in ([] if a.settings_only else ['kotoba', 'grok', 'whisper', 'missing']):
        if model == 'missing':
            adb('shell', 'rm', base + 'fal.json')
        else:
            write('fal-model.json', json.dumps({'model': model}))
        adb('shell', 'am', 'start', '-W', '-n', 'org.agentos.audio/.AudioHostActivity')
        time.sleep(2)
        env = {**os.environ, 'ADB': a.adb, 'TAKU_OS_SERIAL': a.serial}
        result = subprocess.check_output(['node', 'system/audio-engine/tests/review-defaults.mjs', model], cwd=ROOT, env=env, text=True)
        (a.output / (model + '-defaults.json')).write_text(result)
        reports.append(json.loads(result))
    (a.output / 'config.json').write_text(json.dumps({'passed': True, 'syntheticCredentialOnly': True, 'networkDisabled': True,
                                                   'defaultProfilesVerified': not a.settings_only,
                                                   'cloudTranslationVerified': False, 'reports': reports}, indent=2) + '\n')
finally:
    try:
        if original is not None:
            profile = original['reading'].get('en-US')
            public({'action': 'resetReading', 'language': 'en-US'} if profile is None else {'action': 'configureReading', 'language': 'en-US', **profile})
            public({'action': 'setAudioEnabled', 'enabled': original['audioEnabled']})
            view = original['dictation']['assistant-entry']
            try:
                if view['isDefault']:
                    public({'action': 'resetDictation', 'scope': 'assistant-entry'})
                else:
                    public({'action': 'configureDictation', 'scope': 'assistant-entry',
                            'primaryProfileId': view['primaryProfileId'],
                            'fallbackProfileId': view['fallbackProfileId'],
                            'language': view['language']})
            except AssertionError:
                pass
    finally:
        adb('shell', 'am', 'force-stop', 'org.taku.callfixture.target')
        adb('shell', 'am', 'force-stop', 'org.agentos.audio')
        adb('shell', 'rm', '-f', base + 'fal.json', base + 'fal-model.json')
        adb('shell', 'settings delete global taku_ai_audio_enabled' if voice == 'null' else 'settings put global taku_ai_audio_enabled ' + voice)
        adb('shell', 'svc', 'wifi', 'enable' if wifi == '1' else 'disable')
        adb('shell', 'svc', 'data', 'enable' if mobile == '1' else 'disable')
