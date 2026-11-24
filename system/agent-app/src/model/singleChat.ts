/**
 * Single Chat (v1) conversation model: one persistent personal main chat alongside
 * lightweight independent task sessions. Pure functions, free of React/native imports.
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
 * An empty selection ('') or a fresh device with no primary thread yet is always main chat.
 */
export function isMainConversation(selectedId: string, primaryThreadId: string): boolean {
  if (!selectedId) return true;
  if (!primaryThreadId) return true;
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
 * - If no primary thread is recorded yet (or it was deleted from the list), promote the
 *   most recent thread (or legacy selected thread if still present) as the primary thread.
 */
export function resolveSingleChatStartup<T extends SingleChatThread>(options: {
  explicitRouteId?: string;
  storedPrimaryId?: string | null;
  legacySelectedId?: string | null;
  threads: readonly T[];
}): SingleChatStartupDecision {
  const explicit = (options.explicitRouteId || '').trim();
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
 * Partition all threads into the pinned personal main chat and independent task sessions.
 * Preserves incoming order (newest first) for task threads.
 */
export function partitionThreads<T extends SingleChatThread>(
  threads: readonly T[],
  primaryThreadId: string,
): PartitionedThreads<T> {
  if (!threads.length) return {mainThread: null, taskThreads: []};
  const resolvedPrimary = primaryThreadId && threads.some(t => t.id === primaryThreadId)
    ? primaryThreadId
    : threads[0].id;
  let mainThread: T | null = null;
  const taskThreads: T[] = [];
  for (const thread of threads) {
    if (!mainThread && thread.id === resolvedPrimary) mainThread = thread;
    else taskThreads.push(thread);
  }
  return {mainThread, taskThreads};
}

/**
 * Whether a newly created thread should be claimed as the personal main Single Chat.
 * True only when no primary thread exists yet and the user is not explicitly creating
 * an independent task session.
 */
export function shouldClaimPrimaryThread(primaryThreadId: string, asIndependentTask: boolean): boolean {
  return !primaryThreadId && !asIndependentTask;
}

/** Recent independent task sessions for quick switching from the main chat stream. */
export function recentTaskThreads<T extends SingleChatThread>(
  threads: readonly T[],
  primaryThreadId: string,
  limit = 3,
): T[] {
  const {taskThreads} = partitionThreads(threads, primaryThreadId);
  return limit > 0 ? taskThreads.slice(0, limit) : [];
}
