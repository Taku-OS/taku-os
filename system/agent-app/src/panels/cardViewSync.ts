/**
 * Writes the shared card inputs back to native card-view state.
 *
 * The local /ui projection stays visible while an apply is in flight, so typing is never blocked.
 * The revision sent is the one the local edits are based on: if another surface changed the same
 * matter meanwhile, native answers VERSION_CONFLICT, this reads the canonical state, rebases once,
 * and only a second conflict becomes a visible error. A rejected change set is not resent unchanged.
 */
import {applyCardView, readCardView} from './client';
import {cardChanges, getCard, genUiStore, mergeCardView, updateCard} from '../model/genuiStore';
import {record} from '../model/session';

type Timer = ReturnType<typeof setTimeout>;
interface Queue {timer?: Timer; running: boolean; dirty: boolean; conflicts: number; rejected?: string}

const DEBOUNCE_MS = 250;
const queues = new Map<string, Queue>();

const errorCode = (error: unknown): string => {
  const code = record(error).code;
  return typeof code === 'string' && code ? code : error instanceof Error ? error.message : 'UNAVAILABLE';
};

export function scheduleCardViewSync(id: string, immediate = false): void {
  const queue: Queue = queues.get(id) ?? {running: false, dirty: false, conflicts: 0};
  queues.set(id, queue);
  if (immediate) queue.rejected = undefined;
  clearTimeout(queue.timer);
  queue.timer = undefined;
  if (!immediate && JSON.stringify(cardChanges(id)) === queue.rejected) return;
  queue.timer = setTimeout(() => { queue.timer = undefined; void flushCardView(id); }, immediate ? 0 : DEBOUNCE_MS);
}

export async function flushCardView(id: string): Promise<void> {
  const queue: Queue = queues.get(id) ?? {running: false, dirty: false, conflicts: 0};
  queues.set(id, queue);
  if (queue.running) { queue.dirty = true; return; }
  queue.running = true;
  clearTimeout(queue.timer);
  queue.timer = undefined;
  try {
    for (let attempt = 0; attempt < 4; attempt++) {
      const card = getCard(id);
      if (!card?.hydrated) return;
      const changes = cardChanges(id);
      if (!changes.length) { queue.conflicts = 0; return; }
      const fingerprint = JSON.stringify(changes);
      if (fingerprint === queue.rejected) return;
      try {
        mergeCardView(id, await applyCardView(id, card.baseUiRevision, changes), card);
        queue.conflicts = 0;
        queue.rejected = undefined;
        updateCard(id, state => state.syncError === 'VERSION_CONFLICT' ? {...state, syncError: ''} : state);
      } catch (error) {
        const code = errorCode(error);
        if (code === 'VERSION_CONFLICT' && queue.conflicts < 1) {
          queue.conflicts++;
          try { mergeCardView(id, await readCardView(id)); }
          catch (readError) { updateCard(id, state => ({...state, syncError: errorCode(readError)})); return; }
          // The conflict is the rebase: resend this user's pending fields against the canonical revision.
          updateCard(id, state => ({...state, baseUiRevision: state.uiRevision}));
          continue;
        }
        queue.rejected = fingerprint;
        updateCard(id, state => ({...state, syncError: code}));
        return;
      }
    }
  } finally {
    queue.running = false;
    if (queue.dirty) { queue.dirty = false; scheduleCardViewSync(id); }
  }
}

genUiStore.subscribe((next, before) => {
  for (const [id, card] of Object.entries(next.cards)) {
    if (!card.hydrated || card.snapshot.ui === before.cards[id]?.snapshot.ui) continue;
    if (cardChanges(id).length) scheduleCardViewSync(id);
  }
});
