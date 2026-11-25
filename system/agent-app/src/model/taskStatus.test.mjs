import test from 'node:test';
import assert from 'node:assert/strict';
import {
  applyTaskState, emptyTaskBoard, liveTasks, markAnswered, pollDelay, retainTasks, roundTasks, sessionTaskState,
  taskCards, taskDecision, taskState, IDLE_POLL_MS, LIVE_POLL_MS, SWEEP_EVERY,
} from './taskStatus.ts';

const labels = {allowOnce: 'Allow once', decline: 'Don’t allow'};
const ask = (threadId, key = 'q-1', options = ['Card', 'Wallet']) => ({key, method: 'phone/askUser',
  params: {threadId, turnId: 'turn-1', arguments: {question: 'Which payment method comes first?', options}}});
const read = (threadId, native = {}, task) => ({thread: {id: threadId}, statusOnly: true,
  native: {ready: true, requests: [], unsupportedRequestCount: 0, ...native}, ...(task ? {task} : {})});
const tasks = [
  {id: 'design', title: 'Checkout design'},
  {id: 'build', title: 'Checkout implementation'},
  {id: 'trip', title: 'Kyoto trip'},
];

test('a status-only read becomes Needs you, In progress, Done or Didn’t finish', () => {
  assert.equal(taskState(read('build', {activeTurnId: 'turn-1', requests: [ask('build')]}), 'build').status, 'needsYou');
  assert.equal(taskState(read('build', {activeTurnId: 'turn-1'}), 'build').status, 'inProgress');
  assert.equal(taskState(read('build', {}, {id: '7', state: 'queued', published: true}), 'build').status, 'inProgress');
  assert.equal(taskState(read('build', {}, {id: '7', state: 'completed', published: true}), 'build').status, 'done');
  assert.equal(taskState(read('build'), 'build').status, 'done');
  assert.equal(taskState(read('build', {}, {id: '7', state: 'failed', published: true}), 'build').status, 'failed');
  assert.equal(taskState(read('build', {}, {id: '7', state: 'needs_user', published: true}), 'build').status, 'failed');
});

test('an unpublished or stale task record never reads as work in progress', () => {
  assert.equal(taskState(read('build', {}, {id: '7', state: 'running', published: false}), 'build').status, 'done');
});

test('another thread’s request is not this Task’s question', () => {
  const state = taskState(read('build', {activeTurnId: 'turn-1', requests: [ask('design'), ask('build', 'q-2')]}), 'build');
  assert.deepEqual(state.questions.map(question => question.key), ['q-2']);
});

test('a request the chat cannot draw stops only a Task that is working', () => {
  assert.equal(taskState(read('build', {activeTurnId: 'turn-1', unsupportedRequestCount: 1}), 'build').status, 'needsYou');
  assert.equal(taskState(read('build', {unsupportedRequestCount: 1}), 'build').status, 'done');
});

test('nothing is claimed about a Task while the runtime cannot vouch for it', () => {
  assert.equal(taskState(read('build', {ready: false, activeTurnId: 'turn-1'}), 'build'), null);
  assert.equal(taskState({thread: {id: 'build'}}, 'build'), null);
  assert.equal(taskState(null, 'build'), null);
});

test('the open conversation reports the same state from its session', () => {
  const session = {ready: true, running: true, questions: [], nativeOnlyRequests: 0, taskFailure: null};
  assert.equal(sessionTaskState(session).status, 'inProgress');
  assert.equal(sessionTaskState({...session, questions: [ask('build')]}).status, 'needsYou');
  assert.equal(sessionTaskState({...session, nativeOnlyRequests: 1}).status, 'needsYou');
  assert.equal(sessionTaskState({...session, running: false, nativeOnlyRequests: 1}).status, 'done');
  assert.equal(sessionTaskState({...session, running: false, taskFailure: {taskId: '7'}}).status, 'failed');
  assert.equal(sessionTaskState({...session, running: false}).status, 'done');
  assert.equal(sessionTaskState({...session, ready: false}), null);
});

test('an unchanged read keeps the same board so nothing re-renders', () => {
  const first = applyTaskState(emptyTaskBoard, 'build', taskState(read('build', {activeTurnId: 'turn-1', requests: [ask('build')]}), 'build'));
  const again = applyTaskState(first, 'build', taskState(read('build', {activeTurnId: 'turn-1', requests: [ask('build')]}), 'build'));
  assert.equal(again, first);
  const next = applyTaskState(first, 'build', taskState(read('build', {activeTurnId: 'turn-1', requests: [ask('build', 'q-2')]}), 'build'));
  assert.notEqual(next, first);
  assert.equal(next.states.build.questions[0].key, 'q-2');
});

test('an unknown state forgets the Task instead of keeping its last capsule', () => {
  const board = applyTaskState(emptyTaskBoard, 'build', {status: 'inProgress', questions: []});
  assert.deepEqual(applyTaskState(board, 'build', null).states, {});
  assert.equal(applyTaskState(emptyTaskBoard, 'build', null), emptyTaskBoard);
});

test('capsules list only Tasks at work and keep the list’s order when a status changes', () => {
  let board = emptyTaskBoard;
  board = applyTaskState(board, 'design', {status: 'inProgress', questions: []});
  board = applyTaskState(board, 'build', {status: 'needsYou', questions: [ask('build')]});
  board = applyTaskState(board, 'trip', {status: 'done', questions: []});
  assert.deepEqual(liveTasks(tasks, board).map(item => [item.thread.id, item.status]),
    [['design', 'inProgress'], ['build', 'needsYou']]);
  // The answered Task turns in place: its capsule does not jump past the other.
  board = applyTaskState(board, 'build', {status: 'inProgress', questions: []});
  assert.deepEqual(liveTasks(tasks, board).map(item => [item.thread.id, item.status]),
    [['design', 'inProgress'], ['build', 'inProgress']]);
  assert.deepEqual(liveTasks(tasks, emptyTaskBoard), []);
});

test('choosing an option in the main stream turns the capsule and the card to In progress together', () => {
  // The Task asks; the main stream shows its card with the choices.
  let board = applyTaskState(emptyTaskBoard, 'build', taskState(read('build', {activeTurnId: 'turn-1', requests: [ask('build')]}), 'build'));
  let [card] = taskCards(tasks, board, labels);
  assert.equal(card.status, 'needsYou');
  assert.deepEqual(card.decision.options, [{label: 'Card', response: {optionIndex: 0}}, {label: 'Wallet', response: {optionIndex: 1}}]);
  assert.equal(liveTasks(tasks, board)[0].status, 'needsYou');
  // The answer was accepted and the next read shows the turn still running without the request.
  board = markAnswered(board, 'build', 'Card');
  board = applyTaskState(board, 'build', taskState(read('build', {activeTurnId: 'turn-1'}), 'build'));
  [card] = taskCards(tasks, board, labels);
  assert.deepEqual({status: card.status, decision: card.decision, answer: card.answer}, {status: 'inProgress', decision: null, answer: 'Card'});
  assert.equal(liveTasks(tasks, board)[0].status, 'inProgress');
});

test('a Task in progress has a card only after the user answered it from the main stream', () => {
  const board = applyTaskState(emptyTaskBoard, 'design', {status: 'inProgress', questions: []});
  assert.deepEqual(taskCards(tasks, board, labels), []);
});

test('the answered card leaves when the Task finishes and gives way to its next question', () => {
  const answered = markAnswered(applyTaskState(emptyTaskBoard, 'build', {status: 'inProgress', questions: []}), 'build', 'Card');
  const finished = applyTaskState(answered, 'build', {status: 'done', questions: []});
  assert.deepEqual(finished.answered, {});
  assert.deepEqual(taskCards(tasks, finished, labels), []);
  const asked = applyTaskState(answered, 'build', {status: 'needsYou', questions: [ask('build', 'q-2', ['Yes', 'No'])]});
  const [card] = taskCards(tasks, asked, labels);
  assert.equal(card.answer, '');
  assert.deepEqual(card.decision.options.map(option => option.label), ['Yes', 'No']);
  // A later, unrelated run of the same Task does not bring the old answer back.
  assert.deepEqual(taskCards(tasks, applyTaskState(finished, 'build', {status: 'inProgress', questions: []}), labels), []);
});

test('an approval is answerable in place with the page’s own responses', () => {
  const decision = taskDecision([{key: 'a-1', method: 'phone/action/requestApproval', params: {threadId: 'build', reason: 'Send the order email'}}], labels);
  assert.deepEqual(decision, {key: 'a-1', prompt: 'Send the order email', options: [
    {label: 'Allow once', response: {decision: 'accept'}}, {label: 'Don’t allow', response: {decision: 'decline'}}]});
});

test('a confirmation with details to review is opened, not answered from the card', () => {
  const decision = taskDecision([{key: 'c-1', method: 'phone/confirmAction', params: {threadId: 'build',
    arguments: {title: 'Pay ¥14,000?', summary: 'Reserved seat to Kyoto', fields: [{label: 'Amount', value: '¥14,000'}], confirmLabel: 'Pay'}}}], labels);
  assert.deepEqual(decision, {key: 'c-1', prompt: 'Pay ¥14,000?\nReserved seat to Kyoto', options: []});
});

test('a single choice question from the runtime keeps its answer shape', () => {
  const question = {key: 'u-1', method: 'item/tool/requestUserInput', params: {threadId: 'build',
    questions: [{id: 'method', question: 'Which payment method comes first?', options: [{label: 'Card'}, {label: 'Wallet'}]}]}};
  assert.deepEqual(taskDecision([question], labels), {key: 'u-1', prompt: 'Which payment method comes first?', options: [
    {label: 'Card', response: {answers: {method: {answers: ['Card']}}}},
    {label: 'Wallet', response: {answers: {method: {answers: ['Wallet']}}}}]});
});

test('free text, secrets, several questions and long lists stay on the Task’s page', () => {
  const input = questions => taskDecision([{key: 'u-1', method: 'item/tool/requestUserInput', params: {threadId: 'build', questions}}], labels);
  assert.deepEqual(input([{id: 'a', question: 'Name?'}]).options, []);
  assert.deepEqual(input([{id: 'a', question: 'Token?', isSecret: true, options: [{label: 'x'}]}]).options, []);
  assert.deepEqual(input([{id: 'a', question: 'A?', options: [{label: 'x'}]}, {id: 'b', question: 'B?', options: [{label: 'y'}]}]).options, []);
  assert.deepEqual(taskDecision([ask('build', 'q-1', ['1', '2', '3', '4', '5'])], labels).options, []);
  assert.deepEqual(taskDecision([ask('build', 'q-1', ['Card', 7])], labels).options, []);
  assert.deepEqual(taskDecision([{key: 'x-1', method: 'something/else', params: {threadId: 'build'}}], labels), {key: 'x-1', prompt: '', options: []});
  assert.deepEqual(taskDecision([], labels), {key: '', prompt: '', options: []});
});

test('the board follows live Tasks closely and only checks in otherwise', () => {
  const live = applyTaskState(emptyTaskBoard, 'build', {status: 'inProgress', questions: []});
  assert.equal(pollDelay(live, ['design', 'build']), LIVE_POLL_MS);
  assert.equal(pollDelay(live, ['design']), IDLE_POLL_MS);
  assert.equal(pollDelay(applyTaskState(emptyTaskBoard, 'build', {status: 'done', questions: []}), ['build']), IDLE_POLL_MS);
});

test('between sweeps a round reads only the Tasks at work', () => {
  const ids = ['design', 'build', 'trip'];
  const live = applyTaskState(applyTaskState(emptyTaskBoard, 'build', {status: 'needsYou', questions: []}), 'trip', {status: 'done', questions: []});
  assert.deepEqual(roundTasks(live, ids, 0), ids, 'the first round reads every followed Task');
  assert.deepEqual(roundTasks(live, ids, 1), ['build']);
  assert.deepEqual(roundTasks(live, ids, SWEEP_EVERY - 1), ['build']);
  assert.deepEqual(roundTasks(live, ids, SWEEP_EVERY), ids);
  const idle = applyTaskState(emptyTaskBoard, 'build', {status: 'done', questions: []});
  assert.deepEqual(roundTasks(idle, ids, 1), ids, 'with nothing at work every round is a sweep');
});

test('a Task that left the list cannot keep a capsule or an answer', () => {
  const board = markAnswered(applyTaskState(applyTaskState(emptyTaskBoard, 'build', {status: 'inProgress', questions: []}),
    'gone', {status: 'needsYou', questions: []}), 'gone', 'Yes');
  const kept = retainTasks(board, ['build']);
  assert.deepEqual(Object.keys(kept.states), ['build']);
  assert.deepEqual(kept.answered, {});
  assert.equal(retainTasks(kept, ['build', 'design']), kept);
});
