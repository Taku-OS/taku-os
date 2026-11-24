#!/usr/bin/env python3
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import shutil
import zipfile
from service_ready import wait_for_service

root = pathlib.Path(__file__).resolve().parents[3]
build = pathlib.Path(sys.argv[1]).resolve()
java = pathlib.Path(os.environ['JAVA_HOME']) / 'bin/java'
with tempfile.TemporaryDirectory(prefix='taku-cli-') as temporary:
    temporary = pathlib.Path(temporary)
    env = dict(os.environ, TAKU_GODMODE_HOST_JAR=str(build / 'taku-godmode-host.jar'), TAKU_GODMODE_SOCKET=str(temporary / 'service.sock'))
    env.pop('TAKU_PLUGIN_TRUSTED_PUBLISHER_KEY', None)
    env['TAKU_TEST_HOST_ONLY'] = 'host-only-fixture'
    service = subprocess.Popen([str(java), '-cp', env['TAKU_GODMODE_HOST_JAR'], 'org.taku.plugins.host.Server', str(temporary / 'state'), env['TAKU_GODMODE_SOCKET']], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        wait_for_service(service, env['TAKU_GODMODE_SOCKET'])
        def cli(*args, status=0):
            result = subprocess.run([str(root / 'system/plugins/taku-plugin'), 'godmode', *args], env=env, text=True, capture_output=True)
            print('$ taku-plugin godmode ' + ' '.join(args))
            print(result.stdout.strip())
            assert result.returncode == status, result.stderr
            return json.loads(result.stdout)
        local_file = temporary / 'local-file'
        local_file.write_text('direct file read')
        local_request = json.dumps({'target': 'local', 'action': 'read-file', 'path': str(local_file)})
        assert cli('local', local_request)['result']['base64'] == 'ZGlyZWN0IGZpbGUgcmVhZA=='
        remote_request = json.dumps({'target': 'remote', 'action': 'packages'})
        assert cli('local', remote_request, status=1)['error']['code'] == 'REMOTE_UNAVAILABLE'
        invalid_url = json.dumps({'url': 'http://example.com/plugin.zip', 'sha256': '0' * 64})
        assert cli('install-url', invalid_url, status=1)['error']['code'] == 'INVALID_URL'
        invalid_checksum = json.dumps({'url': 'https://example.com/plugin.zip', 'sha256': 'bad'})
        assert cli('install-url', invalid_checksum, status=1)['error']['code'] == 'INVALID_CHECKSUM'
        untrusted_url = json.dumps({'url': 'https://example.invalid/plugin.zip', 'sha256': '0' * 64,
                                    'signature': 'fixture', 'publicKey': 'self-reported'})
        assert cli('install-url', untrusted_url, status=1)['error']['code'] == 'UNTRUSTED_PUBLISHER'
        identifier = 'org.example.settings'
        assert cli('install', str(build / 'examples/settings-page'))['result']['state'] == 'started'
        assert len(cli('list')['result']) == 1
        assert not cli('disable', identifier)['result']['enabled']
        assert cli('enable', identifier)['result']['enabled']
        assert cli('reload', identifier)['result']['state'] == 'started'
        assert cli('remove', identifier)['result']['removed']
        assert cli('show', identifier, status=1)['error']['code'] == 'NOT_FOUND'
        provider = temporary / 'provider'
        shutil.copytree(root / 'examples/godmode/hello-world', provider)
        manifest_path = provider / 'manifest.json'
        manifest = json.loads(manifest_path.read_text())
        manifest['mcp']['command'] = '/bin/sh'
        manifest_path.write_text(json.dumps(manifest))
        provider_zip = temporary / 'hello-world.zip'
        with zipfile.ZipFile(provider_zip, 'w', compression=zipfile.ZIP_DEFLATED) as package:
            for path in sorted(item for item in provider.rglob('*') if item.is_file()):
                package.write(path, path.relative_to(provider))
        installed = cli('install', str(provider_zip))['result']
        assert installed['state'] == 'started'
        assert installed['manifest']['id'] == 'org.taku.hello-world'
        discovered = cli('tools')['result']
        assert discovered[0]['pluginId'] == 'org.taku.hello-world'
        assert discovered[0]['tools'][0]['name'] == 'hello_world'
        assert discovered[0]['skills'] == ['skills/hello-world/SKILL.md']
        hello = cli('call-tool', 'org.taku.hello-world', 'hello_world', '{}')['result']
        assert hello['content'][0]['text'] == 'Hello, World! 👋 from the first Taku OS plugin'
        assert hello['structuredContent']['runtime'] == 'on-device'
        mcp_requests = [
            {'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2024-11-05', 'capabilities': {}, 'clientInfo': {'name': 'test', 'version': '1'}}},
            {'jsonrpc': '2.0', 'method': 'notifications/initialized', 'params': {}},
            {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list', 'params': {}},
            {'jsonrpc': '2.0', 'id': 3, 'method': 'tools/call', 'params': {'name': 'org_taku_hello-world__hello_world', 'arguments': {}}},
        ]
        bridge = subprocess.run(
            [str(root / 'system/plugins/taku-plugin'), 'godmode', 'mcp'],
            input='\n'.join(json.dumps(item) for item in mcp_requests) + '\n',
            text=True, capture_output=True, timeout=15, env=env)
        assert bridge.returncode == 0, bridge.stderr
        replies = [json.loads(line) for line in bridge.stdout.splitlines() if line.strip()]
        if os.environ.get('TAKU_DEBUG'):
            print('BRIDGE', json.dumps(replies), file=sys.stderr)
            print('BRIDGE-ERR', bridge.stderr, file=sys.stderr)
        assert [reply['id'] for reply in replies] == [1, 2, 3]
        assert replies[1]['result']['tools'][0]['name'] == 'org_taku_hello-world__hello_world'
        assert replies[1]['result']['tools'][0]['inputSchema']['type'] == 'object'
        assert replies[2]['result']['content'][0]['text'] == 'Hello, World! 👋 from the first Taku OS plugin'
        assert cli('install', str(root / 'examples/godmode/rea-provider'), status=1)['error']['code'] == 'LOAD_FAILED'
        assert cli('tools')['result'][0]['pluginId'] == 'org.taku.hello-world'
        cli('disable', 'org.taku.hello-world')
        assert cli('tools')['result'] == []
        cli('enable', 'org.taku.hello-world')
        assert cli('tools')['result'][0]['tools'][0]['name'] == 'hello_world'
        cli('remove', 'org.taku.hello-world')
        wechat_dir = temporary / 'wechat-provider'
        shutil.copytree(root / 'examples/godmode/wechat-monitor', wechat_dir)
        wechat_manifest_path = wechat_dir / 'manifest.json'
        wechat_manifest = json.loads(wechat_manifest_path.read_text())
        wechat_manifest['mcp']['command'] = shutil.which('node')
        wechat_manifest['mcp']['env']['TAKU_WECHAT_STORAGE'] = str(temporary / 'wechat-events')
        wechat_manifest_path.write_text(json.dumps(wechat_manifest))
        assert cli('install', str(wechat_dir))['result']['state'] == 'started'
        assert [tool['name'] for tool in cli('tools')['result'][0]['tools']] == ['start_monitor', 'read_events', 'stop_monitor']
        events = cli('call-tool', 'org.taku.wechat.monitor', 'read_events', '{}')['result']
        assert json.loads(events['content'][0]['text'])['events'] == []
        failed = cli('call-tool', 'org.taku.wechat.monitor', 'start_monitor', '{}')['result']
        assert failed['isError'] and failed['content'][0]['text']
        cli('remove', 'org.taku.wechat.monitor')
        checks_dir = temporary / 'checks-provider'
        shutil.copytree(root / 'examples/godmode/tool-provider', checks_dir)
        shutil.copy(root / 'system/plugins/tests/check-provider.py', checks_dir / 'check-provider.py')
        check_manifest_path = checks_dir / 'manifest.json'
        check_manifest = json.loads(check_manifest_path.read_text())
        check_manifest['id'] = 'org.example.checks'
        del check_manifest['type']
        check_manifest['roles'] = ['tool-provider', 'check-provider']
        check_manifest['checks'] = [{'id': name, 'title': name, 'description': 'fixture', 'kinds': ['test'], 'targets': ['local']} for name in ('pass', 'fail', 'slow')]
        check_manifest['mcp'] = {'command': sys.executable, 'args': ['check-provider.py'], 'env': {'TAKU_TEST_PLUGIN_ENV': 'plugin-fixture'}}
        check_manifest['healthcheck']['timeoutMs'] = 300
        check_manifest_path.write_text(json.dumps(check_manifest))
        assert cli('install', str(checks_dir))['result']['state'] == 'started'
        assert 'env' not in cli('show', 'org.example.checks')['result']['manifest']['mcp']
        assert 'checks' not in cli('list')['result'][0]['manifest']
        filtered = cli('tools', 'checks')['result']
        assert [tool['id'] for tool in filtered[0]['tools']] == ['pass', 'fail']
        assert all('inputSchema' not in tool for tool in filtered[0]['tools'])
        detail = cli('tool', 'org.example.checks', 'pass')['result']
        assert detail['inputSchema']['type'] == 'object' and detail['extensions']['extraFixture'] == 42
        assert cli('tool', 'org.example.checks', 'missing', status=1)['error']['code'] == 'NOT_FOUND'
        twin_dir = temporary / 'twin-provider'
        shutil.copytree(checks_dir, twin_dir)
        twin_manifest_path = twin_dir / 'manifest.json'
        twin_manifest = json.loads(twin_manifest_path.read_text())
        twin_manifest['id'] = 'org.example.twin'
        twin_manifest_path.write_text(json.dumps(twin_manifest))
        cli('install', str(twin_dir))
        assert len(cli('tools', 'checks')['result']) == 2
        assert cli('tool', 'org.example.twin', 'pass')['result']['id'] == 'pass'
        cli('remove', 'org.example.twin')
        assert len(cli('checks')['result'][0]['checks']) == 3
        result = cli('run-check', 'org.example.checks', 'pass', '{}')['result']
        assert result['passed'] is False and result['similarity'] == 87.5 and result['unknowns'] == ['not inspected']
        assert cli('run-check', 'org.example.checks', 'fail', '{}', status=1)['error']['code'] == 'CHECK_FAILED'
        assert cli('run-check', 'org.example.checks', 'slow', '{}', status=1)['error']['code'] == 'CHECK_TIMEOUT'
        evidence = cli('evidence')['result']
        assert any(item['operation'] == 'run-check' and item['targetHash'] is not None for item in evidence)
        assert any(item['operation'] == 'run-check' and item['result'].get('unknowns') == ['not inspected'] and item['coverage'] == 'partial' and item['externalContent'] for item in evidence)
        assert any(item['operation'] == 'run-check' and item['result'].get('error', {}).get('code') == 'CHECK_TIMEOUT' for item in evidence)
        assert cli('evidence', evidence[0]['id'])['result']['id'] == evidence[0]['id']
        duplicate_dir = temporary / 'duplicate-provider'
        shutil.copytree(checks_dir, duplicate_dir)
        duplicate_manifest_path = duplicate_dir / 'manifest.json'
        duplicate_manifest = json.loads(duplicate_manifest_path.read_text())
        duplicate_manifest['id'] = 'org.example.duplicate'
        duplicate_manifest['mcp']['env'] = {'DUPLICATE': '1'}
        duplicate_manifest_path.write_text(json.dumps(duplicate_manifest))
        assert cli('install', str(duplicate_dir), status=1)['error']['code'] == 'LOAD_FAILED'
        cli('remove', 'org.example.checks')
        cli('shutdown')
        assert service.wait(timeout=5) == 0
        print('PASS: CLI service integration')
    finally:
        if service.poll() is None:
            service.terminate()
            service.wait(timeout=5)
