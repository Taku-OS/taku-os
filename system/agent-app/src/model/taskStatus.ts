/**
 * What TAKU shows about a Task outside its own page: the status capsules under the title, the
 * drawer's list and the decision cards in the main stream. Everything here is derived from the
 * gateway's read-only `readSession {statusOnly:true}` view of that Task's thread (its active turn,
 * its pending requests and its published task), never from message text. Pure functions, free of
 * React and native imports.
 */
import {list, record, string, type Json, type PendingQuestion, type SessionView} from './session.ts';

export type TaskStatus = 'needsYou' | 'inProgress' | 'done' | 'failed';
/** The statuses of a Task that is still at work; only these earn a capsule or a card. */
export type LiveTaskStatus = 'needsYou' | 'inProgress';

export interface TaskState {
  status: TaskStatus;
  /** The requests this Task is waiting on, oldest first, that a card can show. */
  questions: PendingQuestion[];
}

/**
 * The statuses read so far, by thread ID, and the Tasks the user answered from the main stream
 * (the label they chose), remembered only while that Task keeps working on the answer.
 */
export interface TaskBoard {
  states: Readonly<Record<string, TaskState>>;
  answered: Readonly<Record<string, string>>;
}
export const emptyTaskBoard: TaskBoard = {states: {}, answered: {}};

/**
 * While any Task is at work the board follows the Tasks at work closely and looks at the others
 * only every [SWEEP_EVERY]th round; with none at work it only checks in on all of them.
 */
export const LIVE_POLL_MS = 3000;
export const IDLE_POLL_MS = 10000;
export const SWEEP_EVERY = 3;
/** The newest Tasks whose status the main page follows, and the drawer's wider list. */
export const STRIP_TASKS = 8;
export const DRAWER_TASKS = 30;
/** A card offers at most this many choices; a longer list is answered on the Task's own page. */
export const MAX_CARD_OPTIONS = 4;

const isLive = (status: TaskStatus | undefined): status is LiveTaskStatus => status === 'needsYou' || status === 'inProgress';

/**
 * A Task's state from a status-only read of its thread, or null when the runtime could not vouch
 * for it (the broker is unreachable), in which case nothing is claimed about that Task.
 */
export function taskState(read: unknown, threadId: string): TaskState | null {
  const root = record(read), native = record(root.native), task = record(root.task);
  if (native.ready !== true) return null;
  const questions = list(native.requests).map(record)
    .filter(question => string(question.key) && record(question.params).threadId === threadId)
    .map(question => ({key: string(question.key), method: string(question.method), params: record(question.params)}));
  const unsupported = typeof native.unsupportedRequestCount === 'number' ? native.unsupportedRequestCount : 0;
  const working = Boolean(string(native.activeTurnId))
    || task.published === true && ['queued', 'running'].includes(string(task.state));
  // A request the chat cannot draw (a credential, a global runtime prompt) still stops a working Task.
  const status: TaskStatus = questions.length > 0 || working && unsupported > 0 ? 'needsYou'
    : working ? 'inProgress'
    : ['failed', 'needs_user'].includes(string(task.state)) ? 'failed' : 'done';
  return {status, questions};
}

/** The same state for the conversation that is open, from the session the chat already follows. */
export function sessionTaskState(session: SessionView): TaskState | null {
  if (!session.ready) return null;
  const status: TaskStatus = session.questions.length > 0 || session.running && session.nativeOnlyRequests > 0 ? 'needsYou'
    : session.running ? 'inProgress' : session.taskFailure ? 'failed' : 'done';
  return {status, questions: session.questions};
}

function sameState(a: TaskState | undefined, b: TaskState): boolean {
  return !!a && a.status === b.status && a.questions.length === b.questions.length
    && a.questions.every((question, index) => question.key === b.questions[index].key);
}

function without<T>(map: Readonly<Record<string, T>>, id: string): Readonly<Record<string, T>> {
  if (!(id in map)) return map;
  const next = {...map};
  delete next[id];
  return next;
}

/**
 * Records one Task's newly read state. Null forgets the Task (its state is unknown). An answer
 * given from the main stream is remembered only while that Task is still in progress. Returns the
 * same board when nothing changed, so an unchanged poll never re-renders.
 */
export function applyTaskState(board: TaskBoard, id: string, state: TaskState | null): TaskBoard {
  const states = state === null ? without(board.states, id)
    : sameState(board.states[id], state) ? board.states : {...board.states, [id]: state};
  const answered = state?.status === 'inProgress' ? board.answered : without(board.answered, id);
  return states === board.states && answered === board.answered ? board : {states, answered};
}

/** The user chose [label] for this Task from the main stream. */
export function markAnswered(board: TaskBoard, id: string, label: string): TaskBoard {
  return board.answered[id] === label ? board : {...board, answered: {...board.answered, [id]: label}};
}

/** Keeps only the Tasks still followed, so a Task that left the list cannot keep a capsule. */
export function retainTasks(board: TaskBoard, ids: readonly string[]): TaskBoard {
  const keep = new Set(ids);
  const pick = <T>(map: Readonly<Record<string, T>>) => Object.keys(map).every(id => keep.has(id)) ? map
    : Object.fromEntries(Object.entries(map).filter(([id]) => keep.has(id)));
  const states = pick(board.states), answered = pick(board.answered);
  return states === board.states && answered === board.answered ? board : {states, answered};
}

/** How long to wait before reading the followed Tasks again. */
export function pollDelay(board: TaskBoard, ids: readonly string[]): number {
  return ids.some(id => isLive(board.states[id]?.status)) ? LIVE_POLL_MS : IDLE_POLL_MS;
}

/**
 * The Tasks one round reads. Every [SWEEP_EVERY]th round, and every round while none is at work,
 * reads them all; the rounds between read only the Tasks at work, whose state is what changes.
 */
export function roundTasks(board: TaskBoard, ids: readonly string[], round: number): string[] {
  const live = ids.filter(id => isLive(board.states[id]?.status));
  return round % SWEEP_EVERY === 0 || !live.length ? [...ids] : live;
}

/**
 * The capsules under the title: the Tasks at work, in the list's own order (newest first). A
 * capsule keeps its place when its status changes, so answering a Task turns its capsule where
 * the eye already is instead of moving it.
 */
export function liveTasks<T extends {id: string}>(tasks: readonly T[], board: TaskBoard): {thread: T; status: LiveTaskStatus}[] {
  const live: {thread: T; status: LiveTaskStatus}[] = [];
  for (const thread of tasks) {
    const status = board.states[thread.id]?.status;
    if (isLive(status)) live.push({thread, status});
  }
  return live;
}

export interface TaskDecisionOption {
  label: string;
  /** The response the gateway's `answer` action takes for this choice. */
  response: Json;
}
export interface TaskDecision {
  /** The pending request the options answer; '' when there is nothing a card can answer. */
  key: string;
  /** What the Task is asking, in its own words; '' when the request carries no text. */
  prompt: string;
  /** Choices a card may offer in place. Empty: the request is reviewed on the Task's page. */
  options: TaskDecisionOption[];
}

const lines = (...parts: string[]) => parts.map(part => part.trim()).filter(Boolean).join('\n');

/**
 * What a card shows for the request a Task is waiting on. Only plain choices and one-tap
 * approvals are answerable in place, with the same response the Task's own page would send. A
 * confirmation that lists details to review, free text and anything with more than
 * [MAX_CARD_OPTIONS] choices stays on the Task's page, where the whole request is visible.
 */
export function taskDecision(questions: readonly PendingQuestion[], labels: {allowOnce: string; decline: string}): TaskDecision {
  const question = questions[0];
  if (!question) return {key: '', prompt: '', options: []};
  const args = record(question.params.arguments);
  const few = (options: TaskDecisionOption[]) => options.length > 0 && options.length <= MAX_CARD_OPTIONS ? options : [];
  switch (question.method) {
    case 'phone/askUser': {
      const choices = list(args.options);
      const options = choices.every(choice => typeof choice === 'string' && choice.trim())
        ? choices.map((choice, optionIndex) => ({label: string(choice).trim(), response: {optionIndex}})) : [];
      return {key: question.key, prompt: lines(string(args.title) || string(args.question), string(args.summary)), options: few(options)};
    }
    case 'phone/action/requestApproval':
      return {key: question.key, prompt: string(question.params.reason).trim(), options: [
        {label: labels.allowOnce, response: {decision: 'accept'}}, {label: labels.decline, response: {decision: 'decline'}}]};
    case 'phone/confirmAction':
      return {key: question.key, prompt: lines(string(args.title) || string(args.question), string(args.summary)), options: []};
    case 'item/tool/requestUserInput': {
      const asked = list(question.params.questions).map(record), only = asked[0];
      const id = only ? string(only.id) : '';
      const choices = asked.length === 1 && id && only.isSecret !== true ? list(only.options).map(record) : [];
      const options = choices.every(choice => string(choice.label).trim())
        ? choices.map(choice => ({label: string(choice.label).trim(), response: {answers: {[id]: {answers: [string(choice.label)]}}}})) : [];
      return {key: question.key, prompt: only ? string(only.question).trim() : '', options: few(options)};
    }
    default:
      return {key: question.key, prompt: '', options: []};
  }
}

export interface TaskCard<T> {
  thread: T;
  status: LiveTaskStatus;
  /** The request to decide; null once the Task is working on the user's answer. */
  decision: TaskDecision | null;
  /** The choice the user made from the main stream; '' while the Task still needs them. */
  answer: string;
}

/**
 * The Task cards in the main stream: every Task that needs the user, and a Task the user just
 * answered from here for as long as it keeps working on that answer.
 */
export function taskCards<T extends {id: string}>(tasks: readonly T[], board: TaskBoard,
  labels: {allowOnce: string; decline: string}): TaskCard<T>[] {
  const cards: TaskCard<T>[] = [];
  for (const thread of tasks) {
    const state = board.states[thread.id];
    if (state?.status === 'needsYou') cards.push({thread, status: 'needsYou', decision: taskDecision(state.questions, labels), answer: ''});
    else if (state?.status === 'inProgress' && board.answered[thread.id])
      cards.push({thread, status: 'inProgress', decision: null, answer: board.answered[thread.id]});
  }
  return cards;
}
