import test from 'node:test';
import assert from 'node:assert/strict';
import {registerHooks} from 'node:module';
import {setImmediate as nextTurn} from 'node:timers/promises';

const runtime = {current: null, native: null};
globalThis[Symbol.for('taku.live-talk.lifecycle-test')] = runtime;
const mock = source => `data:text/javascript,${encodeURIComponent(`const runtime = globalThis[Symbol.for('taku.live-talk.lifecycle-test')];\n${source}`)}`;
const hookUrl = new URL('./useLiveTalk.ts', import.meta.url).href;
const react = mock(`
  export const useRef = initial => runtime.current.ref(initial);
  export const useState = initial => runtime.current.state(initial);
  export const useCallback = (callback, dependencies) => runtime.current.memo(callback, dependencies);
  export const useEffect = (effect, dependencies) => runtime.current.effect(effect, dependencies);
`);
const expo = mock('export const requireOptionalNativeModule = () => new Proxy({}, {get: (_target, key) => runtime.native[key]});');
registerHooks({resolve(specifier, context, nextResolve) {
  if (context.parentURL === hookUrl && specifier === 'react') return {url: react, shortCircuit: true};
  if (context.parentURL === hookUrl && specifier === 'expo') return {url: expo, shortCircuit: true};
  return nextResolve(specifier, context);
}});
const {useLiveTalk} = await import('./useLiveTalk.ts');
const same = (a, b) => a.length === b.length && a.every((value, index) => Object.is(value, b[index]));
let sequence = 0;
const snapshot = (threadId, active = true, revision = ++sequence, sessionId = 'live-1') => ({liveTalk: {
  sessionId, sequence: revision, threadId, lifecycle: active ? 'active' : 'closed', provider: 'fal-grok', muted: false,
  capturing: true, levels: [], playback: {state: 'stopped'}, input: null, output: null, task: null, error: null,
}});

function environment(t, threadId = '') {
  const actions = [], selected = [], listeners = new Set();
  let current = snapshot(null, false), initialResolve;
  const initial = new Promise(resolve => { initialResolve = resolve; });
  runtime.native = {
    liveState: () => initial,
    liveAction: async (method, params) => { actions.push([method, JSON.parse(params)]); return '{"ok":true}'; },
    addListener: (_event, listener) => { listeners.add(listener); return {remove: () => listeners.delete(listener)}; },
  };
  const select = id => { selected.push(id); host.threadId = id; host.dirty = true; };
  const host = {
    threadId, loading: false, slots: [], queued: [], index: 0, dirty: true, result: null,
    ref(initialValue) { return this.slots[this.index++] ??= {current: initialValue}; },
    state(initialValue) {
      const index = this.index++;
      const slot = this.slots[index] ??= {value: initialValue, set: value => { slot.value = value; this.dirty = true; }};
      return [slot.value, slot.set];
    },
    memo(value, dependencies) {
      const index = this.index++, previous = this.slots[index];
      if (previous && same(previous.dependencies, dependencies)) return previous.value;
      this.slots[index] = {value, dependencies}; return value;
    },
    effect(effect, dependencies) {
      const index = this.index++, previous = this.slots[index];
      if (previous && same(previous.dependencies, dependencies)) return;
      const slot = this.slots[index] = {dependencies, cleanup: previous?.cleanup};
      this.queued.push(() => { slot.cleanup?.(); slot.cleanup = effect(); });
    },
    render: function useLiveTalkRender() {
      this.index = 0; this.dirty = false; runtime.current = this;
      this.result = useLiveTalk(this.threadId, this.loading, select);
      runtime.current = null;
      for (const effect of this.queued.splice(0)) effect();
    },
    remove() { for (const slot of this.slots) slot.cleanup?.(); },
  };
  host.render(); t.after(() => host.remove());
  return {
    host, actions, selected, listeners,
    emit(value) { current = value; for (const listener of listeners) listener({json: JSON.stringify(current)}); },
    initial(value) { initialResolve(JSON.stringify(value)); },
    async settle() {
      for (let i = 0; i < 10; i++) { await nextTurn(); if (!host.dirty) return; host.render(); }
      assert.fail('Live Talk hook did not settle');
    },
  };
}

test('empty chat survives null thread and adopts its first lazily created conversation without stopping', async t => {
  const env = environment(t);
  await env.host.result.start();
  const connecting = snapshot(null);
  env.emit({liveTalk: {...connecting.liveTalk, lifecycle: 'connecting', provider: null}}); await env.settle();
  assert.equal(env.host.result.active, true);
  assert.equal(env.host.result.state.provider, null);
  assert.deepEqual(env.actions, [['assistant/live-start', {threadId: ''}]]);
  const connected = snapshot('first-thread');
  env.emit({liveTalk: {...connected.liveTalk, provider: 'gemini-live'}}); await env.settle();
  assert.equal(env.host.result.state.provider, 'gemini-live');
  assert.deepEqual(env.selected, ['first-thread']);
  assert.equal(env.host.result.active, true);
  assert.equal(env.actions.length, 1);
});

test('mounted old chat cannot cancel or redirect the native Keep talking handoff', async t => {
  const env = environment(t, 'old-thread');
  env.emit(snapshot(null)); await env.settle();
  env.emit(snapshot('new-thread')); await env.settle();
  assert.deepEqual(env.actions, []);
  assert.deepEqual(env.selected, []);
  // Native onOpenThread arrives after the 820ms transition.
  env.host.threadId = 'new-thread'; env.host.render(); await env.settle();
  assert.equal(env.host.result.active, true);
  assert.deepEqual(env.actions, []);
});

test('explicit stop ends Live Talk while unmount only removes its subscription', async t => {
  const env = environment(t, 'current');
  env.emit(snapshot('current')); await env.settle();
  env.host.result.stop(); await env.settle();
  assert.deepEqual(env.actions, [['assistant/live-stop', {sessionId: 'live-1'}]]);
  env.host.remove(); await env.settle();
  assert.equal(env.listeners.size, 0);
  assert.equal(env.actions.length, 1);
});

test('delayed initial state cannot overwrite a native handoff event', async t => {
  const env = environment(t, 'old');
  const initial = snapshot('old', false);
  env.emit(snapshot('new')); await env.settle();
  env.initial(initial); await env.settle();
  assert.equal(env.host.result.state.threadId, 'new');
  assert.equal(env.host.result.active, false);
  assert.deepEqual(env.actions, []);
});

test('inactive and loading empty chats do not adopt stale conversations', async t => {
  const env = environment(t);
  env.emit(snapshot('old', false)); await env.settle();
  assert.deepEqual(env.selected, []);
  env.host.loading = true; env.host.render();
  env.emit(snapshot('new')); await env.settle();
  assert.deepEqual(env.selected, []);
  env.host.loading = false; env.host.render(); await env.settle();
  assert.deepEqual(env.selected, ['new']);
});

test('switching conversations keeps Live Talk running for its own thread', async t => {
  const env = environment(t, 'current');
  env.emit(snapshot('current')); await env.settle();
  await env.host.result.toggleMic();
  env.host.threadId = 'next'; env.host.render(); await env.settle();
  assert.equal(env.host.result.active, false);
  // Another conversation cannot stop or mute a session it does not own.
  env.host.result.stop(); await env.host.result.toggleMic(); await env.settle();
  assert.deepEqual(env.actions, [['assistant/live-set-mic', {sessionId: 'live-1', muted: true}]]);
  env.host.threadId = 'current'; env.host.render(); await env.settle();
  assert.equal(env.host.result.active, true);
  assert.equal(env.host.result.state.sessionId, 'live-1');
});

test('a late closed snapshot cannot overwrite a newer session', async t => {
  const env = environment(t, 'current');
  const previous = snapshot('current', false, undefined, 'old');
  env.emit(snapshot('current', true, undefined, 'new')); await env.settle();
  env.emit(previous); await env.settle();
  env.host.result.stop(); await env.settle();
  assert.deepEqual(env.actions, [['assistant/live-stop', {sessionId: 'new'}]]);
});
