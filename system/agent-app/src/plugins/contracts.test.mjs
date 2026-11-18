import test from 'node:test';
import assert from 'node:assert/strict';
import {catalogResults, pluginErrorKey, unavailableRepository} from './contracts.ts';
import {createFixtureRepository} from './fixtureRepository.ts';

test('search, filter and deterministic sorting cover tags and capabilities', () => {
  const items = [
    {id: '2', name: 'Zulu', author: 'Ada', description: '', category: 'Work', tags: ['Note'], capabilities: ['MCP']},
    {id: '1', name: 'Alpha', author: 'Bob', description: '', category: 'Work', tags: ['Note'], capabilities: ['Hooks']},
  ];
  assert.deepEqual(catalogResults(items, '', 'Work').map(item => item.name), ['Alpha', 'Zulu']);
  assert.deepEqual(catalogResults(items, 'mCp', 'Note').map(item => item.id), ['2']);
  assert.deepEqual(catalogResults(items, 'ada', 'Other'), []);
  assert.deepEqual(items.map(item => item.name), ['Zulu', 'Alpha']);
});

test('fixture transitions are read back from repository including update and rollback', async () => {
  const template = (await createFixtureRepository().getDetails('example')).value;
  const repository = createFixtureRepository({installed: [{...template, version: '1.0.0', installed: true, enabled: false}]});
  assert.equal((await repository.listUpdates()).value[0].candidateVersion, '2.0.0');
  assert.equal((await repository.enable('example')).ok, true);
  assert.equal((await repository.listInstalled()).value[0].enabled, true);
  assert.equal((await repository.disable('example')).ok, true);
  assert.equal((await repository.reload('example')).ok, true);
  assert.equal((await repository.install('example', '2.0.0')).ok, true);
  assert.equal((await repository.listInstalled()).value[0].version, '2.0.0');
  assert.equal((await repository.listUpdates()).value.length, 0);
  assert.equal((await repository.rollback('example')).ok, true);
  assert.equal((await repository.listInstalled()).value[0].version, '1.0.0');
  assert.equal((await repository.remove('example')).ok, true);
  assert.equal((await repository.listInstalled()).value.length, 0);
  assert.equal((await repository.listCatalog()).value.items[0].installed, false);
});

test('failure codes, source operations, and disconnected bridge are explicit', async () => {
  const repository = createFixtureRepository();
  assert.equal((await repository.enable('missing')).code, 'NOT_FOUND');
  assert.equal((await repository.install('example', '3')).code, 'INVALID_ARGUMENT');
  assert.equal((await repository.rollback('example')).code, 'NOT_FOUND');
  assert.equal((await repository.addSource('file:///tmp/a')).code, 'INVALID_ARGUMENT');
  assert.equal((await repository.addSource('https://example.org/index.json')).ok, true);
  assert.equal((await repository.addSource('https://example.org/index.json')).code, 'CONFLICT');
  assert.equal((await repository.removeSource('https://example.org/index.json')).ok, true);
  assert.equal((await repository.syncSources()).ok, true);
  assert.equal((await repository.importLocal()).ok, false);
  assert.equal((await unavailableRepository.listInstalled()).code, 'UNAVAILABLE');
  assert.equal((await unavailableRepository.install('example')).code, 'UNAVAILABLE');
  assert.equal(pluginErrorKey('UNAVAILABLE'), 'unavailable');
  assert.equal(pluginErrorKey('OFFLINE'), 'offlineError');
  assert.equal(pluginErrorKey('PERMISSION_DENIED'), 'error');
});
