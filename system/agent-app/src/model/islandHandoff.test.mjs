import test from 'node:test';
import assert from 'node:assert/strict';
import {handoffCandidate, MAX_KNOWN_TURNS} from './islandHandoff.ts';

const session = (fields = {}) => ({id: 'thread-a', title: '', blocks: [], questions: [], running: false, activeTurnId: '',
  ready: true, error: '', historyComplete: true, knownEmpty: false, historyStatus: 'complete', totalTurns: 1,
  nativeOnlyRequests: 0, queued: false, taskId: '', taskState: '', taskPrompt: '', taskTurnId: '', taskFailure: null, ...fields});
const block = (turnId, active = false) => ({id: `${turnId}/b`, turnId, role: 'assistant', active});
const row = (fields = {}) => ({threadId: 'thread-a', requestId: 'r1', text: 'Plan', action: 'sendMessage', startedAt: 0,
  baselineBlockIds: [], baselineTurnIds: ['t1'], state: 'sending', ...fields});

test('an idle or unselected conversation is never handed off', () => {
  assert.equal(handoffCandidate('', session({activeTurnId: 'turn-1', running: true}), null), null);
  assert.equal(handoffCandidate('thread-a', session(), null), null);
  assert.equal(handoffCandidate('thread-a', null, null), null);
});

test('a running turn is handed off with the turn Home must wait for', () => {
  assert.deepEqual(handoffCandidate('thread-a', session({activeTurnId: 'turn-2', running: true}), null),
    {threadId: 'thread-a', turnId: 'turn-2', taskId: '', knownTurnIds: null});
  // Running blocks without a native active turn id still name their turn.
  assert.deepEqual(handoffCandidate('thread-a', session({running: true, blocks: [block('turn-1'), block('turn-3', true)]}), null),
    {threadId: 'thread-a', turnId: 'turn-3', taskId: '', knownTurnIds: null});
});

test('a queued task is handed off with the turns that came before it', () => {
  assert.deepEqual(handoffCandidate('thread-a', session({queued: true, running: true, taskId: '17', blocks: [block('t1'), block('t2')]}), null),
    {threadId: 'thread-a', turnId: '', taskId: '17', knownTurnIds: ['t1', 't2']});
});

test('the message row hands off from the tap, before the session reports anything', () => {
  for (const state of ['sending', 'accepted'])
    assert.deepEqual(handoffCandidate('thread-a', session({blocks: [block('t1')]}), null, [row({state})]),
      {threadId: 'thread-a', turnId: '', taskId: '', knownTurnIds: ['t1']});
  assert.deepEqual(handoffCandidate('thread-a', session(), null, [row({state: 'accepted', taskId: '9'})]),
    {threadId: 'thread-a', turnId: '', taskId: '9', knownTurnIds: ['t1']}, 'the accepted task, never an earlier one');
  assert.deepEqual(handoffCandidate('thread-a', session(), null, [row({state: 'accepted', turnId: 't2'})]),
    {threadId: 'thread-a', turnId: 't2', taskId: '', knownTurnIds: null});
  assert.deepEqual(handoffCandidate('thread-a', session(), null, [row({action: 'steer', expectedTurnId: 't1'})]),
    {threadId: 'thread-a', turnId: 't1', taskId: '', knownTurnIds: null}, 'a steer continues its running turn');
});

test('a settled, failed or other conversation\'s row hands nothing off', () => {
  for (const state of ['settled', 'failed', 'stopped', 'timedOut'])
    assert.equal(handoffCandidate('thread-a', session(), null, [row({state})]), null);
  assert.equal(handoffCandidate('thread-a', session(), null, [row({threadId: 'thread-b'})]), null);
});

test('a new conversation hands off once its thread exists', () => {
  // Before creation the row and the selection are both unsaved: nothing to name yet.
  assert.equal(handoffCandidate('', null, null, [row({threadId: '', baselineTurnIds: []})]), null);
  assert.deepEqual(handoffCandidate('thread-new', null, null, [row({threadId: 'thread-new', baselineTurnIds: []})]),
    {threadId: 'thread-new', turnId: '', taskId: '', knownTurnIds: []});
});

test('a send the session has not reported yet still hands off its conversation', () => {
  assert.deepEqual(handoffCandidate('thread-a', session({blocks: [block('t1')]}), 'thread-a'),
    {threadId: 'thread-a', turnId: '', taskId: '', knownTurnIds: ['t1']});
  assert.deepEqual(handoffCandidate('thread-a', null, 'thread-a'), {threadId: 'thread-a', turnId: '', taskId: '', knownTurnIds: null});
  assert.equal(handoffCandidate('thread-a', session(), 'thread-b'), null);
});

test('a conversation waiting on the user is not handed off', () => {
  assert.equal(handoffCandidate('thread-a', session({activeTurnId: 'turn-2', running: true, questions: [{key: 'q'}]}), null), null);
});

test('only the newest earlier turns are reported', () => {
  const many = Array.from({length: MAX_KNOWN_TURNS + 5}, (_, i) => `t${i}`);
  const result = handoffCandidate('thread-a', session(), null, [row({baselineTurnIds: many})]);
  assert.equal(result.knownTurnIds.length, MAX_KNOWN_TURNS);
  assert.equal(result.knownTurnIds.at(-1), many.at(-1));
});
