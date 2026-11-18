import test from 'node:test';
import assert from 'node:assert/strict';
import {createNativePluginRepository} from './nativeRepository.ts';

const record = {
  id: 'org.example.settings', enabled: true, state: 'started', generation: 'generation-1',
  manifest: {name: 'Settings example', version: '1.0.0', capabilities: ['settings'], permissions: [], skills: []},
  registrations: {tools: [{name: 'hello'}], skills: ['skills/example/SKILL.md']},
};

test('manager records map into the marketplace contract', async () => {
  const seen = [];
  const repository = createNativePluginRepository(async (action, params) => {
    seen.push([action, params]);
    if (params.operation === 'list') return {ok: true, result: [record]};
    if (params.operation === 'show') return {ok: true, result: record};
    return {ok: true, result: {}};
  });
  const installed = await repository.listInstalled();
  assert.deepEqual(installed.value.map(item => [item.id, item.name, item.installed, item.enabled]), [
    ['org.example.settings', 'Settings example', true, true],
  ]);
  const details = await repository.getDetails(record.id);
  assert.deepEqual(details.value.tools, ['hello']);
  assert.deepEqual(details.value.skills, ['skills/example/SKILL.md']);
  assert.equal(details.value.fingerprint, 'generation-1');
  assert.equal((await repository.enable(record.id)).ok, true);
  assert.deepEqual(seen.map(([, params]) => params.operation), ['list', 'show', 'enable']);
});

test('manager errors and unavailable transport preserve stable error codes', async () => {
  const repository = createNativePluginRepository(async (_action, params) => {
    if (params.operation === 'list') return {ok: false, error: {code: 'USAGE', message: 'bad operation'}};
    throw new Error('host offline');
  });
  assert.deepEqual(await repository.listInstalled(), {ok: false, code: 'INVALID_ARGUMENT', message: 'bad operation'});
  assert.equal((await repository.enable('x')).code, 'UNAVAILABLE');
  assert.equal((await repository.importLocal()).code, 'UNAVAILABLE');
});
