import type {SessionView} from './session';

/** What the chat tells native about its running turn, so leaving it can hand the turn to the island. */
export interface HandoffCandidate {threadId: string; turnId: string; taskId: string}

/**
 * The conversation whose turn is still running in the chat, or null (BUG-0027). Home opens the
 * island card when that turn has a result, so it needs the turn to wait for: the active turn, or
 * the queued task whose turn has not started. A conversation waiting on the user's answer is not
 * handed off; the user just left that question.
 */
export function handoffCandidate(selected: string, session: SessionView | null, pendingThreadId: string | null): HandoffCandidate | null {
  if (!selected) return null;
  const current = session?.id === selected ? session : null;
  if (current?.questions.length) return null;
  if (current?.activeTurnId) return {threadId: selected, turnId: current.activeTurnId, taskId: ''};
  if (current?.queued) return {threadId: selected, turnId: '', taskId: current.taskId};
  if (current?.running) {
    const active = [...current.blocks].reverse().find(block => block.active);
    return {threadId: selected, turnId: active?.turnId ?? '', taskId: ''};
  }
  // Sent but not yet reported by the session: Home waits for the first turn that starts.
  if (pendingThreadId === selected) return {threadId: selected, turnId: '', taskId: ''};
  return null;
}
