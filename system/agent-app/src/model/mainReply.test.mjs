import test from 'node:test';
import assert from 'node:assert/strict';
import {parseMainReply} from './genuiCompose.ts';
import {projectSession} from './session.ts';
import {emptySessionStream, reduceSessionEvent, retainSessionBlocks, streamSessionRoot} from './sessionStream.ts';
import {mainReply, replyItems} from './mainReplyFixtures.mjs';

const event = (seq, method, params = {}, brokerEpoch = 'epoch') => ({schema: 'taku.session-events/1', brokerEpoch, threadId: 'thread', seq, method, params});
const user = id => ({id, type: 'userMessage', content: [{type: 'text', text: 'Read my notes'}]});
const snapshot = (seq, reply, items = replyItems(reply), brokerEpoch = 'epoch') => event(seq, 'session/snapshot', {thread: {id: 'thread', turns: [
  {id: 'turn', status: 'inProgress', mainReplies: {[reply.id]: reply}, items: [user('user'), ...items]},
]}}, brokerEpoch);
const display = state => projectSession(streamSessionRoot(state, {native: {ready: true}}));
const replyPart = view => view.blocks.flatMap(block => block.parts).find(part => part.card?.kind === 'primary');

test('one ordered reply event installs native data, view, readable text and sanitized source items atomically', () => {
  let state = reduceSessionEvent(emptySessionStream('thread'), event(0, 'session/snapshot', {thread: {id: 'thread', turns: [{id: 'turn', status: 'inProgress', items: [user('user')]}]}}));
  const reply = mainReply('thread', 'atomic');
  assert.equal(replyPart(display(state)), undefined);
  state = reduceSessionEvent(state, event(1, 'session/reply', {turnId: 'turn', reply, items: replyItems(reply)}));
  const after = display(state), part = replyPart(after);
  assert.equal(state.turns.turn.data.mainReplies[reply.id], reply);
  assert.equal(part.card.reply, reply);
  assert.equal(part.card.reply.card.view.spec.root, 'native-card');
  assert.equal(part.text, reply.readableText);
  assert.equal(after.blocks.at(-1).text, reply.readableText);
  assert.equal(state.turns.turn.items.answer.text, reply.readableText);
  assert.equal(reduceSessionEvent(state, event(1, 'session/reply', {turnId: 'turn', reply, items: replyItems(reply)})), state);
  state = reduceSessionEvent(state, event(2, 'item/agentMessage/delta', {turnId: 'turn', itemId: 'answer', delta: '@taku.reply/1\n{"raw":"wire"}'}));
  assert.equal(display(state).blocks.at(-1).text, reply.readableText);
});

test('terminal updates, canonical item aliases and reconnect preserve primary card and row keys', () => {
  const first = mainReply('thread', 'canonical'), state = reduceSessionEvent(emptySessionStream('thread'), snapshot(0, first));
  const before = display(state), partId = replyPart(before).id, blockId = before.blocks.at(-1).id;
  const final = mainReply('thread', first.key, 2, 'completed', {provenance: {...first.provenance, sourceItemIds: ['canonical-answer']}, readableText: 'Final readable answer'});
  const restored = reduceSessionEvent(state, snapshot(2, final, replyItems(final), 'restarted'));
  const view = display(restored);
  assert.equal(replyPart(view).id, partId);
  assert.equal(view.blocks.at(-1).id, blockId);
  assert.equal(replyPart(view).card.reply.id, first.id);
  assert.equal(replyPart(view).card.reply.lifecycle.phase, 'completed');
  assert.equal(view.blocks.at(-1).text, 'Final readable answer');
  assert.equal(replyPart(view).active, false);
  const unchanged = retainSessionBlocks(display(restored), view);
  assert.equal(unchanged.blocks.at(-1), view.blocks.at(-1));
});

test('multi-item replies expose exactly one readable answer and keep commentary and native tool history', () => {
  const reply = mainReply('thread', 'multi-source', 1, 'streaming');
  reply.provenance.sourceItemIds = ['answer', 'continuation'];
  const items = [user('user'), {id: 'comment', type: 'agentMessage', phase: 'commentary', text: 'Checking notes'},
    {id: 'tool', type: 'commandExecution', command: 'read notes', status: 'completed'}, ...replyItems(reply)];
  const view = projectSession({thread: {id: 'thread', turns: [{id: 'turn', status: 'inProgress', items, mainReplies: {[reply.id]: reply}}]}});
  assert.equal(view.blocks.at(-1).parts.length, 1);
  assert.equal(view.blocks.at(-1).text, reply.readableText);
  assert.deepEqual(view.blocks.at(-1).activity.map(item => item.id), ['turn/comment', 'turn/tool']);
});

test('invalid or unsanitized atomic reply events retain the last accepted view and require resync', () => {
  const reply = mainReply('thread', 'reject-atomic');
  const initial = reduceSessionEvent(emptySessionStream('thread'), snapshot(0, reply));
  for (const mutate of [value => { value.reply.provenance.threadId = 'other'; }, value => { value.items[0].text = '@taku.reply/1\n{}'; },
    value => { value.reply.card.view.signature = 'wrong'; }, value => { value.items = []; }]) {
    const params = {turnId: 'turn', reply: structuredClone({...reply, revision: 2}), items: replyItems(reply)};
    mutate(params);
    const rejected = reduceSessionEvent(initial, event(1, 'session/reply', params));
    assert.equal(rejected.needsResync, true);
    assert.equal(rejected.seq, 0);
    assert.equal(replyPart(display(rejected)).card.reply, reply);
  }
});

test('bridge admission enforces current view, semantic signature, primary metrics and provenance', () => {
  const reply = mainReply('thread', 'bridge-validation');
  assert.equal(parseMainReply(reply, 'thread', 'turn'), reply);
  for (const mutate of [value => { value.card.view.catalogVersion = 'old'; }, value => { value.card.view.signature = 'old'; },
    value => { value.card.view.metrics.inputTokens = 10; }, value => { value.card.request.actions[0].actionRef = 'forged'; },
    value => { value.provenance.contractHash = ''; }, value => { value.provenance.turnId = 'other'; },
    value => { value.card.view.spec.elements['block-note'].props.value = 'Invalid typed source'; }]) {
    const invalid = structuredClone(reply);
    mutate(invalid);
    assert.equal(parseMainReply(invalid, 'thread', 'turn'), null);
  }
});

test('primary prefixes may omit component coverage until sealed without client repair', () => {
  const reply = mainReply('thread', 'partial-view');
  reply.card.view.spec = {root: 'native-card', elements: {'native-card': {...reply.card.view.spec.elements['native-card'], children: []}}};
  for (const phase of ['streaming', 'failed', 'interrupted']) {
    const partial = {...reply, lifecycle: {phase}};
    assert.equal(parseMainReply(partial, 'thread', 'turn'), partial);
    assert.deepEqual(Object.keys(partial.card.view.spec.elements), ['native-card']);
  }
  for (const phase of ['sealed', 'completed']) assert.equal(parseMainReply({...reply, lifecycle: {phase}}, 'thread', 'turn'), null);
});

test('failed, interrupted and unavailable views remain honest and never expose framed wire text', () => {
  for (const phase of ['failed', 'interrupted']) {
    const reply = mainReply('thread', phase, 2, phase);
    const view = display(reduceSessionEvent(emptySessionStream('thread'), snapshot(0, reply)));
    assert.equal(replyPart(view).replyState.phase, phase);
    assert.equal(view.blocks.at(-1).text, reply.readableText);
  }
  const reply = mainReply('thread', 'catalog-mismatch');
  reply.card.view.catalogVersion = 'old';
  const view = display(reduceSessionEvent(emptySessionStream('thread'), snapshot(0, reply)));
  assert.equal(replyPart(view), undefined);
  assert.equal(view.blocks.at(-1).parts[0].replyState.code, 'INVALID_REPLY_VIEW');
  assert.equal(view.blocks.at(-1).text, reply.readableText);
  const raw = projectSession({thread: {id: 'thread', turns: [{id: 'turn', status: 'completed', items: [{id: 'answer', type: 'agentMessage', text: '@taku.reply/2\n{"secret":"wire"}'}]}]}});
  assert.equal(raw.blocks.at(-1).text, '');
  assert.equal(raw.blocks.at(-1).parts[0].replyState.code, 'INVALID_REPLY_FRAME');
});
