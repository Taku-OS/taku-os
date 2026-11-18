import test from 'node:test';
import assert from 'node:assert/strict';
import {registerHooks} from 'node:module';

// The shared native bridge and AppState are the only device I/O these modules touch.
const runtime = {
  calls: [], sequence: 0, subscriptions: new Map(), appState: new Set(),
  callAgent: async (method, params) => { runtime.calls.push([method, params]); return {}; },
  subscribeMatter: ids => {
    const subscriptionId = `lease-${++runtime.sequence}`, lease = {ids: [...ids], closed: false};
    runtime.subscriptions.set(subscriptionId, lease);
    return {remove() { lease.closed = true; runtime.subscriptions.delete(subscriptionId); }};
  },
};
const access = "const runtime = globalThis[Symbol.for('taku.panels.test')];";
const mock = source => `data:text/javascript,${encodeURIComponent(`${access}\n${source}`)}`;
const mocks = new Map([
  ['../native/agent', mock(`export const callAgent = (...args) => runtime.callAgent(...args);
    export const newRequestId = () => \`request-\${++runtime.sequence}\`;
    export const subscribeMatter = (...args) => runtime.subscribeMatter(...args);
    export class AgentGatewayError extends Error { constructor(message, code = 'GATEWAY_UNAVAILABLE') { super(message); this.code = code; } }`)],
  ['react-native', mock(`export const AppState = {currentState: 'active', addEventListener(_event, listener) {
    runtime.appState.add(listener); return {remove() { runtime.appState.delete(listener); }}; }};`)],
]);
registerHooks({resolve(specifier, context, nextResolve) {
  if (mocks.has(specifier)) return {url: mocks.get(specifier), shortCircuit: true};
  if (specifier.startsWith('.') && !/\.[a-z]+$/.test(specifier)) return nextResolve(`${specifier}.ts`, context);
  return nextResolve(specifier, context);
}});
globalThis[Symbol.for('taku.panels.test')] = runtime;

const {openMatterSurface} = await import('./matterDemand.ts');
const {listAllPanels, requirePanelContract} = await import('./client.ts');
const {flushCardView, scheduleCardViewSync} = await import('./cardViewSync.ts');
const {ensureCard, getCard, mergeCardView, rendererStore} = await import('../model/genuiStore.ts');
const {cardState} = await import('../model/genui.ts');

const settle = () => new Promise(resolve => setTimeout(resolve, 160));
const block = (id, kind, patch = {}) => ({id, kind, title: '', text: '', value: {type: 'text', value: null}, image: '', url: '', items: [], config: {}, ...patch});
const request = key => ({version: 2, key, mode: 'card', title: 'Notes', summary: '', actions: [], blocks: [
  block('note', 'input', {value: {type: 'text', value: 'Source'}}),
  block('topics', 'choices', {items: [{id: 'cinema', label: 'Cinema', detail: '', image: '', url: '', icon: '', value: {type: 'text', value: null}}]}),
]});
const matter = key => ({kind: 'matter', request: request(key), sourceId: `source-${key}`});
const view = (matterId, uiRevision, ui) => ({matterId, uiRevision, ui, review: []});

function reset() {
  for (const lease of runtime.subscriptions.values()) lease.closed = true;
  runtime.subscriptions.clear();
  runtime.calls = [];
  runtime.callAgent = async (method, params) => { runtime.calls.push([method, params]); return {}; };
}
test.afterEach(reset);

for (const id of ['a', 'b']) ensureCard('thread', matter(id));
const idA = 'thread/a', idB = 'thread/b';

test('two roots that show the same matter keep one lease until both release it', async () => {
  const first = openMatterSurface('chat'), second = openMatterSurface('panel');
  first.setDemand({visible: [idA]});
  await settle();
  assert.equal(runtime.subscriptions.size, 1);
  const [subscriptionId, lease] = [...runtime.subscriptions][0];
  assert.deepEqual(lease.ids, [idA]);

  second.setDemand({visible: [idA]});
  await settle();
  assert.equal(runtime.subscriptions.size, 1, 'a second root reuses the same lease');
  assert.deepEqual([...runtime.subscriptions][0][1].ids, [idA]);

  first.release();
  await settle();
  assert.equal(runtime.subscriptions.get(subscriptionId)?.closed, false, 'the remaining root keeps its reader');
  second.release();
  await settle();
  assert.equal(runtime.subscriptions.size, 0);
});

test('a new matter joins a bounded group and renews with a full snapshot', async () => {
  const surface = openMatterSurface('chat');
  surface.setDemand({visible: [idA]});
  await settle();
  assert.deepEqual(runtime.calls.filter(call => call[0] === 'readMatter').map(call => call[1].matterId), [idA]);
  runtime.calls = [];
  surface.setDemand({visible: [idA, idB]});
  await settle();
  const leases = [...runtime.subscriptions.values()];
  assert.equal(leases.length, 1);
  assert.deepEqual(leases[0].ids, [idA, idB], 'the group reopens with the union instead of a second reader');
  const reads = runtime.calls.filter(call => call[0] === 'readMatter').map(call => call[1].matterId);
  assert.deepEqual(reads.sort(), [idA, idB], 'a renewed reader re-reads full snapshots');
  surface.release();
  await settle();
});

test('demand over two leases is capped at 32 per lease and reports only the visible overflow', async () => {
  const ids = Array.from({length: 70}, (_, index) => `many/${index}`);
  for (const id of ids) ensureCard('many', matter(id.slice(5)));
  const surface = openMatterSurface('chat');
  surface.setDemand({visible: ids});
  await settle();
  const leases = [...runtime.subscriptions.values()];
  assert.equal(leases.length, 2);
  assert.deepEqual(leases.map(lease => lease.ids.length), [32, 32]);
  assert.equal(getCard('many/69').syncError, 'SUBSCRIPTION_LIMITED');
  assert.equal(getCard('many/0').syncError, '');
  surface.release();
  await settle();
  assert.equal(runtime.subscriptions.size, 0);
});

test('app background releases every reader and foreground re-reads from scratch', async () => {
  const surface = openMatterSurface('chat');
  surface.setDemand({visible: [idA]});
  await settle();
  assert.equal(runtime.subscriptions.size, 1);
  for (const listener of runtime.appState) listener('background');
  await settle();
  assert.equal(runtime.subscriptions.size, 0, 'a backgrounded app holds no lease');
  runtime.calls = [];
  for (const listener of runtime.appState) listener('active');
  await settle();
  assert.equal(runtime.subscriptions.size, 1);
  assert.deepEqual(runtime.calls.filter(call => call[0] === 'readMatter').map(call => call[1].matterId), [idA]);
  surface.release();
  await settle();
});

test('an edit is written with the revision it was based on and acknowledges native state', async () => {
  const id = idA, ui = request('a'), canonical = cardState(ui).ui;
  mergeCardView(id, view(id, 4, canonical));
  rendererStore(id).set('/ui/fields/note/draft', 'Edited');
  runtime.calls = [];
  runtime.callAgent = async (method, params) => {
    assert.equal(method, 'cardView');
    assert.equal(params.operation, 'apply');
    const {expectedUiRevision, changes} = params.args;
    assert.equal(expectedUiRevision, 4);
    assert.deepEqual(changes, [{kind: 'field', id: 'note', value: 'Edited'}]);
    return {matterId: id, uiRevision: 5, ui: cardState(ui, {fields: {note: {draft: 'Edited'}}}).ui};
  };
  scheduleCardViewSync(id, true);
  await flushCardView(id);
  assert.equal(getCard(id).uiRevision, 5);
  assert.equal(getCard(id).snapshot.ui.fields.note.draft, 'Edited');
});

test('a revision conflict rebases once with the canonical revision and retries', async () => {
  const id = idB, ui = request('b');
  mergeCardView(id, view(id, 2, cardState(ui).ui));
  rendererStore(id).set('/ui/fields/note/draft', 'Mine');
  let applied = 0;
  runtime.callAgent = async (method, params) => {
    assert.equal(method, 'cardView');
    if (params.operation === 'read') return {matterId: id, uiRevision: 9, ui: cardState(ui, {fields: {topics: {selectedItemIds: []}}, views: {}}).ui};
    applied++;
    assert.equal(params.args.expectedUiRevision, applied === 1 ? 2 : 9);
    if (applied === 1) throw Object.assign(new Error('stale'), {code: 'VERSION_CONFLICT'});
    return {matterId: id, uiRevision: 10, ui: cardState(ui, {fields: {note: {draft: 'Mine'}}}).ui};
  };
  scheduleCardViewSync(id, true);
  await flushCardView(id);
  assert.equal(applied, 2);
  assert.equal(getCard(id).uiRevision, 10);
  assert.equal(getCard(id).syncError, '');
});

test('a directory read pages to the end and restarts after LIST_CHANGED', async () => {
  const panel = id => ({panelId: id, originThreadId: 'thread', matterId: `thread/${id}`, sourceVersion: 0,
    metadataRevision: 0, title: id, position: 0, createdAt: 1, updatedAt: 1});
  const pages = [
    () => ({items: [panel('a')], nextCursor: 'page-2', listRevision: 4}),
    () => { throw Object.assign(new Error('changed'), {code: 'LIST_CHANGED'}); },
    () => ({items: [panel('a'), panel('c')], nextCursor: 'page-2', listRevision: 5}),
    () => ({items: [panel('b')], nextCursor: null, listRevision: 5}),
  ];
  let lists = 0;
  runtime.callAgent = async (method, params) => {
    if (params.operation !== 'list') return {};
    return pages[lists++]();
  };
  assert.deepEqual(await listAllPanels(), {items: [panel('a'), panel('c'), panel('b')], listRevision: 5});
  assert.equal(lists, 4, 'the paging restart rereads from the first page');
});

test('a second conflict is a visible error and the rejected change is not resent', async () => {
  const id = 'thread/contested';
  ensureCard('thread', matter('contested'));
  mergeCardView(id, view(id, 1, cardState(request('contested')).ui));
  rendererStore(id).set('/ui/fields/note/draft', 'Contested');
  let applies = 0;
  runtime.callAgent = async (method, params) => {
    if (params.operation === 'read') return {matterId: id, uiRevision: 12, ui: cardState(request('contested')).ui};
    applies++;
    throw Object.assign(new Error('stale'), {code: 'VERSION_CONFLICT'});
  };
  scheduleCardViewSync(id, true);
  await flushCardView(id);
  assert.equal(applies, 2);
  assert.equal(getCard(id).syncError, 'VERSION_CONFLICT');
  assert.equal(rendererStore(id).get('/ui/fields/note/draft'), 'Contested');
  scheduleCardViewSync(id);
  await new Promise(resolve => setTimeout(resolve, 300));
  assert.equal(applies, 2, 'an unchanged rejected value waits for the user');
});

test('older Assistant gateways produce the update-required error', async () => {
  for (const code of ['UNSUPPORTED_ACTION', 'UNKNOWN_METHOD']) {
    runtime.callAgent = async () => { throw Object.assign(new Error('unsupported'), {code}); };
    await assert.rejects(requirePanelContract(), error => error.code === 'PANEL_CONTRACT_MISMATCH');
  }
});

test('a replaced source rejects its late edit without acknowledging or retrying it', async () => {
  const id = 'thread/replaced';
  ensureCard('thread', matter('replaced'));
  mergeCardView(id, view(id, 1, cardState(request('replaced')).ui));
  rendererStore(id).set('/ui/fields/note/draft', 'Last typed');
  let applies = 0;
  runtime.callAgent = async (method, params) => {
    assert.equal(params.operation, 'apply', 'a stale source must not be rebased onto an obsolete matter');
    applies++;
    throw Object.assign(new Error('replaced'), {code: 'STALE_SOURCE'});
  };
  await flushCardView(id);
  assert.equal(getCard(id).syncError, 'STALE_SOURCE');
  assert.equal(getCard(id).uiRevision, 1);
  assert.equal(rendererStore(id).get('/ui/fields/note/draft'), 'Last typed');
  scheduleCardViewSync(id);
  await new Promise(resolve => setTimeout(resolve, 300));
  assert.equal(applies, 1);
});
