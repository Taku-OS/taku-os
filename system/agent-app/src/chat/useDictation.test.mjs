import test from 'node:test';
import assert from 'node:assert/strict';
import {registerHooks} from 'node:module';
import {setImmediate as nextTurn} from 'node:timers/promises';

const runtime = {current: null, native: null};
globalThis[Symbol.for('taku.dictation.lifecycle-test')] = runtime;
const mockModule = source => `data:text/javascript,${encodeURIComponent(`const runtime = globalThis[Symbol.for('taku.dictation.lifecycle-test')];\n${source}`)}`;
const hookUrl = new URL('./useDictation.ts', import.meta.url).href;
const nativeUrl = new URL('../native/voice.ts', import.meta.url).href;
const react = mockModule(`
  export const useRef = initial => runtime.current.ref(initial);
  export const useState = initial => runtime.current.state(initial);
  export const useCallback = (callback, dependencies) => runtime.current.memo(callback, dependencies);
  export const useEffect = (effect, dependencies) => runtime.current.effect(effect, dependencies);
`);
const expo = mockModule('export const requireOptionalNativeModule = () => new Proxy({}, {get: (_target, key) => runtime.native[key]});');
registerHooks({resolve(specifier, context, nextResolve) {
  if (context.parentURL === hookUrl) {
    if (specifier === 'react') return {url: react, shortCircuit: true};
    if (specifier.startsWith('.')) return nextResolve(`${specifier}.ts`, context);
  }
  if (context.parentURL === nativeUrl && specifier === 'expo') return {url: expo, shortCircuit: true};
  return nextResolve(specifier, context);
}});
const {useDictation} = await import('./useDictation.ts');

const snapshot = (operationId, phase, {text = '', outcome = null, levels = null, threadId = 'thread', owner = 'chat'} = {}) => ({
  owner, threadId, operationId, phase, active: phase !== 'Idle', sessionId: null,
  listening: ['Preparing', 'Capturing', 'Finalizing'].includes(phase), ready: phase === 'Capturing',
  transcript: text, preview: text, levels: levels ?? (phase === 'Capturing' ? [0.2, 0.6] : []),
  levelSource: 'pcm-rms', final: phase === 'Closed',
  error: outcome?.kind === 'Failed' ? outcome.code : null, outcome, session: null,
});
const success = (id, text) => snapshot(id, 'Closed', {text, outcome: {kind: 'Success', text}});
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
};
const same = (left, right) => left.length === right.length && left.every((value, index) => Object.is(value, right[index]));

function environment(t) {
  const requests = [], listeners = new Set(), mounts = new Set();
  const env = {
    requests, current: null, available: true,
    emit(value) { for (const listener of listeners) listener(value); },
    capture(id, text = '', levels = null) {
      const value = snapshot(id, 'Capturing', {text, levels});
      if (env.current?.operationId === id) env.current = value;
      env.emit(value);
    },
    close(value) {
      if (env.current?.operationId === value.operationId) env.current = value;
      env.emit(value);
    },
    mount(threadId = 'thread', owner = 'chat') {
      const activeOwner = {current: owner};
      const heard = [], failures = [];
      const onHeard = text => heard.push(text), onFailure = reason => failures.push(reason);
      const host = {
        slots: [], queued: [], index: 0, dirty: true, result: null, heard, failures,
        ref(initial) {
          const index = this.index++;
          return this.slots[index] ??= {current: initial};
        },
        state(initial) {
          const index = this.index++;
          const slot = this.slots[index] ??= {value: initial, set: value => {
            slot.value = typeof value === 'function' ? value(slot.value) : value;
            this.dirty = true;
          }};
          return [slot.value, slot.set];
        },
        memo(value, dependencies) {
          const index = this.index++, previous = this.slots[index];
          if (previous && same(previous.dependencies, dependencies)) return previous.value;
          this.slots[index] = {value, dependencies};
          return value;
        },
        effect(effect, dependencies) {
          const index = this.index++, previous = this.slots[index];
          if (previous && same(previous.dependencies, dependencies)) return;
          const slot = this.slots[index] = {dependencies, cleanup: previous?.cleanup};
          this.queued.push(() => { slot.cleanup?.(); slot.cleanup = effect(); });
        },
        render: function useDictationRender() {
          this.index = 0;
          this.dirty = false;
          runtime.current = this;
          this.result = useDictation(threadId, activeOwner, () => onHeard, onFailure);
          runtime.current = null;
          for (const effect of this.queued.splice(0)) effect();
        },
        remove() {
          if (!mounts.delete(this)) return;
          for (const slot of this.slots) slot.cleanup?.();
        },
      };
      mounts.add(host);
      host.render();
      return host;
    },
    async settle() {
      for (let turn = 0; turn < 10; turn++) {
        await nextTurn();
        for (const host of mounts) if (host.dirty) host.render();
        await nextTurn();
        if ([...mounts].every(host => !host.dirty)) return;
      }
      assert.fail('Dictation hook did not settle');
    },
  };
  runtime.native = {
    isAvailable: () => env.available,
    start(owner, threadId) {
      const operationId = requests.length + 1, pending = deferred();
      const initial = snapshot(operationId, 'Preparing', {owner, threadId});
      const request = {operationId, threadId, initial, ...pending};
      requests.push(request);
      env.current = initial;
      return pending.promise;
    },
    finish(operationId) {
      assert.ok(Number.isInteger(operationId), 'finish must name its intended operation');
      if (env.current?.operationId === operationId) {
        if (env.current.phase === 'Preparing') {
          env.current = snapshot(operationId, 'Idle');
          env.emit(env.current);
        } else if (env.current.phase === 'Capturing') {
          env.current = snapshot(operationId, 'Finalizing', {text: env.current.transcript});
          env.emit(env.current);
        }
      }
      return Promise.resolve();
    },
    cancel(operationId) {
      assert.ok(Number.isInteger(operationId), 'cancel must name its intended operation');
      if (env.current?.operationId === operationId) {
        env.current = snapshot(operationId, 'Idle');
        env.emit(env.current);
      }
      return Promise.resolve();
    },
    addListener(_event, listener) {
      listeners.add(listener);
      return {remove() { listeners.delete(listener); }};
    },
  };
  t.after(async () => { for (const host of [...mounts]) host.remove(); await nextTurn(); });
  return env;
}

test('A start/cancel/B start ignores delayed A events and delivers B terminal received before its start resolves', async t => {
  const env = environment(t), host = env.mount();
  host.result.start();
  const a = env.requests[0];
  host.result.cancel();
  host.result.start();
  const b = env.requests[1];
  env.emit(a.initial);
  env.emit(snapshot(a.operationId, 'Idle'));
  env.capture(b.operationId, 'the last');
  env.close(success(b.operationId, 'the last words'));
  env.emit(success(a.operationId, 'old words'));
  await env.settle();
  assert.equal(host.result.phase, 'starting');
  assert.deepEqual(host.heard, []);
  b.resolve(b.initial);
  await env.settle();
  assert.equal(host.result.phase, 'idle');
  assert.deepEqual(host.heard, ['the last words']);
  env.emit(success(b.operationId, 'the last words'));
  a.resolve(a.initial);
  await env.settle();
  assert.deepEqual(host.heard, ['the last words']);
  assert.deepEqual(host.failures, []);
});

test('buffered Running enters recording and a stale start result can cancel only A', async t => {
  const env = environment(t), host = env.mount();
  host.result.start();
  const a = env.requests[0];
  host.result.cancel();
  host.result.start();
  const b = env.requests[1];
  env.emit(a.initial);
  env.capture(b.operationId, '新的录音');
  b.resolve(b.initial);
  await env.settle();
  assert.equal(host.result.phase, 'recording');
  assert.equal(host.result.transcript, '新的录音');
  assert.deepEqual(host.result.levels, [0.2, 0.6]);
  assert.equal(b.threadId, 'thread');
  a.resolve(a.initial);
  env.emit(snapshot(a.operationId, 'Idle'));
  await env.settle();
  assert.equal(env.current.operationId, b.operationId);
  assert.equal(env.current.phase, 'Capturing');
  assert.equal(host.result.phase, 'recording');
  host.result.stop();
  await env.settle();
  assert.equal(env.current.phase, 'Finalizing');
  assert.equal(host.result.phase, 'finishing');
  env.close(success(b.operationId, '新的录音结束'));
  await env.settle();
  assert.deepEqual(host.heard, ['新的录音结束']);
});

test('queued capture levels cannot revive the waveform after stop while the tail preview updates', async t => {
  const env = environment(t), host = env.mount();
  host.result.start();
  const request = env.requests[0];
  request.resolve(request.initial);
  await env.settle();
  const id = request.operationId;

  env.capture(id, 'can you', [0.12, 0.48]);
  await env.settle();
  assert.equal(host.result.phase, 'recording');

  host.result.stop();
  await env.settle();
  env.emit(snapshot(id, 'Finalizing', {text: 'can you check the flight', levels: [0.48, 0.91]}));
  await env.settle();
  env.emit(snapshot(id, 'Capturing', {text: 'stale capture preview', levels: [0.91, 0.48]}));
  await env.settle();
  assert.deepEqual(host.result.levels, []);
  assert.equal(host.result.phase, 'finishing');
  assert.equal(host.result.transcript, 'can you check the flight');
  assert.deepEqual(host.heard, []);
});

test('a buffered terminal failure clears starting without delivering its partial', async t => {
  const env = environment(t), host = env.mount();
  host.result.start();
  const request = env.requests[0];
  env.close(snapshot(request.operationId, 'Closed', {text: 'incomplete', outcome: {kind: 'Failed', code: 'POLICY_DISABLED', message: null}}));
  request.resolve(request.initial);
  await env.settle();
  assert.equal(host.result.phase, 'idle');
  assert.deepEqual(host.heard, []);
  assert.deepEqual(host.failures, ['disabled']);
});

test('an immediate native startup failure is delivered even without a terminal event', async t => {
  const env = environment(t), host = env.mount();
  host.result.start();
  const request = env.requests[0];
  env.emit(request.initial);
  request.resolve(snapshot(request.operationId, 'Closed', {outcome: {kind: 'Failed', code: 'ENGINE_UNAVAILABLE', message: null}}));
  await env.settle();
  assert.equal(host.result.phase, 'idle');
  assert.deepEqual(host.failures, ['unavailable']);
  assert.deepEqual(host.heard, []);
});

test('the start snapshot recovers capture even if its Running event was missed', async t => {
  const env = environment(t), host = env.mount();
  host.result.start();
  const request = env.requests[0];
  env.emit(request.initial);
  request.resolve(snapshot(request.operationId, 'Capturing'));
  await env.settle();
  assert.equal(host.result.phase, 'recording');
  env.emit(request.initial);
  await env.settle();
  assert.equal(host.result.phase, 'recording');
});

test('stop before identity resolves finishes that operation and waits for its tail', async t => {
  const env = environment(t), host = env.mount();
  host.result.start();
  const request = env.requests[0];
  host.result.stop();
  env.capture(request.operationId, 'tail');
  request.resolve(request.initial);
  await env.settle();
  assert.equal(env.current.phase, 'Finalizing');
  assert.equal(host.result.phase, 'finishing');
  assert.deepEqual(host.result.levels, []);
  assert.deepEqual(host.heard, []);
  env.close(success(request.operationId, 'tail complete'));
  await env.settle();
  assert.deepEqual(host.heard, ['tail complete']);
  assert.equal(host.result.phase, 'idle');
});

test('stop during native Preparing returns idle without leaving a delayed recording', async t => {
  const env = environment(t), host = env.mount();
  host.result.start();
  const request = env.requests[0];
  host.result.stop();
  request.resolve(request.initial);
  await env.settle();
  assert.equal(env.current.phase, 'Idle');
  assert.equal(host.result.phase, 'idle');
  assert.deepEqual(host.heard, []);
});

test('old hook cleanup cannot stop a newer hook recording', async t => {
  const env = environment(t), old = env.mount();
  old.result.start();
  const a = env.requests[0];
  env.capture(a.operationId);
  a.resolve(a.initial);
  await env.settle();
  const current = env.mount();
  current.result.start();
  const b = env.requests[1];
  env.capture(b.operationId);
  b.resolve(b.initial);
  await env.settle();
  old.remove();
  await env.settle();
  assert.equal(env.current.operationId, b.operationId);
  assert.equal(env.current.phase, 'Capturing');
  assert.equal(current.result.phase, 'recording');
  env.close(success(b.operationId, 'belongs to new screen'));
  await env.settle();
  assert.deepEqual(old.heard, []);
  assert.deepEqual(current.heard, ['belongs to new screen']);
});

test('cleanup before start resolution revokes delivery without cancelling a newer recording', async t => {
  const env = environment(t), old = env.mount();
  old.result.start();
  const a = env.requests[0];
  old.remove();
  const current = env.mount();
  current.result.start();
  const b = env.requests[1];
  env.capture(b.operationId);
  b.resolve(b.initial);
  await env.settle();
  a.resolve(a.initial);
  env.emit(success(a.operationId, 'unmounted'));
  await env.settle();
  assert.equal(env.current.operationId, b.operationId);
  assert.equal(env.current.phase, 'Capturing');
  assert.equal(current.result.phase, 'recording');
  assert.deepEqual(old.heard, []);
  assert.deepEqual(current.heard, []);
});

test('a rejected stale start cannot clear the current recording or report its failure', async t => {
  const env = environment(t), host = env.mount();
  host.result.start();
  const a = env.requests[0];
  host.result.cancel();
  host.result.start();
  const b = env.requests[1];
  env.capture(b.operationId);
  b.resolve(b.initial);
  await env.settle();
  a.reject(Object.assign(new Error('old failure'), {code: 'RESOURCE_BUSY'}));
  await env.settle();
  assert.equal(host.result.phase, 'recording');
  assert.deepEqual(host.failures, []);
});

test('operation-scoped Idle received before start resolution revokes buffered capture', async t => {
  const env = environment(t), host = env.mount();
  host.result.start();
  const request = env.requests[0];
  env.capture(request.operationId);
  env.emit(snapshot(request.operationId, 'Idle'));
  request.resolve(request.initial);
  await env.settle();
  assert.equal(host.result.phase, 'idle');
  assert.deepEqual(host.heard, []);
  assert.deepEqual(host.failures, []);
});

test('a current native start rejection clears starting and reports the engine reason', async t => {
  const env = environment(t), host = env.mount();
  host.result.start();
  env.requests[0].reject(Object.assign(new Error('microphone busy'), {code: 'RESOURCE_BUSY'}));
  await env.settle();
  assert.equal(host.result.phase, 'idle');
  assert.deepEqual(host.failures, ['busy']);
  assert.deepEqual(host.heard, []);
});

test('a standard Android recording adopts the visible composer and excludes other threads', async t => {
  const env = environment(t), host = env.mount();
  env.emit(snapshot(1, 'Capturing', {threadId: 'other'}));
  env.emit(snapshot(1, 'Capturing', {owner: 'old-chat'}));
  await env.settle();
  assert.equal(host.result.phase, 'idle');
  env.capture(2, 'started by Android');
  await env.settle();
  assert.equal(host.result.phase, 'recording');
  env.close(success(2, 'started by Android'));
  await env.settle();
  assert.deepEqual(host.heard, ['started by Android']);
});
