import test from 'node:test';
import assert from 'node:assert/strict';
import {createTaskFollower} from './taskFollower.ts';
import {IDLE_POLL_MS, LIVE_POLL_MS, SWEEP_EVERY} from '../model/taskStatus.ts';

const ask = (threadId, key = 'q-1') => ({key, method: 'phone/askUser',
  params: {threadId, turnId: 'turn-1', arguments: {question: 'Which payment method comes first?', options: ['Card', 'Wallet']}}});
const snapshot = (threadId, native = {}) => ({thread: {id: threadId}, statusOnly: true,
  native: {ready: true, requests: [], unsupportedRequestCount: 0, ...native}});
const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return {promise, resolve, reject}; };

/** A gateway whose threads are plain objects, with the follower's timers under the test's control. */
function gateway(threads) {
  const calls = [], timers = [], boards = [];
  const host = {
    read: async threadId => {
      calls.push(['read', threadId]);
      const thread = threads[threadId];
      if (thread instanceof Error) throw thread;
      return typeof thread === 'function' ? thread() : thread;
    },
    answer: async (threadId, key, response) => {
      calls.push(['answer', threadId, key, response]);
      const pending = threads[threadId]?.native?.requests ?? [];
      if (!pending.some(question => question.key === key)) throw Object.assign(new Error('REQUEST_EXPIRED'), {code: 'REQUEST_EXPIRED'});
      // The broker consumes the answer and its request together; the turn goes on.
      threads[threadId] = snapshot(threadId, {activeTurnId: 'turn-1', requests: pending.filter(question => question.key !== key)});
      return {};
    },
    publish: board => boards.push(board),
    schedule: (run, ms) => {
      const timer = {ms, cancelled: false, fired: false, run() { timer.fired = true; run(); }};
      timers.push(timer);
      return timer;
    },
    cancel: timer => { timer.cancelled = true; },
  };
  /** The timers still waiting: neither cancelled nor already run. */
  const pending = () => timers.filter(timer => !timer.cancelled && !timer.fired);
  return {host, calls, timers, boards, pending, board: () => boards.at(-1), threads};
}

test('follows each Task with one status-only read at a time, then waits', async () => {
  const first = deferred();
  const world = gateway({build: () => first.promise, design: snapshot('design')});
  const follower = createTaskFollower(world.host);
  follower.follow(['build', 'design']);
  await settle();
  assert.deepEqual(world.calls, [['read', 'build']], 'the second read waits for the first');
  first.resolve(snapshot('build', {activeTurnId: 'turn-1'}));
  await settle();
  assert.deepEqual(world.calls, [['read', 'build'], ['read', 'design']]);
  assert.equal(world.board().states.build.status, 'inProgress');
  assert.equal(world.board().states.design.status, 'done');
  assert.deepEqual(world.pending().map(timer => timer.ms), [LIVE_POLL_MS]);
});

test('with nothing at work it only checks in, and the next round reads again', async () => {
  const world = gateway({build: snapshot('build')});
  const follower = createTaskFollower(world.host);
  follower.follow(['build']);
  await settle();
  assert.deepEqual(world.pending().map(timer => timer.ms), [IDLE_POLL_MS]);
  world.threads.build = snapshot('build', {activeTurnId: 'turn-2', requests: [ask('build')]});
  world.pending()[0].run();
  await settle();
  assert.equal(world.board().states.build.status, 'needsYou');
  assert.equal(world.calls.filter(call => call[0] === 'read').length, 2);
});

test('while a Task works, the rounds between sweeps read only that Task', async () => {
  const world = gateway({build: snapshot('build', {activeTurnId: 'turn-1'}), design: snapshot('design'), trip: snapshot('trip')});
  const follower = createTaskFollower(world.host);
  follower.follow(['build', 'design', 'trip']);
  await settle();
  const reads = () => world.calls.filter(call => call[0] === 'read').map(call => call[1]);
  assert.deepEqual(reads(), ['build', 'design', 'trip']);
  for (let round = 1; round < SWEEP_EVERY; round++) {
    world.pending()[0].run();
    await settle();
  }
  assert.deepEqual(reads().slice(3), Array(SWEEP_EVERY - 1).fill('build'));
  world.pending()[0].run();
  await settle();
  assert.deepEqual(reads().slice(-3), ['build', 'design', 'trip'], 'the sweep finds a Task that started meanwhile');
});

test('an unchanged round publishes nothing', async () => {
  const world = gateway({build: snapshot('build', {activeTurnId: 'turn-1'})});
  const follower = createTaskFollower(world.host);
  follower.follow(['build']);
  await settle();
  const published = world.boards.length;
  world.pending()[0].run();
  await settle();
  assert.equal(world.boards.length, published);
});

test('stopping cancels the wait and ends a round in flight; following again reads at once', async () => {
  const slow = deferred();
  const world = gateway({build: () => slow.promise, design: snapshot('design')});
  const follower = createTaskFollower(world.host);
  follower.follow(['build', 'design']);
  await settle();
  follower.stop();
  slow.resolve(snapshot('build', {activeTurnId: 'turn-1'}));
  await settle();
  assert.deepEqual(world.calls, [['read', 'build']], 'the stopped round reads no further Task');
  assert.deepEqual(world.pending(), []);
  world.threads.build = snapshot('build');
  follower.follow(['build', 'design']);
  await settle();
  assert.deepEqual(world.calls.slice(1), [['read', 'build'], ['read', 'design']]);
});

test('following the same Tasks again does not restart the round', async () => {
  const world = gateway({build: snapshot('build')});
  const follower = createTaskFollower(world.host);
  follower.follow(['build']);
  await settle();
  follower.follow(['build']);
  await settle();
  assert.equal(world.calls.length, 1);
  assert.equal(world.pending().length, 1);
});

test('a Task that left the followed list loses its capsule', async () => {
  const world = gateway({build: snapshot('build', {activeTurnId: 'turn-1'}), design: snapshot('design', {activeTurnId: 'turn-9'})});
  const follower = createTaskFollower(world.host);
  follower.follow(['build', 'design']);
  await settle();
  follower.follow(['design']);
  await settle();
  assert.deepEqual(Object.keys(world.board().states), ['design']);
  follower.follow([]);
  assert.deepEqual(world.board().states, {});
  assert.deepEqual(world.pending(), []);
});

test('one missed read keeps what was known; a second forgets it', async () => {
  const world = gateway({build: snapshot('build', {activeTurnId: 'turn-1'})});
  const follower = createTaskFollower(world.host);
  follower.follow(['build']);
  await settle();
  world.threads.build = new Error('GATEWAY_UNAVAILABLE');
  world.pending()[0].run();
  await settle();
  assert.equal(world.board().states.build.status, 'inProgress');
  world.pending()[0].run();
  await settle();
  assert.deepEqual(world.board().states, {});
});

test('a choice made in the main stream reaches that Task and turns it to In progress', async () => {
  const world = gateway({build: snapshot('build', {activeTurnId: 'turn-1', requests: [ask('build')]})});
  const follower = createTaskFollower(world.host);
  follower.follow(['build']);
  await settle();
  assert.equal(world.board().states.build.status, 'needsYou');
  const accepted = await follower.answer('build', 'q-1', 'Card', {optionIndex: 0});
  assert.equal(accepted, true);
  assert.deepEqual(world.calls.filter(call => call[0] === 'answer'), [['answer', 'build', 'q-1', {optionIndex: 0}]]);
  assert.equal(world.board().states.build.status, 'inProgress');
  assert.deepEqual(world.board().answered, {build: 'Card'});
  assert.deepEqual(world.calls.at(-1), ['read', 'build'], 'the Task is read again after the answer');
});

test('the capsule turns before the confirming read returns', async () => {
  const confirm = deferred();
  const world = gateway({build: snapshot('build', {activeTurnId: 'turn-1', requests: [ask('build')]})});
  const follower = createTaskFollower(world.host);
  follower.follow(['build']);
  await settle();
  world.host.read = () => confirm.promise;
  const answering = follower.answer('build', 'q-1', 'Card', {optionIndex: 0});
  await settle();
  assert.equal(world.board().states.build.status, 'inProgress');
  confirm.resolve(snapshot('build', {activeTurnId: 'turn-1'}));
  assert.equal(await answering, true);
});

test('a second question of the same Task keeps it waiting for the user', async () => {
  const world = gateway({build: snapshot('build', {activeTurnId: 'turn-1', requests: [ask('build', 'q-1'), ask('build', 'q-2')]})});
  const follower = createTaskFollower(world.host);
  follower.follow(['build']);
  await settle();
  await follower.answer('build', 'q-1', 'Card', {optionIndex: 0});
  assert.equal(world.board().states.build.status, 'needsYou');
  assert.deepEqual(world.board().states.build.questions.map(question => question.key), ['q-2']);
  assert.deepEqual(world.board().answered, {});
});

test('a refused answer changes nothing but the truth it reads back', async () => {
  const world = gateway({build: snapshot('build', {activeTurnId: 'turn-1', requests: [ask('build')]})});
  const follower = createTaskFollower(world.host);
  follower.follow(['build']);
  await settle();
  // The request was answered elsewhere in the meantime and the Task has finished.
  world.threads.build = snapshot('build');
  assert.equal(await follower.answer('build', 'q-1', 'Card', {optionIndex: 0}), false);
  assert.equal(world.board().states.build.status, 'done');
  assert.deepEqual(world.board().answered, {});
});

test('a read that began before the answer cannot bring the question back', async () => {
  const stale = deferred();
  const world = gateway({build: snapshot('build', {activeTurnId: 'turn-1', requests: [ask('build')]})});
  const follower = createTaskFollower(world.host);
  follower.follow(['build']);
  await settle();
  const read = world.host.read;
  world.host.read = () => stale.promise;
  world.pending()[0].run();
  await settle();
  world.host.read = read;
  await follower.answer('build', 'q-1', 'Card', {optionIndex: 0});
  stale.resolve(snapshot('build', {activeTurnId: 'turn-1', requests: [ask('build')]}));
  await settle();
  assert.equal(world.board().states.build.status, 'inProgress');
});

test('the open conversation reports its own state without a read', async () => {
  const world = gateway({build: snapshot('build', {activeTurnId: 'turn-1', requests: [ask('build')]})});
  const follower = createTaskFollower(world.host);
  follower.follow(['build']);
  await settle();
  follower.stop();
  const reads = world.calls.length;
  follower.observe('build', {status: 'inProgress', questions: []});
  assert.equal(world.board().states.build.status, 'inProgress');
  follower.observe('elsewhere', {status: 'needsYou', questions: []});
  assert.equal(world.board().states.elsewhere, undefined);
  assert.equal(world.calls.length, reads);
});
