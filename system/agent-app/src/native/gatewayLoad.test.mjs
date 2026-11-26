import test from 'node:test';
import assert from 'node:assert/strict';
import {BACKGROUND_PATIENCE_MS, createGatewayLoad, isGatewayBusy, isGatewayRead, retryWhileBusy} from './gatewayLoad.ts';

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return {promise, resolve, reject}; };
/** A clock the test moves: every sleep advances it and lets the test release calls in between. */
function manualClock(onSleep = () => {}) {
  let time = 0;
  return {now: () => time, sleep: async ms => { time += ms; onSleep(time); }, elapsed: () => time};
}

test('a call counts as in flight until it settles, whether it succeeds or fails', async () => {
  const load = createGatewayLoad(manualClock());
  const first = deferred(), second = deferred();
  const a = load.track(() => first.promise), b = load.track(() => second.promise);
  assert.equal(load.inFlight(), 2);
  first.resolve('ok');
  assert.equal(await a, 'ok');
  assert.equal(load.inFlight(), 1);
  second.reject(new Error('GATEWAY_UNAVAILABLE'));
  await assert.rejects(b, /GATEWAY_UNAVAILABLE/);
  assert.equal(load.inFlight(), 0);
});

test('a background read goes at once while the conversation leaves it room', async () => {
  const clock = manualClock();
  const load = createGatewayLoad(clock);
  const poll = deferred();
  void load.track(() => poll.promise);
  await load.quiet();
  assert.equal(clock.elapsed(), 0, 'one call in flight still leaves room');
  poll.resolve();
});

test('a background read waits out a burst instead of taking a slot the conversation needs', async () => {
  const calls = [deferred(), deferred(), deferred()];
  const clock = manualClock(time => { if (time === 100) calls[0].resolve(); if (time === 250) calls[1].resolve(); });
  const load = createGatewayLoad(clock);
  for (const call of calls) void load.track(() => call.promise);
  await load.quiet();
  assert.equal(load.inFlight(), 1);
  assert.ok(clock.elapsed() >= 250 && clock.elapsed() < BACKGROUND_PATIENCE_MS, `waited ${clock.elapsed()} ms`);
  calls[2].resolve();
});

test('its patience ends: a long call never starves the board for good', async () => {
  const clock = manualClock();
  const load = createGatewayLoad(clock);
  const stuck = [deferred(), deferred()];
  for (const call of stuck) void load.track(() => call.promise);
  await load.quiet();
  assert.ok(clock.elapsed() >= BACKGROUND_PATIENCE_MS);
  assert.equal(load.inFlight(), 2);
});

test('BUSY is retried because the gateway refused before running anything', async () => {
  const busy = Object.assign(new Error('BUSY'), {code: 'BUSY'});
  assert.equal(isGatewayBusy(busy), true);
  assert.equal(isGatewayBusy(Object.assign(new Error('x'), {code: 'REQUEST_EXPIRED'})), false);
  assert.equal(isGatewayBusy(null), false);
  let attempts = 0;
  const pauses = [];
  const result = await retryWhileBusy(async () => { if (++attempts < 3) throw busy; return 'accepted'; }, 3, async ms => { pauses.push(ms); });
  assert.equal(result, 'accepted');
  assert.equal(attempts, 3);
  assert.deepEqual(pauses, [150, 300]);
});

test('any other refusal is final, and so is BUSY after the last attempt', async () => {
  const expired = Object.assign(new Error('REQUEST_EXPIRED'), {code: 'REQUEST_EXPIRED'});
  let attempts = 0;
  await assert.rejects(retryWhileBusy(async () => { attempts++; throw expired; }, 3, async () => {}), /REQUEST_EXPIRED/);
  assert.equal(attempts, 1, 'an answer that may have run is never sent twice');
  attempts = 0;
  const busy = Object.assign(new Error('BUSY'), {code: 'BUSY'});
  await assert.rejects(retryWhileBusy(async () => { attempts++; throw busy; }, 3, async () => {}), /BUSY/);
  assert.equal(attempts, 3);
});

test('only reads are asked again by themselves; anything that changes or starts something is not', () => {
  for (const action of ['listThreads', 'readSession', 'resumeThread', 'composer/read', 'composer/receipt', 'getWorkspaces', 'requests/list'])
    assert.equal(isGatewayRead(action), true, action);
  for (const action of ['answer', 'sendMessage', 'steer', 'composer/submit', 'composer/write', 'createThread', 'interrupt', 'cancelQueued',
    'requests/submit', 'panels', 'cardView', 'dispatchMatter', 'pluginManager', 'generateUi', 'openWorkspace'])
    assert.equal(isGatewayRead(action), false, action);
});
