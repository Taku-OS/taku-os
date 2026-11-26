/**
 * Single Chat conversation model: TAKU, the one persistent personal main conversation, alongside
 * Tasks, which are every other conversation. Pure functions, free of React/native imports.
 * What a Task is doing right now lives in taskStatus.ts.
 */

export interface SingleChatThread {
  id: string;
  title: string;
  preview: string;
  updatedAt: number;
}

export type ConversationMode = 'main' | 'task';

export interface PartitionedThreads<T extends SingleChatThread = SingleChatThread> {
  mainThread: T | null;
  taskThreads: T[];
}

export interface SingleChatStartupDecision {
  /** Thread ID to open immediately ('' means empty main conversation). */
  targetThreadId: string;
  /** Primary thread ID to persist if non-empty; '' means none exists yet. */
  primaryThreadId: string;
}

/**
 * Determine whether the active thread represents the personal main Single Chat.
 * The empty selection ('') is TAKU before its conversation has a thread: on a fresh device, and
 * after TAKU was started afresh. Any existing thread is TAKU only if it is the primary one.
 */
export function isMainConversation(selectedId: string, primaryThreadId: string): boolean {
  if (!selectedId) return true;
  return selectedId === primaryThreadId;
}

/** Conversation mode for the currently open thread. */
export function conversationMode(selectedId: string, primaryThreadId: string): ConversationMode {
  return isMainConversation(selectedId, primaryThreadId) ? 'main' : 'task';
}

/**
 * Resolve which thread to open on startup when entering Agent Chat:
 * - Explicit deep-link / Panel / Dynamic Island route always wins for targetThreadId.
 * - Otherwise, land directly in the continuous personal main chat (primaryThreadId).
 * - If no primary thread was ever recorded (null: an upgrade from before Single Chat), or the
 *   recorded one left the list, promote the legacy selected thread if still present, else the
 *   most recent thread.
 * - A recorded '' means TAKU was started afresh and has no thread yet: nothing is promoted and
 *   TAKU opens on its empty conversation.
 */
export function resolveSingleChatStartup<T extends SingleChatThread>(options: {
  explicitRouteId?: string;
  storedPrimaryId?: string | null;
  legacySelectedId?: string | null;
  threads: readonly T[];
}): SingleChatStartupDecision {
  const explicit = (options.explicitRouteId || '').trim();
  if (options.storedPrimaryId === '') return {targetThreadId: explicit, primaryThreadId: ''};
  const storedPrimary = (options.storedPrimaryId || '').trim();
  const legacySelected = (options.legacySelectedId || '').trim();
  const threadIds = new Set(options.threads.map(t => t.id));
  const hasThreads = options.threads.length > 0;

  let primaryThreadId = '';
  if (storedPrimary && (!hasThreads || threadIds.has(storedPrimary))) {
    primaryThreadId = storedPrimary;
  } else if (legacySelected && threadIds.has(legacySelected)) {
    primaryThreadId = legacySelected;
  } else if (hasThreads) {
    primaryThreadId = options.threads[0].id;
  }

  const targetThreadId = explicit || primaryThreadId;
  return {targetThreadId, primaryThreadId};
}

/**
 * Partition all threads into TAKU's own thread and the Tasks, which are every other thread.
 * Preserves incoming order (newest first) for the Tasks. While TAKU has no thread of its own
 * (fresh, or started afresh) every thread is a Task: none is promoted in its place.
 */
export function partitionThreads<T extends SingleChatThread>(
  threads: readonly T[],
  primaryThreadId: string,
): PartitionedThreads<T> {
  const mainThread = primaryThreadId ? threads.find(thread => thread.id === primaryThreadId) ?? null : null;
  return {mainThread, taskThreads: threads.filter(thread => thread !== mainThread)};
}

/**
 * Whether a newly created thread should be claimed as the personal main Single Chat.
 * True only when no primary thread exists yet and the user is not explicitly creating
 * an independent task session.
 */
export function shouldClaimPrimaryThread(primaryThreadId: string, asIndependentTask: boolean): boolean {
  return !primaryThreadId && !asIndependentTask;
}
