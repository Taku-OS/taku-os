import test from 'node:test';
import assert from 'node:assert/strict';
import {
  accessSummary, compareApps, createSnapshotGate, eventActor, filterApps, filterCount, modeChange, normalizePermissionError,
  parseAppPermissionsSnapshot, PERMISSION_MODES, PERMISSION_SCOPES, recentRequests, relativeTime, rootExposure, scopeEditable, scopeNote,
} from './appPermissions.ts';
import {permissionTextFor} from '../i18n/permissions.ts';

const none = {'memory.read': false, 'memory.write': false, 'taku.control': false, 'taku.root': false};
const scopes = (partial = {}) => ({...none, ...partial});
const app = (packageName, overrides = {}) => ({
  packageName, label: packageName.split('.').pop(), versionName: '1.0', versionCode: 1, enabled: true, certSha256: 'abc',
  grantSource: 'default', scopes: scopes(), effectiveScopes: scopes(), autoReason: null, updatedAt: null, ...overrides,
});
const snapshot = (overrides = {}) => ({revision: 3, mode: 'manual', allowRootInYolo: false, apps: [], recentEvents: [], ...overrides});

test('contract constants match the gateway literals', () => {
  assert.deepEqual(PERMISSION_MODES, ['deny', 'manual', 'auto', 'yolo']);
  assert.deepEqual(PERMISSION_SCOPES, ['memory.read', 'memory.write', 'taku.control', 'taku.root']);
});

test('a valid snapshot is normalized and unknown fields are dropped', () => {
  const raw = snapshot({
    extra: 'ignored',
    apps: [{...app('com.user.habit'), label: '  ', versionName: '', versionCode: 'x', enabled: 'yes', certSha256: 7, autoReason: '  Safe offline tool  ', updatedAt: Infinity, secret: 1}],
    recentEvents: [
      {at: 10, packageName: 'com.user.habit', label: '', scope: 'memory.read', decision: 'denied', by: 'mode:deny'},
      {at: 11, packageName: 'com.user.habit', scope: 'memory.everything', decision: 'denied', by: 'x'},
      {at: 'later', packageName: 'com.user.habit', scope: 'memory.read', decision: 'denied'},
      {at: 12, packageName: 'bad name', scope: 'memory.read', decision: 'allowed'},
    ],
  });
  const result = parseAppPermissionsSnapshot(raw);
  assert.equal(result.ok, true);
  assert.deepEqual(result.value, {
    revision: 3, mode: 'manual', allowRootInYolo: false,
    apps: [{
      packageName: 'com.user.habit', label: 'com.user.habit', versionName: null, versionCode: 0, enabled: true, certSha256: '',
      grantSource: 'default', scopes: none, effectiveScopes: none, autoReason: 'Safe offline tool', updatedAt: null,
    }],
    recentEvents: [{at: 10, packageName: 'com.user.habit', label: 'com.user.habit', scope: 'memory.read', decision: 'denied', by: 'mode:deny'}],
  });
  assert.deepEqual(parseAppPermissionsSnapshot(snapshot({recentEvents: undefined})).value.recentEvents, []);
});

test('malformed security state rejects the whole snapshot instead of guessing', () => {
  const rejects = [
    null, [], 'snapshot',
    snapshot({revision: -1}), snapshot({revision: '3'}), snapshot({mode: 'open'}),
    snapshot({allowRootInYolo: 'false'}), snapshot({allowRootInYolo: undefined}), snapshot({apps: {}}),
    snapshot({apps: [app('com.a', {scopes: {'memory.read': true}})]}),
    snapshot({apps: [app('com.a', {effectiveScopes: {...none, 'taku.root': 1}})]}),
    snapshot({apps: [app('com.a', {grantSource: 'agent'})]}),
    snapshot({apps: [app('com.a'), app('com.a')]}),
    snapshot({apps: [app('')]}),
  ];
  for (const raw of rejects) {
    const result = parseAppPermissionsSnapshot(raw);
    assert.equal(result.ok, false, JSON.stringify(raw));
    assert.equal(result.code, 'INVALID_RESPONSE');
  }
});

test('gateway error codes collapse to the states the screen explains', () => {
  const cases = {
    UNSUPPORTED_ACTION: 'UNSUPPORTED', UNSUPPORTED_PARAMETER: 'UNSUPPORTED', NATIVE_UNAVAILABLE: 'UNAVAILABLE',
    GATEWAY_UNAVAILABLE: 'UNAVAILABLE', RUNTIME_UNAVAILABLE: 'UNAVAILABLE', PERMISSION_DENIED: 'PERMISSION_DENIED',
    MANUAL_MODE_LOCKED: 'PERMISSION_DENIED', PACKAGE_NOT_FOUND: 'NOT_FOUND', REVISION_CONFLICT: 'CONFLICT',
    INVALID_SCOPE: 'INVALID_ARGUMENT', INVALID_RESPONSE: 'INVALID_RESPONSE', DISK_FULL: 'OPERATION_FAILED',
  };
  for (const [code, expected] of Object.entries(cases)) assert.equal(normalizePermissionError(code), expected, code);
  assert.equal(normalizePermissionError(undefined), 'UNAVAILABLE');
  assert.equal(normalizePermissionError(''), 'UNAVAILABLE');
});

test('search matches label or package; filters and riskiest-first order are deterministic', () => {
  const apps = [
    app('com.z.notes', {label: 'Notes'}),
    app('com.a.shop', {label: 'Shop', grantSource: 'manual'}),
    app('com.b.reader', {label: 'Reader', effectiveScopes: scopes({'memory.read': true})}),
    app('com.c.toolbox', {label: 'Toolbox', grantSource: 'manual', effectiveScopes: scopes({'taku.root': true})}),
    app('com.d.alpha', {label: 'alpha'}),
  ];
  const before = apps.map(entry => entry.packageName);
  assert.deepEqual(filterApps(apps, '', 'all').map(entry => entry.label), ['Toolbox', 'Reader', 'Shop', 'alpha', 'Notes']);
  assert.deepEqual(apps.map(entry => entry.packageName), before, 'input is not mutated');
  assert.deepEqual(filterApps(apps, '  READ ', 'all').map(entry => entry.label), ['Reader']);
  assert.deepEqual(filterApps(apps, 'com.z', 'all').map(entry => entry.label), ['Notes']);
  assert.deepEqual(filterApps(apps, '', 'allowed').map(entry => entry.label), ['Toolbox', 'Reader']);
  assert.deepEqual(filterApps(apps, '', 'custom').map(entry => entry.label), ['Toolbox', 'Shop']);
  assert.deepEqual(filterApps(apps, '', 'root').map(entry => entry.label), ['Toolbox']);
  assert.deepEqual(filterApps(apps, 'missing', 'all'), []);
  assert.equal(filterCount(apps, 'root'), 1);
  assert.ok(compareApps(app('a.b', {label: 'Same'}), app('a.c', {label: 'Same'})) < 0, 'package name breaks label ties');
});

test('switch editability follows the global mode', () => {
  for (const scope of PERMISSION_SCOPES) assert.equal(scopeEditable({mode: 'deny', allowRootInYolo: false}, scope), false);
  assert.equal(scopeEditable({mode: 'manual', allowRootInYolo: false}, 'taku.root'), true);
  assert.equal(scopeEditable({mode: 'yolo', allowRootInYolo: false}, 'taku.root'), true);
  assert.equal(scopeEditable({mode: 'yolo', allowRootInYolo: true}, 'taku.root'), false, 'global Root owns Root');
  assert.equal(scopeEditable({mode: 'yolo', allowRootInYolo: true}, 'memory.write'), true);
});

test('scope notes explain every gap between the explicit choice and the enforced state', () => {
  const manual = {mode: 'manual', allowRootInYolo: false};
  const saved = app('com.a', {grantSource: 'manual', scopes: scopes({'memory.read': true})});
  assert.equal(scopeNote({mode: 'deny', allowRootInYolo: false}, saved, 'memory.read'), 'savedPaused');
  assert.equal(scopeNote(manual, {...saved, effectiveScopes: scopes({'memory.read': true})}, 'memory.read'), null);
  const agent = app('com.b', {grantSource: 'auto', scopes: scopes({'memory.read': true}), effectiveScopes: scopes({'memory.read': true})});
  assert.equal(scopeNote({mode: 'auto', allowRootInYolo: false}, agent, 'memory.read'), 'byAgent');
  assert.equal(scopeNote({mode: 'auto', allowRootInYolo: false}, agent, 'memory.write'), 'needsYou');
  assert.equal(scopeNote({mode: 'auto', allowRootInYolo: false}, agent, 'taku.root'), 'needsYou');
  assert.equal(scopeNote({mode: 'auto', allowRootInYolo: false}, agent, 'taku.control'), null);
  assert.equal(scopeNote(manual, agent, 'memory.read'), 'byAgent');
  const open = app('com.c', {effectiveScopes: scopes({'memory.read': true, 'memory.write': true, 'taku.control': true})});
  assert.equal(scopeNote({mode: 'yolo', allowRootInYolo: false}, open, 'memory.write'), 'byOpenAccess');
  assert.equal(scopeNote({mode: 'yolo', allowRootInYolo: false}, open, 'taku.root'), null);
  assert.equal(scopeNote({mode: 'yolo', allowRootInYolo: true}, open, 'taku.root'), 'rootForAll');
  const chosen = app('com.d', {grantSource: 'manual', scopes: scopes({'memory.read': true}), effectiveScopes: scopes({'memory.read': true})});
  assert.equal(scopeNote({mode: 'yolo', allowRootInYolo: false}, chosen, 'memory.read'), null, 'your own choice needs no note');
});

test('summaries, root exposure, mode bodies and audit actors', () => {
  assert.deepEqual(accessSummary(scopes({'memory.read': true, 'taku.control': true})), {memory: 'read', control: true, root: false});
  assert.equal(accessSummary(scopes({'memory.read': true, 'memory.write': true})).memory, 'readWrite');
  assert.equal(accessSummary(scopes({'memory.write': true})).memory, 'write');
  assert.equal(accessSummary(none).memory, 'none');
  assert.equal(rootExposure(snapshot({mode: 'yolo', allowRootInYolo: true})), 'all');
  assert.equal(rootExposure(snapshot({apps: [app('com.a', {effectiveScopes: scopes({'taku.root': true})})]})), 'some');
  assert.equal(rootExposure(snapshot({apps: [app('com.a')]})), 'none');
  for (const mode of PERMISSION_MODES) assert.deepEqual(modeChange(mode), {mode, allowRootInYolo: false}, 'Root for all is never carried across a mode change');
  assert.equal(eventActor('mode:deny'), 'mode');
  assert.equal(eventActor('auto'), 'agent');
  assert.equal(eventActor('agent:classifier'), 'agent');
  assert.equal(eventActor('manual'), 'you');
  assert.equal(eventActor(''), 'system');
});

test('recent requests are newest first, one per app and scope, and keep uninstalled apps', () => {
  const apps = [app('com.shop'), app('com.widget')];
  const events = [
    {at: 1, packageName: 'com.shop', label: 'Shop', scope: 'memory.read', decision: 'denied', by: 'mode:manual'},
    {at: 5, packageName: 'com.shop', label: 'Shop', scope: 'memory.read', decision: 'denied', by: 'mode:manual'},
    {at: 4, packageName: 'com.widget', label: 'Widget', scope: 'taku.control', decision: 'allowed', by: 'auto'},
    {at: 3, packageName: 'com.gone', label: 'Gone', scope: 'taku.root', decision: 'denied', by: 'mode:manual'},
    {at: 2, packageName: 'com.shop', label: 'Shop', scope: 'taku.control', decision: 'denied', by: 'mode:manual'},
  ];
  const rows = recentRequests(events, apps);
  assert.deepEqual(rows.map(row => [row.event.packageName, row.event.at, row.app?.packageName ?? null]), [
    ['com.shop', 5, 'com.shop'], ['com.widget', 4, 'com.widget'], ['com.gone', 3, null], ['com.shop', 2, 'com.shop'],
  ]);
  assert.equal(recentRequests(events, apps, 2).length, 2);
  assert.equal(events[0].at, 1, 'input order is preserved');
});

test('relative times use 24-hour local clock', () => {
  const now = new Date(2026, 10, 19, 21, 30).getTime();
  assert.deepEqual(relativeTime(now - 59_000, now), {kind: 'now'});
  assert.deepEqual(relativeTime(now - 5 * 60_000, now), {kind: 'minutes', value: 5});
  assert.deepEqual(relativeTime(new Date(2026, 10, 19, 8, 5).getTime(), now), {kind: 'time', value: '08:05'});
  assert.deepEqual(relativeTime(new Date(2026, 10, 18, 23, 59).getTime(), now), {kind: 'date', month: 11, day: 18});
});

test('snapshot gate applies responses in request order, not arrival order', () => {
  const gate = createSnapshotGate();
  const read = gate.issue(), mutation = gate.issue();
  assert.equal(gate.accept(mutation), true);
  assert.equal(gate.accept(read), false, 'a read issued before the mutation cannot overwrite its result');
  const later = gate.issue();
  assert.equal(gate.accept(later), true);
});

test('permission copy is complete in both languages and follows the Taku copy rules', () => {
  const en = permissionTextFor('en'), zh = permissionTextFor('zh');
  assert.equal(en('confirmRootTitle', {app: 'HabitTracker'}), 'Allow Root for HabitTracker?');
  assert.equal(zh('confirmRootTitle', {app: 'HabitTracker'}), '允许 HabitTracker 使用 Root？');
  assert.equal(en('confirmRootTitle'), 'Allow Root for {app}?', 'missing values stay visible instead of becoming undefined');
  assert.equal(en.appCount(1), '1 app');
  assert.equal(en.appCount(12), '12 apps');
  assert.equal(zh.appCount(12), '12 个应用');
  assert.equal(en.time({kind: 'minutes', value: 3}), '3 min ago');
  assert.equal(zh.time({kind: 'date', month: 11, day: 18}), '11月18日');
  assert.equal(en.time({kind: 'date', month: 11, day: 18}), 'Nov 18');
  assert.equal(en.time({kind: 'time', value: '08:05'}), '08:05');
  assert.equal(zh.error('UNSUPPORTED'), '请更新 Taku 系统后管理应用权限。');
  for (const mode of PERMISSION_MODES) {
    for (const t of [en, zh]) { assert.ok(t.mode(mode)); assert.ok(t.modeBody(mode)); }
  }
  for (const scope of PERMISSION_SCOPES) {
    for (const t of [en, zh]) { assert.ok(t.scope(scope)); assert.ok(t.scopeHint(scope)); }
  }
  assert.equal(en.mode('deny'), 'Block third-party apps');
  assert.equal(zh.scope('taku.root'), 'Root 与底层插件执行');
  const keys = ['title', 'entrySubtitle', 'readFailed', 'refreshFailed', 'updateFailed', 'denyBanner', 'confirmRootBody', 'confirmRootAllBody', 'confirmYoloBody',
    'rootForAll', 'rootForAllOn', 'note.savedPaused', 'note.byOpenAccess', 'resetToMode', 'search'];
  for (const key of keys) {
    const english = en(key), chinese = zh(key);
    assert.doesNotMatch(english, /[–—]/, `${key}: no dashes in UI copy`);
    assert.doesNotMatch(english, /'/, `${key}: typographic apostrophes`);
    assert.doesNotMatch(chinese, /[,?!]/, `${key}: full-width Chinese punctuation`);
    assert.notEqual(english, chinese, `${key} is translated`);
  }
});
