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
    getItem: async key => runtime.storage.get(key) || null, // as the library: an empty string reads back as null
    getAllKeys: async () => [...runtime.storage.keys()],
    setItem: async (key, value) => {runtime.storage.set(key, value);},
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
    export const setHandoffCandidate = report => {runtime.handoffs.push(report.threadId || null); runtime.sending = report.sending;};
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

const target = {panelId: 'panel-a', sourceVersion: 3, originThreadId: 'thread-a', launchId: 'launch-a'};
const pendingKey = id => `taku.agent.pending.v1.${id}`;
const draftKey = id => `taku.agent.draft.v1.${id}`;
const accepted = (args, panelId) => ({threadId: panelId === 'panel-b' ? 'thread-b' : 'thread-a',
  requestId: args.requestId, state: 'queued', ...(panelId ? {panelId} : {})});
const deferred = () => {let resolve; const promise = new Promise(yes => {resolve = yes;}); return {promise, resolve};};

function reset(t) {
  Object.assign(runtime, {sequence: 0, storage: new Map(), drafts: new Map(), receipts: new Map(), consumed: new Set(),
    events: [], calls: [], lookups: [], completed: [], handoffs: [], openListeners: new Set(), attachmentListeners: new Set(), target: {...target}, threadId: 'thread-a',
    failConsumption: false, mounts: [], receipt: async (args, panelId) => accepted(args, panelId)});
  runtime.callAgent = async (action, params = {}) => {
    const threadId = params.threadId;
    const snapshot = () => runtime.drafts.get(threadId) || {threadId, revision: '', text: '', attachments: []};
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
    if (action === 'panels' && params.operation === 'get') {
      const panel = {panelId: params.args.panelId, originThreadId: 'thread-a', matterId: 'thread-a/card', sourceVersion: runtime.panelVersion ?? 3,
        metadataRevision: 1, title: 'Plan', position: 0};
      return {panel, sourceState: {state: 'current'}, updateReceipt: runtime.panelUpdate ?? null, capabilities: {canOpen: true, canContinue: true, canUnpin: true}, listRevision: 1};
    }
    if (action === 'composer/plugin/add' || action === 'composer/plugin/remove') {
      const current = snapshot();
      assert.equal(params.expectedRevision, current.revision);
      const others = (current.plugins ?? []).filter(plugin => plugin.id !== params.pluginId);
      const plugins = action === 'composer/plugin/add' ? [...others, {id: params.pluginId, name: params.pluginId.toUpperCase()}] : others;
      const next = {...current, plugins, revision: `draft-${++runtime.sequence}`};
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
    if (action === 'createThread') { runtime.events.push(['createThread']); return {thread: {id: 'thread-new'}}; }
    if (action === 'composer/move') {
      const moved = {...snapshot(), threadId: params.targetThreadId, revision: `draft-${++runtime.sequence}`};
      runtime.drafts.delete(threadId); runtime.drafts.set(params.targetThreadId, moved);
      return moved;
    }
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

test('a reconciled accepted pending continuation consumes its original launch after restart', async t => {
  reset(t);
  runtime.target.sourceVersion = 4;
  const pending = {threadId: 'thread-a', requestId: 'interrupted-request', text: 'Update before restart', action: 'continue',
    panel: {panelId: 'panel-a', expectedSourceVersion: 3, launchId: 'launch-a'}, baselineBlockIds: [], baselineTurnIds: []};
  runtime.storage.set(pendingKey('thread-a'), JSON.stringify(pending));
  runtime.storage.set(draftKey('thread-a'), pending.text);
  const restored = mount(); await flush(restored);
  assert.equal(runtime.calls.length, 0);
  assert.equal(runtime.lookups[0].requestId, pending.requestId);
  assert.equal(runtime.lookups[0].expectedSourceVersion, 3);
  assert.equal(runtime.consumed.has('launch-a'), true);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  await send(restored, 'A later ordinary turn');
  assert.equal(runtime.calls.at(-1).action, 'sendMessage');
});

test('a send in flight names its conversation for the island handoff, and an idle one clears it', async t => {
  reset(t); runtime.target = null;
  const gate = deferred(); runtime.receipt = () => gate.promise;
  const host = mount(); await flush(host);
  assert.equal(runtime.handoffs.at(-1), null);
  assert.equal(host.result.onDraftChange('Plan the trip'), true); await flush(host);
  host.result.onSend(); await flush(host);
  assert.equal(runtime.handoffs.at(-1), 'thread-a');
  assert.equal(runtime.sending, true, 'native knows a send is in flight');
  gate.resolve(accepted(runtime.calls[0])); await flush(host);
  runtime.completed.push({threadId: 'thread-a', requestId: runtime.calls[0].requestId, text: 'Plan the trip'});
  host.result.onRefresh(); await flush(host);
  assert.equal(runtime.handoffs.at(-1), null);
  assert.equal(runtime.sending, false);
  host.unmount();
  assert.equal(runtime.handoffs.at(-1), null);
});

test('New task creates a separate conversation without changing TAKU', async t => {
  plainChat(t);
  runtime.storage.set(primaryKey, 'thread-a');
  const host = mount(); await flush(host);
  assert.equal(host.result.draftSessionId, 'thread-a');
  host.result.onCreateTaskThread(); await flush(host);
  assert.deepEqual([host.result.primaryThreadId, host.result.draftSessionId, host.result.isMainChat], ['thread-a', 'thread-new', false]);
  assert.equal(runtime.threadId, 'thread-new', 'native selection follows');
});

test('New task during a send opens once the send ends instead of being dropped', async t => {
  reset(t); runtime.target = null;
  const gate = deferred(); runtime.receipt = () => gate.promise;
  const host = mount(); await flush(host);
  assert.equal(host.result.onDraftChange('Plan the trip'), true); await flush(host);
  host.result.onSend(); await flush(host);
  host.result.onCreateTaskThread(); await flush(host);
  assert.equal(host.result.draftSessionId, 'thread-a', 'the send keeps its conversation until it is delivered');
  assert.equal(runtime.events.some(([name]) => name === 'createThread'), false);
  gate.resolve(accepted(runtime.calls[0])); await flush(host);
  assert.equal(host.result.draftSessionId, 'thread-new');
  assert.equal(runtime.threadId, 'thread-new');
});

test('every outside open is counted, reopening the selected conversation included', async t => {
  reset(t); runtime.target = null;
  const host = mount(); await flush(host);
  assert.equal(host.result.draftSessionId, 'thread-a');
  assert.equal(host.result.externalOpens, 0);
  // The island card reopens the conversation that is already selected: no thread change to watch.
  runtime.threadId = 'thread-a';
  for (const listener of runtime.openListeners) listener('thread-a', null);
  await flush(host);
  assert.equal(host.result.draftSessionId, 'thread-a');
  assert.equal(host.result.externalOpens, 1);
  // A refused Panel route still asks the chat to come forward and show its error.
  for (const listener of runtime.openListeners) listener('thread-a', null, new Error('Panel target unavailable'));
  await flush(host);
  assert.equal(host.result.externalOpens, 2);
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
    if (state === 'deliveryUnknown') {
      const request = runtime.calls[0];
      runtime.receipts.set(request.requestId, accepted(request, request.panelId));
    }
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
  assert.equal(runtime.calls.length, 0);
  assert.equal(runtime.lookups[0].requestId, pending.requestId);
  assert.equal(restored.result.draft, '');
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  await send(restored, 'An ordinary message after cleanup');
  assert.equal(runtime.calls.at(-1).action, 'sendMessage');
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

const primaryKey = 'taku.agent.primaryThread.v1';
/** The chat opened by itself (no Panel, no island route), with a gateway that can create a thread. */
function plainChat(t) {
  reset(t);
  runtime.target = null; runtime.threadId = '';
  const gateway = runtime.callAgent;
  runtime.callAgent = async (action, params = {}) => {
    if (action === 'createThread') return {thread: {id: 'thread-new'}};
    if (action === 'composer/move') {
      const source = runtime.drafts.get(params.threadId) || {threadId: params.threadId, revision: '', text: '', attachments: []};
      const moved = {...source, threadId: params.targetThreadId, revision: `draft-${++runtime.sequence}`};
      runtime.drafts.delete(params.threadId);
      runtime.drafts.set(params.targetThreadId, moved);
      return moved;
    }
    return gateway(action, params);
  };
}

test('TAKU carries on in a new conversation: the old one becomes a Task and the first message claims the new thread', async t => {
  plainChat(t);
  runtime.storage.set(primaryKey, 'thread-a');
  const host = mount(); await flush(host);
  assert.deepEqual([host.result.primaryThreadId, host.result.draftSessionId, host.result.isMainChat], ['thread-a', 'thread-a', true]);
  host.result.onStartMainAfresh('Plan my week');
  await flush(host);
  assert.deepEqual([host.result.primaryThreadId, host.result.draftSessionId, host.result.isMainChat], ['', '', true]);
  assert.equal(host.result.draft, 'Plan my week', 'the request that failed comes along as a draft');
  assert.equal(runtime.storage.get(primaryKey), 'none', 'recorded, so a restart does not promote a Task');
  assert.equal(runtime.calls.length, 0, 'nothing is sent for the user');
  host.result.onSend();
  await flush(host);
  assert.deepEqual([runtime.calls.at(-1).threadId, runtime.calls.at(-1).text], ['thread-new', 'Plan my week']);
  assert.deepEqual([host.result.primaryThreadId, host.result.draftSessionId, host.result.isMainChat], ['thread-new', 'thread-new', true]);
  assert.equal(runtime.storage.get(primaryKey), 'thread-new');
  assert.equal(runtime.threadId, 'thread-new', 'native follows the created conversation');
});

test('a draft already waiting in the new conversation is kept', async t => {
  plainChat(t);
  runtime.storage.set(primaryKey, 'thread-a');
  runtime.drafts.set('', {threadId: '', revision: 'draft-kept', text: 'Already here', attachments: []});
  const host = mount(); await flush(host);
  host.result.onStartMainAfresh('Plan my week');
  await flush(host);
  assert.equal(host.result.draft, 'Already here');
  assert.equal(runtime.drafts.get('').text, 'Already here');
});

test('Continue afresh keeps a failed menu photo and its hidden OCR through editing and sending', async t => {
  plainChat(t);
  runtime.storage.set(primaryKey, 'thread-a');
  const suffix = '\n\n[taku.menu-context/1]\n{"dishes":[{"name":"Pastrami"}]}';
  const prompt = 'BUILD A MENU APP' + suffix;
  const photo = {id: 'menu-photo', name: 'menu.jpg', mime: 'image/jpeg', size: 42};
  const gateway = runtime.callAgent;
  runtime.receipt = async ({threadId, requestId}) => ({threadId, requestId, state: 'queued'});
  runtime.callAgent = async (action, params = {}) => {
    if (action === 'readSession' && params.threadId === 'thread-a') {
      return {...await gateway(action, params), request: {prompt}, task: {id: '123', state: 'failed'}};
    }
    if (action === 'composer/restoreTask') {
      assert.deepEqual(params, {threadId: 'thread-a', expectedRevision: '', taskId: '123', afresh: true});
      const stored = {threadId: '', revision: 'restored', text: prompt, attachments: [photo]};
      runtime.drafts.set('', stored);
      return stored;
    }
    return gateway(action, params);
  };
  const host = mount(); await flush(host);
  host.result.onStartMainAfresh(host.result.session.taskFailure.prompt, '123'); await flush(host);
  assert.equal(host.result.draftSessionId, '');
  assert.equal(host.result.draft, 'Build a menu app');
  assert.deepEqual(host.result.attachments, [photo]);
  host.result.onDraftChange('Build an ordering app'); await flush(host);
  assert.equal(host.result.draft, 'Build an ordering app');
  host.result.onSend(); await flush(host);
  assert.equal(runtime.calls.at(-1).threadId, 'thread-new');
  assert.equal(runtime.calls.at(-1).text, 'Build an ordering app' + suffix);
  assert.deepEqual(runtime.calls.at(-1).attachmentIds, [photo.id]);
});

test('while TAKU has no thread, a Task stays a Task and going back opens the empty conversation', async t => {
  plainChat(t);
  runtime.storage.set(primaryKey, 'thread-a');
  const host = mount(); await flush(host);
  host.result.onStartMainAfresh();
  await flush(host);
  host.result.onSelectThread('thread-b');
  await flush(host);
  assert.deepEqual([host.result.draftSessionId, host.result.isMainChat], ['thread-b', false]);
  host.result.onSelectMainChat();
  await flush(host);
  assert.deepEqual([host.result.primaryThreadId, host.result.draftSessionId, host.result.isMainChat], ['', '', true]);
  host.unmount();
  // After a restart TAKU is still on its empty conversation.
  const restarted = mount(); await flush(restarted);
  assert.deepEqual([restarted.result.primaryThreadId, restarted.result.draftSessionId, restarted.result.isMainChat], ['', '', true]);
});

test('plugins attached with @ live in the native draft and ride with the submission', async t => {
  reset(t); runtime.target = null;
  const host = mount(); await flush(host);
  assert.equal(host.result.onDraftChange('Design a launch card'), true); await flush(host);
  host.result.onAddPlugin('figma'); await flush(host);
  host.result.onAddPlugin('notion'); await flush(host);
  host.result.onRemovePlugin('notion'); await flush(host);
  assert.deepEqual(host.result.plugins, [{id: 'figma', name: 'FIGMA'}]);
  assert.equal(host.result.draft, 'Design a launch card');
  host.result.onSend(); await flush(host);
  assert.equal(runtime.calls.length, 1);
  assert.deepEqual(runtime.calls[0].pluginIds, ['figma']);
  assert.equal(runtime.calls[0].text, 'Design a launch card');
});

test('a message without plugins sends no plugin ids', async t => {
  reset(t); runtime.target = null;
  const host = mount(); await flush(host);
  await send(host, 'Plain request');
  assert.equal(runtime.calls.length, 1);
  assert.equal('pluginIds' in runtime.calls[0], false);
  assert.deepEqual(host.result.plugins, []);
});

test('a further edit after an accepted continuation keeps updating the same Panel at its new version', async t => {
  reset(t);
  const host = mount(); await flush(host);
  await send(host, 'Set Count to 2');
  assert.equal(runtime.calls[0].action, 'continue');
  assert.equal(runtime.calls[0].expectedSourceVersion, 3);
  assert.equal(host.result.panelTarget?.panelId, 'panel-a');
  runtime.panelVersion = 4;
  await send(host, 'Now set Count to 3');
  assert.equal(runtime.calls[1].action, 'continue');
  assert.equal(runtime.calls[1].panelId, 'panel-a');
  assert.equal(runtime.calls[1].expectedSourceVersion, 4);
  host.result.onDetachPanel(); await flush(host);
  await send(host, 'An ordinary message');
  assert.equal(runtime.calls[2].action, 'sendMessage');
  assert.equal(host.result.panelTarget, null);
});

test('a further Panel edit waits for the previous update and refuses while it is still applying', async t => {
  reset(t);
  const host = mount(); await flush(host);
  await send(host, 'Set Count to 2');
  runtime.panelUpdate = {outcome: 'pending'};
  const realTimeout = globalThis.setTimeout;
  globalThis.setTimeout = (fn, _ms, ...rest) => realTimeout(fn, 0, ...rest);
  t.after(() => { globalThis.setTimeout = realTimeout; });
  await send(host, 'Now set Count to 3');
  assert.equal(runtime.calls.length, 1);
  assert.equal(host.result.draft, 'Now set Count to 3');
});
