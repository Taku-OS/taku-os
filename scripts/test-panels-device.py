#!/usr/bin/env python3
"""Exercise the public Panel interface on one explicitly named isolated emulator.

Every assertion reads `content://org.agentos.assistant.open-access` back. The probe
APK is an independently signed ordinary application with no permissions; the shell
surface uses the same public provider. Nothing here seeds `matters.db`, invents a
producer observation, or adds a test endpoint to the Assistant.

Modes fall in two groups. `accept` runs the provider-verifiable part of spec section 10
against a caller-supplied real source and leaves the directory as it found it.
`shell`, `probe`, `get`, `state`, `continue`, `wait-update`, `pagination`, `card-view`,
`expiry` and `large` are driver steps for the GUI, cross-turn and large-result cases the
parent controls by hand. `card-view` writes only to the card state of the matter it is given,
proves each declared false, 0, empty-text and cleared-selection transition, and restores the
drafts and selections it changed.
"""
import argparse
import json
import os
import re
import select
import shlex
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB_DEFAULT = os.environ.get('ADB', str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
PROVIDER = 'content://org.agentos.assistant.open-access'
ASSISTANT = 'org.agentos.assistant'
AGENT_APP = 'org.agentos.agent'
PROBE_PACKAGE = 'org.agentos.panelsprobe'
PROBE_COMPONENT = 'org.agentos.panelsprobe/.PanelsProbe'
PROBE_BUILD = ROOT / 'system/assistant/tests/panels-device/build-probe.sh'
PROBE_OUTPUT = ROOT / 'out/panels-device/probe'
DEDICATED_AVD = 'Taku_Panels_20261115'
USER_EMULATOR = 'emulator-5680'
INLINE_RESULT_LIMIT = 256 * 1024
UUID_PATTERN = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z')
MATTER_PATTERN = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[a-z0-9-]{1,64}\Z')
PANEL_FIELDS = ('panelId', 'originThreadId', 'matterId', 'sourceVersion', 'metadataRevision',
                'title', 'position', 'createdAt', 'updatedAt')
REQUIRED_METHODS = ('panels/describe', 'panels/list', 'panels/get', 'panels/pin', 'panels/unpin',
                    'panels/rename', 'panels/move', 'panels/open', 'panels/navigation',
                    'panels/continue', 'matter/read', 'matter/dispatch', 'card-view/read',
                    'card-view/apply')
EXPECTED_ERRORS = ('INVALID_ARGUMENT', 'UNKNOWN_METHOD', 'PANEL_NOT_FOUND', 'IDEMPOTENCY_CONFLICT',
                   'VERSION_CONFLICT', 'LIST_CHANGED', 'SOURCE_UNAVAILABLE', 'MATTER_UNAVAILABLE')


class Failed(Exception):
    """A device or provider observation that contradicts the contract."""


def require(condition, message):
    if not condition:
        raise Failed(message)


def new_request_id():
    return str(uuid.uuid4())


def sha256_bytes(data):
    import hashlib
    return hashlib.sha256(data).hexdigest()


def slug(text):
    cleaned = re.sub(r'[^A-Za-z0-9]+', '-', text).strip('-').lower()
    return cleaned[:32] or 'call'


def json_arg(payload):
    return json.dumps(payload if payload is not None else {}, ensure_ascii=True, separators=(',', ':'))


def record(details, response):
    details.setdefault('requests', []).append(response.describe())
    return response


class Device:
    def __init__(self, serial, adb, avd):
        self.serial = serial
        self.adb = adb
        self.expected_avd = avd
        self.avd_name = ''
        self.clock_command = None
        self.clock_scale = None
        self.clock_resolution_ms = None

    def run(self, argv, timeout=120, check=True, text=True):
        command = [self.adb, '-s', self.serial, *argv]
        result = subprocess.run(command, capture_output=True, text=text, timeout=timeout)
        if check and result.returncode != 0:
            output = result.stderr if text else result.stderr.decode('utf-8', 'replace')
            raise Failed(f"adb {argv[0]} failed: {output.strip()[:400]}")
        return result

    def shell(self, *command, timeout=120, check=True):
        return self.run(['shell', shlex.join(command)], timeout=timeout, check=check).stdout

    def exec_out(self, *command, timeout=180, check=True):
        return self.run(['exec-out', *command], timeout=timeout, check=check, text=False).stdout

    def verify(self):
        require(re.fullmatch(r'emulator-\d{4}', self.serial) is not None,
                f'Use an explicit emulator serial, got {self.serial!r}; physical devices are excluded')
        require(self.serial != USER_EMULATOR,
                f'{USER_EMULATOR} belongs to the user and is never used by this harness')
        state = self.run(['get-state']).stdout.strip()
        require(state == 'device', f'{self.serial} is {state!r}, not an online emulator')
        self.avd_name = self.shell('getprop', 'ro.boot.qemu.avd_name').strip()
        if not self.avd_name:
            lines = self.run(['emu', 'avd', 'name'], check=False).stdout.splitlines()
            self.avd_name = lines[0].strip() if lines else ''
        require(self.avd_name == self.expected_avd,
                f'{self.serial} runs AVD {self.avd_name!r}; expected {self.expected_avd!r} '
                '(pass --avd only for another explicitly isolated AVD)')
        self._detect_clock()
        return self

    def _detect_clock(self):
        raw = self.shell('sh', '-c', 'date +%s%3N', check=False).strip()
        if re.fullmatch(r'\d{13}', raw):
            self.clock_command, self.clock_scale, self.clock_resolution_ms = 'date +%s%3N', 1, 1
        else:
            self.clock_command, self.clock_scale, self.clock_resolution_ms = 'date +%s', 1000, 1000

    def shell_windowed_call(self, method, payload):
        quoted = (f"a=$({self.clock_command}); "
                  f"out=$(content call --user 0 --uri {shlex.quote(PROVIDER)} "
                  f"--method {shlex.quote(method)} --arg {shlex.quote(payload)} 2>&1); "
                  f"b=$({self.clock_command}); "
                  f'printf "WINDOW %s %s\\n" "$a" "$b"; '
                  'printf "%s\\n" "$out"')
        raw = self.shell('sh', '-c', quoted, timeout=120)
        window, body = None, []
        for line in raw.splitlines():
            if line.startswith('WINDOW '):
                parts = line.split()
                if len(parts) == 3 and parts[1].isdigit() and parts[2].isdigit():
                    window = (int(parts[1]) * self.clock_scale, int(parts[2]) * self.clock_scale)
            else:
                body.append(line)
        return '\n'.join(body), window

    def package_version(self, package):
        raw = self.shell('dumpsys', 'package', package, check=False)
        if not raw.strip():
            return None
        version_name = re.search(r'versionName=(\S+)', raw)
        version_code = re.search(r'versionCode=(\d+)', raw)
        updated = re.search(r'lastUpdateTime=(\S+)', raw)
        return {'versionName': version_name.group(1) if version_name else None,
                'versionCode': int(version_code.group(1)) if version_code else None,
                'lastUpdateTime': updated.group(1) if updated else None}


class Response:
    def __init__(self, surface, method, params, outer, payload, payload_text,
                 outer_bytes, payload_bytes, payload_hash, followed, meta):
        self.surface = surface
        self.method = method
        self.params = params
        self.outer = outer
        self.payload = payload
        self.payload_text = payload_text
        self.outer_bytes = outer_bytes
        self.payload_bytes = payload_bytes
        self.payload_sha256 = payload_hash
        self.followed_result_uri = followed
        self.meta = meta

    @property
    def ok(self):
        return bool(self.payload.get('ok')) if isinstance(self.payload, dict) else False

    @property
    def code(self):
        if isinstance(self.payload, dict) and self.payload.get('code'):
            return str(self.payload['code'])
        return ''

    @property
    def result(self):
        return self.payload.get('result') if isinstance(self.payload, dict) else None

    @property
    def request_id(self):
        return self.params.get('requestId') if isinstance(self.params, dict) else None

    def describe(self, full_payload_limit=64 * 1024):
        data = {'surface': self.surface, 'method': self.method, 'params': self.params,
                'requestId': self.request_id, 'outer': self.outer, 'ok': self.ok, 'code': self.code,
                'outerBytes': self.outer_bytes, 'payloadBytes': self.payload_bytes,
                'payloadSha256': self.payload_sha256, 'followedResultUri': self.followed_result_uri}
        if self.followed_result_uri and self.payload_bytes <= full_payload_limit:
            data['payload'] = self.payload
        data.update({key: value for key, value in self.meta.items() if value is not None})
        return data


class ProbeRun:
    def __init__(self, document, evidence_path):
        self.document = document
        self.evidence_path = evidence_path
        self.name = document['name']
        self.calls = document['calls']

    def response(self, index=-1):
        position = index if index >= 0 else len(self.calls) + index
        call = self.calls[position]
        require('transportError' not in call,
                f"probe {self.document['method']} call {position} failed: {call.get('transportError')}")
        outer_text = call['outer']
        require(sha256_bytes(outer_text.encode()) == call['outerSha256'], 'probe outer envelope hash mismatch')
        outer = json.loads(outer_text)
        payload_text = call.get('payloadText') or outer_text
        payload = json.loads(payload_text)
        payload_bytes = len(payload_text.encode())
        if call.get('payloadBytes') is not None:
            require(payload_bytes == call['payloadBytes'], 'probe payload byte count mismatch')
            require(sha256_bytes(payload_text.encode()) == call['payloadSha256'], 'probe payload hash mismatch')
        meta = {'probeFile': str(self.evidence_path.name), 'probeCall': position,
                'probeUid': self.document.get('uid'), 'probePackage': self.document.get('package'),
                'startedAtEpochMs': call.get('startedAtEpochMs'),
                'finishedAtEpochMs': call.get('finishedAtEpochMs')}
        return Response('probe', self.document['method'], json.loads(self.document['arg']), outer, payload,
                        payload_text, len(outer_text.encode()), payload_bytes,
                        sha256_bytes(payload_text.encode()), call.get('payloadBytes') is not None, meta)

    def call_fields(self):
        fields = []
        for index, call in enumerate(self.calls):
            entry = {'index': index, 'start': call.get('startedAtEpochMs'),
                     'end': call.get('finishedAtEpochMs')}
            if 'transportError' in call:
                entry.update({'ok': False, 'code': 'TRANSPORT_ERROR', 'error': call['transportError'],
                              'created': False, 'replayed': False, 'panelId': None})
                fields.append(entry)
                continue
            require(sha256_bytes(call['outer'].encode()) == call['outerSha256'],
                    f'probe call {index} outer envelope hash mismatch')
            outer = json.loads(call['outer'])
            result = outer.get('result') if isinstance(outer.get('result'), dict) else {}
            panel = result.get('panel') if isinstance(result.get('panel'), dict) else {}
            entry.update({'ok': bool(outer.get('ok')), 'code': outer.get('code', ''),
                          'created': bool(result.get('created')), 'replayed': bool(result.get('replayed')),
                          'panelId': panel.get('panelId')})
            fields.append(entry)
        return fields


class Client:
    def __init__(self, device, run_dir, token):
        self.device = device
        self.run_dir = run_dir
        self.token = token
        self.sequence = 0

    def shell(self, method, params=None, window=False):
        payload = json_arg(params)
        started = time.monotonic()
        detail = ''
        if window:
            raw, measured = self.device.shell_windowed_call(method, payload)
        else:
            # Exit status alone is not success: a permission or lookup exception can leave
            # stdout empty while the command still exits 0, so both streams are inspected.
            completed = self.device.run(['shell', shlex.join([
                'content', 'call', '--user', '0', '--uri', PROVIDER,
                '--method', method, '--arg', payload])], timeout=120, check=False)
            raw = completed.stdout
            detail = (completed.stdout + completed.stderr).strip()[:600]
            measured = None
        match = re.search(r'Bundle\[\{result=(?P<body>.*)\}\]\s*\Z', raw, re.S)
        require(match is not None,
                f'content call {method} returned no result bundle (exit detail: {detail or raw.strip()[:600]})')
        outer_text = match.group('body')
        outer = json.loads(outer_text)
        payload_text = outer_text
        followed = False
        payload_file = None
        if isinstance(outer, dict) and outer.get('resultUri') and 'result' not in outer:
            uri = outer['resultUri']
            require(isinstance(uri, str) and uri.startswith(PROVIDER + '/'),
                    f'unexpected result URI {uri!r}')
            data = self.device.exec_out('content', 'read', '--uri', uri)
            payload_text = data.decode('utf-8')
            followed = True
            self.sequence += 1
            payload_file = self.run_dir / f'shell-payload-{self.sequence:02d}-{slug(method)}.json'
            payload_file.write_bytes(data)
        payload = json.loads(payload_text)
        meta = {'durationMs': round((time.monotonic() - started) * 1000),
                'window': list(measured) if measured else None,
                'clockResolutionMs': self.device.clock_resolution_ms,
                'payloadFile': str(payload_file.name) if payload_file else None,
                'shellBytes': len(payload_text.encode()) if followed else None}
        return Response('shell', method, params or {}, outer, payload, payload_text,
                        len(outer_text.encode()), len(payload_text.encode()),
                        sha256_bytes(payload_text.encode()), followed, meta)

    def probe(self, method, params=None, repeat=1, interval_ms=0, barrier_ms=0,
              on_ready=None, ready_fallback_ms=3000, consume_delay_ms=0):
        require(1 <= repeat <= 200, f'repeat must be 1..200, got {repeat}')
        require(0 <= interval_ms <= 10_000, f'interval must be 0..10000 ms, got {interval_ms}')
        require(0 <= barrier_ms <= 60_000, f'barrier must be 0..60000 ms, got {barrier_ms}')
        require(0 <= ready_fallback_ms <= 60_000, f'ready fallback must be 0..60000 ms, got {ready_fallback_ms}')
        require(0 <= consume_delay_ms <= 60_000, f'consume delay must be 0..60000 ms, got {consume_delay_ms}')
        self.sequence += 1
        name = f'{self.token}-{self.sequence:02d}-{slug(method)}'
        require(len(name) <= 80, 'probe evidence name is too long')
        arguments = ['am', 'instrument', '-w',
                     '-e', 'method', method, '-e', 'arg', json_arg(params),
                     '-e', 'name', name, '-e', 'repeat', str(repeat),
                     '-e', 'intervalMs', str(interval_ms), '-e', 'barrierMs', str(barrier_ms),
                     '-e', 'consumeDelayMs', str(consume_delay_ms),
                     PROBE_COMPONENT]
        process = subprocess.Popen([self.device.adb, '-s', self.device.serial, 'shell', shlex.join(arguments)],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        descriptor = process.stdout.fileno()
        lines, buffer, fired = [], b'', False

        def fire():
            nonlocal fired
            if fired:
                return
            fired = True
            if on_ready:
                on_ready()

        def drain(chunk):
            nonlocal buffer
            buffer += chunk
            while b'\n' in buffer:
                raw, buffer = buffer.split(b'\n', 1)
                line = raw.decode('utf-8', 'replace')
                lines.append(line + '\n')
                if 'phase=ready' in line:
                    fire()

        fallback_deadline = time.monotonic() + ready_fallback_ms / 1000
        try:
            while True:
                timeout = 1.0 if fired else max(0.0, fallback_deadline - time.monotonic())
                readable, _, _ = select.select([descriptor], [], [], timeout)
                if not readable:
                    if not fired:
                        fire()
                        continue
                    if process.poll() is not None:
                        break
                    continue
                chunk = os.read(descriptor, 65536)
                if not chunk:
                    if buffer:
                        lines.append(buffer.decode('utf-8', 'replace'))
                    break
                drain(chunk)
            process.wait(timeout=240)
        except subprocess.TimeoutExpired:
            process.kill()
            raise Failed(f'am instrument {method} did not finish within 240s')
        finally:
            if process.poll() is None:
                process.kill()
            process.stdout.close()
        output = ''.join(lines)
        result_line = next((line for line in reversed(output.splitlines())
                            if line.startswith('INSTRUMENTATION_RESULT: result=')), None)
        error_line = next((line for line in reversed(output.splitlines())
                           if line.startswith('INSTRUMENTATION_RESULT: error=')), None)
        require(error_line is None, f'probe reported {error_line or ""}')
        require(result_line is not None, f'probe produced no result: {output.strip()[-600:]}')
        report = json.loads(result_line.split('result=', 1)[1])
        remote = report['filePath']
        data = self.device.exec_out('run-as', PROBE_PACKAGE, '--user', '0', 'cat', remote)
        require(len(data) == report['fileBytes'],
                f"probe evidence file is {len(data)} bytes, probe reported {report['fileBytes']}")
        require(sha256_bytes(data) == report['fileSha256'], 'probe evidence file hash mismatch')
        document = json.loads(data)
        require(document.get('uid') == report['uid'] and isinstance(document.get('uid'), int)
                and document['uid'] >= 10000, 'probe did not report an ordinary application UID')
        require(not document.get('requestedPermissions'), 'probe declares permissions')
        evidence_path = self.run_dir / f'probe-{name}.json'
        evidence_path.write_bytes(data)
        self.device.exec_out('run-as', PROBE_PACKAGE, '--user', '0', 'rm', '-f', remote, check=False)
        return ProbeRun(document, evidence_path)

    def wait_ready(self, timeout=90):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            try:
                response = self.shell('panels/list', {'limit': 1})
                if response.ok:
                    return response
                last = response.code or response.outer
            except Failed as failure:
                last = str(failure)
            time.sleep(1)
        raise Failed(f'public Panel interface did not become ready within {timeout}s (last: {last})')


def expect_ok(response, label):
    require(response.ok, f"{label}: {json.dumps(response.payload, ensure_ascii=False)[:600]}")
    return response.result


def expect_error(response, allowed, label):
    require(not response.ok, f"{label}: expected an error, got {json.dumps(response.payload, ensure_ascii=False)[:600]}")
    code = response.code
    require(re.fullmatch(r'[A-Z][A-Z_]{2,80}', code) is not None, f'{label}: malformed error code {code!r}')
    require(code in allowed, f'{label}: code {code} is not one of {sorted(allowed)}')
    return code


def require_panel(panel, label):
    require(isinstance(panel, dict), f'{label}: panel is not an object')
    for field in PANEL_FIELDS:
        require(field in panel, f'{label}: panel has no {field}')
    require(UUID_PATTERN.match(str(panel['panelId'])) is not None, f"{label}: panelId {panel['panelId']!r} is not a lowercase UUID")
    require(UUID_PATTERN.match(str(panel['originThreadId'])) is not None, f'{label}: originThreadId is not a lowercase UUID')
    require(MATTER_PATTERN.match(str(panel['matterId'])) is not None, f"{label}: matterId {panel['matterId']!r} is not threadId/key")
    for field in ('sourceVersion', 'metadataRevision', 'position'):
        require(isinstance(panel[field], int) and not isinstance(panel[field], bool) and panel[field] >= 0,
                f'{label}: {field} is not a non-negative integer')
    require(isinstance(panel['title'], str) and 1 <= len(panel['title']) <= 120,
            f"{label}: title {panel['title']!r} is not 1..120 characters")
    return panel




def list_all(client, limit=100, surface='shell'):
    items, cursor, revisions, pages = [], None, set(), 0
    while True:
        params = {'limit': limit}
        if cursor is not None:
            params['cursor'] = cursor
        response = client.shell('panels/list', params) if surface == 'shell' else client.probe('panels/list', params).response()
        result = expect_ok(response, f'panels/list limit={limit}')
        require(isinstance(result, dict), 'panels/list result is not an object')
        for key in ('items', 'nextCursor', 'listRevision'):
            require(key in result, f'panels/list result has no {key}')
        require(isinstance(result['items'], list), 'panels/list items is not a list')
        require(isinstance(result['listRevision'], int) and result['listRevision'] >= 0,
                'panels/listRevision is not a non-negative integer')
        for item in result['items']:
            require(isinstance(item, dict) and isinstance(item.get('panelId'), str),
                    'panels/list item has no panelId')
        revisions.add(result['listRevision'])
        items.extend(result['items'])
        pages += 1
        require(pages <= 500, 'panels/list pagination did not terminate')
        cursor = result['nextCursor']
        if not cursor:
            break
        require(isinstance(cursor, str) and cursor, 'panels/list nextCursor is not a string')
    require(len(revisions) == 1, f'listRevision changed during one paginated read: {sorted(revisions)}')
    return items, revisions.pop(), pages


def get_panel(client, panel_id, surface='probe'):
    response = (client.probe('panels/get', {'panelId': panel_id}).response() if surface == 'probe'
                else client.shell('panels/get', {'panelId': panel_id}))
    result = expect_ok(response, 'panels/get')
    for key in ('panel', 'sourceState', 'updateReceipt', 'capabilities'):
        require(key in result, f'panels/get result has no {key}')
    require_panel(result['panel'], 'panels/get panel')
    require(isinstance(result['capabilities'], dict), 'panels/get capabilities is not an object')
    return result


def directory_ids(items):
    return [item['panelId'] for item in items]


def panel_for_source(items, source):
    if source.get('kind') == 'matter':
        return [item for item in items if item.get('matterId') == source['matterId']]
    return []


class Recorder:
    def __init__(self, run_dir, metadata):
        self.run_dir = run_dir
        self.metadata = metadata
        self.index = 0
        self.entries = []
        self.started = timestamp()

    def save(self, name, status, details, started, finished, reason=''):
        self.index += 1
        path = self.run_dir / f'{self.index:02d}-{name}.json'
        document = {'scenario': name, 'status': status, 'device': self.metadata,
                    'startedAt': started, 'finishedAt': finished, 'details': details}
        if reason:
            document['reason'] = reason
        path.write_text(json.dumps(document, ensure_ascii=False, indent=2, default=str) + '\n')
        gaps = details.get('gaps', []) if isinstance(details, dict) else []
        self.entries.append({'scenario': name, 'status': status, 'file': path.name,
                             'reason': reason, 'gaps': gaps})
        return path

    def write_summary(self, extra=None):
        summary = {'startedAt': self.started, 'finishedAt': timestamp(),
                   'device': self.metadata, 'scenarios': self.entries}
        if extra:
            summary.update(extra)
        (self.run_dir / 'summary.json').write_text(
            json.dumps(summary, ensure_ascii=False, indent=2, default=str) + '\n')


def timestamp():
    return time.strftime('%Y-%m-%dT%H:%M:%S%z')


def run_scenario(recorder, name, function, ctx):
    started = timestamp()
    details, reason, status = {'requests': []}, '', 'passed'
    clock = time.monotonic()
    try:
        function(details, ctx)
        status = details.pop('status', 'passed')
        reason = details.pop('reason', '')
    except Failed as failure:
        status, reason = 'failed', str(failure)
    except Exception as failure:  # unexpected harness failure still becomes evidence
        status, reason = 'failed', f'{type(failure).__name__}: {failure}'
    details['durationMs'] = round((time.monotonic() - clock) * 1000)
    recorder.save(name, status, details, started, timestamp(), reason)
    print(f'{name}: {status}' + (f' ({reason})' if reason else ''), flush=True)
    return status


def preflight(details, ctx):
    device, client, options = ctx['device'], ctx['client'], ctx['options']
    details['avd'] = device.avd_name
    details['assistant'] = device.package_version(ASSISTANT)
    details['agentApp'] = device.package_version(AGENT_APP)
    details['probe'] = probe_installation(device)
    details['probeArtifact'] = installed_probe_artifact(device)
    require(details['probeArtifact'].get('matchesBuild') is not False,
            'the installed probe APK differs from the build artifact: rebuild and reinstall before accepting')
    probe_run = client.probe('panels/list', {'limit': 1})
    probe_response = record(details, probe_run.response())
    details['probeRuntime'] = {'uid': probe_run.document['uid'],
                               'package': probe_run.document['package'],
                               'requestedPermissions': probe_run.document['requestedPermissions'],
                               'listOk': probe_response.ok, 'listCode': probe_response.code}
    ctx['probe_uid'] = probe_run.document['uid']
    items, revision, pages = list_all(client)
    ctx['baseline_ids'] = directory_ids(items)
    ctx['baseline_revision'] = revision
    details['baseline'] = {'panelIds': ctx['baseline_ids'], 'listRevision': revision, 'pages': pages}
    details['source'] = options.source
    if options.source.get('kind') == 'matter':
        response = record(details, client.probe('matter/read', {'matterId': options.source['matterId']}).response())
        if response.ok:
            result = response.result
            snapshot = result.get('snapshot') if isinstance(result, dict) else None
            require(isinstance(snapshot, dict) and isinstance(snapshot.get('pin'), dict),
                    'matter/read returned no pin capability for the pin source')
            details['matterRead'] = {'resultKeys': sorted(result), 'pin': snapshot['pin']}
            require(snapshot['pin'].get('canPin') is True,
                    'the supplied source is not pinnable: '
                    f"{snapshot['pin'].get('code') or snapshot['pin']}")
            require(snapshot['pin'].get('panelId') is None,
                    f"the supplied source is already pinned as {snapshot['pin'].get('panelId')}")
        else:
            details['matterRead'] = {'ok': False, 'code': response.code,
                                     'note': 'the source is not admitted yet; pin and panels/get stay authoritative'}
        existing = panel_for_source(items, options.source)
        require(not existing,
                'source is already pinned as ' + ', '.join(item['panelId'] for item in existing) +
                '; unpin it or choose another real source; this harness never adopts an existing panel')
    if options.second_source:
        details['secondSource'] = options.second_source
    details['versions'] = [{'listRevision': revision, 'panelCount': len(items)}]


def describe_scenario(details, ctx):
    response = record(details, ctx['client'].probe('panels/describe', {}).response())
    result = expect_ok(response, 'panels/describe')
    require(isinstance(result, dict) and result, 'panels/describe result is empty')
    methods = result.get('methods')
    require(isinstance(methods, dict), 'panels/describe has no methods object')
    missing = [name for name in REQUIRED_METHODS if name not in methods]
    require(not missing, f'panels/describe omits methods: {missing}')
    version_keys = [key for key in result if 'version' in key.lower()]
    require(version_keys, 'panels/describe has no contract version field')
    errors = result.get('errors')
    require(isinstance(errors, list) and errors, 'panels/describe has no errors list')
    require(all(isinstance(code, str) and re.fullmatch(r'[A-Z][A-Z_]{2,80}', code) for code in errors),
            f'panels/describe errors is not a list of codes: {errors}')
    details['versionKeys'] = version_keys
    details['documentedMethods'] = len(methods)
    details['documentedErrors'] = len(errors)
    undocumented = [code for code in EXPECTED_ERRORS if code not in errors]
    if undocumented:
        details['undocumentedErrors'] = undocumented
        details['gaps'] = [f'panels/describe does not document codes this build returns: {undocumented}']
    limits = result.get('limits')
    details['limits'] = limits if limits is not None else 'not a top-level field'


def race_pin(details, ctx):
    client, options = ctx['client'], ctx['options']
    shell_request, probe_request = new_request_id(), new_request_id()
    shell_params = {'source': options.source, 'requestId': shell_request}
    probe_params = {'source': options.source, 'requestId': probe_request}
    captured = {}

    def fire_shell():
        def run():
            try:
                captured['response'] = client.shell('panels/pin', shell_params, window=True)
            except Exception as failure:  # captured for evidence, not swallowed
                captured['error'] = f'{type(failure).__name__}: {failure}'
        captured['thread'] = threading.Thread(target=run, name='shell-pin', daemon=True)
        captured['thread'].start()

    run = client.probe('panels/pin', probe_params, repeat=options.race_repeat,
                       interval_ms=options.race_interval_ms, on_ready=fire_shell,
                       ready_fallback_ms=options.race_delay_ms)
    require('thread' in captured,
            'the probe finished before the shell pin was released; the concurrent window was never opened')
    captured['thread'].join(timeout=240)
    require('error' not in captured, f"shell pin failed: {captured.get('error')}")
    require('response' in captured, 'shell pin did not complete')
    shell_response = record(details, captured['response'])
    record(details, run.response())
    details['requestIds'] = {'shell': shell_request, 'probe': probe_request}
    details['race'] = {'probeCalls': len(run.calls), 'intervalMs': options.race_interval_ms,
                       'readyFallbackMs': options.race_delay_ms,
                       'clockResolutionMs': client.device.clock_resolution_ms}
    expect_ok(shell_response, 'concurrent shell pin')
    fields = run.call_fields()
    details['probeCalls'] = fields
    for entry in fields:
        require(entry['ok'], f"probe pin call {entry['index']} failed: {entry['code']}")
    panel_ids = {shell_response.result['panel']['panelId']} | {entry['panelId'] for entry in fields}
    require(len(panel_ids) == 1, f'entries reported different panels: {sorted(panel_ids)}')
    panel_id = panel_ids.pop()
    ctx['panel_id'] = panel_id
    ctx['created'].append(panel_id)
    require_panel(shell_response.result['panel'], 'shell pin panel')
    created = 1 if (shell_response.result.get('created') and not shell_response.result.get('replayed')) else 0
    created += sum(1 for entry in fields if entry['created'] and not entry['replayed'])
    require(created == 1, f'expected exactly one creating response, saw {created}')
    items, revision, _ = list_all(client)
    added = [item for item in items if item['panelId'] not in set(ctx['baseline_ids'])]
    ctx['created'].extend(item['panelId'] for item in added)
    require(len(added) == 1 and added[0]['panelId'] == panel_id,
            f'expected one new directory item, saw {[item["panelId"] for item in added]}')
    window = shell_response.meta.get('window')
    require(window is not None, 'shell pin produced no device window for overlap measurement')
    overlapping = [entry for entry in fields if entry['start'] is not None
                   and window[0] <= entry['end'] and entry['start'] <= window[1]]
    details['overlap'] = {'shellWindow': list(window), 'overlappingProbeCalls': len(overlapping),
                          'clockResolutionMs': client.device.clock_resolution_ms}
    require(overlapping, 'no probe pin call overlapped the shell pin window; concurrency was not exercised')
    panel = get_panel(client, panel_id)['panel']
    source = options.source
    if source.get('kind') == 'matter':
        require(panel['matterId'] == source['matterId'],
                f"panel matterId {panel['matterId']!r} differs from the pinned source")
        require(panel['originThreadId'] == source['matterId'].split('/', 1)[0],
                'panel originThreadId is not the source conversation')
    else:
        require(panel['originThreadId'] == source['threadId'],
                'panel originThreadId is not the primary thread')
        require('/' in panel['matterId'], 'panel has no resolved matterId')
    ctx['panel'] = panel
    winner = 'shell' if shell_response.result.get('created') else 'probe'
    ctx['pin'] = {'surface': winner, 'params': shell_params if winner == 'shell' else probe_params,
                  'panelId': panel_id}
    details['versions'] = [{'listRevision': revision, 'panelCount': len(items),
                            'panel': {key: panel[key] for key in ('panelId', 'matterId', 'sourceVersion',
                                                                  'metadataRevision', 'title', 'position')}}]


def list_get_parity(details, ctx):
    client, panel_id = ctx['client'], ctx['panel_id']
    shell_items, shell_revision, shell_pages = list_all(client, surface='shell')
    probe_items, probe_revision, probe_pages = list_all(client, surface='probe')
    require(directory_ids(shell_items) == directory_ids(probe_items),
            'shell and probe read different panel order')
    require(shell_revision == probe_revision, 'shell and probe read different listRevision')
    probe_get = get_panel(client, panel_id, surface='probe')
    shell_get = get_panel(client, panel_id, surface='shell')
    for field in PANEL_FIELDS:
        require(probe_get['panel'][field] == shell_get['panel'][field],
                f'{field} differs between probe and shell panels/get')
    listing = next(item for item in shell_items if item['panelId'] == panel_id)
    for field in ('position', 'title', 'matterId', 'sourceVersion'):
        if field in listing:
            require(listing[field] == probe_get['panel'][field],
                    f'panels/list {field} differs from panels/get')
    for capability in ('canOpen', 'canContinue', 'canUnpin'):
        require(isinstance(probe_get['capabilities'].get(capability), bool),
                f'panels/get has no boolean {capability}')
    require(probe_get.get('listRevision') == shell_revision,
            f"panels/get reports listRevision {probe_get.get('listRevision')} "
            f'for a directory at {shell_revision}')
    source_state = probe_get['sourceState']
    require(isinstance(source_state, dict), 'panels/get sourceState is not an object')
    state = source_state.get('state')
    require(state in ('current', 'offline', 'thread-unavailable', 'unavailable'),
            f'panels/get sourceState.state is {state!r}')
    expected_code = {'unavailable': 'SOURCE_UNAVAILABLE',
                     'thread-unavailable': 'SOURCE_THREAD_NOT_FOUND'}.get(state)
    require(source_state.get('code') == expected_code,
            f'panels/get sourceState {state!r} carries code {source_state.get("code")!r} '
            f'instead of {expected_code!r}')
    details['capabilities'] = probe_get['capabilities']
    details['list'] = {'items': len(shell_items), 'listRevision': shell_revision,
                       'shellPages': shell_pages, 'probePages': probe_pages}
    details['panel'] = {key: probe_get['panel'][key] for key in PANEL_FIELDS}
    details['sourceState'] = probe_get['sourceState']
    details['updateReceipt'] = probe_get['updateReceipt']
    matter_id = probe_get['panel']['matterId']
    read = record(details, client.probe('matter/read', {'matterId': matter_id}).response())
    snapshot = expect_ok(read, 'matter/read')['snapshot']
    require(snapshot['matterId'] == matter_id, 'matter/read returned a different source')
    require(isinstance(snapshot['pin']['canPin'], bool), 'matter/read has no boolean pin.canPin')
    require(snapshot['pin']['panelId'] == panel_id, 'matter/read disagrees with the pinned directory')
    details['matterContentMatchesSource'] = True
    details['pinCapability'] = snapshot['pin']
    details['versions'] = [{'listRevision': shell_revision, 'panelCount': len(shell_items)}]


def idempotent_retry(details, ctx):
    client, pin = ctx['client'], ctx['pin']
    items, revision, _ = list_all(client)
    ids = directory_ids(items)
    surface = pin['surface']

    def repeat_call(preferred):
        if preferred == 'shell':
            return client.shell('panels/pin', pin['params'])
        return client.probe('panels/pin', pin['params']).response()

    replay = record(details, repeat_call(surface))
    result = expect_ok(replay, 'pin replay on the original surface')
    require(result['panel']['panelId'] == pin['panelId'], 'pin replay returned a different panel')
    require(result.get('replayed') is True, 'pin replay did not set replayed')
    require(result['listRevision'] == revision, 'pin replay changed listRevision')
    other = 'probe' if surface == 'shell' else 'shell'
    cross = record(details, repeat_call(other))
    cross_result = expect_ok(cross, 'pin replay on the other surface')
    require(cross_result['panel']['panelId'] == pin['panelId'], 'cross-surface replay returned a different panel')
    require(cross_result.get('replayed') is True, 'cross-surface replay did not set replayed')
    require(cross_result['listRevision'] == revision, 'cross-surface replay changed listRevision')
    conflict_source = {'kind': 'matter', 'matterId': f'{uuid.uuid4()}/panels-device-conflict'}
    conflict = record(details, client.probe('panels/pin', {'source': conflict_source,
                                                           'requestId': pin['params']['requestId']}).response())
    code = expect_error(conflict, {'IDEMPOTENCY_CONFLICT'}, 'reused requestId with a different source')
    again_items, again_revision, _ = list_all(client)
    require(directory_ids(again_items) == ids and again_revision == revision,
            'a rejected idempotency conflict changed the directory')
    details['replays'] = {'originalSurface': surface, 'otherSurface': other, 'conflictCode': code}


def rejected_inputs(details, ctx):
    client, panel = ctx['client'], ctx['panel']
    items, revision, _ = list_all(client)
    ids = directory_ids(items)
    checks = []

    def check(label, response, allowed):
        record(details, response)
        code = expect_error(response, allowed, label)
        after_items, after_revision, _ = list_all(client)
        require(directory_ids(after_items) == ids and after_revision == revision,
                f'{label} changed the directory')
        checks.append({'check': label, 'code': code})

    check('unknown list parameter', client.probe('panels/list', {'limit': 5, 'bogus': True}).response(),
          {'INVALID_ARGUMENT'})
    check('list limit below range', client.probe('panels/list', {'limit': 0}).response(),
          {'INVALID_ARGUMENT'})
    check('list limit above range', client.probe('panels/list', {'limit': 101}).response(),
          {'INVALID_ARGUMENT'})
    check('pin without source', client.probe('panels/pin', {
        'requestId': new_request_id()}).response(), {'INVALID_ARGUMENT'})
    check('unknown source kind', client.probe('panels/pin', {
        'source': {'kind': 'view', 'matterId': panel['matterId']},
        'requestId': new_request_id()}).response(), {'INVALID_ARGUMENT'})
    check('unknown matter/read parameter', client.probe('matter/read', {
        'matterId': panel['matterId'], 'bogus': True}).response(), {'INVALID_ARGUMENT'})
    check('missing get panelId', client.probe('panels/get', {}).response(), {'INVALID_ARGUMENT'})
    check('unknown get panelId', client.probe('panels/get', {'panelId': str(uuid.uuid4())}).response(),
          {'PANEL_NOT_FOUND'})
    check('fabricated matter source', client.probe('panels/pin', {
        'source': {'kind': 'matter', 'matterId': f'{uuid.uuid4()}/panels-device-nonexistent'},
        'requestId': new_request_id()}).response(),
        {'MATTER_UNAVAILABLE'})
    malformed = {'kind': 'primary', 'threadId': str(uuid.uuid4()), 'turnId': 'turn-panels-device',
                 'replyId': 'panels-device-not-a-reply', 'revision': 0, 'contractHash': '0' * 64}
    check('malformed primary source', client.probe('panels/pin', {
        'source': malformed, 'requestId': new_request_id()}).response(), {'INVALID_ARGUMENT'})
    absent = dict(malformed, replyId='reply-' + uuid.uuid4().hex)
    check('primary source with no readable reply', client.probe('panels/pin', {
        'source': absent, 'requestId': new_request_id()}).response(), {'SOURCE_UNAVAILABLE'})
    check('non-UUID requestId', client.probe('panels/pin', {
        'source': {'kind': 'matter', 'matterId': panel['matterId']},
        'requestId': 'not-a-uuid'}).response(), {'INVALID_ARGUMENT'})
    check('unknown open panelId', client.shell('panels/open', {'panelId': str(uuid.uuid4())}),
          {'PANEL_NOT_FOUND'})
    check('unknown method', client.probe('panels/does-not-exist', {}).response(), {'UNKNOWN_METHOD'})
    check('rename with a 121 character title', client.probe('panels/rename', {
        'panelId': panel['panelId'], 'title': 'x' * 121,
        'expectedMetadataRevision': panel['metadataRevision'], 'requestId': new_request_id()}).response(),
        {'INVALID_ARGUMENT'})
    check('unpin without expectedListRevision', client.probe('panels/unpin', {
        'panelId': panel['panelId'], 'requestId': new_request_id()}).response(), {'INVALID_ARGUMENT'})
    check('continue without text', client.probe('panels/continue', {
        'panelId': panel['panelId'], 'expectedSourceVersion': panel['sourceVersion'],
        'requestId': new_request_id()}).response(), {'INVALID_ARGUMENT'})
    default_list = record(details, client.probe('panels/list', {}).response())
    default_result = expect_ok(default_list, 'panels/list with its default limit')
    require(isinstance(default_result.get('items'), list) and len(default_result['items']) <= 50,
            'panels/list default page exceeds the documented default of 50')
    details['checks'] = checks
    details['gaps'] = ['REPLY_INCOMPLETE, SOURCE_CHANGED and STALE_SOURCE need a real streaming, stale-contract '
                       'or superseded reply; this driver can only fabricate sources that do not exist']


def restart_durability(details, ctx):
    if not ctx['options'].restart:
        details['status'] = 'not_exercised'
        details['reason'] = '--no-restart skipped the process restart'
        return
    client, device = ctx['client'], ctx['device']
    before = get_panel(client, ctx['panel_id'])
    items, revision, _ = list_all(client)
    device.shell('am', 'force-stop', ASSISTANT)
    time.sleep(1)
    client.wait_ready(timeout=120)
    after = get_panel(client, ctx['panel_id'])
    for field in PANEL_FIELDS:
        require(after['panel'][field] == before['panel'][field],
                f'{field} changed across a process restart')
    after_items, after_revision, _ = list_all(client)
    require(after_revision == revision, f'listRevision changed across a restart: {revision} -> {after_revision}')
    require(directory_ids(after_items) == directory_ids(items), 'directory order changed across a restart')
    pin = ctx['pin']
    replay = record(details, (client.shell('panels/pin', pin['params']) if pin['surface'] == 'probe'
                              else client.probe('panels/pin', pin['params']).response()))
    result = expect_ok(replay, 'pin receipt replay after restart')
    require(result['panel']['panelId'] == pin['panelId'], 'receipt lost its panel across a restart')
    require(result.get('replayed') is True, 'pin receipt was not durable across a process restart')
    final_items, final_revision, _ = list_all(client)
    require(final_revision == revision, 'replaying a receipt after a restart mutated the directory')
    details['restart'] = {'panelId': pin['panelId'], 'listRevision': revision,
                          'replayedReceipt': True, 'panelCount': len(final_items)}


def rename_scenario(details, ctx):
    client, panel_id = ctx['client'], ctx['panel_id']
    base = get_panel(client, panel_id)['panel']
    items, revision, _ = list_all(client)
    title = f'  Panels probe {ctx["token"]}  '
    request_id = new_request_id()
    params = {'panelId': panel_id, 'title': title, 'expectedMetadataRevision': base['metadataRevision'],
              'requestId': request_id}
    response = record(details, client.shell('panels/rename', params))
    result = expect_ok(response, 'panels/rename')
    for key in ('panel', 'listRevision', 'replayed'):
        require(key in result, f'panels/rename result has no {key}')
    renamed = require_panel(result['panel'], 'renamed panel')
    require(renamed['title'] == title.strip(), f"title was not trimmed: {renamed['title']!r}")
    require(renamed['metadataRevision'] > base['metadataRevision'], 'metadataRevision did not advance')
    require(result['listRevision'] > revision, 'listRevision did not advance on rename')
    replay = record(details, client.probe('panels/rename', params).response())
    replay_result = expect_ok(replay, 'rename replay')
    require(replay_result.get('replayed') is True, 'rename replay did not set replayed')
    require(replay_result['panel']['title'] == renamed['title'], 'rename replay returned a different title')
    conflict = record(details, client.probe('panels/rename', {
        'panelId': panel_id, 'title': 'Panels probe stale',
        'expectedMetadataRevision': base['metadataRevision'], 'requestId': new_request_id()}).response())
    conflict_code = expect_error(conflict, {'VERSION_CONFLICT'}, 'stale metadata revision')
    current = get_panel(client, panel_id)['panel']
    require(current['title'] == renamed['title'], 'a stale rename overwrote the newer title')
    idem = record(details, client.probe('panels/rename', {
        'panelId': panel_id, 'title': 'Panels probe other',
        'expectedMetadataRevision': renamed['metadataRevision'], 'requestId': request_id}).response())
    expect_error(idem, {'IDEMPOTENCY_CONFLICT'}, 'reused rename requestId with a different title')
    boundary = 'P' * 120
    boundary_response = record(details, client.probe('panels/rename', {
        'panelId': panel_id, 'title': boundary,
        'expectedMetadataRevision': current['metadataRevision'], 'requestId': new_request_id()}).response())
    boundary_panel = expect_ok(boundary_response, 'rename to 120 characters')['panel']
    require(boundary_panel['title'] == boundary, 'a 120 character title was not accepted verbatim')
    for value, label in (('', 'empty'), ('   ', 'blank'), ('x' * 121, '121 characters'), (7, 'non-string')):
        expect_error(client.probe('panels/rename', {
            'panelId': panel_id, 'title': value,
            'expectedMetadataRevision': boundary_panel['metadataRevision'],
            'requestId': new_request_id()}).response(), {'INVALID_ARGUMENT'}, f'title {label}')
    restored = record(details, client.probe('panels/rename', {
        'panelId': panel_id, 'title': renamed['title'],
        'expectedMetadataRevision': boundary_panel['metadataRevision'],
        'requestId': new_request_id()}).response())
    final = expect_ok(restored, 'restore the recorded title')['panel']
    require(final['title'] == renamed['title'], 'final rename did not restore the recorded title')
    ctx['panel'] = final
    final_revision = list_all(client)[1]
    details['rename'] = {'conflictCode': conflict_code, 'title': final['title'],
                         'metadataRevision': final['metadataRevision'], 'listRevision': final_revision}


def move_scenario(details, ctx):
    client, options, panel_id = ctx['client'], ctx['options'], ctx['panel_id']
    items, revision, _ = list_all(client)
    ids = directory_ids(items)
    self_move = record(details, client.probe('panels/move', {
        'panelId': panel_id, 'beforePanelId': panel_id,
        'expectedListRevision': revision, 'requestId': new_request_id()}).response())
    self_result = expect_ok(self_move, 'move a panel before itself')
    for key in ('listRevision', 'replayed'):
        require(key in self_result, f'panels/move result has no {key}')
    require(self_result['listRevision'] == revision, 'moving a panel before itself changed listRevision')
    require(directory_ids(list_all(client)[0]) == ids, 'a self move changed the order')
    unknown = record(details, client.probe('panels/move', {
        'panelId': panel_id, 'beforePanelId': str(uuid.uuid4()),
        'expectedListRevision': revision, 'requestId': new_request_id()}).response())
    unknown_code = expect_error(unknown, {'PANEL_NOT_FOUND'}, 'move before an unknown panel')
    require(directory_ids(list_all(client)[0]) == ids, 'a rejected move changed the order')
    details['selfMove'] = {'listRevision': revision}
    details['unknownTargetCode'] = unknown_code

    # A freshly pinned panel is already last, so moving to the end must report no change.
    to_end = record(details, client.probe('panels/move', {
        'panelId': panel_id, 'beforePanelId': None,
        'expectedListRevision': revision, 'requestId': new_request_id()}).response())
    to_end_result = expect_ok(to_end, 'move an already last panel to the end')
    require(to_end_result['listRevision'] == revision, 'a no-op move advanced listRevision')
    stale_revision = revision - 1 if revision > 0 else None
    if stale_revision is not None:
        stale = record(details, client.probe('panels/move', {
            'panelId': panel_id, 'beforePanelId': None,
            'expectedListRevision': stale_revision, 'requestId': new_request_id()}).response())
        details['staleMoveCode'] = expect_error(stale, {'VERSION_CONFLICT'},
                                                'move with a stale listRevision')
        require(directory_ids(list_all(client)[0]) == ids, 'a stale move changed the order')

    second = options.second_source
    if not second:
        paginate(details, ctx)
        details['gaps'] = ['reordering against another panel (no --second-source)',
                           'stale cursor rejection (needs a reordering mutation)']
        details['note'] = 'a single freshly pinned panel is already last, so move-to-end is a no-op by contract'
        final_items, final_revision, _ = list_all(client)
        details['move'] = {'order': directory_ids(final_items), 'listRevision': final_revision,
                           'staleMoveCode': details.get('staleMoveCode')}
        return

    second_pin = record(details, client.probe('panels/pin', {
        'source': second, 'requestId': new_request_id()}).response())
    second_result = expect_ok(second_pin, 'pin the second real source')
    second_panel = require_panel(second_result['panel'], 'second pinned panel')
    require(second_panel['panelId'] != panel_id, 'the second source resolved to the first panel')
    require(second_result.get('created') is True, 'the second source was not newly pinned')
    ctx['created'].append(second_panel['panelId'])
    order = directory_ids(list_all(client)[0])
    require(order.index(panel_id) < order.index(second_panel['panelId']),
            'pinned panels are not appended in pin order')

    current_revision = list_all(client)[1]
    to_end = record(details, client.probe('panels/move', {
        'panelId': panel_id, 'beforePanelId': None,
        'expectedListRevision': current_revision, 'requestId': new_request_id()}).response())
    to_end_result = expect_ok(to_end, 'move the first panel to the end')
    order_after_end = directory_ids(list_all(client)[0])
    require(order_after_end[-1] == panel_id, 'moving to the end did not place the panel last')
    require(to_end_result['listRevision'] > current_revision, 'a reordering move did not advance listRevision')

    stale = record(details, client.probe('panels/move', {
        'panelId': panel_id, 'beforePanelId': None,
        'expectedListRevision': current_revision, 'requestId': new_request_id()}).response())
    stale_code = expect_error(stale, {'VERSION_CONFLICT'}, 'move with a stale listRevision')
    require(directory_ids(list_all(client)[0]) == order_after_end, 'a stale move changed the order')

    before_second = record(details, client.probe('panels/move', {
        'panelId': panel_id, 'beforePanelId': second_panel['panelId'],
        'expectedListRevision': to_end_result['listRevision'], 'requestId': new_request_id()}).response())
    expect_ok(before_second, 'move before another panel')
    order_after_before = directory_ids(list_all(client)[0])
    require(order_after_before.index(panel_id) + 1 == order_after_before.index(second_panel['panelId']),
            'move before another panel did not place it immediately before')

    first_page = record(details, client.shell('panels/list', {'limit': 1}))
    first_page_result = expect_ok(first_page, 'first page for the stale cursor check')
    cursor = first_page_result.get('nextCursor')
    if cursor:
        invalidate = record(details, client.probe('panels/move', {
            'panelId': second_panel['panelId'], 'beforePanelId': panel_id,
            'expectedListRevision': list_all(client)[1], 'requestId': new_request_id()}).response())
        invalidated = expect_ok(invalidate, 'reorder the directory after the cursor was issued')
        require(invalidated['listRevision'] > first_page_result['listRevision'],
                'the reorder that was meant to invalidate the cursor did not advance listRevision')
        stale_cursor = record(details, client.shell('panels/list', {'limit': 1, 'cursor': cursor}))
        details['staleCursor'] = {
            'code': expect_error(stale_cursor, {'LIST_CHANGED'},
                                 'cursor bound to the previous listRevision'),
            'issuedAt': first_page_result['listRevision'],
            'current': invalidated['listRevision']}
    else:
        details['staleCursor'] = 'single page; no cursor to invalidate'

    paginate(details, ctx)
    final_revision = list_all(client)[1]
    unpin = record(details, client.probe('panels/unpin', {
        'panelId': second_panel['panelId'], 'expectedListRevision': final_revision,
        'requestId': new_request_id()}).response())
    expect_ok(unpin, 'unpin the temporary second panel')
    require(second_panel['panelId'] not in directory_ids(list_all(client)[0]),
            'the temporary second panel is still listed')
    ctx['created'].remove(second_panel['panelId'])
    final_items, final_revision, _ = list_all(client)
    details['move'] = {'order': directory_ids(final_items), 'listRevision': final_revision,
                       'staleMoveCode': stale_code}


def paginate(details, ctx):
    client = ctx['client']
    full_items, full_revision, full_pages = list_all(client, limit=100)
    full_ids = directory_ids(full_items)
    walks = {}
    for limit in (1, 2, 7):
        walk_items, walk_revision, walk_pages = list_all(client, limit=limit)
        walk_ids = directory_ids(walk_items)
        require(walk_revision == full_revision,
                f'limit={limit} read revision {walk_revision} instead of {full_revision}')
        require(walk_ids == full_ids,
                f'limit={limit} did not cover the complete directory in order')
        require(len(set(walk_ids)) == len(walk_ids), f'limit={limit} repeated a panel')
        walks[str(limit)] = {'pages': walk_pages, 'items': len(walk_ids)}
    details['pagination'] = {'items': len(full_ids), 'pagesAtLimit100': full_pages,
                             'walks': walks, 'over64Exercised': len(full_ids) > 64}
    if len(full_ids) <= 64:
        details['over64Reason'] = 'the live directory has at most 64 panels; create more real pins to cover the old cap'
    return full_ids, full_revision


def navigation_scenario(details, ctx):
    client, panel_id = ctx['client'], ctx['panel_id']
    before = record(details, client.probe('panels/navigation', {}).response())
    before_result = expect_ok(before, 'panels/navigation before open')
    for key in ('available', 'visible', 'selectedPanelId'):
        require(key in before_result, f'panels/navigation has no {key}')
    for key in ('available', 'visible'):
        require(isinstance(before_result[key], list) and all(isinstance(value, str) for value in before_result[key]),
                f'panels/navigation {key} is not a panel ID list')
    opened = record(details, client.shell('panels/open', {'panelId': panel_id}))
    open_result = expect_ok(opened, 'panels/open')
    require(open_result.get('accepted') is True, 'panels/open did not report accepted')
    require(open_result.get('panelId') == panel_id, 'panels/open reported a different panelId')
    after = record(details, client.probe('panels/navigation', {}).response())
    after_result = expect_ok(after, 'panels/navigation after open')
    if panel_id in after_result.get('visible', []) and after_result.get('selectedPanelId') == panel_id:
        require(panel_id in after_result.get('available', []),
                'navigation selected a panel absent from the desktop directory')
        observed = 'selected'
    else:
        observed = 'accepted-only'
        details['gaps'] = ['panels/open was accepted but the desktop projection reported no visible or '
                           'selected page, so GUI selection and page order stay parent-driven']
    unknown = record(details, client.probe('panels/open', {'panelId': str(uuid.uuid4())}).response())
    expect_error(unknown, {'PANEL_NOT_FOUND'}, 'open an unknown panel')
    final = record(details, client.probe('panels/navigation', {}).response())
    expect_ok(final, 'panels/navigation after a rejected open')
    require(final.result.get('selectedPanelId') == after_result.get('selectedPanelId'),
            'a rejected open changed the navigation selection')
    listing, revision, _ = list_all(client)
    details['navigation'] = {'before': before_result, 'after': after_result, 'observed': observed,
                             'listRevision': revision, 'panelIds': directory_ids(listing)}


def read_card(client, details, matter_id):
    response = record(details, client.probe('card-view/read', {'matterId': matter_id}).response())
    result = expect_ok(response, 'card-view/read')
    require('uiRevision' in result, 'card-view/read result has no uiRevision')
    require(isinstance(result['uiRevision'], int) and not isinstance(result['uiRevision'], bool)
            and result['uiRevision'] >= 0,
            'card-view/read uiRevision is not a non-negative integer')
    state = result['ui']
    require(isinstance(state, dict) and isinstance(state.get('fields'), dict)
            and isinstance(state.get('collections'), dict),
            'card-view/read ui has no fields and collections objects')
    return {'uiRevision': result['uiRevision'], 'state': state}


def read_declared_blocks(client, details, matter_id):
    response = record(details, client.probe('matter/read', {'matterId': matter_id}).response())
    result = expect_ok(response, 'matter/read for the card source')
    require(isinstance(result, dict) and isinstance(result.get('snapshot'), dict),
            'matter/read returned no snapshot for the card source')
    snapshot = result['snapshot']
    require(snapshot.get('matterId') == matter_id, 'matter/read returned a different card source')
    request = snapshot.get('request')
    require(isinstance(request, dict) and isinstance(request.get('blocks'), list),
            'the card source declares no request blocks')
    blocks = {}
    for block in request['blocks']:
        require(isinstance(block, dict) and isinstance(block.get('id'), str),
                'the card source declares a block without an ID')
        blocks[block['id']] = block
    return blocks


def card_value(state, kind, identifier):
    if kind == 'selection':
        collection = state['collections'].get(identifier)
        return None if collection is None else list(collection['selectedItemIds'])
    field = state['fields'].get(identifier)
    return None if field is None else field['draft']


def card_values(state):
    return {'fields': {identifier: field['draft'] for identifier, field in state['fields'].items()},
            'collections': {identifier: list(collection['selectedItemIds'])
                            for identifier, collection in state['collections'].items()}}


def declared_kind(block):
    if block.get('kind') == 'choices':
        return 'selection'
    if block.get('kind') != 'input':
        return None
    return (block.get('value') or {}).get('type')


def legal_number(block, value):
    config = block.get('config') or {}
    low, high, step = config.get('min'), config.get('max'), config.get('step') or 0
    if low is not None and value < low:
        return False
    if high is not None and value > high:
        return False
    if step > 0:
        base = low if low is not None else 0
        offset = (value - base) / step
        if abs(offset - round(offset)) > 1e-7:
            return False
    return True


def number_primes(block):
    config = block.get('config') or {}
    low, high, step = config.get('min'), config.get('max'), config.get('step') or 0
    base = low if low is not None else 0
    options = [base, base + step, base + 2 * step, 1] if step > 0 else [1, base + 1, base + 10]
    if high is not None:
        options.append(high)
    primes = []
    for option in options:
        value = round(option, 6)
        if value != 0 and value not in primes and legal_number(block, value):
            primes.append(value)
    return primes


def card_legal_value(kind, block, value):
    if kind == 'selection':
        offered = {item['id'] for item in block.get('items') or []}
        return all(item in offered for item in value)
    if value is None:
        return True
    if kind == 'boolean':
        return isinstance(value, bool)
    if kind == 'text':
        return isinstance(value, str) and len(value) <= 4000
    return not isinstance(value, bool) and isinstance(value, (int, float)) and legal_number(block, value)


def card_target(kind, block):
    if kind == 'number':
        return 0 if legal_number(block, 0) else None
    return {'boolean': False, 'text': '', 'selection': []}[kind]


def card_prime(kind, block, current):
    if kind == 'boolean':
        return True
    if kind == 'text':
        return 'panels-device prime'
    if kind == 'selection':
        items = [item['id'] for item in block.get('items') or []]
        return [items[0]] if items else None
    primes = [value for value in number_primes(block) if value != current] or number_primes(block)
    return primes[0] if primes else None


def apply_card(client, details, matter_id, revision, change):
    return record(details, client.probe('card-view/apply', {
        'matterId': matter_id, 'expectedUiRevision': revision,
        'requestId': new_request_id(), 'changes': [change]}).response())


def card_transition(client, details, matter_id, kind, identifier, value):
    before = read_card(client, details, matter_id)
    change = ({'kind': 'selection', 'id': identifier, 'itemIds': list(value)} if kind == 'selection'
              else {'kind': 'field', 'id': identifier, 'value': value})
    response = apply_card(client, details, matter_id, before['uiRevision'], change)
    require(response.ok, f'card-view/apply rejected the declared {kind} value {value!r} '
                         f'for {identifier}: {response.code}')
    after = read_card(client, details, matter_id)
    require(after['uiRevision'] > before['uiRevision'],
            f'card-view/apply did not advance uiRevision for {identifier}')
    observed = card_value(after['state'], kind, identifier)
    require(observed == value,
            f'card-view/apply stored {observed!r} for {identifier} instead of {value!r}')
    return after


def card_view_scenario(details, ctx):
    if ctx['options'].skip_card_view:
        details['status'] = 'not_exercised'
        details['reason'] = '--skip-card-view requested'
        return
    client = ctx['client']
    matter_id = ctx['options'].card_view_matter_id or ctx['panel']['matterId']
    if '/' not in str(matter_id):
        details['status'] = 'not_exercised'
        details['reason'] = f'the pinned panel has no matter identifier: {matter_id!r}'
        return
    details['matterId'] = matter_id
    blocks = read_declared_blocks(client, details, matter_id)
    initial = read_card(client, details, matter_id)
    for scope in ('fields', 'collections'):
        for identifier in initial['state'][scope]:
            require(identifier in blocks,
                    f'card-view/read reports {scope} entry {identifier} that matter/read does not declare')
    candidates = {kind: [] for kind in ('boolean', 'number', 'text', 'selection')}
    declared = {}
    for identifier, block in blocks.items():
        kind = declared_kind(block)
        declared[kind or block.get('kind')] = declared.get(kind or block.get('kind'), 0) + 1
        if kind in candidates:
            entries = initial['state']['collections' if kind == 'selection' else 'fields']
            require(identifier in entries,
                    f'card-view/read has no {kind} entry for the declared block {identifier}')
        if kind in ('boolean', 'number', 'text'):
            source = initial['state']['fields'][identifier].get('source')
            require(isinstance(source, dict) and source.get('type') == block['value']['type'],
                    f'card-view/read projects {identifier} as {source!r}, '
                    f"matter/read declares {block['value']['type']!r}")
        if kind == 'selection':
            collection = initial['state']['collections'][identifier]
            offered = {item['id'] for item in block.get('items') or []}
            stale = [item for item in collection['selectedItemIds'] if item not in offered]
            require(not stale or collection.get('needsReview') is True,
                    f'card-view/read keeps {stale} selected for {identifier} without flagging review')
        if kind in candidates:
            current = card_value(initial['state'], kind, identifier)
            candidates[kind].append({'id': identifier, 'block': block, 'current': current,
                                     'restorable': card_legal_value(kind, block, current)})
    details['declaredBlocks'] = declared
    details['candidates'] = {kind: len(values) for kind, values in candidates.items()}

    checks, gaps = [], []
    for kind, label in (('boolean', 'false'), ('number', '0'), ('text', 'empty text'),
                        ('selection', 'an empty selection')):
        if not candidates[kind]:
            gaps.append(f'the card source declares no {kind} block, so {label} was not exercised')
            continue
        skipped = [candidate['id'] for candidate in candidates[kind] if not candidate['restorable']]
        if skipped:
            gaps.append(f'{", ".join(skipped)} holds a value its declared schema rejects, so it can be '
                        'neither written nor restored and was left alone')
        usable = [candidate for candidate in candidates[kind]
                  if candidate['restorable'] and card_target(kind, candidate['block']) is not None]
        if not usable:
            if not skipped:
                gaps.append(f'no declared {kind} block accepts {label} as a legal value, '
                            'so it was not exercised')
            continue
        for candidate in usable:
            target = card_target(kind, candidate['block'])
            prime = None
            if candidate['current'] == target:
                prime = card_prime(kind, candidate['block'], candidate['current'])
                if prime is None:
                    continue
            applied = []
            if prime is not None:
                card_transition(client, details, matter_id, kind, candidate['id'], prime)
                applied.append(prime)
            card_transition(client, details, matter_id, kind, candidate['id'], target)
            applied.append(target)
            if candidate['current'] != target:
                card_transition(client, details, matter_id, kind, candidate['id'], candidate['current'])
            checks.append({'kind': kind, 'id': candidate['id'], 'original': candidate['current'],
                           'applied': applied, 'realTransition': candidate['current'] != target})
            break
        else:
            gaps.append(f'no declared {kind} block can reach {label} from a legal value of its own type')
    details['exercised'] = {check['kind']: True for check in checks}
    details['checks'] = checks

    current = read_card(client, details, matter_id)

    def rejected(label, params, code='INVALID_ARGUMENT'):
        nonlocal current
        response = record(details, client.probe('card-view/apply', params).response())
        observed = expect_error(response, {code}, label)
        after = read_card(client, details, matter_id)
        require(after['uiRevision'] == current['uiRevision'],
                f'a rejected card-view write changed uiRevision ({label})')
        current = after
        return observed

    def apply_params(change, revision):
        return {'matterId': matter_id, 'expectedUiRevision': revision,
                'requestId': new_request_id(), 'changes': [change]}

    details['unknownFieldCode'] = rejected(
        'card-view unknown field',
        apply_params({'kind': 'field', 'id': 'taku-panels-device-unknown-field', 'value': False},
                     current['uiRevision']))
    if candidates['boolean']:
        details['wrongTypeCode'] = rejected(
            'card-view boolean field written as a string',
            apply_params({'kind': 'field', 'id': candidates['boolean'][0]['id'], 'value': 'false'},
                         current['uiRevision']))
    if candidates['selection']:
        details['unknownItemCode'] = rejected(
            'card-view selection of an item the source does not declare',
            apply_params({'kind': 'selection', 'id': candidates['selection'][0]['id'],
                          'itemIds': ['taku-panels-device-missing-item']}, current['uiRevision']))
    details['emptyChangesCode'] = rejected(
        'card-view apply without changes',
        {'matterId': matter_id, 'expectedUiRevision': current['uiRevision'],
         'requestId': new_request_id(), 'changes': []})
    details['unknownParameterCode'] = rejected(
        'card-view unknown parameter',
        {'matterId': matter_id, 'expectedUiRevision': current['uiRevision'],
         'requestId': new_request_id(), 'changes': [], 'bogus': True})
    writable = checks[0] if checks else None
    fresh = read_card(client, details, matter_id)
    stale_revision = fresh['uiRevision'] - 1
    if writable and stale_revision >= 0:
        change = ({'kind': 'selection', 'id': writable['id'], 'itemIds': list(writable['original'])}
                  if writable['kind'] == 'selection' else
                  {'kind': 'field', 'id': writable['id'], 'value': writable['original']})
        stale = apply_card(client, details, matter_id, stale_revision, change)
        details['staleUiRevisionCode'] = expect_error(
            stale, {'VERSION_CONFLICT'}, 'card-view apply with a stale uiRevision')
        after_stale = read_card(client, details, matter_id)
        require(after_stale['uiRevision'] == fresh['uiRevision'],
                'a rejected stale card-view write advanced uiRevision')
    elif writable:
        details['staleUiRevision'] = 'uiRevision is 0; no stale revision can be formed'
    else:
        details['staleUiRevision'] = 'no writable field was discovered'
    final = read_card(client, details, matter_id)
    require(card_values(final['state']) == card_values(initial['state']),
            'card-view changes were not restored to the values the panel started with')
    details['restored'] = True
    details['editedNote'] = ('card-view/apply marks every written field edited and the public interface '
                             'cannot clear that flag, so only drafts and selections come back')
    details['gaps'] = gaps
    if not checks:
        details['status'] = 'not_exercised'
        details['reason'] = 'no declared card field could be written: ' + '; '.join(gaps)


def large_result_scenario(details, ctx):
    options, client = ctx['options'], ctx['client']
    if not options.large_matter_id:
        details['status'] = 'not_exercised'
        details['reason'] = 'no --large-matter-id was supplied; the caller creates the real large source'
        return
    run = client.probe('matter/read', {'matterId': options.large_matter_id})
    response = record(details, run.response())
    result = expect_ok(response, 'matter/read on the large source')
    outer_bytes = response.outer_bytes
    details['largeResult'] = {'inlineBytes': None if response.followed_result_uri else outer_bytes,
                              'payloadBytes': response.payload_bytes,
                              'payloadSha256': response.payload_sha256,
                              'probeEvidence': str(run.evidence_path.name)}
    if not response.followed_result_uri:
        if outer_bytes > INLINE_RESULT_LIMIT:
            raise Failed(f'a {outer_bytes} byte result was returned inline instead of through resultUri')
        details['status'] = 'not_exercised'
        details['reason'] = (f'matter/read returned {outer_bytes} inline bytes, below the 256 KiB '
                            'resultUri threshold; supply a larger real matter')
        return
    require(response.payload_bytes > INLINE_RESULT_LIMIT,
            f'resultUri payload is only {response.payload_bytes} bytes')
    require(isinstance(result, dict), 'the resultUri payload is not a JSON object')
    call = run.calls[-1]
    second = call.get('secondRead')
    require(isinstance(second, dict), 'the probe recorded no second read for the resultUri')
    require(second.get('readSucceeded') is False, 'the consumed resultUri was readable twice')
    require('RESULT_EXPIRED' in str(second.get('message', '')),
            f"a second read failed with {second.get('errorClass')}: {second.get('message')} instead of RESULT_EXPIRED")
    details['largeResult']['secondRead'] = second
    details['largeResult']['verifiedBytes'] = response.payload_bytes
    if options.oversize_matter_id:
        oversize = record(details, client.probe('matter/read', {'matterId': options.oversize_matter_id}).response())
        code = expect_error(oversize, {'RESULT_TOO_LARGE'}, 'matter/read above the 8 MiB result cap')
        details['largeResult']['oversizeCode'] = code
    else:
        details['largeResult']['oversize'] = 'no --oversize-matter-id; RESULT_TOO_LARGE was not exercised'


def unpin_scenario(details, ctx):
    client, panel_id = ctx['client'], ctx['panel_id']
    panel = get_panel(client, panel_id)['panel']
    items, revision, _ = list_all(client)
    if revision > 0:
        stale = record(details, client.probe('panels/unpin', {
            'panelId': panel_id, 'expectedListRevision': revision - 1,
            'requestId': new_request_id()}).response())
        details['staleUnpinCode'] = expect_error(stale, {'VERSION_CONFLICT'},
                                                 'unpin with a stale listRevision')
        current_items, current_revision, _ = list_all(client)
        require(panel_id in directory_ids(current_items) and current_revision == revision,
                'a stale unpin removed the panel or advanced listRevision')
    request_id = new_request_id()
    params = {'panelId': panel_id, 'expectedListRevision': revision, 'requestId': request_id}
    removed = record(details, client.probe('panels/unpin', params).response())
    result = expect_ok(removed, 'panels/unpin')
    for key in ('removed', 'listRevision', 'replayed'):
        require(key in result, f'panels/unpin result has no {key}')
    require(result['removed'] is True, 'panels/unpin did not report removed')
    require(result['listRevision'] > revision, 'panels/unpin did not advance listRevision')
    after_items, after_revision, _ = list_all(client)
    require(panel_id not in directory_ids(after_items), 'an unpinned panel is still listed')
    listed_get = record(details, client.probe('panels/get', {'panelId': panel_id}).response())
    get_code = expect_error(listed_get, {'PANEL_NOT_FOUND'}, 'panels/get after unpin')
    if ctx['options'].source.get('kind') == 'matter':
        source_read = record(details, client.probe('matter/read', {
            'matterId': panel['matterId']}).response())
        details['sourceAfterUnpin'] = {'ok': source_read.ok, 'code': source_read.code or None}
        if not source_read.ok:
            require(source_read.code in {'MATTER_UNAVAILABLE', 'SOURCE_UNAVAILABLE'},
                    f'the matter behind an unpinned panel failed with {source_read.code}')
            details.setdefault('gaps', []).append(
                f"native retention reclaimed {panel['matterId']} after the unpin ({source_read.code}); "
                'unpin itself deletes no conversation content')
    replay = record(details, client.probe('panels/unpin', params).response())
    replay_result = expect_ok(replay, 'unpin replay')
    require(replay_result.get('replayed') is True, 'unpin replay did not set replayed')
    require(panel_id not in directory_ids(list_all(client)[0]), 'replaying unpin resurrected the panel')
    original_pin = record(details, (client.shell('panels/pin', ctx['pin']['params'])
                                    if ctx['pin']['surface'] == 'probe'
                                    else client.probe('panels/pin', ctx['pin']['params']).response()))
    original_result = expect_ok(original_pin, 'replay of the original pin receipt')
    require(original_result['panel']['panelId'] == panel_id,
            'replaying the original pin receipt returned a different panel')
    require(original_result.get('replayed') is True, 'original pin replay did not set replayed')
    require(panel_id not in directory_ids(list_all(client)[0]),
            'replaying the original pin request re-pinned the removed panel')
    repin_request = new_request_id()
    repin = record(details, client.shell('panels/pin', {'source': ctx['options'].source,
                                                        'requestId': repin_request}))
    repin_result = expect_ok(repin, 're-pin the same source after unpin')
    repinned = require_panel(repin_result['panel'], 're-pinned panel')
    require(repinned['panelId'] != panel_id, 're-pinning reused the removed panelId')
    require(repin_result.get('created') is True, 're-pin did not report created')
    require(repinned['matterId'] == panel['matterId'], 're-pin resolved a different matter')
    ctx['panel'] = repinned
    ctx['panel_id'] = repinned['panelId']
    ctx['created'] = [repinned['panelId']]
    current_items, current_revision, _ = list_all(client)
    require(directory_ids(current_items)[-1] == repinned['panelId'], 're-pin was not appended last')
    details['unpin'] = {'removedPanelId': panel_id, 'removedGetCode': get_code,
                        'rePinnedPanelId': repinned['panelId'],
                        'listRevision': current_revision}
    details['versions'] = [{'listRevision': current_revision, 'panelCount': len(current_items)}]


def cleanup(details, ctx):
    client = ctx['client']
    created = [panel_id for panel_id in dict.fromkeys(ctx.get('created', []))]
    details['ownedAtCleanup'] = list(created)
    if not created:
        details['status'] = 'not_exercised'
        details['reason'] = 'no panel from this run remains to remove'
        return
    failures = []
    for panel_id in created:
        items, revision, _ = list_all(client)
        if panel_id not in directory_ids(items):
            continue
        response = client.probe('panels/unpin', {'panelId': panel_id,
                                                 'expectedListRevision': revision,
                                                 'requestId': new_request_id()}).response()
        details.setdefault('requests', []).append(response.describe())
        if not response.ok:
            failures.append({'panelId': panel_id, 'code': response.code})
    final_items, final_revision, _ = list_all(client)
    details['final'] = {'listRevision': final_revision, 'panelIds': directory_ids(final_items)}
    if 'baseline_ids' in ctx:
        details['directoryRestored'] = set(directory_ids(final_items)) == set(ctx['baseline_ids'])
        details['baselineIds'] = ctx['baseline_ids']
        if not details['directoryRestored']:
            failures.append({'directoryRestored': False, 'panelIds': directory_ids(final_items)})
    require(not failures, f'cleanup could not restore the directory: {failures}')


def probe_installation(device):
    listing = device.shell('pm', 'list', 'packages', '-U', PROBE_PACKAGE, check=False).strip()
    require(PROBE_PACKAGE in listing, f'{PROBE_PACKAGE} is not installed; build and install the probe first')
    dump = device.shell('dumpsys', 'package', PROBE_PACKAGE, check=False)
    uid = re.search(r'\buid:(\d+)', listing)
    require(uid is not None, 'the installed probe has no UID')
    uid_value = int(uid.group(1))
    require(uid_value >= 10000, f'probe UID {uid_value} is not an ordinary application UID')
    require('requested permissions:' not in dump, 'the installed probe requests permissions')
    flags = re.findall(r'flags=\[([^\]]*)\]', dump)
    require(flags, 'the installed probe reports no package flags')
    require(all('SYSTEM' not in value.split() for value in flags), 'the probe is installed as a system app')
    run_as = device.shell('run-as', PROBE_PACKAGE, '--user', '0', 'id', '-u', check=False).strip()
    require(run_as.isdigit() and int(run_as) == uid_value,
            'the probe is not a debuggable ordinary package that the host can read')
    return {'package': PROBE_PACKAGE, 'uid': uid_value, 'requestedPermissions': []}


def installed_probe_artifact(device):
    remote = device.shell('pm', 'path', PROBE_PACKAGE).strip()
    require(remote.startswith('package:'), f'cannot resolve the installed probe APK: {remote!r}')
    data = device.exec_out('cat', remote.split(':', 1)[1])
    facts = installed_apk_facts()
    digest = sha256_bytes(data)
    result = {'installedBytes': len(data), 'installedSha256': digest, 'installedPath': remote.split(':', 1)[1]}
    if facts:
        result['buildSha256'] = facts.get('apkSha256')
        result['matchesBuild'] = facts.get('apkSha256') == digest
    return result


def installed_apk_facts():
    signing = PROBE_OUTPUT / 'signing.json'
    if not signing.is_file():
        return None
    try:
        return json.loads(signing.read_text())
    except json.JSONDecodeError:
        return None


def parse_source(text, flag):
    try:
        source = json.loads(text)
    except json.JSONDecodeError as error:
        raise Failed(f'{flag} is not JSON: {error}')
    require(isinstance(source, dict), f'{flag} must be a JSON object')
    kind = source.get('kind')
    if kind == 'matter':
        require(set(source) == {'kind', 'matterId'}, f'{flag} for kind=matter takes kind and matterId only')
        require(isinstance(source['matterId'], str) and MATTER_PATTERN.match(source['matterId']),
                f'{flag} matterId must be threadId/key with a lowercase UUID thread')
    elif kind == 'primary':
        keys = {'kind', 'threadId', 'turnId', 'replyId', 'revision', 'contractHash'}
        require(set(source) == keys, f'{flag} for kind=primary takes exactly {sorted(keys)}')
        require(UUID_PATTERN.match(str(source['threadId'])) is not None, f'{flag} threadId must be a lowercase UUID')
        require(isinstance(source['turnId'], str) and 0 < len(source['turnId']) <= 160,
                f'{flag} turnId must be 1 to 160 characters')
        require(re.fullmatch(r'reply-[a-f0-9]{32}', str(source['replyId'])) is not None,
                f'{flag} replyId must be reply- followed by 32 lowercase hex characters')
        require(isinstance(source['revision'], int) and not isinstance(source['revision'], bool)
                and source['revision'] >= 0, f'{flag} revision must be a non-negative integer')
        require(re.fullmatch(r'[0-9a-f]{64}', str(source['contractHash'])) is not None,
                f'{flag} contractHash must be 64 lowercase hex characters')
    else:
        raise Failed(f'{flag} kind must be matter or primary')
    return source


def new_run_directory(path=None):
    run_dir = Path(path) if path else ROOT / 'out/panels-device' / f"{time.strftime('%Y%m%dT%H%M%S')}-{uuid.uuid4().hex[:6]}"
    run_dir.mkdir(parents=True, exist_ok=True)
    return run_dir


def open_device(args, require_probe=True):
    device = Device(args.serial, args.adb, args.avd).verify()
    if require_probe:
        probe_installation(device)
    return device


def prepare(args, require_probe=True):
    run_dir = new_run_directory(getattr(args, 'output', None))
    device = open_device(args, require_probe)
    metadata = {'serial': args.serial, 'avd': device.avd_name,
                'fingerprint': device.shell('getprop', 'ro.build.fingerprint').strip(),
                'buildId': device.shell('getprop', 'ro.build.display.id').strip(),
                'sdk': device.shell('getprop', 'ro.build.version.sdk').strip(),
                'assistant': device.package_version(ASSISTANT),
                'agentApp': device.package_version(AGENT_APP),
                'probePackage': PROBE_PACKAGE,
                'probeApk': installed_apk_facts(),
                'script': str(Path(__file__).resolve()),
                'serialSelected': args.serial}
    metadata['assistantVersion'] = metadata['assistant']
    recorder = Recorder(run_dir, metadata)
    token = time.strftime('%H%M%S')
    client = Client(device, run_dir, token)
    return run_dir, device, recorder, client


def run_accept(args):
    options = argparse.Namespace(
        source=parse_source(args.source, '--source'),
        second_source=parse_source(args.second_source, '--second-source') if args.second_source else None,
        large_matter_id=args.large_matter_id,
        oversize_matter_id=args.oversize_matter_id,
        card_view_matter_id=args.card_view_matter_id,
        race_repeat=args.race_repeat,
        race_interval_ms=args.race_interval_ms,
        race_delay_ms=args.race_delay_ms,
        restart=not args.no_restart,
        skip_card_view=args.skip_card_view)
    run_dir, device, recorder, client = prepare(args)
    ctx = {'device': device, 'client': client, 'options': options, 'created': [],
           'token': client.token}
    steps = [('00-preflight', preflight), ('01-describe', describe_scenario),
             ('02-race-pin', race_pin), ('03-list-get-parity', list_get_parity),
             ('04-idempotent-retry', idempotent_retry), ('05-rejected-inputs', rejected_inputs),
             ('06-restart-durability', restart_durability), ('07-rename', rename_scenario),
             ('08-move-and-pagination', move_scenario), ('09-navigation', navigation_scenario),
             ('10-card-view', card_view_scenario), ('11-large-result', large_result_scenario),
             ('12-unpin-replay-repin', unpin_scenario)]
    executed, failed = [], False
    try:
        for name, function in steps:
            status = run_scenario(recorder, name, function, ctx)
            executed.append(name)
            if status == 'failed':
                failed = True
                break
        if not failed:
            run_scenario(recorder, '13-cleanup', cleanup, ctx)
    finally:
        if not any(entry['scenario'] == '13-cleanup' for entry in recorder.entries):
            executed.append('13-cleanup')
            run_scenario(recorder, '13-cleanup', cleanup, ctx)
        for name, _ in steps:
            if name not in executed:
                recorder.save(name, 'not_run', {}, timestamp(), timestamp(),
                              'an earlier scenario failed; this one was not reached')
        not_exercised = [entry['scenario'] for entry in recorder.entries if entry['status'] == 'not_exercised']
        gaps = [entry['scenario'] for entry in recorder.entries if entry.get('gaps')]
        recorder.write_summary({'mode': 'accept', 'failed': failed, 'probeUid': ctx.get('probe_uid'),
                                'strict': args.strict,
                                'strictGaps': sorted(set(not_exercised + gaps)) if args.strict else [],
                                'notExercised': not_exercised, 'scenariosWithGaps': gaps})
    statuses = {entry['status'] for entry in recorder.entries}
    print(f'Evidence: {run_dir}', flush=True)
    if 'failed' in statuses:
        raise SystemExit(1)
    if args.strict and any(entry['status'] == 'not_exercised' or entry.get('gaps')
                           for entry in recorder.entries):
        print('Strict acceptance: not every required scenario was exercised.', file=sys.stderr, flush=True)
        raise SystemExit(1)
    print('Provider-verifiable scenarios passed; GUI, cross-turn and task outcomes remain parent-driven.', flush=True)


def run_single(args, function, require_probe=True):
    run_dir, device, recorder, client = prepare(args, require_probe)
    ctx = {'device': device, 'client': client, 'options': args, 'created': [], 'token': client.token}
    status = run_scenario(recorder, args.mode, lambda details, context: function(args, details, context), ctx)
    recorder.write_summary({'mode': args.mode})
    print(f'Evidence: {run_dir}', flush=True)
    if status == 'failed':
        raise SystemExit(1)


def preflight_only(args, details, ctx):
    items, revision, pages = list_all(ctx['client'])
    details['directory'] = {'panelIds': directory_ids(items), 'listRevision': revision, 'pages': pages}
    details['probe'] = probe_installation(ctx['device'])
    details['assistant'] = ctx['device'].package_version(ASSISTANT)
    details['agentApp'] = ctx['device'].package_version(AGENT_APP)
    print(json.dumps(details, ensure_ascii=False, indent=2), flush=True)


def single_call(args, details, ctx):
    params = json.loads(args.arg) if args.arg else {}
    require(isinstance(params, dict), '--arg must be a JSON object')
    client = ctx['client']
    if args.mode == 'probe':
        response = client.probe(args.method, params, repeat=args.repeat,
                                interval_ms=args.interval_ms, barrier_ms=args.barrier_ms).response()
    else:
        response = client.shell(args.method, params)
    record(details, response)
    details['envelope'] = response.payload
    print(json.dumps(response.payload, ensure_ascii=False, indent=2), flush=True)


def single_describe(args, details, ctx):
    response = record(details, ctx['client'].probe('panels/describe', {}).response())
    details['envelope'] = response.payload
    print(json.dumps(response.payload, ensure_ascii=False, indent=2), flush=True)
    expect_ok(response, 'panels/describe')


def single_list(args, details, ctx):
    items, revision, pages = list_all(ctx['client'], limit=args.limit)
    details.update({'items': items, 'listRevision': revision, 'pages': pages})
    print(json.dumps({'items': items, 'listRevision': revision, 'pages': pages},
                     ensure_ascii=False, indent=2), flush=True)


def single_get(args, details, ctx):
    result = get_panel(ctx['client'], args.panel_id)
    details['panel'] = result
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


def single_state(args, details, ctx):
    client = ctx['client']
    items, revision, pages = list_all(client)
    state = {'items': items, 'listRevision': revision, 'pages': pages}
    if args.panel_id:
        result = get_panel(client, args.panel_id)
        state['panel'] = result
        require(args.panel_id in directory_ids(items),
                f'{args.panel_id} is not in the directory the GUI should show')
        if args.expect_title:
            require(result['panel']['title'] == args.expect_title,
                    f"title is {result['panel']['title']!r}, expected {args.expect_title!r}")
        if args.expect_matter:
            require(result['panel']['matterId'] == args.expect_matter,
                    f"matterId is {result['panel']['matterId']!r}, expected {args.expect_matter!r}")
        if args.expect_source_version is not None:
            require(result['panel']['sourceVersion'] == args.expect_source_version,
                    f"sourceVersion is {result['panel']['sourceVersion']}, expected {args.expect_source_version}")
    navigation = record(details, client.probe('panels/navigation', {}).response())
    state['navigation'] = expect_ok(navigation, 'panels/navigation')
    details['state'] = state
    print(json.dumps(state, ensure_ascii=False, indent=2), flush=True)


def single_pagination(args, details, ctx):
    client = ctx['client']
    full_items, revision, _ = list_all(client, limit=100)
    full_ids = directory_ids(full_items)
    walk_items, walk_revision, pages = list_all(client, limit=args.limit)
    walk_ids = directory_ids(walk_items)
    require(walk_revision == revision, 'pagination read a different listRevision')
    require(walk_ids == full_ids, f'limit={args.limit} did not cover the complete directory in order')
    require(len(set(walk_ids)) == len(walk_ids), 'pagination repeated a panel')
    details['pagination'] = {'items': len(full_ids), 'pages': pages, 'limit': args.limit,
                             'over64Exercised': len(full_ids) > 64, 'listRevision': revision}
    print(json.dumps(details['pagination'], indent=2), flush=True)


def single_card_view(args, details, ctx):
    panel = get_panel(ctx['client'], args.panel_id)['panel']
    options = argparse.Namespace(skip_card_view=False, card_view_matter_id=args.matter_id or panel['matterId'])
    standalone = dict(ctx)
    standalone['panel'] = panel
    standalone['options'] = options
    card_view_scenario(details, standalone)


def single_expiry(args, details, ctx):
    run = ctx['client'].probe('matter/read', {'matterId': args.matter_id}, consume_delay_ms=args.wait_ms)
    call = run.calls[-1]
    require('transportError' not in call, f"probe call failed: {call.get('transportError')}")
    require(sha256_bytes(call['outer'].encode()) == call['outerSha256'], 'probe envelope hash mismatch')
    outer = json.loads(call['outer'])
    require(outer.get('ok') is True, f"matter/read failed: {outer.get('code')}")
    uri = outer.get('resultUri')
    require(isinstance(uri, str) and uri.startswith(PROVIDER + '/'),
            'matter/read returned no resultUri; use a real matter whose read exceeds 256 KiB')
    delayed = call.get('delayedRead')
    require(isinstance(delayed, dict), 'the probe recorded no delayed read')
    require(delayed.get('readSucceeded') is False,
            'a resultUri read after the documented 30 second window succeeded')
    require('RESULT_EXPIRED' in str(delayed.get('message', '')),
            f"delayed read failed with {delayed.get('errorClass')}: {delayed.get('message')} instead of RESULT_EXPIRED")
    details['expiry'] = {'resultUri': uri, 'waitMs': args.wait_ms, 'delayedRead': delayed,
                         'outer': outer, 'outerBytes': call['outerBytes'],
                         'evidenceFile': run.evidence_path.name}
    print(json.dumps(details['expiry'], indent=2), flush=True)


def single_large(args, details, ctx):
    options = argparse.Namespace(large_matter_id=args.matter_id, oversize_matter_id=args.oversize_matter_id)
    standalone = dict(ctx)
    standalone['options'] = options
    large_result_scenario(details, standalone)


def single_continue(args, details, ctx):
    client = ctx['client']
    panel = get_panel(client, args.panel_id)['panel']
    params = {'panelId': args.panel_id, 'text': args.text,
              'expectedSourceVersion': args.expected_source_version
              if args.expected_source_version is not None else panel['sourceVersion'],
              'requestId': new_request_id()}
    response = record(details, client.shell('panels/continue', params))
    details['request'] = {'panelId': args.panel_id, 'expectedSourceVersion': params['expectedSourceVersion'],
                          'requestId': params['requestId'], 'textLength': len(args.text)}
    print(json.dumps(response.payload, ensure_ascii=False, indent=2), flush=True)
    result = expect_ok(response, 'panels/continue')
    for key in ('panelId',):
        require(key in result, f'panels/continue result has no {key}')
    require(result['panelId'] == args.panel_id, 'panels/continue returned a different panelId')
    details['receipt'] = result


def single_wait_update(args, details, ctx):
    client = ctx['client']
    deadline = time.monotonic() + args.timeout
    starting = get_panel(client, args.panel_id)['panel']
    require(starting['sourceVersion'] >= args.from_source_version,
            f"panel sourceVersion {starting['sourceVersion']} is already below {args.from_source_version}")
    observed = None
    while time.monotonic() < deadline:
        current = get_panel(client, args.panel_id)['panel']
        details.setdefault('observations', []).append(
            {'sourceVersion': current['sourceVersion'], 'matterId': current['matterId']})
        if current['sourceVersion'] > args.from_source_version:
            observed = current
            break
        time.sleep(args.poll)
    require(observed is not None,
            f'no source version above {args.from_source_version} arrived within {args.timeout}s')
    require(observed['panelId'] == args.panel_id, 'the panelId changed across a continuation')
    require(observed['title'] == starting['title'],
            f"the title changed across a continuation: {starting['title']!r} -> {observed['title']!r}")
    require(observed['position'] == starting['position'],
            f"the position changed across a continuation: {starting['position']} -> {observed['position']}")
    if args.expect_title is not None:
        require(observed['title'] == args.expect_title,
                f"title is {observed['title']!r}, expected {args.expect_title!r}")
    if args.expect_position is not None:
        require(observed['position'] == args.expect_position,
                f"position is {observed['position']}, expected {args.expect_position}")
    items, revision, _ = list_all(client)
    require(args.panel_id in directory_ids(items), 'the continued panel left the directory')
    details['update'] = {'startingSourceVersion': starting['sourceVersion'],
                         'observedSourceVersion': observed['sourceVersion'],
                         'startingMatterId': starting['matterId'],
                         'matterId': observed['matterId'],
                         'sourceChanged': observed['matterId'] != starting['matterId'],
                         'title': observed['title'], 'titlePreserved': observed['title'] == starting['title'],
                         'position': observed['position'],
                         'positionPreserved': observed['position'] == starting['position'],
                         'listRevision': revision}
    print(json.dumps(details['update'], ensure_ascii=False, indent=2), flush=True)


def build_probe(args):
    output = (Path(args.output) if args.output else PROBE_OUTPUT).resolve()
    result = subprocess.run([str(PROBE_BUILD), '--output', str(output)], text=True,
                            capture_output=True, cwd=str(ROOT))
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    if result.returncode != 0:
        raise SystemExit(result.returncode)
    signing = output / 'signing.json'
    require(signing.is_file(), f'build did not write {signing}')
    print(json.dumps(json.loads(signing.read_text()), indent=2), flush=True)


def install_probe(args):
    run_dir = new_run_directory(args.output)
    device = Device(args.serial, args.adb, args.avd).verify()
    apk = (Path(args.apk) if args.apk else PROBE_OUTPUT / 'panels-probe.apk').resolve()
    require(apk.is_file(), f'{apk} is missing; run the build mode first')
    result = device.run(['install', '-r', str(apk)], check=False)
    if result.returncode != 0 and 'UPDATE_INCOMPATIBLE' in (result.stdout + result.stderr):
        device.run(['uninstall', PROBE_PACKAGE], check=False)
        result = device.run(['install', '-r', str(apk)])
    require(result.returncode == 0, f'install failed: {result.stdout}{result.stderr}')
    details = {'install': result.stdout.strip()}
    device = Device(args.serial, args.adb, args.avd).verify()
    details['probe'] = probe_installation(device)
    details['probeArtifact'] = installed_probe_artifact(device)
    require(details['probeArtifact'].get('matchesBuild') is not False,
            'the installed probe APK differs from the build artifact')
    details['assistant'] = device.package_version(ASSISTANT)
    details['agentApp'] = device.package_version(AGENT_APP)
    metadata = {'serial': args.serial, 'avd': device.avd_name, 'apk': str(apk),
                'probeApkPath': str(apk), 'signing': installed_apk_facts(),
                'assistant': details['assistant'], 'script': str(Path(__file__).resolve())}
    recorder = Recorder(run_dir, metadata)
    recorder.save('install', 'passed', details, timestamp(), timestamp())
    recorder.write_summary({'mode': 'install'})
    print(f"Installed {PROBE_PACKAGE} as UID {details['probe']['uid']} with no permissions", flush=True)
    print(f'Evidence: {run_dir}', flush=True)


def add_device_arguments(parser):
    parser.add_argument('--serial', required=True, help='Explicit isolated emulator serial')
    parser.add_argument('--avd', default=DEDICATED_AVD,
                        help=f'Expected AVD name (default {DEDICATED_AVD}); never the user emulator')
    parser.add_argument('--adb', default=ADB_DEFAULT)
    parser.add_argument('--output', type=Path, help='Evidence directory (default out/panels-device/<time>)')


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='Run order: build, install, then accept with a real source. Example source: '
               '{"kind":"matter","matterId":"<thread uuid>/<key>"} or '
               '{"kind":"primary","threadId":"<uuid>","turnId":"turn-N","replyId":"reply-<32 hex>",'
               '"revision":N,"contractHash":"<64 hex>"}. '
               'Mutation modes only touch panels this run created, and only on the named isolated emulator.')
    modes = parser.add_subparsers(dest='mode', required=True)

    build = modes.add_parser('build', help='Build the independently signed probe APK')
    build.add_argument('--output', type=Path)

    install = modes.add_parser('install', help='Install the probe and verify its UID and permissions')
    add_device_arguments(install)
    install.add_argument('--apk', type=Path)

    for name, text in (('preflight', 'Verify the emulator, AVD, probe and directory baseline'),
                       ('describe', 'Print panels/describe'),
                       ('list', 'Read the complete directory'),
                       ('get', 'Read one panel through panels/get'),
                       ('state', 'Snapshot the directory, one panel and navigation for GUI evidence'),
                       ('pagination', 'Walk the complete directory at one page size')):
        command = modes.add_parser(name, help=text)
        add_device_arguments(command)
        command.set_defaults(mode=name)
        if name == 'list':
            command.add_argument('--limit', type=int, default=100)
        if name == 'get':
            command.add_argument('--panel-id', required=True)
        if name == 'state':
            command.add_argument('--panel-id')
            command.add_argument('--expect-title')
            command.add_argument('--expect-matter')
            command.add_argument('--expect-source-version', type=int)
        if name == 'pagination':
            command.add_argument('--limit', type=int, default=1)

    for name, text in (('shell', 'Call one public method from the shell surface'),
                       ('probe', 'Call one public method from the ordinary-UID probe')):
        command = modes.add_parser(name, help=text)
        add_device_arguments(command)
        command.set_defaults(mode=name)
        command.add_argument('--method', required=True)
        command.add_argument('--arg', default='{}', help='JSON object, as the public interface takes')
        if name == 'probe':
            command.add_argument('--repeat', type=int, default=1)
            command.add_argument('--interval-ms', type=int, default=0)
            command.add_argument('--barrier-ms', type=int, default=0)

    card = modes.add_parser('card-view', help='Exercise false, 0, empty text and selection clearing')
    add_device_arguments(card)
    card.add_argument('--panel-id', required=True)
    card.add_argument('--matter-id')

    expiry = modes.add_parser('expiry', help='Verify a resultUri expires after the documented window')
    add_device_arguments(expiry)
    expiry.add_argument('--matter-id', required=True)
    expiry.add_argument('--wait-ms', type=int, default=31000)

    large = modes.add_parser('large', help='Read a real large matter and verify full resultUri bytes')
    add_device_arguments(large)
    large.add_argument('--matter-id', required=True)
    large.add_argument('--oversize-matter-id')

    cont = modes.add_parser('continue', help='Submit a real panel continuation and print its receipt')
    add_device_arguments(cont)
    cont.add_argument('--panel-id', required=True)
    cont.add_argument('--text', required=True)
    cont.add_argument('--expected-source-version', type=int)

    wait = modes.add_parser('wait-update', help='Wait for a real continuation to publish a new source')
    add_device_arguments(wait)
    wait.add_argument('--panel-id', required=True)
    wait.add_argument('--from-source-version', type=int, required=True)
    wait.add_argument('--timeout', type=int, default=180)
    wait.add_argument('--poll', type=int, default=3)
    wait.add_argument('--expect-title')
    wait.add_argument('--expect-position', type=int)

    accept = modes.add_parser('accept', help='Run the provider-verifiable section 10 scenarios')
    add_device_arguments(accept)
    accept.add_argument('--source', required=True, help='Actual PinSource JSON returned by the device')
    accept.add_argument('--second-source', help='Second actual PinSource for ordering and multi-page reads')
    accept.add_argument('--large-matter-id', help='Real matter whose read exceeds 256 KiB')
    accept.add_argument('--oversize-matter-id', help='Real matter whose read exceeds 8 MiB')
    accept.add_argument('--card-view-matter-id', help='Matter whose card state may be changed and restored')
    accept.add_argument('--race-repeat', type=int, default=200,
                        help='Pin calls the probe issues around the shell pin')
    accept.add_argument('--race-interval-ms', type=int, default=20)
    accept.add_argument('--race-delay-ms', type=int, default=3000,
                        help='Fallback delay before the shell pin if the probe status line is delayed')
    accept.add_argument('--no-restart', action='store_true', help='Skip force-stopping the Assistant')
    accept.add_argument('--skip-card-view', action='store_true')
    accept.add_argument('--strict', action='store_true',
                        help='Fail when a required scenario was not exercised or reported a gap')

    args = parser.parse_args(argv)
    try:
        if args.mode == 'build':
            build_probe(args)
        elif args.mode == 'install':
            install_probe(args)
        elif args.mode == 'accept':
            run_accept(args)
        elif args.mode == 'describe':
            run_single(args, single_describe)
        elif args.mode == 'list':
            run_single(args, single_list, require_probe=False)
        elif args.mode == 'get':
            run_single(args, single_get)
        elif args.mode == 'state':
            run_single(args, single_state)
        elif args.mode == 'pagination':
            run_single(args, single_pagination, require_probe=False)
        elif args.mode == 'card-view':
            run_single(args, single_card_view)
        elif args.mode == 'large':
            run_single(args, single_large)
        elif args.mode == 'expiry':
            run_single(args, single_expiry)
        elif args.mode == 'continue':
            run_single(args, single_continue)
        elif args.mode == 'wait-update':
            run_single(args, single_wait_update)
        elif args.mode in ('shell', 'probe'):
            run_single(args, single_call, require_probe=args.mode == 'probe')
        elif args.mode == 'preflight':
            run_single(args, preflight_only, require_probe=False)
        else:
            parser.error(f'unknown mode {args.mode}')
    except Failed as failure:
        print(f'FAILED: {failure}', file=sys.stderr)
        return 1
    except (FileNotFoundError, subprocess.TimeoutExpired) as failure:
        print(f'FAILED: {type(failure).__name__}: {failure}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
