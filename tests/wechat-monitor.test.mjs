import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import vm from 'node:vm';
import {validateManifest} from '../sdk/plugins/manifest-v1.mjs';

const provider = path.resolve('examples/godmode/wechat-monitor');

test('WeChat provider manifest is valid', () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(provider, 'manifest.json'), 'utf8'));
  assert.deepEqual(validateManifest(manifest), []);
  assert.equal(manifest.id, 'org.taku.wechat.monitor');
});

test('notification hook forwards original call and emits notification fields', () => {
  const sent = [];
  const calls = [];
  const overload = {call: (...args) => { calls.push(args); return 42; }};
  const java = {perform: fn => fn(), use: () => ({notify: {overload: () => overload}})};
  vm.runInNewContext(fs.readFileSync(path.join(provider, 'hook.js'), 'utf8'), {Java: java, send: message => sent.push(message), Date});
  const extras = {getCharSequence: name => ({'android.title': 'Example', 'android.text': 'Test'}[name])};
  assert.equal(overload.implementation.call({}, 'tag', 3, {extras: {value: extras}}), 42);
  assert.equal(sent[0].kind, 'ready');
  assert.equal(sent[1].title, 'Example');
  assert.equal(sent[1].text, 'Test');
  assert.equal(calls.length, 1);
});

test('MCP tools capture and stop through the REA bridge', () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'wechat-monitor-test-'));
  try {
    const cli = path.join(temporary, 'fake-taku-plugin');
    fs.writeFileSync(cli, `#!/usr/bin/env node
      const tool = process.argv[5];
      const payload = {
        start_frida_session: {session_id: 'session-1'},
        load_frida_script: {loaded: true},
        frida_session_status: {messages: [
          {payload: {kind: 'ready', hook: 'test hook'}},
          {payload: {kind: 'event', title: 'Example', text: 'Test', time: '2026-11-06T00:00:00.000Z'}}
        ]},
        close_frida_session: {}
      }[tool] ?? {};
      process.stdout.write(JSON.stringify({
        ok: true,
        result: {content: [{type: 'text', text: JSON.stringify(payload)}]}
      }));
    `, {mode: 0o755});
    fs.writeFileSync(path.join(temporary, 'pidof'), '#!/bin/sh\nprintf "123\\n"\n', {mode: 0o755});
    const requests = [
      {id: 1, method: 'initialize'},
      {id: 2, method: 'tools/list'},
      {id: 3, method: 'tools/call', params: {name: 'start_monitor', arguments: {}}},
      {id: 4, method: 'tools/call', params: {name: 'start_monitor', arguments: {}}},
      {id: 5, method: 'tools/call', params: {name: 'read_events', arguments: {limit: 1}}},
      {id: 6, method: 'tools/call', params: {name: 'read_events', arguments: {limit: 0}}},
      {id: 7, method: 'tools/call', params: {name: 'stop_monitor', arguments: {}}},
      {id: 8, method: 'tools/call', params: {name: 'read_events', arguments: {}}}
    ];
    const storage = path.join(temporary, 'events');
    const result = spawnSync(process.execPath, [path.join(provider, 'server.mjs')], {
      input: requests.map(item => JSON.stringify({jsonrpc: '2.0', ...item})).join('\n') + '\n',
      env: {
        ...process.env,
        PATH: `${temporary}${path.delimiter}${process.env.PATH}`,
        TAKU_PLUGIN_CLI: cli,
        TAKU_WECHAT_STORAGE: storage
      },
      encoding: 'utf8',
      timeout: 10000
    });
    assert.equal(result.status, 0, result.stderr);
    const replies = result.stdout.trim().split('\n').map(line => JSON.parse(line));
    const value = index => JSON.parse(replies[index].result.content[0].text);
    assert.deepEqual(replies[1].result.tools.map(tool => tool.name), ['start_monitor', 'read_events', 'stop_monitor']);
    assert.equal(value(2).running, true);
    assert.equal(value(3).alreadyRunning, true);
    assert.equal(value(4).events[0].text, 'Test');
    assert.equal(replies[5].result.isError, true);
    assert.equal(value(6).running, false);
    assert.equal(value(7).events.length, 1);
    assert.equal(fs.statSync(path.join(storage, 'events.jsonl')).mode & 0o777, 0o600);
  } finally { fs.rmSync(temporary, {recursive: true, force: true}); }
});
