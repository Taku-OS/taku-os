import test from 'node:test';
import assert from 'node:assert/strict';
import {registerHooks} from 'node:module';

const runtime = {current: null};
globalThis[Symbol.for('taku.controller.route-test')] = runtime;
const mock = source => `data:text/javascript,${encodeURIComponent(`const runtime = globalThis[Symbol.for('taku.controller.route-test')];\n${source}`)}`;
const mocks = new Map([
  ['react', mock(`export const useState = initial => runtime.current.state(initial);
    export const useRef = initial => runtime.current.ref(initial);
    export const useMemo = (compute, dependencies) => runtime.current.memo(compute, dependencies);
    export const useCallback = (callback, dependencies) => runtime.current.memo(() => callback, dependencies);
    export const useEffect = (effect, dependencies) => runtime.current.effect(effect, dependencies);`)],
  ['react-native', mock(`export const AppState = {currentState: 'active', addEventListener() {return {remove() {}};}};
    export const BackHandler = {addEventListener() {return {remove() {}};}};`)],
  ['expo-localization', mock(`export const useLocales = () => [{languageCode: 'en'}];`)],
  ['@react-native-async-storage/async-storage', mock(`export default {
    getItem: async key => runtime.storage.get(key) ?? null,
    setItem: async (key, value) => {runtime.storage.set(key, value);},
    multiGet: async keys => keys.map(key => [key, runtime.storage.get(key) ?? null]),
    multiSet: async entries => {for (const [key, value] of entries) runtime.storage.set(key, value);},
    removeItem: async key => {runtime.events.push(['remove', key]); runtime.storage.delete(key);},
  };`)],
  ['../native/agent', mock(`export const callAgent = (...args) => runtime.callAgent(...args);
    export const newRequestId = () => 'request-' + ++runtime.sequence;
    export const launchThreadId = () => runtime.threadId;
    export const launchPanelTarget = async () => runtime.target && !runtime.consumed.has(runtime.target.launchId) ? {...runtime.target} : null;
    export const isPanelLaunchConsumed = id => runtime.consumed.has(id);
    export const consumePanelLaunch = async id => {
      if (runtime.failConsumption) throw new Error('disk full');
      runtime.consumed.add(id); runtime.events.push(['consume', id]);
    };
    export const onOpenThread = listener => {runtime.openListeners.add(listener); return {remove() {runtime.openListeners.delete(listener);}};};
    export const selectConversation = id => {runtime.target = null; runtime.threadId = id || '';};
    export const subscribeSession = () => ({remove() {}});
    export const watchConversationVisible = listener => {listener(true); return {remove() {}};};
    export const closeAgent = async () => {};
    export class AgentGatewayError extends Error {constructor(message, code = 'GATEWAY_UNAVAILABLE') {super(message); this.code = code;}};`)],
]);
const hookUrl = new URL('./useAgentController.ts', import.meta.url).href;
registerHooks({resolve(specifier, context, nextResolve) {
  if (mocks.has(specifier) && (specifier !== 'react' || context.parentURL === hookUrl))
    return {url: mocks.get(specifier), shortCircuit: true};
  if (specifier.startsWith('.') && !/\.[a-z]+$/.test(specifier)) return nextResolve(`${specifier}.ts`, context);
  return nextResolve(specifier, context);
}});
const {useAgentController} = await import('./useAgentController.ts');
const {PANEL_CONTRACT} = await import('../model/panel.ts');

const target = {panelId: 'panel-a', sourceVersion: 3, originThreadId: 'thread-a', launchId: 'launch-a'};
const pendingKey = id => `taku.agent.pending.v1.${id}`;
const draftKey = id => `taku.agent.draft.v1.${id}`;
const photoKey = id => `taku.agent.draft-photo.v1.${id}`;
const photo = 'data:image/jpeg;base64,/9j/AA==';
const accepted = (args, panelId) => ({threadId: panelId === 'panel-b' ? 'thread-b' : 'thread-a',
  requestId: args.requestId, state: 'queued', ...(panelId ? {panelId} : {})});
const deferred = () => {let resolve; const promise = new Promise(yes => {resolve = yes;}); return {promise, resolve};};

function reset(t) {
  Object.assign(runtime, {sequence: 0, storage: new Map(), consumed: new Set(), events: [], calls: [], completed: [],
    openListeners: new Set(), target: {...target}, threadId: 'thread-a', failConsumption: false, mounts: [],
    receipt: async (args, panelId) => accepted(args, panelId)});
  runtime.callAgent = async (action, params = {}) => {
    if (action === 'panels' && params.operation === 'describe') return {version: PANEL_CONTRACT};
    if (action === 'panels' && params.operation === 'continue' || action === 'sendMessage' || action === 'steer') {
      const args = action === 'panels' ? params.args : params;
      runtime.calls.push({action: action === 'panels' ? 'continue' : action, ...args});
      const receipt = await runtime.receipt(args, action === 'panels' ? args.panelId : undefined);
      if (receipt.state === 'queued') runtime.completed.push({...args, threadId: receipt.threadId});
      return receipt;
    }
    if (action === 'listThreads') return {threads: [{id: 'thread-a'}, {id: 'thread-b'}]};
    if (action === 'readSession') return {native: {ready: true}, historyComplete: true,
      thread: {id: params.threadId, turns: runtime.completed.filter(item => item.threadId === params.threadId).map(item => ({
        id: `turn-${item.requestId}`, status: 'completed', items: [
          {id: `user-${item.requestId}`, type: 'userMessage', content: [{type: 'text', text: item.text},
            ...(item.photo ? [{type: 'localImage', path: '/private/capture.jpg'}] : [])]},
          {id: `answer-${item.requestId}`, type: 'agentMessage', text: 'Completed'},
        ],
      }))}};
    return {};
  };
  t.after(() => {for (const mounted of runtime.mounts) mounted.unmount();});
}

function mount() {
  const host = {
    slots: [], queued: [], index: 0, dirty: true, active: true, result: null,
    state(initial) {
      const at = this.index++;
      if (!this.slots[at]) this.slots[at] = {value: typeof initial === 'function' ? initial() : initial};
      const slot = this.slots[at];
      return [slot.value, next => {
        const value = typeof next === 'function' ? next(slot.value) : next;
        if (!Object.is(value, slot.value)) {slot.value = value; this.dirty = true;}
      }];
    },
    ref(initial) {
      const at = this.index++;
      if (!this.slots[at]) this.slots[at] = {current: initial};
      return this.slots[at];
    },
    memo(compute, dependencies) {
      const at = this.index++, previous = this.slots[at];
      if (!previous || dependencies.some((value, i) => !Object.is(value, previous.dependencies[i])))
        this.slots[at] = {value: compute(), dependencies};
      return this.slots[at].value;
    },
    effect(effect, dependencies) {
      const at = this.index++, previous = this.slots[at];
      if (previous && dependencies.every((value, i) => Object.is(value, previous.dependencies[i]))) return;
      this.slots[at] = {dependencies, cleanup: previous?.cleanup};
      this.queued.push(() => {this.slots[at].cleanup?.(); this.slots[at].cleanup = effect();});
    },
    Render() {
      this.index = 0; this.dirty = false; runtime.current = this;
      this.result = useAgentController(); runtime.current = null;
      for (const effect of this.queued.splice(0)) effect();
    },
    unmount() {
      if (!this.active) return;
      this.active = false;
      for (const slot of this.slots) slot.cleanup?.();
    },
  };
  runtime.mounts.push(host);
  host.Render();
  return host;
}

async function flush(host) {
  for (let i = 0; i < 20; i++) {
    await new Promise(resolve => setImmediate(resolve));
    if (host.active && host.dirty) host.Render();
  }
}
async function send(host, text) {
  assert.equal(host.result.onDraftChange(text), true);
  await flush(host);
  host.result.onSend();
  await flush(host);
}
function open(next) {
  runtime.target = {...next}; runtime.threadId = next.originThreadId;
  for (const listener of runtime.openListeners) listener(next.originThreadId, {...next});
}

test('an accepted continuation consumes the restored launch before retiring its pending receipt', async t => {
  reset(t);
  const first = mount(); await flush(first);
  await send(first, 'Update the panel');
  assert.equal(runtime.calls[0].action, 'continue');
  assert.equal(runtime.consumed.has('launch-a'), true);
  assert.ok(runtime.events.findIndex(event => event[0] === 'consume') < runtime.events.findIndex(event => event[0] === 'remove'));
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  first.unmount();
  const restored = mount(); await flush(restored);
  await send(restored, 'An ordinary follow-up');
  assert.equal(runtime.calls.at(-1).action, 'sendMessage');
});

test('a photo draft preserves its panel launch until the attachment is removed', async t => {
  reset(t);
  const host = mount(); await flush(host);
  assert.equal(host.result.onDraftPhotoChange('thread-a', photo), true);
  await send(host, 'Update the panel');
  assert.deepEqual(runtime.calls, []);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  assert.equal(host.result.draft, 'Update the panel');
  assert.equal(host.result.draftPhoto, photo);
  assert.equal(runtime.storage.get(photoKey('thread-a')), photo);
  assert.equal(host.result.onDraftPhotoChange('thread-a'), true);
  host.result.onSend(); await flush(host);
  assert.equal(runtime.calls[0].action, 'continue');
  assert.equal(runtime.consumed.has('launch-a'), true);
});

test('a photo submission reuses its saved attachment and request ID after an interrupted delivery', async t => {
  reset(t);
  runtime.target = null;
  runtime.receipt = async () => {throw new Error('receipt unavailable');};
  const host = mount(); await flush(host);
  assert.equal(host.result.onDraftPhotoChange('thread-a', photo), true);
  await send(host, 'Read the menu');
  const pending = JSON.parse(runtime.storage.get(pendingKey('thread-a')));
  assert.equal(pending.photo, photo);
  assert.equal(host.result.draftPhoto, photo);
  const attemptsBeforeRestart = runtime.calls.length;
  assert.ok(attemptsBeforeRestart > 0);
  host.unmount();
  runtime.receipt = async args => accepted(args);
  const restored = mount(); await flush(restored);
  assert.equal(runtime.calls.length, attemptsBeforeRestart + 1);
  for (const call of runtime.calls) {
    assert.equal(call.action, 'sendMessage');
    assert.equal(call.requestId, pending.requestId);
    assert.equal(call.photo, photo);
  }
  assert.equal(restored.result.draft, '');
  assert.equal(restored.result.draftPhoto, undefined);
  assert.equal(runtime.storage.get(photoKey('thread-a')), '');
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  assert.deepEqual(restored.result.session?.blocks.find(block => block.role === 'user')?.attachments, ['image']);
});

test('a reconciled accepted pending continuation consumes its original launch after restart', async t => {
  reset(t);
  runtime.target.sourceVersion = 4;
  const pending = {threadId: 'thread-a', requestId: 'interrupted-request', text: 'Update before restart', action: 'continue',
    panel: {panelId: 'panel-a', expectedSourceVersion: 3, launchId: 'launch-a'}, baselineBlockIds: [], baselineTurnIds: []};
  runtime.storage.set(pendingKey('thread-a'), JSON.stringify(pending));
  runtime.storage.set(draftKey('thread-a'), pending.text);
  const restored = mount(); await flush(restored);
  assert.equal(runtime.calls[0].requestId, pending.requestId);
  assert.equal(runtime.calls[0].expectedSourceVersion, 3);
  assert.equal(runtime.consumed.has('launch-a'), true);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  await send(restored, 'A later ordinary turn');
  assert.equal(runtime.calls.at(-1).action, 'sendMessage');
});

test('a deliberate same-panel reopen remains a fresh continuation after acceptance', async t => {
  reset(t);
  const host = mount(); await flush(host);
  await send(host, 'First panel edit');
  open({...target, launchId: 'launch-reopened'}); await flush(host);
  await send(host, 'Second panel edit');
  assert.deepEqual(runtime.calls.map(call => call.action), ['continue', 'continue']);
  assert.deepEqual([...runtime.consumed], ['launch-a', 'launch-reopened']);
});

for (const next of [{...target, launchId: 'launch-same-panel'},
  {panelId: 'panel-b', sourceVersion: 0, originThreadId: 'thread-b', launchId: 'launch-other-panel'}]) {
  test(`an old acceptance preserves newer navigation ${next.launchId}`, async t => {
    reset(t);
    const gate = deferred(); runtime.receipt = () => gate.promise;
    const host = mount(); await flush(host);
    await send(host, 'The older panel edit');
    const sent = runtime.calls[0];
    assert.equal(JSON.parse(runtime.storage.get(pendingKey('thread-a'))).panel.launchId, 'launch-a');
    open(next); await flush(host);
    gate.resolve(accepted(sent, 'panel-a')); await flush(host);
    assert.deepEqual([...runtime.consumed], ['launch-a']);
    runtime.receipt = async (args, panelId) => accepted(args, panelId);
    await send(host, 'Edit the newly selected panel');
    assert.equal(runtime.calls.at(-1).action, 'continue');
    assert.equal(runtime.calls.at(-1).panelId, next.panelId);
    assert.equal(runtime.consumed.has(next.launchId), true);
  });
}

for (const state of ['notSent', 'deliveryUnknown']) {
  test(`${state} retains the continuation target through controller restoration`, async t => {
    reset(t);
    runtime.receipt = async (args, panelId) => ({...accepted(args, panelId), state, code: 'SOURCE_CHANGED'});
    const first = mount(); await flush(first);
    await send(first, 'Keep this panel edit');
    assert.equal(runtime.consumed.size, 0);
    assert.equal(first.result.draft, 'Keep this panel edit');
    assert.equal(runtime.storage.has(pendingKey('thread-a')), state !== 'notSent');
    first.unmount();
    runtime.receipt = async (args, panelId) => accepted(args, panelId);
    const restored = mount(); await flush(restored);
    if (state === 'notSent') await send(restored, 'Retry the panel edit');
    assert.equal(runtime.calls.at(-1).action, 'continue');
    assert.equal(runtime.consumed.has('launch-a'), true);
  });
}

test('a thrown continuation request preserves the target and original pending identity', async t => {
  reset(t);
  runtime.receipt = async () => {throw new Error('gateway unavailable');};
  const host = mount(); await flush(host);
  await send(host, 'Keep after failure');
  assert.equal(runtime.consumed.size, 0);
  assert.equal(host.result.draft, 'Keep after failure');
  const stored = JSON.parse(runtime.storage.get(pendingKey('thread-a')));
  assert.equal(stored.panel.launchId, 'launch-a');
  assert.equal(runtime.calls.every(call => call.requestId === stored.requestId), true);
});

test('failed durable consumption keeps pending evidence for the accepted receipt', async t => {
  reset(t); runtime.failConsumption = true;
  const host = mount(); await flush(host);
  await send(host, 'Keep until consumption commits');
  assert.equal(runtime.consumed.size, 0);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), true);
  runtime.failConsumption = false;
  host.result.onRetrySubmission(); await flush(host);
  assert.equal(runtime.consumed.has('launch-a'), true);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
});

test('a controller that read its target before an older controller consumed it sends an ordinary turn', async t => {
  reset(t);
  const remounted = mount(); await flush(remounted);
  runtime.consumed.add('launch-a');
  await send(remounted, 'Send after the older controller retired its receipt');
  assert.equal(runtime.calls.at(-1).action, 'sendMessage');
});

test('reconciling an old saved receipt preserves a newer same-panel launch after restart', async t => {
  reset(t);
  runtime.target = {...target, launchId: 'launch-new'};
  const pending = {threadId: 'thread-a', requestId: 'old-request', text: 'An earlier edit', action: 'continue',
    panel: {panelId: 'panel-a', expectedSourceVersion: 3, launchId: 'launch-a'}, baselineBlockIds: [], baselineTurnIds: []};
  runtime.storage.set(pendingKey('thread-a'), JSON.stringify(pending));
  const restored = mount(); await flush(restored);
  assert.deepEqual([...runtime.consumed], ['launch-a']);
  await send(restored, 'The explicitly reopened panel edit');
  assert.equal(runtime.calls.at(-1).action, 'continue');
  assert.equal(runtime.consumed.has('launch-new'), true);
});

test('a crash after consumption but before pending cleanup reconciles the same receipt', async t => {
  reset(t);
  runtime.consumed.add('launch-a');
  const pending = {threadId: 'thread-a', requestId: 'accepted-before-crash', text: 'Already accepted', action: 'continue',
    panel: {panelId: 'panel-a', expectedSourceVersion: 3, launchId: 'launch-a'}, baselineBlockIds: [], baselineTurnIds: []};
  runtime.storage.set(pendingKey('thread-a'), JSON.stringify(pending));
  runtime.storage.set(draftKey('thread-a'), pending.text);
  const restored = mount(); await flush(restored);
  assert.equal(runtime.calls[0].requestId, pending.requestId);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  await send(restored, 'An ordinary message after cleanup');
  assert.equal(runtime.calls.at(-1).action, 'sendMessage');
});
