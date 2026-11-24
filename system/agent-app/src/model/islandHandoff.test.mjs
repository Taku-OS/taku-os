import test from 'node:test';
import assert from 'node:assert/strict';
import {handoffCandidate} from './islandHandoff.ts';

const session = (fields = {}) => ({id: 'thread-a', title: '', blocks: [], questions: [], running: false, activeTurnId: '',
  ready: true, error: '', historyComplete: true, knownEmpty: false, historyStatus: 'complete', totalTurns: 1,
  nativeOnlyRequests: 0, queued: false, taskId: '', taskState: '', taskPrompt: '', taskTurnId: '', taskFailure: null, ...fields});

test('an idle or unselected conversation is never handed off', () => {
  assert.equal(handoffCandidate('', session({activeTurnId: 'turn-1', running: true}), null), null);
  assert.equal(handoffCandidate('thread-a', session(), null), null);
  assert.equal(handoffCandidate('thread-a', null, null), null);
});

test('a running turn is handed off with the turn Home must wait for', () => {
  assert.deepEqual(handoffCandidate('thread-a', session({activeTurnId: 'turn-2', running: true}), null),
    {threadId: 'thread-a', turnId: 'turn-2', taskId: ''});
  // Running blocks without a native active turn id still name their turn.
  const blocks = [{id: 'b1', turnId: 'turn-1', active: false}, {id: 'b2', turnId: 'turn-3', active: true}];
  assert.deepEqual(handoffCandidate('thread-a', session({running: true, blocks}), null),
    {threadId: 'thread-a', turnId: 'turn-3', taskId: ''});
});

test('a queued task is handed off by task, its turn not started yet', () => {
  assert.deepEqual(handoffCandidate('thread-a', session({queued: true, running: true, taskId: '17'}), null),
    {threadId: 'thread-a', turnId: '', taskId: '17'});
});

test('a send the session has not reported yet still hands off its conversation', () => {
  assert.deepEqual(handoffCandidate('thread-a', session(), 'thread-a'), {threadId: 'thread-a', turnId: '', taskId: ''});
  assert.equal(handoffCandidate('thread-a', session(), 'thread-b'), null);
});

test('a conversation waiting on the user is not handed off', () => {
  assert.equal(handoffCandidate('thread-a', session({activeTurnId: 'turn-2', running: true, questions: [{key: 'q'}]}), null), null);
});

test('another conversation\'s session never names the selected one\'s turn', () => {
  assert.deepEqual(handoffCandidate('thread-b', session({activeTurnId: 'turn-2', running: true}), 'thread-b'),
    {threadId: 'thread-b', turnId: '', taskId: ''});
  assert.equal(handoffCandidate('thread-b', session({activeTurnId: 'turn-2', running: true}), null), null);
});
