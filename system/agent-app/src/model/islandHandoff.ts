import type {OptimisticSubmission} from './optimisticChat';
import type {SessionView} from './session';

/**
 * What the chat tells native about its running turn, so leaving it can hand the turn to the
 * island. [knownTurnIds] are the conversation's turns before the send when [turnId] is not known
 * yet; Home waits for the first other turn, even one that already finished. Null when unknown.
 */
export interface HandoffCandidate {threadId: string; turnId: string; taskId: string; knownTurnIds: string[] | null}

/** Home accepts at most this many earlier turns; the newest are the ones that matter. */
export const MAX_KNOWN_TURNS = 200;

// All earlier turns, including ones that showed nothing: Home must never take one of them for the new turn.
const turnsOf = (session: SessionView | null) => session
  ? [...new Set([...(session.turnIds ?? []), ...session.blocks.map(block => block.turnId)])] : null;
const newest = (ids: string[] | null) => ids && ids.slice(-MAX_KNOWN_TURNS);

/**
 * Whether the chat's own message for the selected conversation is still on its way (from the tap
 * until the gateway accepts it and its turn is known). Leaving during it keeps the handoff pending
 * until the conversation is known, which for a new chat is only after its thread is created.
 */
export function sendInFlight(selected: string, optimistic: readonly OptimisticSubmission[]): boolean {
  return optimistic.some(request => request.threadId === selected && (request.state === 'sending' || request.state === 'accepted'));
}

/**
 * The conversation whose turn is still running in the chat, or null (BUG-0027). Home opens the
 * island card when that turn has a result, so it needs the turn to wait for: the active turn, or,
 * before it is known (a message still being sent, a queued task), the turns that came before it.
 * The chat's own message row exists from the tap, so leaving right after Send still hands off. A
 * conversation waiting on the user's answer is not handed off; the user just left that question.
 */
export function handoffCandidate(selected: string, session: SessionView | null, pendingThreadId: string | null,
  optimistic: readonly OptimisticSubmission[] = []): HandoffCandidate | null {
  if (!selected) return null;
  const current = session?.id === selected ? session : null;
  if (current?.questions.length) return null;
  if (current?.activeTurnId) return {threadId: selected, turnId: current.activeTurnId, taskId: '', knownTurnIds: null};
  const sending = [...optimistic].reverse().find(request => request.threadId === selected
    && (request.state === 'sending' || request.state === 'accepted'));
  if (sending) {
    const turnId = sending.turnId || (sending.action === 'steer' ? sending.expectedTurnId || '' : '');
    // Only this send's own task: the session may still report an earlier, cancelled one.
    return {threadId: selected, turnId, taskId: sending.taskId || '',
      knownTurnIds: turnId ? null : newest(sending.baselineTurnIds)};
  }
  if (current?.queued) return {threadId: selected, turnId: '', taskId: current.taskId, knownTurnIds: newest(turnsOf(current))};
  if (current?.running) {
    const active = [...current.blocks].reverse().find(block => block.active);
    return {threadId: selected, turnId: active?.turnId ?? '', taskId: '', knownTurnIds: null};
  }
  // Sent but not yet reported by the session: Home waits for the first turn after the ones it has.
  if (pendingThreadId === selected) return {threadId: selected, turnId: '', taskId: '', knownTurnIds: newest(turnsOf(current))};
  return null;
}
