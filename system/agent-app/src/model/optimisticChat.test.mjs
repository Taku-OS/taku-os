import test from 'node:test';
import assert from 'node:assert/strict';
import {acceptOptimisticReceipt, beginOptimisticSubmission, reconcileOptimisticSubmission, recoverQueuedSubmission, FIRST_RESPONSE_TIMEOUT_MS} from './optimisticChat.ts';
import {projectSession} from './session.ts';
import {chatPresentation} from './chatPresentation.ts';
import {mainReply, replyItems} from './mainReplyFixtures.mjs';

const prompt = 'Check the screen';
const user = (id = 'u', text = prompt) => ({id, type: 'userMessage', content: [{type: 'text', text}]});
const reply = (text = 'Hello') => ({id: 'a', type: 'agentMessage', text});
const session = (turns = [], extra = {}) => projectSession({thread: {id: 't', turns}, ...extra});
const turn = (items = [], status = 'inProgress', id = 'turn') => ({id, status, items});
const begin = (view = session(), extra = {}) => beginOptimisticSubmission({threadId: 't', requestId: 'r', text: prompt, action: 'sendMessage', ...extra}, view, 100);
const visible = (view, request) => chatPresentation(view.blocks, [request]);

test('both rows exist before any gateway result and keep their keys through every native stage', () => {
  let request = begin();
  const keys = visible(session(), request).map(block => block.id);
  assert.deepEqual(visible(session(), request).map(block => [block.role, block.text, block.responsePending]), [
    ['user', prompt, undefined], ['assistant', '', true],
  ]);
  for (const view of [session(), session([turn()]), session([turn([user()])]),
    session([turn([user(), reply('H')])]), session([turn([user(), reply()], 'completed')])]) {
    request = reconcileOptimisticSubmission(request, view, 200);
    assert.deepEqual(visible(view, request).map(block => block.id), keys);
    assert.equal(visible(view, request).length, 2);
  }
  assert.equal(request.state, 'settled');
});

test('a tool event stops the initial loading state even before answer text', () => {
  const view = session([turn([user(), {id: 'tool', type: 'commandExecution', command: 'date', status: 'inProgress'}])]);
  const request = reconcileOptimisticSubmission(begin(), view, 200);
  const block = visible(view, request)[1];
  assert.equal(block.responsePending, false);
  assert.equal(block.activity.length, 1);
  assert.equal(request.state, 'settled');
});

test('the original deadline survives repeated queue polls and late output recovers in place', () => {
  let request = {...begin(), state: 'accepted', taskId: 'task'};
  const queued = session([], {task: {id: 'task', state: 'queued', published: true}, request: {prompt}});
  request = reconcileOptimisticSubmission(request, queued, 100 + FIRST_RESPONSE_TIMEOUT_MS - 1);
  assert.equal(visible(queued, request)[1].responsePending, true);
  request = reconcileOptimisticSubmission(request, queued, 100 + FIRST_RESPONSE_TIMEOUT_MS);
  const timedOut = visible(queued, request)[1];
  assert.equal(timedOut.responsePending, false);
  assert.equal(timedOut.responseErrorKey, 'replyTimeout');
  const streamed = session([turn([user(), reply()])]);
  request = reconcileOptimisticSubmission(request, streamed, 90_000);
  assert.equal(visible(streamed, request)[1].id, timedOut.id);
  assert.equal(visible(streamed, request)[1].responseErrorKey, undefined);
  assert.equal(visible(streamed, request)[1].text, 'Hello');
});

test('only the matching broker task fails a pending request; auth failures are terminal too', () => {
  const request = {...begin(), state: 'accepted', taskId: 'current'};
  const oldFailure = session([], {task: {id: 'old', state: 'failed', message: 'Old failure'}});
  assert.equal(reconcileOptimisticSubmission(request, oldFailure, 200).state, 'accepted');
  for (const state of ['failed', 'needs_user']) {
    const failed = session([], {task: {id: 'current', state, message: 'Check the connection'}, request: {prompt}});
    const block = visible(failed, reconcileOptimisticSubmission(request, failed, 200))[1];
    assert.equal(block.responsePending, false);
    assert.equal(block.responseError, 'Check the connection');
    assert.equal(block.status, 'failed');
  }
});

test('completed empty native output cannot leave a spinner', () => {
  const view = session([turn([user()], 'completed')]);
  const request = reconcileOptimisticSubmission(begin(), view, 200);
  assert.equal(visible(view, request)[1].responseErrorKey, 'replyFailed');
  assert.equal(visible(view, request)[1].responsePending, false);
});

test('repeated prompt matches only records after the submission baseline', () => {
  const old = turn([user('old'), reply('Earlier answer')], 'completed', 'old-turn');
  const before = session([old]);
  let request = begin(before);
  request = reconcileOptimisticSubmission(request, before, 200);
  assert.equal(request.userBlockId, undefined);
  const next = session([old, turn([user(), reply()])]);
  request = reconcileOptimisticSubmission(request, next, 300);
  assert.equal(request.userBlockId, 'turn/u');
  assert.equal(visible(next, request).length, 4);
  assert.equal(visible(next, request)[0].id, 'old-turn/old');
});

test('steering binds its new segment without replacing the earlier answer in the same turn', () => {
  const before = session([turn([user('u0', 'Original'), reply('Earlier answer')])]);
  const request = begin(before, {action: 'steer', expectedTurnId: 'turn'});
  const next = session([turn([user('u0', 'Original'), reply('Earlier answer'), user(), {id: 'a2', type: 'agentMessage', text: 'Follow-up'}])]);
  const result = visible(next, reconcileOptimisticSubmission(request, next, 200));
  assert.equal(result.length, 4);
  assert.equal(result[1].text, 'Earlier answer');
  assert.equal(result[3].id, 'optimistic/r/assistant');
  assert.equal(result[3].text, 'Follow-up');
});

test('a steer queued as the next task binds its new turn and no longer blocks the next send', () => {
  const earlier = turn([user('old-user', 'Earlier'), reply('Earlier answer')], 'completed', 'old-turn');
  const before = session([earlier]);
  const original = begin(before, {action: 'steer', expectedTurnId: 'old-turn'});
  const accepted = acceptOptimisticReceipt(original, {taskId: '123', result: {queuedAfterTurn: true}});
  assert.equal(accepted.action, 'sendMessage');
  assert.equal(accepted.expectedTurnId, undefined);
  const next = session([earlier, turn([user('new-user'), reply('Finished')], 'completed', 'new-turn')],
    {task: {id: '123', state: 'completed', published: true}, runtime: {turnId: 'new-turn'}});
  const reconciled = reconcileOptimisticSubmission(accepted, next, 200);
  assert.equal(reconciled.userBlockId, 'new-turn/new-user');
  assert.equal(reconciled.state, 'settled');
  assert.equal(visible(next, reconciled).at(-1).text, 'Finished');
});

test('a direct steer never binds a same-text message from an unrelated new turn', () => {
  const earlier = turn([user('old-user', 'Earlier'), reply('Earlier answer')], 'completed', 'old-turn');
  const original = begin(session([earlier]), {action: 'steer', expectedTurnId: 'old-turn'});
  const accepted = acceptOptimisticReceipt(original, {result: {turnId: 'old-turn'}});
  const next = session([earlier, turn([user('unrelated'), reply('Other reply')], 'completed', 'new-turn')]);
  assert.equal(reconcileOptimisticSubmission(accepted, next, 200).userBlockId, undefined);
});

test('legacy unconfirmed delivery cannot claim unrelated historical records before its receipt', () => {
  const request = {...begin(), correlationPending: true, state: 'failed'};
  const history = session([turn([user(), reply()], 'completed', 'old')]);
  const unconfirmed = reconcileOptimisticSubmission(request, history, 200);
  assert.equal(unconfirmed.userBlockId, undefined);
  assert.equal(unconfirmed.assistantBlockId, undefined);
  assert.equal(unconfirmed.state, 'failed');
  const confirmed = session([turn([user(), reply()], 'completed', 'old')],
    {task: {id: 'task', state: 'completed'}, runtime: {turnId: 'old'}});
  assert.equal(reconcileOptimisticSubmission({...request, taskId: 'task'}, confirmed, 300).state, 'settled');
});

test('definitively unsent rows never steal the native response of a later same-text request', () => {
  const failed = {...begin(), state: 'failed', nonDeliverable: true};
  const next = session([turn([user(), reply()])]);
  assert.equal(reconcileOptimisticSubmission(failed, next, 200), failed);
  const sent = reconcileOptimisticSubmission(begin(session(), {requestId: 'r2'}), next, 200);
  const blocks = chatPresentation(next.blocks, [failed, sent]);
  assert.equal(blocks.length, 4);
  assert.deepEqual(blocks.map(block => block.id), ['optimistic/r/user', 'optimistic/r/assistant', 'optimistic/r2/user', 'optimistic/r2/assistant']);
  assert.equal(blocks.find(block => block.id === 'optimistic/r2/assistant').text, 'Hello');
  assert.equal(blocks.find(block => block.id === 'optimistic/r/assistant').responseErrorKey, 'replyFailed');
  const retry = reconcileOptimisticSubmission({...failed, state: 'sending'}, next, 60_100);
  assert.equal(retry.state, 'timedOut');
  assert.equal(retry.userBlockId, undefined);
});

test('queue restoration is deduplicated against pending receipts and stale completed tasks', () => {
  const queued = session([], {task: {id: 'task', state: 'queued', published: true}, request: {prompt}});
  const recovered = recoverQueuedSubmission(queued, [], 100);
  assert.equal(recovered.text, prompt);
  assert.equal(recovered.taskId, 'task');
  assert.equal(recoverQueuedSubmission(queued, [recovered], 200), null);
  assert.equal(recoverQueuedSubmission(queued, [{...begin(), state: 'failed'}], 200), null);
  const stale = session([turn([user(), reply()], 'completed')], {task: {id: 'task', state: 'queued', published: true}, request: {prompt}, runtime: {turnId: 'turn'}});
  assert.equal(recoverQueuedSubmission(stale, [], 200), null);
});

test('an active empty native response recovered after reopening also has a bounded wait', () => {
  const view = session([turn([user()])]);
  const recovered = recoverQueuedSubmission(view, [], 100);
  assert.equal(visible(view, recovered).length, 2);
  assert.equal(recoverQueuedSubmission(view, [recovered], 200), null);
  assert.equal(reconcileOptimisticSubmission(recovered, view, 60_100).state, 'timedOut');
});

test('a temporarily empty broker resnapshot never relabels a successful answer as stopped', () => {
  const completed = session([turn([user(), reply()], 'completed')]);
  const request = reconcileOptimisticSubmission(begin(), completed, 200);
  const empty = session();
  const result = visible(empty, reconcileOptimisticSubmission(request, empty, 300));
  assert.equal(result.some(block => block.requestStopped), false);
  assert.equal(result.length, 0);
});


test('native card-only replies settle optimistic loading without losing the card', () => {
  const request = {version: 2, key: 'weather', mode: 'card', title: '', summary: '', actions: [], blocks: [
    {id: 'temperature', kind: 'text', title: '', text: '23°C', value: {type: 'text', value: null}, image: '', url: '', items: [], config: {}},
  ]};
  for (const status of ['inProgress', 'completed']) {
    const view = session([turn([user(), {id: 'card', type: 'dynamicToolCall', tool: 'taku_phone__with_state_card',
      status: 'completed', arguments: request}], status)]);
    assert.equal(view.blocks.at(-1).parts[0].card.request.key, 'weather');
    const submission = reconcileOptimisticSubmission(begin(), view, 200);
    assert.equal(submission.state, 'settled');
    assert.equal(visible(view, submission).at(-1).responsePending, false);
    assert.equal(visible(view, submission).at(-1).parts[0].card.request.key, 'weather');
  }
});

test('a primary prefix or explicit primary failure settles loading without ordinary text or a fake Matter', () => {
  for (const phase of ['streaming', 'failed', 'interrupted']) {
    const reply = mainReply('t', `primary-${phase}`, 1, phase, {readableText: ''});
    if (phase !== 'streaming') reply.card = null;
    const view = session([{...turn([user(), ...replyItems(reply)]), mainReplies: {[reply.id]: reply}}]);
    const submission = reconcileOptimisticSubmission(begin(), view, 200);
    assert.equal(submission.state, 'settled');
    assert.equal(visible(view, submission).at(-1).responsePending, false);
    const part = visible(view, submission).at(-1).parts[0];
    assert.equal(part.replyState.phase, phase);
    assert.equal(part.card?.kind, phase === 'streaming' ? 'primary' : undefined);
  }
});
