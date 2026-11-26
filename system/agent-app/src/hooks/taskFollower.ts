/**
 * Follows the Tasks TAKU's main page shows: reads each one's status in turn through the gateway,
 * keeps the board the capsules, the drawer and the cards are drawn from, and carries a choice
 * made in the main stream to that Task. It reads and answers; it never starts, stops or resends
 * anything, and it holds no state the native thread does not already have. Free of React.
 */
import type {Json} from '../model/session.ts';
import {applyTaskState, emptyTaskBoard, markAnswered, pollDelay, retainTasks, roundTasks, taskState, type TaskBoard, type TaskState} from '../model/taskStatus.ts';

export interface TaskFollowerHost {
  /** The gateway's status-only read of one thread (`readSession {threadId, statusOnly:true}`). */
  read: (threadId: string) => Promise<unknown>;
  /** The gateway's `answer` for one pending request of that thread. */
  answer: (threadId: string, key: string, response: Json) => Promise<unknown>;
  /** Called with every board that differs from the last one. */
  publish: (board: TaskBoard) => void;
  /** Timers, replaceable in tests. */
  schedule?: (run: () => void, ms: number) => unknown;
  cancel?: (handle: unknown) => void;
}

export interface TaskFollower {
  /** Follows exactly these Tasks from now on, reading them at once. An empty list follows none. */
  follow(ids: readonly string[]): void;
  /** Stops reading. What is known is kept until the next [follow] reads it again. */
  stop(): void;
  /** The open conversation's own state, which the chat already follows live. */
  observe(id: string, state: TaskState | null): void;
  /**
   * Sends the user's choice to the Task's pending request. Resolves true once the gateway
   * accepted it; the Task is read again either way, so the board shows what is actually true.
   */
  answer(id: string, key: string, label: string, response: Json): Promise<boolean>;
}

export function createTaskFollower(host: TaskFollowerHost): TaskFollower {
  const schedule = host.schedule ?? ((run: () => void, ms: number) => setTimeout(run, ms));
  const cancel = host.cancel ?? ((handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>));
  const tickets = new Map<string, number>(), missed = new Map<string, number>();
  let board = emptyTaskBoard, ids: string[] = [], following = false, generation = 0, timer: unknown;

  const publish = (next: TaskBoard) => {
    if (next === board) return;
    board = next;
    host.publish(board);
  };
  const ticketFor = (id: string) => {
    const ticket = (tickets.get(id) ?? 0) + 1;
    tickets.set(id, ticket);
    return ticket;
  };

  const read = async (id: string): Promise<void> => {
    const ticket = ticketFor(id);
    let state: TaskState | null;
    try {
      state = taskState(await host.read(id), id);
      missed.delete(id);
    } catch {
      // One missed read keeps what was known; a second in a row means it no longer is.
      const count = (missed.get(id) ?? 0) + 1;
      missed.set(id, count);
      if (count < 2) return;
      state = null;
    }
    // A slower, older read never overwrites what a newer one or the open session reported.
    if (tickets.get(id) !== ticket || !ids.includes(id)) return;
    publish(applyTaskState(board, id, state));
  };

  const cycle = async (run: number, round: number): Promise<void> => {
    // One read at a time: the board never costs the gateway more than a single call in flight.
    for (const id of roundTasks(board, ids, round)) {
      if (run !== generation) return;
      await read(id);
    }
    if (run !== generation) return;
    timer = schedule(() => { void cycle(run, round + 1); }, pollDelay(board, ids));
  };

  const halt = () => {
    generation++;
    if (timer !== undefined) cancel(timer);
    timer = undefined;
    following = false;
  };

  return {
    follow(next) {
      if (following && next.length === ids.length && next.every((id, index) => id === ids[index])) return;
      halt();
      ids = [...next];
      publish(retainTasks(board, ids));
      if (!ids.length) return;
      following = true;
      void cycle(generation, 0);
    },
    stop: halt,
    observe(id, state) {
      if (!ids.includes(id)) return;
      ticketFor(id);
      publish(applyTaskState(board, id, state));
    },
    async answer(id, key, label, response) {
      let accepted = true;
      try { await host.answer(id, key, response); } catch { accepted = false; }
      if (accepted && ids.includes(id)) {
        // The broker consumed the answer with its request, so that request is gone and the turn
        // that asked goes on. The read below confirms it.
        const remaining = (board.states[id]?.questions ?? []).filter(question => question.key !== key);
        ticketFor(id);
        publish(applyTaskState(markAnswered(board, id, label), id,
          {status: remaining.length ? 'needsYou' : 'inProgress', questions: remaining}));
      }
      await read(id);
      return accepted;
    },
  };
}
