import {list, record, string, type Json, type SessionView} from './session.ts';
import {parseMainReply} from './genuiCompose.ts';
import {sameMainReplySource} from './genui.ts';

export interface SessionCursor {brokerEpoch: string; afterSeq: number}
export interface SessionEvent {
  schema: 'taku.session-events/1';
  brokerEpoch: string;
  threadId: string;
  seq: number;
  method: string;
  params: Json;
}
interface StreamTurn {data: Json; itemIds: string[]; items: Record<string, Json>}
export interface SessionStream {
  threadId: string;
  brokerEpoch: string;
  seq: number;
  revision: number;
  ready: boolean;
  needsResync: boolean;
  thread: Json;
  turnIds: string[];
  turns: Record<string, StreamTurn>;
}

export function emptySessionStream(threadId: string): SessionStream {
  return {threadId, brokerEpoch: '', seq: 0, revision: 0, ready: false, needsResync: false,
    thread: {id: threadId}, turnIds: [], turns: {}};
}

function normalizeTurn(value: unknown, fallback: string): StreamTurn {
  const data = record(value), items: Record<string, Json> = {}, itemIds: string[] = [];
  list(data.items).forEach((raw, index) => {
    const item = record(raw), id = string(item.id) || `item-${index}`;
    if (!items[id]) itemIds.push(id);
    items[id] = {...item, id};
  });
  return {data: {...data, id: string(data.id) || fallback}, itemIds, items};
}

/** The model's public reasoning summary, as it is written (SESSION_STREAMING.md). */
const REASONING_SUMMARY_DELTA = 'item/reasoning/summaryTextDelta';
const MAX_SUMMARY_PARTS = 64;

/**
 * A snapshot is the whole thread, yet a relay may leave a reasoning summary out of it: one that
 * predates this event, or one that keeps only the newest summaries. A record without a `summary`
 * field keeps the summary this client was already given for the same record; an empty `summary`
 * is the relay saying there is none.
 */
function keepShownReasoning(turn: StreamTurn, before: SessionStream): StreamTurn {
  const shown = before.turns[string(turn.data.id)];
  if (!shown) return turn;
  let items = turn.items;
  for (const id of turn.itemIds) {
    const item = turn.items[id], prior = shown.items[id];
    if (item.type !== 'reasoning' || item.summary !== undefined || prior?.type !== 'reasoning' || !list(prior.summary).length) continue;
    if (items === turn.items) items = {...turn.items};
    items[id] = {...item, summary: prior.summary};
  }
  return items === turn.items ? turn : {...turn, items};
}

function upsertItem(turn: StreamTurn, item: Json): StreamTurn {
  const id = string(item.id);
  if (!id) return turn;
  return {...turn, itemIds: turn.items[id] ? turn.itemIds : [...turn.itemIds, id], items: {...turn.items, [id]: item}};
}

/** A snapshot replaces the generation, including historical IDs; never merge live and rollout IDs. */
export function reduceSessionEvent(state: SessionStream, value: unknown): SessionStream {
  const event = record(value), method = string(event.method), params = record(event.params);
  if (event.schema !== 'taku.session-events/1' || event.threadId !== state.threadId) return state;
  const brokerEpoch = string(event.brokerEpoch), seq = event.seq;
  if (!brokerEpoch || typeof seq !== 'number' || !Number.isSafeInteger(seq) || seq < 0)
    return {...state, ready: false, needsResync: true};
  if (method === 'session/heartbeat') return state;
  // Out of band: a reaction decided after its message arrived repeats the last sequence and is
  // applied in place; an unknown message waits for the next snapshot, which carries it.
  if (method === 'session/reaction') {
    if (state.needsResync || brokerEpoch !== state.brokerEpoch) return state;
    const turn = state.turns[string(params.turnId)], item = turn?.items[string(params.itemId)];
    if (!turn || !item || item.type !== 'userMessage') return state;
    const reaction = typeof params.reaction === 'string' ? params.reaction : '';
    return {...state, turns: {...state.turns, [string(params.turnId)]: upsertItem(turn, {...item, reaction})}, revision: state.revision + 1};
  }
  if (method === 'session/resync') return {...state, ready: false, needsResync: true};
  if (method === 'session/snapshot') {
    const thread = record(params.thread);
    if (thread.id !== state.threadId) return {...state, ready: false, needsResync: true};
    // Ignore a delayed duplicate snapshot once newer increments have been applied.
    if (brokerEpoch === state.brokerEpoch && seq < state.seq && !state.needsResync) return state;
    const turnIds: string[] = [], turns: Record<string, StreamTurn> = {};
    list(thread.turns).forEach((raw, index) => {
      const turn = keepShownReasoning(normalizeTurn(raw, `turn-${index}`), state), id = string(turn.data.id);
      if (!turns[id]) turnIds.push(id);
      turns[id] = turn;
    });
    return {...state, brokerEpoch, seq, thread, turnIds, turns, ready: true, needsResync: false, revision: state.revision + 1};
  }
  if (state.needsResync) return state;
  if (method === 'session/ready') {
    return state.brokerEpoch === brokerEpoch && state.seq === seq
      ? {...state, ready: true} : {...state, ready: false, needsResync: true};
  }
  if (state.brokerEpoch !== brokerEpoch) return {...state, ready: false, needsResync: true};
  if (seq <= state.seq) return state;
  if (seq !== state.seq + 1) return {...state, ready: false, needsResync: true};
  let next = {...state, seq, ready: true};
  if (method === 'thread/name/updated') return {...next, thread: {...state.thread, name: params.threadName ?? params.name}, revision: state.revision + 1};
  if (method === 'thread/status/changed') return {...next, thread: {...state.thread, status: params.status}, revision: state.revision + 1};
  const sourceTurn = record(params.turn);
  const turnId = string(params.turnId) || string(sourceTurn.id);
  if (!turnId) return next;
  const existing = state.turns[turnId];
  let turn = existing || normalizeTurn({id: turnId, status: 'inProgress'}, turnId);
  const source = record(params.item), itemId = string(source.id) || string(params.itemId);
  if (method === 'session/reply') {
    const reply = parseMainReply(params.reply, state.threadId, turnId), items = list(params.items).map(record);
    if (!reply || !Array.isArray(params.items) || !items.length
      || items.some(item => typeof item.id !== 'string' || !reply.provenance.sourceItemIds.includes(item.id) || item.type !== 'agentMessage'
        || item.replyId !== undefined && item.replyId !== reply.id || item.text !== (item.replyId === reply.id ? reply.readableText : ''))
      || items.filter(item => item.replyId === reply.id).length !== 1 || new Set(items.map(item => item.id)).size !== items.length)
      return {...state, ready: false, needsResync: true};
    const prior = parseMainReply(record(turn.data.mainReplies)[reply.id], state.threadId, turnId);
    if (prior && !sameMainReplySource(prior, reply)) return {...state, ready: false, needsResync: true};
    if (prior && prior.revision >= reply.revision) return next;
    turn = {...turn, data: {...turn.data, mainReplies: {...record(turn.data.mainReplies), [reply.id]: reply}}};
    for (const item of items) turn = upsertItem(turn, {...turn.items[string(item.id)], ...item});
  } else if (method === 'item/agentMessage/delta' && itemId) {
    const prior = turn.items[itemId];
    // Completed text is authoritative even if a late source notification has a newer relay seq.
    if (prior?._streamCompleted === true || prior?.replyId
      || Object.values(record(turn.data.mainReplies)).some(reply => list(record(record(reply).provenance).sourceItemIds).includes(itemId))) return next;
    turn = upsertItem(turn, {...prior, id: itemId, type: 'agentMessage',
      text: string(prior?.text) + string(params.delta), _streamCompleted: false});
  } else if (method === REASONING_SUMMARY_DELTA && itemId) {
    const prior = turn.items[itemId], delta = string(params.delta);
    // A completed summary is authoritative, and a summary only ever belongs to a reasoning record.
    if (!delta || prior?._streamCompleted === true || prior && prior.type !== 'reasoning') return next;
    const index = typeof params.summaryIndex === 'number' && Number.isSafeInteger(params.summaryIndex)
      && params.summaryIndex >= 0 && params.summaryIndex < MAX_SUMMARY_PARTS ? params.summaryIndex : 0;
    const summary = list(prior?.summary).map(string);
    while (summary.length <= index) summary.push('');
    summary[index] += delta;
    turn = upsertItem(turn, {...prior, id: itemId, type: 'reasoning', summary, _streamCompleted: false});
  } else if ((method === 'item/started' || method === 'item/completed') && itemId) {
    const prior = turn.items[itemId], completed = method === 'item/completed';
    if (!completed && prior?._streamCompleted === true) return next;
    turn = upsertItem(turn, {...prior, ...source, id: itemId,
      ...(prior?.replyId ? {text: prior.text, replyId: prior.replyId} : {}),
      // Started can follow a delta during recovery; metadata must not erase the prefix.
      ...(!completed && typeof prior?.text === 'string' ? {text: prior.text} : {}),
      ...(!completed && list(prior?.summary).length ? {summary: prior?.summary} : {}),
      _streamCompleted: completed});
  } else if (method === 'turn/started' || method === 'turn/completed') {
    const completed = method === 'turn/completed';
    turn = {...turn, data: {...turn.data, ...sourceTurn,
      status: sourceTurn.status || (completed ? 'completed' : 'inProgress')}};
    // A terminal turn may carry only its last message, not the whole item history.
    for (const raw of list(sourceTurn.items)) {
      const item = record(raw), id = string(item.id), prior = turn.items[id];
      turn = upsertItem(turn, {...prior, ...item,
        ...(prior?.replyId ? {text: prior.text, replyId: prior.replyId} : {}),
        ...(!completed && typeof prior?.text === 'string' ? {text: prior.text} : {}),
        _streamCompleted: completed || prior?._streamCompleted === true});
    }
    if (completed) {
      turn = {...turn, items: Object.fromEntries(turn.itemIds.map(id => [id, {...turn.items[id], _streamCompleted: true}]))};
    }
  } else return next;
  next = {...next, revision: state.revision + 1, turnIds: existing ? state.turnIds : [...state.turnIds, turnId],
    turns: {...state.turns, [turnId]: turn}};
  return next;
}

export function reduceSessionBatch(state: SessionStream, events: unknown[]): SessionStream {
  return events.reduce<SessionStream>(reduceSessionEvent, state);
}

export function streamCursor(state: SessionStream): SessionCursor | undefined {
  return state.brokerEpoch && !state.needsResync ? {brokerEpoch: state.brokerEpoch, afterSeq: state.seq} : undefined;
}

/** Sideband polls supply task/approval state; streamed text and active-turn status own their revision. */
export function streamSessionRoot(state: SessionStream, sideband: Json): Json {
  const turns = state.turnIds.map(id => {
    const turn = state.turns[id];
    return {...turn.data, items: turn.itemIds.map(itemId => turn.items[itemId])};
  });
  const active = [...turns].reverse().find(turn => ['inProgress', 'in_progress'].includes(string(record(turn).status)));
  const projection = record(state.thread._projection);
  return {...sideband, thread: {...state.thread, turns},
    native: {...record(sideband.native), activeTurnId: active ? string(record(active).id) : ''},
    ...(typeof projection.historyComplete === 'boolean' ? {historyComplete: projection.historyComplete} : {}),
    totalTurns: turns.length};
}

/** History completeness is a proof for one broker generation, not a property of a thread ID. */
export function reconcileStreamHistory(before: SessionStream, next: SessionStream, sideband: Json): Json {
  const epochChanged = Boolean(before.brokerEpoch && next.brokerEpoch !== before.brokerEpoch);
  const knownTurnsMissing = Boolean(next.brokerEpoch) && sideband.historyComplete === true && list(record(sideband.thread).turns)
    .some(turn => !next.turns[string(record(turn).id)]);
  if (!epochChanged && !knownTurnsMissing) return sideband;
  return {...sideband, historyComplete: false, knownEmpty: false, historyStatus: 'unknown'};
}

/** Unversioned disk polls can lag a received delta. Once attached, only the relay owns text. */
export function sessionRootForDisplay(state: SessionStream, sideband: Json): Json {
  return state.brokerEpoch ? streamSessionRoot(state, sideband) : sideband;
}

/** Keep completed React card/part identities when only the current item changes. */
export function retainSessionBlocks(next: SessionView, prior: SessionView | null): SessionView {
  if (!prior || prior.id !== next.id) return next;
  const old = new Map(prior.blocks.map(block => [block.id, block]));
  next.blocks = next.blocks.map(block => {
    const previous = old.get(block.id);
    if (!previous) return block;
    const oldParts = new Map(previous.parts.map(part => [part.id, part]));
    block.parts = block.parts.map(part => {
      const before = oldParts.get(part.id);
      return before && before.text === part.text && before.active === part.active && before.superseded === part.superseded
        && (before.replyState === part.replyState || JSON.stringify(before.replyState) === JSON.stringify(part.replyState))
        && (before.card === part.card || JSON.stringify(before.card) === JSON.stringify(part.card)) ? before : part;
    });
    const sameActivity = previous.activity.length === block.activity.length && block.activity.every((item, i) => {
      const old = previous.activity[i];
      return old.id === item.id && old.type === item.type && old.titleKey === item.titleKey
        && old.name === item.name && old.node === item.node && old.text === item.text && old.status === item.status;
    });
    const was = previous.thinking, now = block.thinking;
    const sameThinking = was === now || !!was && !!now && was.id === now.id && was.text === now.text && was.active === now.active
      && was.startedAtMs === now.startedAtMs && was.durationMs === now.durationMs;
    // The same thinking keeps its object, so a reply whose only change is its text never re-renders it.
    if (sameThinking && was) block.thinking = was;
    return sameThinking && previous.text === block.text && previous.active === block.active && previous.status === block.status
      && previous.parts.length === block.parts.length && block.parts.every((part, i) => part === previous.parts[i])
      && sameActivity && previous.attachments.length === block.attachments.length
      && block.attachments.every((attachment, i) => attachment === previous.attachments[i]) ? previous : block;
  });
  return next;
}
