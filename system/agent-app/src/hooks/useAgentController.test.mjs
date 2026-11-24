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
  ['../native/composerPicker', mock(`export const pickComposerAttachment = async () => null;
    export const onComposerAttachment = listener => {
      runtime.attachmentListeners.add(listener);
      return {remove() {runtime.attachmentListeners.delete(listener);}};
    };`)],
  ['@react-native-async-storage/async-storage', mock(`export default {
    getItem: async key => runtime.storage.get(key) ?? null,
    getAllKeys: async () => [...runtime.storage.keys()],
    setItem: async (key, value) => {runtime.storage.set(key, value);},
    removeItem: async key => {runtime.events.push(['remove', key]); runtime.storage.delete(key);},
  };`)],
  ['../native/agent', mock(`export const callAgent = (...args) => runtime.callAgent(...args);
    export const newRequestId = () => 'request-' + ++runtime.sequence;
    export const launchThreadId = () => runtime.threadId;
    export const launchPanelTarget = async () => runtime.target && {...runtime.target};
    export const isLaunchCurrent = generation => generation === runtime.routeGeneration;
    export const isPanelRouteCurrent = target => target.panelId === runtime.target?.panelId && target.launchId === runtime.target?.launchId;
    export const onOpenThread = listener => {runtime.openListeners.add(listener); return {remove() {runtime.openListeners.delete(listener);}};};
    export const selectConversation = (id, expectedGeneration) => {
      if (expectedGeneration != null && expectedGeneration !== String(runtime.routeGeneration)) return null;
      runtime.target = null;
      runtime.threadId = id || '';
      return String(++runtime.routeGeneration);
    };
    export const subscribeSession = () => ({remove() {}});
    export const watchConversationVisible = listener => {listener(true); return {remove() {}};};
    export const closeAgent = async () => {runtime.events.push(['close']);};
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

const target = {panelId: 'panel-a', originThreadId: 'thread-a', launchId: 'launch-a', title: 'Trip plan'};
const pendingKey = id => `taku.agent.pending.v1.${id}`;
const draftKey = id => `taku.agent.draft.v1.${id}`;
const accepted = (args, panelId) => ({threadId: args.threadId,
  requestId: args.requestId, state: 'queued', ...(panelId ? {panelId} : {})});
const deferred = () => {let resolve; const promise = new Promise(yes => {resolve = yes;}); return {promise, resolve};};

function reset(t) {
  Object.assign(runtime, {sequence: 0, storage: new Map(), drafts: new Map(), receipts: new Map(),
    events: [], calls: [], lookups: [], panelReads: [], completed: [], openListeners: new Set(), attachmentListeners: new Set(),
    target: {...target}, threadId: 'thread-a', routeGeneration: 0, sourceVersion: 3,
    mounts: [], receipt: async (args, panelId) => accepted(args, panelId)});
  runtime.callAgent = async (action, params = {}) => {
    const threadId = params.threadId;
    const snapshot = () => runtime.drafts.get(threadId) || {threadId, revision: '', text: '', attachments: []};
    if (action === 'panels' && params.operation === 'get') {
      runtime.panelReads.push(params.args.panelId);
      const panel = runtime.target;
      assert.equal(params.args.panelId, panel.panelId);
      return {panel: {...panel, matterId: `${panel.originThreadId}/card`, position: 0, metadataRevision: 0,
        sourceVersion: runtime.sourceVersion}, sourceState: {state: 'current'}, capabilities: {canOpen: true, canContinue: true}};
    }
    if (action === 'panels' && params.operation === 'open') {
      runtime.events.push(['open-panel', params.args.panelId]);
      return {accepted: true, panelId: params.args.panelId};
    }
    if (action === 'composer/read') return snapshot();
    if (action === 'composer/write' || action === 'composer/import') {
      const next = {...snapshot(), text: params.text, revision: `draft-${++runtime.sequence}`};
      runtime.drafts.set(threadId, next);
      return next;
    }
    if (action === 'composer/preserveDraft') {
      const current = snapshot();
      const recovery = current.recovery ?? [];
      const next = recovery.some(item => item.id === params.recoveryId) ? current : {...current, recovery: [...recovery,
        {id: params.recoveryId, text: params.text, status: 'saved-draft', attachments: []}]};
      runtime.drafts.set(threadId, next);
      return next;
    }
    if (action === 'composer/submit') {
      const args = {...params, text: snapshot().text};
      assert.equal(params.revision, snapshot().revision);
      runtime.calls.push(args);
      const receipt = await runtime.receipt(args, args.panelId);
      runtime.receipts.set(args.requestId, receipt);
      if (receipt.state === 'queued') {
        runtime.completed.push({...args, threadId: receipt.threadId});
        if (snapshot().revision === params.revision) runtime.drafts.set(threadId, {...snapshot(), text: '', revision: `draft-${++runtime.sequence}`});
      }
      return receipt;
    }
    if (action === 'composer/receipt') {
      runtime.lookups.push(params);
      if (params.action) return runtime.receipt(params, params.panelId);
      return runtime.receipts.get(params.requestId) || {threadId, requestId: params.requestId, state: 'missing'};
    }
    if (action === 'composer/settle') return runtime.receipts.get(params.requestId) || {threadId, requestId: params.requestId, state: 'missing'};
    if (action === 'listThreads') return {threads: [{id: 'thread-a'}, {id: 'thread-b'}]};
    if (action === 'readSession') return {native: {ready: true}, historyComplete: true,
      thread: {id: params.threadId, turns: runtime.completed.filter(item => item.threadId === params.threadId).map(item => ({
        id: `turn-${item.requestId}`, status: 'completed', items: [
          {id: `user-${item.requestId}`, type: 'userMessage', content: [{type: 'text', text: item.text}]},
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
function open(next, threadId = next?.originThreadId, announce = true) {
  runtime.target = next; runtime.threadId = threadId;
  const generation = ++runtime.routeGeneration;
  const deliver = () => {
    if (generation === runtime.routeGeneration) {
      for (const listener of runtime.openListeners) listener(threadId, next, undefined, generation);
    }
  };
  if (announce) deliver();
  return deliver;
}

test('each follow-up uses the current Panel source across accepted sends and remounts', async t => {
  reset(t);
  const first = mount(); await flush(first);
  assert.equal(first.result.panel.title, 'Trip plan');
  await send(first, 'Update the panel');
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  await flush(first);
  runtime.sourceVersion = 4;
  runtime.target.title = 'Renamed trip plan';
  await send(first, 'Update it again');
  assert.equal(first.result.panel.title, 'Renamed trip plan');
  first.unmount();
  const restored = mount(); await flush(restored);
  runtime.sourceVersion = 5;
  await send(restored, 'A third edit');
  assert.deepEqual(runtime.calls.map(({action, panelId, expectedSourceVersion}) => [action, panelId, expectedSourceVersion]),
    [['continue', 'panel-a', 3], ['continue', 'panel-a', 4], ['continue', 'panel-a', 5]]);
  assert.deepEqual(runtime.panelReads, ['panel-a', 'panel-a', 'panel-a']);
});

test('reconciling an accepted request uses its stored version and keeps the Panel scope', async t => {
  reset(t);
  runtime.sourceVersion = 4;
  const pending = {threadId: 'thread-a', requestId: 'interrupted-request', text: 'Update before restart', action: 'continue',
    panel: {panelId: 'panel-a', expectedSourceVersion: 3}, baselineBlockIds: [], baselineTurnIds: []};
  runtime.storage.set(pendingKey('thread-a'), JSON.stringify(pending));
  runtime.storage.set(draftKey('thread-a'), pending.text);
  const restored = mount(); await flush(restored);
  assert.deepEqual(runtime.calls, []);
  assert.deepEqual(runtime.panelReads, []);
  assert.equal(runtime.lookups[0].requestId, pending.requestId);
  assert.equal(runtime.lookups[0].expectedSourceVersion, 3);
  assert.equal(restored.result.draft, '');
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  await send(restored, 'Another edit');
  assert.equal(runtime.calls[0].action, 'continue');
  assert.equal(runtime.calls[0].expectedSourceVersion, 4);
});

test('the Panel title opens only the scoped Panel and preserves the draft and scope', async t => {
  reset(t);
  const host = mount(); await flush(host);
  assert.equal(host.result.onDraftChange('Still editing'), true);
  host.result.onOpenPanel(); await flush(host);
  assert.deepEqual(runtime.events, [['open-panel', 'panel-a']]);
  assert.equal(host.result.draft, 'Still editing');
  assert.equal(host.result.panel.title, 'Trip plan');
});

for (const [entry, navigate] of [
  ['history selection', host => host.result.onSelectThread('thread-a')],
  ['a native thread intent', () => open(null, 'thread-a')],
]) {
  test(`${entry} clears the Panel scope for the same thread across remount`, async t => {
    reset(t);
    const first = mount(); await flush(first);
    navigate(first); await flush(first);
    assert.equal(first.result.onOpenPanel, undefined);
    first.unmount();
    const restored = mount(); await flush(restored);
    assert.equal(restored.result.panel, null);
    await send(restored, 'Ordinary follow-up');
    assert.equal(runtime.calls[0].action, 'sendMessage');
    assert.deepEqual(runtime.panelReads, []);
  });
}

const otherPanel = {panelId: 'panel-b', originThreadId: 'thread-a', launchId: 'launch-b', title: 'Packing list'};

for (const announce of [true, false]) {
  test(`a new chat cannot overwrite a newer native Panel route ${announce ? 'with a queued event' : 'before its event'}`, async t => {
    reset(t);
    const creation = deferred();
    const gateway = runtime.callAgent;
    runtime.callAgent = (action, params) => action === 'createThread' ? creation.promise : gateway(action, params);
    const host = mount(); await flush(host);

    host.result.onCreateThread();
    const deliver = open(otherPanel, otherPanel.originThreadId, announce);
    creation.resolve({thread: {id: 'created-thread'}});
    await flush(host);
    assert.equal(host.result.draftSessionId, otherPanel.originThreadId);
    if (!announce) deliver();
    await flush(host);

    assert.equal(host.result.panel?.title, otherPanel.title);
    host.unmount();
    const restored = mount(); await flush(restored);
    assert.equal(restored.result.panel?.title, otherPanel.title);
  });
}

test('a new chat selects the created thread if the native route has not changed', async t => {
  reset(t);
  const gateway = runtime.callAgent;
  runtime.callAgent = (action, params) => action === 'createThread' ? Promise.resolve({thread: {id: 'created-thread'}}) : gateway(action, params);
  const host = mount(); await flush(host);
  host.result.onCreateThread(); await flush(host);
  assert.equal(host.result.draftSessionId, 'created-thread');
  assert.equal(host.result.panel, null);
  assert.equal(runtime.threadId, 'created-thread');
});

test('an accepted old request cannot clear the newer scope for another Panel in the same thread', async t => {
  reset(t);
  const gate = deferred(); runtime.receipt = () => gate.promise;
  const host = mount(); await flush(host);
  await send(host, 'The older panel edit');
  const sent = runtime.calls[0];
  open(otherPanel); await flush(host);
  gate.resolve(accepted(sent, 'panel-a')); await flush(host);
  assert.equal(host.result.panel.title, 'Packing list');
  runtime.receipt = async (args, panelId) => accepted(args, panelId);
  await send(host, 'Edit the newly selected panel');
  assert.deepEqual(runtime.calls.map(call => call.panelId), ['panel-a', 'panel-b']);
});

test('a Panel detail returned after navigation cannot submit or update the newer header', async t => {
  reset(t);
  const host = mount(); await flush(host);
  const gate = deferred();
  const gateway = runtime.callAgent;
  runtime.callAgent = async (action, params) => {
    const result = await gateway(action, params);
    if (action === 'panels' && params.operation === 'get' && params.args.panelId === 'panel-a') await gate.promise;
    return result;
  };
  await send(host, 'Draft for the previous Panel');
  assert.deepEqual(runtime.panelReads, ['panel-a']);
  open(otherPanel);
  gate.resolve(); await flush(host);
  assert.deepEqual(runtime.calls, []);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  assert.equal(host.result.panel.title, 'Packing list');
  assert.equal(host.result.draft, 'Draft for the previous Panel');
  await send(host, 'Draft for the new Panel');
  assert.deepEqual(runtime.calls.map(call => call.panelId), ['panel-b']);
});

test('retry replays the persisted Panel request even after its source and the scope change', async t => {
  reset(t);
  const {AgentGatewayError} = await import('../native/agent');
  runtime.receipt = async () => {throw new AgentGatewayError('Timed out', 'GATEWAY_UNAVAILABLE');};
  const host = mount(); await flush(host);
  await send(host, 'Preserve the Panel edit');
  const first = runtime.calls[0];
  assert.equal(first.expectedSourceVersion, 3);
  assert.deepEqual(JSON.parse(runtime.storage.get(pendingKey('thread-a'))).panel, {panelId: 'panel-a', expectedSourceVersion: 3});
  runtime.sourceVersion = 4;
  open(otherPanel); await flush(host);
  runtime.receipt = async (args, panelId) => accepted(args, panelId);
  host.result.onRetrySubmission(); await flush(host);
  assert.deepEqual(runtime.calls, [first, first]);
  assert.deepEqual(runtime.panelReads, ['panel-a']);
  assert.equal(host.result.panel.title, 'Packing list');
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
});

test('an unresolved legacy draft stays out of the composer until its receipt confirms failure', async t => {
  reset(t);
  const pending = {threadId: 'thread-a', requestId: 'legacy-send', text: 'Unconfirmed draft', action: 'sendMessage'};
  runtime.storage.set(pendingKey(pending.threadId), JSON.stringify(pending));
  runtime.storage.set(draftKey(pending.threadId), pending.text);
  runtime.receipt = async () => ({...pending, state: 'missing'});
  const host = mount(); await flush(host);
  assert.equal(host.result.draft, '');
  runtime.receipt = async () => ({...pending, state: 'notSent', code: 'MODEL_UNAVAILABLE'});
  host.result.onRetrySubmission(); await flush(host);
  assert.equal(host.result.draft, pending.text);
  assert.equal(runtime.storage.has(pendingKey(pending.threadId)), false);
  assert.deepEqual(runtime.calls, []);
});

test('an accepted legacy receipt preserves a different follow-up draft', async t => {
  reset(t);
  const pending = {threadId: 'thread-a', requestId: 'legacy-send', text: 'Already delivered', action: 'sendMessage'};
  const followup = 'A new unsent edit';
  runtime.storage.set(pendingKey(pending.threadId), JSON.stringify(pending));
  runtime.storage.set(draftKey(pending.threadId), followup);
  const host = mount(); await flush(host);
  assert.equal(host.result.draft, followup);
  assert.deepEqual(runtime.calls, []);
});

test('an archived unconfirmed message survives an interrupted migration without a resend', async t => {
  reset(t);
  const requestId = '85036b24-6662-4b42-9da2-de71f7a30d04';
  const key = `taku.agent.unconfirmed.v1.thread-a.${requestId}`;
  const text = 'Keep the unconfirmed message after upgrading';
  runtime.storage.set(key, JSON.stringify({threadId: 'thread-a', requestId, text}));
  const gateway = runtime.callAgent;
  runtime.callAgent = async (action, params) => {
    const result = await gateway(action, params);
    if (action === 'composer/preserveDraft') throw new Error('Reply was lost after persistence');
    return result;
  };
  const first = mount(); await flush(first);
  assert.equal(runtime.storage.has(key), true);
  first.unmount();
  runtime.callAgent = gateway;
  const restored = mount(); await flush(restored);
  assert.equal(runtime.storage.has(key), false);
  assert.deepEqual(runtime.drafts.get('thread-a')?.recovery.map(item => [item.id, item.text]), [[requestId, text]]);
  assert.deepEqual(runtime.calls, []);
});

test('a recovered attachment event reloads the current saved draft and unsubscribes on unmount', async t => {
  reset(t);
  const host = mount(); await flush(host);
  const attachment = {id: 'photo-a', name: 'Photo.jpg', mime: 'image/jpeg', size: 1234};
  runtime.drafts.set('thread-a', {threadId: 'thread-a', revision: 'after-picker', text: 'Saved caption', attachments: [attachment]});
  assert.equal(runtime.attachmentListeners.size, 1);
  for (const listener of runtime.attachmentListeners) listener('thread-a');
  await flush(host);
  assert.equal(host.result.draft, 'Saved caption');
  assert.equal(host.result.draftRevision, 'after-picker');
  assert.deepEqual(host.result.attachments, [attachment]);
  host.unmount();
  assert.equal(runtime.attachmentListeners.size, 0);
});

test('an attachment event for another conversation does not reload the selected draft', async t => {
  reset(t);
  const host = mount(); await flush(host);
  const reads = [];
  const callAgent = runtime.callAgent;
  runtime.callAgent = async (action, params) => {
    if (action === 'composer/read') reads.push(params.threadId);
    return callAgent(action, params);
  };
  for (const listener of runtime.attachmentListeners) listener('thread-b');
  await flush(host);
  assert.deepEqual(reads, []);
  assert.equal(host.result.draft, '');
  assert.deepEqual(host.result.attachments, []);
});

test('a refusal before reservation reports not sent whatever its code and keeps the draft', async t => {
  reset(t); runtime.target = null;
  const {AgentGatewayError} = await import('../native/agent');
  runtime.receipt = async () => {throw new AgentGatewayError('Busy', 'BUSY');};
  const host = mount(); await flush(host);
  await send(host, 'Send while busy');
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  assert.equal(host.result.draft, 'Send while busy');
  runtime.receipt = async (args, panelId) => accepted(args, panelId);
  await send(host, 'Send while busy');
  assert.equal(runtime.completed.length, 1);
  assert.notEqual(runtime.calls[0].requestId, runtime.calls[1].requestId);
});

test('a transport failure before reservation stays unconfirmed and retry resubmits the same request', async t => {
  reset(t); runtime.target = null;
  const {AgentGatewayError} = await import('../native/agent');
  runtime.receipt = async () => {throw new AgentGatewayError('Timed out', 'GATEWAY_UNAVAILABLE');};
  const host = mount(); await flush(host);
  await send(host, 'Send over a flaky link');
  const stored = JSON.parse(runtime.storage.get(pendingKey('thread-a')));
  runtime.receipt = async (args, panelId) => accepted(args, panelId);
  host.result.onRetrySubmission(); await flush(host);
  assert.deepEqual(runtime.calls.map(call => call.requestId), [stored.requestId, stored.requestId]);
  assert.equal(runtime.completed.length, 1);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
});
