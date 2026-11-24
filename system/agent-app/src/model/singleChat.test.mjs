import test from 'node:test';
import assert from 'node:assert/strict';
import {
  conversationMode,
  isMainConversation,
  partitionThreads,
  recentTaskThreads,
  resolveSingleChatStartup,
  shouldClaimPrimaryThread,
} from './singleChat.ts';

const threads = [
  {id: 'main-1', title: 'Daily assistant', preview: 'Checked weather', updatedAt: 1795500000},
  {id: 'task-1', title: 'Kyoto trip research', preview: 'Comparing 4 hotels', updatedAt: 1795499000},
  {id: 'task-2', title: 'Book Saturday dinner', preview: 'Table for 4 at 19:00', updatedAt: 1795498000},
  {id: 'task-3', title: 'Review PR #48', preview: 'Checked diff', updatedAt: 1795497000},
  {id: 'task-4', title: 'Flight price watch', preview: 'YHZ -> KIX', updatedAt: 1795496000},
];

test('default startup lands on stored primary single chat', () => {
  const decision = resolveSingleChatStartup({
    storedPrimaryId: 'main-1',
    legacySelectedId: 'task-2',
    threads,
  });
  assert.deepEqual(decision, {targetThreadId: 'main-1', primaryThreadId: 'main-1'});
});

test('explicit deep-link route opens that task while preserving primary single chat', () => {
  const decision = resolveSingleChatStartup({
    explicitRouteId: 'task-2',
    storedPrimaryId: 'main-1',
    threads,
  });
  assert.deepEqual(decision, {targetThreadId: 'task-2', primaryThreadId: 'main-1'});
});

test('first-time upgrade adopts legacy selected thread or newest thread as primary', () => {
  const fromLegacy = resolveSingleChatStartup({
    storedPrimaryId: null,
    legacySelectedId: 'task-1',
    threads,
  });
  assert.deepEqual(fromLegacy, {targetThreadId: 'task-1', primaryThreadId: 'task-1'});

  const fromNewest = resolveSingleChatStartup({
    storedPrimaryId: null,
    legacySelectedId: 'deleted-id',
    threads,
  });
  assert.deepEqual(fromNewest, {targetThreadId: 'main-1', primaryThreadId: 'main-1'});
});

test('offline startup keeps stored primary thread when thread list is empty', () => {
  const decision = resolveSingleChatStartup({
    storedPrimaryId: 'main-1',
    threads: [],
  });
  assert.deepEqual(decision, {targetThreadId: 'main-1', primaryThreadId: 'main-1'});
});

test('brand-new user with no threads lands in empty main chat', () => {
  const decision = resolveSingleChatStartup({
    storedPrimaryId: null,
    threads: [],
  });
  assert.deepEqual(decision, {targetThreadId: '', primaryThreadId: ''});
});

test('partitionThreads separates main chat from independent task sessions', () => {
  const {mainThread, taskThreads} = partitionThreads(threads, 'task-1');
  assert.equal(mainThread?.id, 'task-1');
  assert.deepEqual(taskThreads.map(t => t.id), ['main-1', 'task-2', 'task-3', 'task-4']);
});

test('conversationMode and isMainConversation distinguish main chat and task sessions', () => {
  assert.equal(isMainConversation('', 'main-1'), true);
  assert.equal(isMainConversation('main-1', 'main-1'), true);
  assert.equal(isMainConversation('task-1', 'main-1'), false);
  assert.equal(conversationMode('main-1', 'main-1'), 'main');
  assert.equal(conversationMode('task-1', 'main-1'), 'task');
});

test('shouldClaimPrimaryThread only claims when no primary exists and not an independent task', () => {
  assert.equal(shouldClaimPrimaryThread('', false), true);
  assert.equal(shouldClaimPrimaryThread('', true), false);
  assert.equal(shouldClaimPrimaryThread('main-1', false), false);
  assert.equal(shouldClaimPrimaryThread('main-1', true), false);
});

test('recentTaskThreads caps independent task previews cleanly', () => {
  assert.deepEqual(recentTaskThreads(threads, 'main-1', 2).map(t => t.id), ['task-1', 'task-2']);
  assert.deepEqual(recentTaskThreads(threads, 'main-1', 0), []);
});
