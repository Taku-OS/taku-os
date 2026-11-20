import test from 'node:test';
import assert from 'node:assert/strict';
import {registerHooks} from 'node:module';

// The card hook with React's effect host and the native bridge replaced; everything else is real.
const runtime = {current: null, sequence: 0, calls: [], callAgent: null, subscribeMatter: () => ({remove() {}})};
globalThis[Symbol.for('taku.genui.hook-test')] = runtime;
const access = "const runtime = globalThis[Symbol.for('taku.genui.hook-test')];";
const mock = source => `data:text/javascript,${encodeURIComponent(`${access}\n${source}`)}`;
const mocks = new Map([
  ['react', mock(`export const useMemo = compute => compute();
    export const useCallback = callback => callback;
    export const useEffect = (effect, dependencies) => runtime.current.effect(effect, dependencies);
    export const useLayoutEffect = useEffect;`)],
  ['zustand', mock('export const useStore = (store, select) => select(store.getState());')],
  ['react-native', mock(`export const AppState = {currentState: 'active', addEventListener() { return {remove() {}}; }};`)],
  ['@react-native-async-storage/async-storage', mock(`export default {getItem: async () => null, setItem: async () => {},
    removeItem: async () => {}, getAllKeys: async () => [], multiRemove: async () => {}};`)],
  ['../native/agent', mock(`export const callAgent = (...args) => runtime.callAgent(...args);
    export const newRequestId = () => \`request-\${++runtime.sequence}\`;
    export const subscribeMatter = (...args) => runtime.subscribeMatter(...args);
    export class AgentGatewayError extends Error { constructor(message, code = 'GATEWAY_UNAVAILABLE') { super(message); this.code = code; } }`)],
]);
const hookOnly = new Set(['react', 'zustand']);
const hookUrl = new URL('./useGenUi.ts', import.meta.url).href;
registerHooks({resolve(specifier, context, nextResolve) {
  if (mocks.has(specifier) && (!hookOnly.has(specifier) || context.parentURL === hookUrl))
    return {url: mocks.get(specifier), shortCircuit: true};
  if (specifier.startsWith('.') && !/\.[a-z]+$/.test(specifier)) return nextResolve(`${specifier}.ts`, context);
  return nextResolve(specifier, context);
}});

const {useGenUiCard, needsJevSetup} = await import('./useGenUi.ts');
const {getCard, applyMatterSnapshot} = await import('../model/genuiStore.ts');
const {mainReply, matterSnapshot, primary} = await import('../model/mainReplyFixtures.mjs');

const settle = () => new Promise(resolve => setTimeout(resolve, 20));
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
};

function mount(threadId, presentation) {
  const mounted = {
    slots: [], queued: [], index: 0, result: null, presentation,
    effect(effect, dependencies) {
      const index = this.index++, previous = this.slots[index];
      if (previous && dependencies.every((value, at) => Object.is(value, previous.dependencies[at]))) return;
      this.slots[index] = {dependencies, cleanup: previous?.cleanup};
      this.queued.push(() => {
        this.slots[index].cleanup?.();
        this.slots[index].cleanup = effect();
      });
    },
    Render(nextPresentation) {
      if (nextPresentation) this.presentation = nextPresentation;
      this.index = 0;
      runtime.current = this;
      this.result = useGenUiCard(threadId, this.presentation, true);
      runtime.current = null;
      for (const run of this.queued.splice(0)) run();
      return this;
    },
    unmount() { for (const slot of this.slots) slot.cleanup?.(); },
  };
  return mounted.Render();
}

const pinCapability = (reply, patch = {}) => ({canPin: true, panelId: null, listRevision: 3,
  source: {kind: 'primary', threadId: reply.provenance.threadId, turnId: reply.provenance.turnId,
    replyId: reply.id, revision: reply.revision, contractHash: reply.provenance.contractHash}, ...patch});

const panelRecord = reply => ({panelId: 'panel-1', originThreadId: reply.provenance.threadId,
  matterId: `${reply.provenance.threadId}/${reply.key}`, sourceVersion: 1, metadataRevision: 1,
  title: reply.card.request.title, position: 0, createdAt: 1, updatedAt: 1});

function nativeCalls(reply, {pin = pinCapability(reply), cardView} = {}) {
  runtime.calls = [];
  runtime.callAgent = async (method, params) => {
    runtime.calls.push([method, params]);
    if (method === 'readMatter') return {snapshot: matterSnapshot(reply, 2, {
      pin, cardView: {matterId: `${reply.provenance.threadId}/${reply.key}`, uiRevision: 1, ui: cardView ?? {}}})};
    if (method === 'panels') return {panel: panelRecord(reply), created: true, listRevision: 4, replayed: false};
    return {};
  };
}

test('only a Jev that is not set up or switched off asks for its setup page', () => {
  assert.equal(needsJevSetup('jev-jev_not_configured'), true);
  assert.equal(needsJevSetup('jev-jev_disabled'), true);
  for (const other of ['', 'jev-timeout', 'jev-gateway_unavailable', 'llm-jev_not_configured', 'invalid-layout'])
    assert.equal(needsJevSetup(other), false, other);
});

test('a card only reports Pinned after the native pin receipt', async () => {
  const reply = mainReply('pin-thread', 'pin-card', 1, 'completed');
  nativeCalls(reply);
  const mounted = mount('pin-thread', primary(reply));
  await settle();
  mounted.Render();
  assert.equal(mounted.result.hydrated, true);
  assert.equal(mounted.result.pin.canPin, true);
  assert.equal(mounted.result.pin.panelId, null);

  const receipt = deferred();
  runtime.callAgent = async (method, params) => {
    runtime.calls.push([method, params]);
    if (method === 'panels') return receipt.promise;
    return {};
  };
  const pinned = mounted.result.pinToPanels();
  await settle();
  const call = runtime.calls.at(-1);
  assert.equal(call[1].operation, 'pin');
  assert.deepEqual(call[1].args.source, pinCapability(reply).source);
  assert.equal(typeof call[1].args.requestId, 'string');
  assert.equal(getCard('pin-thread/pin-card').pin.panelId, null, 'no panel id is shown before the commit');
  receipt.resolve({panel: panelRecord(reply), created: true, listRevision: 4, replayed: false});
  await pinned;
  mounted.Render();
  assert.equal(mounted.result.pin.panelId, 'panel-1');
  assert.equal(mounted.result.pin.listRevision, 4);
  mounted.unmount();
});

test('a failed pin keeps the previous capability and reports the real failure', async () => {
  const reply = mainReply('pin-fail-thread', 'pin-fail', 1, 'completed');
  nativeCalls(reply);
  const mounted = mount('pin-fail-thread', primary(reply));
  await settle();
  mounted.Render();
  runtime.callAgent = async (method, params) => {
    runtime.calls.push([method, params]);
    if (method === 'panels') throw Object.assign(new Error('not complete'), {code: 'REPLY_INCOMPLETE'});
    return {};
  };
  await assert.rejects(mounted.result.pinToPanels(), /not complete/);
  mounted.Render();
  assert.equal(mounted.result.pin.panelId, null);
  assert.equal(mounted.result.pin.canPin, true);
  mounted.unmount();
});

for (const operation of ['pin', 'unpin']) test(`a delayed ${operation} receipt cannot overwrite a newer directory event`, async () => {
  const threadId = `late-${operation}-thread`, key = `late-${operation}-card`;
  const reply = mainReply(threadId, key, 1, 'completed');
  nativeCalls(reply, {pin: pinCapability(reply, {panelId: operation === 'unpin' ? 'panel-1' : null})});
  const mounted = mount(threadId, primary(reply));
  await settle();
  mounted.Render();
  const receipt = deferred();
  runtime.callAgent = async (method, params) => {
    if (method === 'panels' && params.operation === 'get') return {
      panel: panelRecord(reply), listRevision: 3, sourceState: {state: 'current', observedAt: 1},
      capabilities: {canOpen: true, canContinue: true, canUnpin: true},
    };
    if (method === 'panels') return receipt.promise;
    // Even the read after unpin can be an old response.
    return {snapshot: matterSnapshot(reply, 2, {pin: pinCapability(reply, {panelId: null, listRevision: 4})})};
  };
  const pending = operation === 'pin' ? mounted.result.pinToPanels() : mounted.result.unpin();
  await settle();
  const newer = pinCapability(reply, {panelId: operation === 'pin' ? null : 'panel-new', listRevision: 5});
  applyMatterSnapshot(matterSnapshot(reply, 2, {pin: newer}));
  receipt.resolve(operation === 'pin'
    ? {panel: panelRecord(reply), created: true, listRevision: 4, replayed: false}
    : {removed: true, listRevision: 4, replayed: false});
  await pending;
  assert.deepEqual(getCard(`${threadId}/${key}`).pin, newer);
  mounted.unmount();
});

test('unpin still works after another card advances the directory', async () => {
  const reply = mainReply('unpin-thread', 'unpin-card', 1, 'completed');
  nativeCalls(reply, {pin: pinCapability(reply, {panelId: 'panel-9'})});
  const mounted = mount('unpin-thread', primary(reply));
  await settle();
  mounted.Render();
  assert.equal(mounted.result.pin.listRevision, 3);
  let revision = 4;
  const pinned = new Set(['panel-9', 'other-panel']);
  runtime.callAgent = async (method, params) => {
    if (method === 'panels' && params.operation === 'get') return {
      panel: {...panelRecord(reply), panelId: 'panel-9'}, listRevision: revision,
      sourceState: {state: 'current', observedAt: 1}, capabilities: {canOpen: true, canContinue: true, canUnpin: true},
    };
    if (method === 'panels' && params.operation === 'unpin') {
      if (params.args.expectedListRevision !== revision) throw Object.assign(new Error('stale'), {code: 'VERSION_CONFLICT'});
      pinned.delete(params.args.panelId);
      return {removed: true, listRevision: ++revision, replayed: false};
    }
    if (method === 'readMatter') return {snapshot: matterSnapshot(reply, 3, {
      pin: pinCapability(reply, {panelId: pinned.has('panel-9') ? 'panel-9' : null, listRevision: revision}),
      cardView: {matterId: 'unpin-thread/unpin-card', uiRevision: 1, ui: {}},
    })};
    return {};
  };
  await mounted.result.unpin();
  mounted.Render();
  assert.deepEqual([...pinned], ['other-panel']);
  assert.equal(mounted.result.pin.panelId, null);
  assert.equal(mounted.result.pin.listRevision, 5);
  mounted.unmount();
});

test('a streaming card waits for native completion before loading its saved state and pin capability', async () => {
  const streaming = mainReply('completion-thread', 'completion-card', 1, 'streaming');
  runtime.calls = [];
  runtime.callAgent = async () => { throw Object.assign(new Error('not admitted'), {code: 'MATTER_UNAVAILABLE'}); };
  const mounted = mount('completion-thread', primary(streaming));
  await settle();
  mounted.Render();
  assert.equal(mounted.result.hydrated, false);
  assert.equal(mounted.result.hydrationError, '');
  const completed = mainReply('completion-thread', 'completion-card', 2, 'completed');
  nativeCalls(completed);
  mounted.Render(primary(completed));
  mounted.Render();
  await settle();
  mounted.Render();
  assert.equal(mounted.result.hydrated, true);
  assert.equal(mounted.result.hydrationError, '');
  assert.equal(mounted.result.pin.canPin, true);
  mounted.unmount();
});

test('a finished reply whose card cannot be read for another reason reports it and offers Retry', async () => {
  const reply = mainReply('denied-thread', 'denied-card', 1, 'completed');
  runtime.calls = [];
  runtime.callAgent = async () => { throw Object.assign(new Error('lease revoked'), {code: 'LEASE_REVOKED'}); };
  const mounted = mount('denied-thread', primary(reply));
  await settle();
  mounted.Render();
  assert.equal(mounted.result.hydrated, false);
  assert.equal(mounted.result.hydrationError, 'LEASE_REVOKED', 'only a not-yet-projected record is retried quietly');
  nativeCalls(reply);
  await mounted.result.retry();
  mounted.Render();
  assert.equal(mounted.result.hydrated, true);
  assert.equal(mounted.result.hydrationError, '');
  mounted.unmount();
});

test('a finished reply whose card record is missing shows no error and loads it quietly once it appears', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const reply = mainReply('quiet-thread', 'quiet-card', 1, 'completed');
  runtime.calls = [];
  runtime.callAgent = async () => { throw Object.assign(new Error('not projected yet'), {code: 'MATTER_UNAVAILABLE'}); };
  const mounted = mount('quiet-thread', primary(reply));
  for (let i = 0; i < 5; i++) await Promise.resolve();
  await new Promise(resolve => setImmediate(resolve));
  mounted.Render();
  assert.equal(mounted.result.hydrated, false);
  assert.equal(mounted.result.hydrationError, '', 'the complete reply is not reported as a failed load');
  nativeCalls(reply);
  t.mock.timers.tick(1000);
  for (let i = 0; i < 5; i++) await new Promise(resolve => setImmediate(resolve));
  mounted.Render();
  assert.equal(mounted.result.hydrated, true);
  assert.equal(mounted.result.hydrationError, '');
  assert.equal(mounted.result.pin.canPin, true);
  mounted.unmount();
});

test('only a panel reply offers Pin to Panels; an ordinary reply offers no Panels action', async () => {
  const {cardPanelActions} = await import('./useGenUi.ts');
  const {request} = await import('../model/mainReplyFixtures.mjs');
  const panelReply = mainReply('menu-thread', 'menu-panel', 1, 'completed');
  nativeCalls(panelReply);
  const panel = mount('menu-thread', primary(panelReply));
  await settle(); panel.Render();
  assert.deepEqual(cardPanelActions('menu-thread/menu-panel'), {pinned: false}, 'mode:"panel" with native pin capability');

  const plain = mainReply('menu-thread', 'menu-card', 1, 'completed', {request: {...request('menu-card'), mode: 'card'}});
  nativeCalls(plain);
  const card = mount('menu-thread', primary(plain));
  await settle(); card.Render();
  assert.equal(card.result.hydrated, true);
  assert.equal(cardPanelActions('menu-thread/menu-card'), null, 'a greeting or one-off answer never offers pinning');

  nativeCalls(panelReply, {pin: pinCapability(panelReply, {panelId: 'panel-1'})});
  const pinned = mount('menu-thread', primary(panelReply));
  await settle(); pinned.Render(); await pinned.result.retry(); pinned.Render();
  assert.deepEqual(cardPanelActions('menu-thread/menu-panel'), {pinned: true}, 'a pinned reply offers Open panel and Unpin');
  for (const mounted of [panel, card, pinned]) mounted.unmount();
});
