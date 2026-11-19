import test from 'node:test';
import assert from 'node:assert/strict';
import {parseAppPermissionsSnapshot, unavailablePermissionsRepository} from './appPermissions.ts';
import {createNativeAppPermissionsRepository} from './appPermissionsRepository.ts';
import {createFixturePermissionsRepository, fixtureApps, fixtureEffectiveScopes} from './appPermissionsFixture.ts';

const none = {'memory.read': false, 'memory.write': false, 'taku.control': false, 'taku.root': false};
const validSnapshot = {revision: 1, mode: 'deny', allowRootInYolo: false, apps: [], recentEvents: []};

function recordingGateway(response = validSnapshot) {
  const calls = [];
  const call = async (action, params) => {
    // The native module receives JSON; serialize to catch undefined keys that JSON would drop silently.
    calls.push([action, JSON.parse(JSON.stringify(params)), Object.keys(params)]);
    return typeof response === 'function' ? response(action, params) : response;
  };
  return {calls, call};
}

class GatewayError extends Error {
  constructor(message, code) { super(message); this.code = code; }
}

test('requests are serialized exactly as the gateway contract, with optional keys omitted', async () => {
  const {calls, call} = recordingGateway();
  const repository = createNativeAppPermissionsRepository(call);
  await repository.read();
  await repository.read('   ');
  await repository.read('  habit ');
  await repository.read('x'.repeat(500));
  await repository.setMode('auto');
  await repository.setMode('yolo', true);
  await repository.setMode('manual', false);
  await repository.setScope('com.user.habittracker', 'memory.read', true);
  await repository.reset('com.user.habittracker');
  assert.deepEqual(calls.map(([action, params]) => [action, params]), [
    ['readAppPermissions', {}],
    ['readAppPermissions', {}],
    ['readAppPermissions', {query: 'habit'}],
    ['readAppPermissions', {query: 'x'.repeat(200)}],
    ['setAppPermissionMode', {mode: 'auto'}],
    ['setAppPermissionMode', {mode: 'yolo', allowRootInYolo: true}],
    ['setAppPermissionMode', {mode: 'manual', allowRootInYolo: false}],
    ['setAppPermissionScope', {packageName: 'com.user.habittracker', scope: 'memory.read', allowed: true}],
    ['resetAppPermission', {packageName: 'com.user.habittracker'}],
  ]);
  assert.deepEqual(calls[4][2], ['mode'], 'no undefined allowRootInYolo key reaches the gateway allowlist');
});

test('invalid arguments are rejected before reaching the gateway', async () => {
  const {calls, call} = recordingGateway();
  const repository = createNativeAppPermissionsRepository(call);
  const results = [
    await repository.setMode('open'),
    await repository.setMode('manual', true),
    await repository.setMode('yolo', 'true'),
    await repository.setScope('bad package!', 'memory.read', true),
    await repository.setScope('com.a', 'memory.delete', true),
    await repository.setScope('com.a', 'memory.read', 'yes'),
    await repository.reset(''),
  ];
  assert.deepEqual(results.map(result => result.code), Array(7).fill('INVALID_ARGUMENT'));
  assert.equal(calls.length, 0);
});

test('responses are validated and gateway failures keep stable codes and messages', async () => {
  const invalid = createNativeAppPermissionsRepository(recordingGateway({...validSnapshot, mode: 'everything'}).call);
  assert.equal((await invalid.read()).code, 'INVALID_RESPONSE');

  const thrown = (error) => createNativeAppPermissionsRepository(async () => { throw error; });
  assert.deepEqual(await thrown(new GatewayError('UNSUPPORTED_ACTION', 'UNSUPPORTED_ACTION')).read(),
    {ok: false, code: 'UNSUPPORTED', message: 'UNSUPPORTED_ACTION'});
  assert.equal((await thrown(new GatewayError('Taku device controls require the installed Android app', 'NATIVE_UNAVAILABLE')).read()).code, 'UNAVAILABLE');
  assert.equal((await thrown(new GatewayError('PERMISSION_DENIED', 'PERMISSION_DENIED')).setScope('com.a', 'taku.root', true)).code, 'PERMISSION_DENIED');
  assert.deepEqual(await thrown(new Error('socket closed')).reset('com.a'), {ok: false, code: 'UNAVAILABLE', message: 'socket closed'});
  assert.equal((await thrown('plain failure').read()).message, 'plain failure');

  assert.equal((await unavailablePermissionsRepository.read()).code, 'UNAVAILABLE');
  assert.equal((await unavailablePermissionsRepository.setScope('com.a', 'memory.read', true)).code, 'UNAVAILABLE');
});

test('fixture policy matrix: deny blocks all, auto never grants memory write or Root, YOLO opens non-root', () => {
  const manual = {source: 'manual', scopes: {...none, 'memory.read': true, 'taku.root': true}, autoReason: null, updatedAt: 0};
  const auto = {source: 'auto', scopes: {...none, 'memory.read': true, 'memory.write': true, 'taku.control': true, 'taku.root': true}, autoReason: 'x', updatedAt: 0};
  assert.deepEqual(fixtureEffectiveScopes('deny', true, manual), none);
  assert.deepEqual(fixtureEffectiveScopes('manual', false, manual), manual.scopes);
  assert.deepEqual(fixtureEffectiveScopes('manual', false, auto), none, 'manual mode ignores Agent decisions');
  assert.deepEqual(fixtureEffectiveScopes('auto', false, auto), {...none, 'memory.read': true, 'taku.control': true});
  assert.deepEqual(fixtureEffectiveScopes('auto', false, undefined), none);
  assert.deepEqual(fixtureEffectiveScopes('yolo', false, undefined), {'memory.read': true, 'memory.write': true, 'taku.control': true, 'taku.root': false});
  assert.equal(fixtureEffectiveScopes('yolo', true, undefined)['taku.root'], true);
  assert.deepEqual(fixtureEffectiveScopes('yolo', false, {...manual, scopes: {...none}}), none, 'an explicit manual denial overrides Open access');
});

test('fixture transitions are read back through the same contract the native gateway uses', async () => {
  const fixture = createFixturePermissionsRepository();
  // Wire the native repository to the fixture through a JSON boundary, as the real module does.
  const repository = createNativeAppPermissionsRepository(async (action, params) => {
    const body = JSON.parse(JSON.stringify(params));
    const result = action === 'readAppPermissions' ? await fixture.read(body.query)
      : action === 'setAppPermissionMode' ? await fixture.setMode(body.mode, body.allowRootInYolo)
        : action === 'setAppPermissionScope' ? await fixture.setScope(body.packageName, body.scope, body.allowed)
          : await fixture.reset(body.packageName);
    if (!result.ok) throw new GatewayError(result.message, result.code);
    return JSON.parse(JSON.stringify(result.value));
  });

  const initial = await repository.read();
  assert.equal(initial.ok, true);
  assert.equal(initial.value.mode, 'deny');
  assert.equal(initial.value.apps.length, fixtureApps.length);
  assert.ok(initial.value.apps.every(entry => Object.values(entry.effectiveScopes).every(value => value === false)), 'deny is the default');

  const yolo = await repository.setMode('yolo', false);
  const habit = (value) => value.apps.find(entry => entry.packageName === 'com.user.habittracker');
  const shop = (value) => value.apps.find(entry => entry.packageName === 'com.example.shop');
  assert.ok(yolo.value.revision > initial.value.revision);
  assert.deepEqual(shop(yolo.value).effectiveScopes, {'memory.read': true, 'memory.write': true, 'taku.control': true, 'taku.root': false});

  // Toggling one switch on a YOLO default app keeps the scopes the user saw.
  const writeOff = await repository.setScope('com.example.shop', 'memory.write', false);
  assert.equal(shop(writeOff.value).grantSource, 'manual');
  assert.deepEqual(shop(writeOff.value).effectiveScopes, {'memory.read': true, 'memory.write': false, 'taku.control': true, 'taku.root': false});

  const rootForAll = await repository.setMode('yolo', true);
  assert.equal(rootForAll.value.allowRootInYolo, true);
  assert.equal(shop(rootForAll.value).effectiveScopes['taku.root'], true);
  const manual = await repository.setMode('manual', false);
  assert.equal(manual.value.allowRootInYolo, false, 'leaving YOLO clears Root for all');
  assert.equal(habit(manual.value).effectiveScopes['memory.read'], true);

  const reset = await repository.reset('com.example.shop');
  assert.equal(shop(reset.value).grantSource, 'default');
  assert.deepEqual(shop(reset.value).effectiveScopes, none);

  const filtered = await repository.read('habit');
  assert.deepEqual(filtered.value.apps.map(entry => entry.packageName), ['com.user.habittracker']);

  assert.equal((await repository.setScope('com.missing.app', 'memory.read', true)).code, 'NOT_FOUND');
  assert.equal((await repository.reset('com.missing.app')).code, 'NOT_FOUND');
});

test('fixture auto mode keeps the Agent assessment and its snapshots are isolated copies', async () => {
  const fixture = createFixturePermissionsRepository({mode: 'auto'});
  const first = await fixture.read();
  const widget = first.value.apps.find(entry => entry.packageName === 'com.user.localwidget');
  assert.equal(widget.grantSource, 'auto');
  assert.equal(widget.autoReason, 'Built on this device and has no network access.');
  assert.deepEqual(widget.effectiveScopes, {...none, 'memory.read': true, 'taku.control': true});
  assert.equal(parseAppPermissionsSnapshot(first.value).ok, true, 'fixture output satisfies the strict parser');
  widget.effectiveScopes['taku.root'] = true;
  first.value.recentEvents.length = 0;
  const second = await fixture.read();
  assert.equal(second.value.apps.find(entry => entry.packageName === 'com.user.localwidget').effectiveScopes['taku.root'], false);
  assert.ok(second.value.recentEvents.length > 0);
  assert.equal((await fixture.setMode('auto', true)).code, 'INVALID_ARGUMENT');
});

test('fixture failures exercise error states deterministically', async () => {
  const fixture = createFixturePermissionsRepository({failures: {read: 'UNAVAILABLE', setScope: 'PERMISSION_DENIED'}});
  assert.deepEqual(await fixture.read(), {ok: false, code: 'UNAVAILABLE', message: 'Fixture read failure'});
  assert.equal((await fixture.setScope('com.user.habittracker', 'memory.read', true)).code, 'PERMISSION_DENIED');
  assert.equal((await fixture.setMode('manual')).ok, true);
});
