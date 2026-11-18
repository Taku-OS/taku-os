import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {validateManifest} from '../sdk/plugins/manifest-v1.mjs';

const fixture = async name => JSON.parse(await readFile(new URL(`fixtures/godmode/${name}/manifest.json`, import.meta.url), 'utf8'));

test('v1 accepts a manifest with no declared capabilities', async () => {
  assert.deepEqual(validateManifest(await fixture('valid')), []);
});

test('v1 accepts a tool-provider manifest', async () => {
  const manifest = JSON.parse(await readFile(new URL('../examples/godmode/tool-provider/manifest.json', import.meta.url), 'utf8'));
  assert.deepEqual(validateManifest(manifest), []);
  manifest.mcp.args = [1];
  assert.ok(validateManifest(manifest).some(error => error.path === '$.mcp'));
});

test('v1 accepts the installable Hello World example', async () => {
  const manifest = JSON.parse(await readFile(new URL('../examples/godmode/hello-world/manifest.json', import.meta.url), 'utf8'));
  assert.deepEqual(validateManifest(manifest), []);
  assert.equal(manifest.id, 'org.taku.hello-world');
  assert.equal(manifest.skills[0], 'skills/hello-world/SKILL.md');
});

test('roles support check providers without legacy type', async () => {
  const manifest = JSON.parse(await readFile(new URL('../examples/godmode/tool-provider/manifest.json', import.meta.url), 'utf8'));
  delete manifest.type;
  manifest.roles = ['tool-provider', 'check-provider'];
  manifest.checks = [{id: 'verify', title: 'Verify', description: '', kinds: ['test'], targets: ['local']}];
  assert.deepEqual(validateManifest(manifest), []);
  manifest.checks.push(manifest.checks[0]);
  assert.ok(validateManifest(manifest).some(error => error.path === '$.checks'));
});

test('optional marketplace metadata is accepted and validated', async () => {
  const manifest = await fixture('valid');
  manifest.description = 'A useful extension';
  manifest.author = 'Example author';
  manifest.category = 'Developer tools';
  manifest.license = 'MIT';
  assert.deepEqual(validateManifest(manifest), []);
  manifest.description = '   ';
  assert.ok(validateManifest(manifest).some(error => error.path === '$.description'));
});

test('REA local runtime pack metadata is validated', async () => {
  const manifest = JSON.parse(await readFile(new URL('../examples/godmode/rea-provider/manifest.json', import.meta.url), 'utf8'));
  assert.deepEqual(validateManifest(manifest), []);
  delete manifest.runtimePack.root;
  assert.ok(validateManifest(manifest).some(error => error.path === '$.runtimePack'));
});

test('v1 diagnoses invalid values and unsupported fields', async () => {
  const manifest = await fixture('invalid');
  manifest.extra = true;
  const paths = validateManifest(manifest).map(error => error.path);
  for (const path of ['$.schemaVersion', '$.id', '$.name', '$.version', '$.type', '$.entrypoint.dex', '$.entrypoint.class', '$.capabilities', '$.extra']) assert.ok(paths.includes(path), path);
  assert.deepEqual(validateManifest(null), [{path: '$', message: 'must be an object'}]);
});

test('validator CLI emits machine-readable success and failure', () => {
  for (const [name, status] of [['valid', 0], ['invalid', 1], ['missing', 1]]) {
    const result = spawnSync(process.execPath, ['sdk/plugins/validate.mjs', `tests/fixtures/godmode/${name}/manifest.json`], {encoding: 'utf8'});
    assert.equal(result.status, status);
    assert.equal(JSON.parse(result.stdout).ok, status === 0);
  }
});
