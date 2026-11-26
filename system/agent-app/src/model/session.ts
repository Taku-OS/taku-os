/** Read-only presentation of native records. The native thread remains authoritative. */
import {phoneToolKeys, phoneToolResultText} from './phoneTools.ts';
import {isStateCardItem, parseUiRequest, type CardPresentation, type MainReply, type UiRequest} from './genui.ts';
import {parseMainReply} from './genuiCompose.ts';

import {mcpPresentation, mcpTool} from './mcpTools.ts';

export type Json = Record<string, unknown>;
export type ItemStatus = 'running' | 'completed' | 'failed' | 'stopped' | 'waiting' | 'unknown';
export interface Activity {
  id: string;
  type: string;
  titleKey: string;
  node?: string;
  name: string;
  text: string;
  status: ItemStatus;
}
export interface MessagePart {
  id: string;
  text: string;
  active: boolean;
  card?: CardPresentation;
  replyState?: MainReply['lifecycle'];
  superseded?: boolean;
}
/**
 * The model's public reasoning summary for one reply: every reasoning record of the reply, in order.
 * Raw reasoning content is never part of it.
 */
export interface Thinking {
  id: string;
  text: string;
  /** A reasoning record is being written now and nothing has followed it yet. */
  active: boolean;
  /** When the record being written began (device clock, ms), as the runtime reported it. */
  startedAtMs?: number;
  /** Time spent on the finished records; absent unless the runtime reported every one of them. */
  durationMs?: number;
}
export interface MessageBlock {
  id: string;
  turnId: string;
  role: 'user' | 'assistant';
  text: string;
  parts: MessagePart[];
  attachments: string[];
  activity: Activity[];
  /** Present only when the reply has reasoning to show: its text, its live state or its duration. */
  thinking?: Thinking;
  status: ItemStatus;
  active: boolean;
  /** Taku's emoji reaction on a user message (Jev's choice, Figma 297:433); absent when none. */
  reaction?: string;
}
export interface PendingQuestion {
  key: string;
  method: string;
  params: Json;
}
export interface TaskFailure {
  taskId: string;
  message: string;
  prompt: string;
  /** False means no positive start evidence; it never proves the request was not executed. */
  turnStarted: boolean;
}
export interface SessionView {
  id: string;
  title: string;
  blocks: MessageBlock[];
  questions: PendingQuestion[];
  running: boolean;
  activeTurnId: string;
  /** Every native turn in order, including turns that produced no visible block. */
  turnIds: string[];
  ready: boolean;
  error: string;
  historyComplete: boolean;
  knownEmpty: boolean;
  historyStatus: 'complete' | 'partial' | 'unknown';
  totalTurns: number;
  nativeOnlyRequests: number;
  queued: boolean;
  taskId: string;
  taskState: string;
  taskPrompt: string;
  taskTurnId: string;
  taskFailure: TaskFailure | null;
}
export interface ThreadSummary {
  id: string;
  title: string;
  preview: string;
  updatedAt: number;
}
export interface WorkspaceView {
  threadId: string;
  screenKey: string;
  generation: string;
  packageName: string;
  title: string;
  status: string;
  controlOwner: string;
  previewUri: string;
  displayId: number | null;
}

export const record = (v: unknown): Json => v !== null && typeof v === 'object' && !Array.isArray(v) ? v as Json : {};
export const list = (v: unknown): unknown[] => Array.isArray(v) ? v : [];
export const string = (v: unknown): string => typeof v === 'string' ? v : '';
const label = (v: unknown): string => { const s = string(v).trim(); return s === 'null' ? '' : s; };

/**
 * The session root right after Continue was accepted: the continuation (a new, published, queued
 * task) replaces the failed task, so the next projection shows the conversation running and no
 * failure card, before the next readSession confirms it.
 */
export function continuedSessionRoot(root: Json, continuationTaskId: string): Json {
  return {...root, task: {id: continuationTaskId, state: 'queued', published: true, turnStarted: false},
    request: {prompt: ''}, runtime: {}};
}

/** Restore is a local draft operation. A newer draft must never be overwritten. */
export function failedTaskDraft(failure: TaskFailure, currentDraft: string): string | null {
  if (!failure.prompt.trim()) return null;
  if (!currentDraft.trim()) return failure.prompt;
  return currentDraft.trim() === failure.prompt.trim() ? currentDraft : null;
}

export function itemStatus(item: Json): ItemStatus {
  if (item.success === false || (item.type === 'commandExecution' && typeof item.exitCode === 'number' && item.exitCode !== 0)) return 'failed';
  switch (item.status) {
    case 'inProgress': case 'in_progress': return 'running';
    case 'completed': return 'completed';
    case 'failed': case 'errored': return 'failed';
    case 'cancelled': case 'canceled': case 'interrupted': case 'declined': return 'stopped';
    case 'pending': case 'queued': return 'waiting';
    default: return item.status == null && typeof item._streamCompleted === 'boolean'
      ? item._streamCompleted ? 'completed' : 'running' : 'unknown';
  }
}

const toolTypes = new Set(['commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall', 'webSearch', 'contextCompaction', 'collabAgentToolCall', 'collabToolCall', 'enteredReviewMode', 'exitedReviewMode']);
const toolKeys: Record<string, string> = {
  commandExecution: 'command', fileChange: 'fileChange', webSearch: 'webSearch', contextCompaction: 'compaction',
  collabAgentToolCall: 'collaboration', collabToolCall: 'collaboration', enteredReviewMode: 'review', exitedReviewMode: 'review',
  ...phoneToolKeys,
};

function activity(item: Json, turnId: string, outputs: Json, index: number): Activity {
  const type = string(item.type);
  const tool = string(item.tool);
  const short = tool.replace(/^.*(?:__|\/|\.)/, '');
  // A bare phone tool name without a namespace (an older Codex record, an agy update without its MCP
  // prefix) is still a phone tool: only taku_phone registers these names, and its raw name never shows.
  const phone = item.server === 'taku_phone' || tool.startsWith('taku_phone') || item.namespace === 'taku_phone'
    || ((type === 'dynamicToolCall' || item.server === 'agy') && !label(item.namespace) && phoneToolKeys[tool] !== undefined);
  const mcp = !phone && (type === 'mcpToolCall' || type === 'dynamicToolCall')
    ? mcpTool(string(item.server), tool || string(item.toolName), string(item.namespace)) : null;
  const named = mcp ? mcpPresentation(mcp) : null;
  const titleKey = toolKeys[type] || (phone && (toolKeys[short] || 'phoneTool')) || named?.titleKey || (type === 'agentMessage' ? 'update' : 'tool');
  const details: string[] = [];
  // A phone tool's JSON answer is for the agent; the activity list keeps only its human message (BUG-0033).
  const output = (text: string) => phone ? phoneToolResultText(text) : text;
  for (const value of [item.command, item.aggregatedOutput || outputs[string(item.id)]]) if (typeof value === 'string' && value) details.push(output(value));
  if (type === 'agentMessage') details.push(string(item.text));
  for (const block of list(item.contentItems)) { const b = record(block); if (b.type === 'inputText') details.push(output(string(b.text))); }
  for (const block of list(record(item.result).content)) { const b = record(block); if (b.type === 'text') details.push(output(string(b.text))); }
  for (const change of list(item.changes)) { const c = record(change); if (typeof c.path === 'string') details.push(c.path); }
  const error = typeof item.error === 'string' ? item.error : string(record(item.error).message);
  if (error) details.push(error);
  return {id: `${turnId}/${label(item.id) || `activity-${index}`}`, type, titleKey, name: titleKey === 'tool' ? named?.name || tool : '',
    ...(named?.node !== undefined ? {node: named.node} : {}), text: details.filter(Boolean).join('\n'), status: itemStatus(item)};
}

export function activityName(item: Activity, translate: (key: string) => string): string {
  if (item.name) return item.name;
  const title = translate(item.titleKey);
  return item.node === undefined ? title : title.replace('{node}', () => item.node || translate('remoteNode'));
}

function userContent(item: Json): {text: string; attachments: string[]} {
  const text: string[] = [], attachments: string[] = [];
  for (const content of list(item.content)) {
    const part = record(content);
    if (part.type === 'text') {
      const value = string(part.text);
      const attachment = /^User-selected attachment: (.+)\nLocal path: \/data\/user\/0\/org\.agentos\.assistant\/files\/composer-attachments\//.exec(value)
        || /^用户选择的附件：(.+)\n本机路径：\/data\/user\/0\/org\.agentos\.assistant\/files\/composer-attachments\//.exec(value);
      if (attachment) attachments.push(attachment[1]); else if (value) text.push(value);
    } else if (part.type === 'image' || part.type === 'localImage') {
      // Keep attachment provenance without leaking a private local filesystem path.
      attachments.push('image');
    }
  }
  return {text: text.join('\n'), attachments};
}

function trimPartialSurrogate(text: string, running: boolean): string {
  const code = text.charCodeAt(text.length - 1);
  return running && code >= 0xd800 && code <= 0xdbff ? text.slice(0, -1) : text;
}
const milliseconds = (value: unknown): number | undefined => typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : undefined;

/** One reasoning record's public summary: its parts, apart from each other as paragraphs. */
export function reasoningText(item: Json): string {
  return list(item.summary).map(part => string(part).trim()).filter(Boolean).join('\n\n');
}

/**
 * [records] are a reply's reasoning records in order; [writing] says the last one is still being
 * written. Undefined when there is nothing to show: no text, nothing under way and no known time.
 */
function thinkingOf(id: string, records: Json[], writing: boolean): Thinking | undefined {
  const current = writing ? records[records.length - 1] : undefined;
  const text: string[] = [];
  let durationMs = 0, timed = false, untimed = false;
  for (const record of records) {
    const summary = reasoningText(record), duration = milliseconds(record.durationMs);
    if (summary) text.push(record === current ? trimPartialSurrogate(summary, true) : summary);
    if (record === current) continue;
    if (duration !== undefined) { durationMs += duration; timed = true; }
    // A record with text but no time makes the total unknown; an empty, untimed one is nothing at all.
    else if (summary) untimed = true;
  }
  const known = timed && !untimed;
  if (!text.length && !current && !known) return undefined;
  const startedAtMs = current && !untimed ? milliseconds(current.startedAtMs) : undefined;
  return {id, text: text.join('\n\n'), active: Boolean(current),
    ...(startedAtMs !== undefined ? {startedAtMs} : {}), ...(known ? {durationMs} : {})};
}

function presentationRequest(item: Json, threadId: string): UiRequest | null {
  const args = record(item.arguments);
  if (args.action !== 'present') return parseUiRequest(args);
  for (const raw of list(item.contentItems)) {
    const content = record(raw);
    if (content.type !== 'inputText' || typeof content.text !== 'string') continue;
    try {
      const snapshot = record(record(JSON.parse(content.text)).snapshot), request = parseUiRequest(snapshot.request);
      if (snapshot.schema === 'taku.matter/1' && snapshot.threadId === threadId && request && request.key === args.key
        && snapshot.matterId === `${threadId}/${request.key}`) return request;
    } catch { /* Other tool output remains readable in Activity. */ }
  }
  return parseUiRequest(args.request);
}

export function projectSession(value: unknown): SessionView {
  const root = record(value), thread = record(root.thread), native = record(root.native), outputs = record(native.outputs);
  const task = record(root.task);
  const id = string(thread.id), turns = list(thread.turns), blocks: MessageBlock[] = [];
  const activeTurnId = string(native.activeTurnId);
  for (const [turnIndex, raw] of turns.entries()) {
    const turn = record(raw), turnId = label(turn.id) || `turn-${turnIndex}`, status = itemStatus(turn);
    const active = status === 'running' || turnId === activeTurnId;
    const replies = record(turn.mainReplies), shownReplies = new Set<string>();
    let segment: Json[] = [], thoughts: Json[] = [], thoughtLast = false, anchor = 'start';
    const flush = (tail: boolean) => {
      const finalState = tail && (active || status === 'failed' || status === 'stopped');
      // Reasoning is being written while the turn runs and nothing has followed its last record.
      const writing = tail && active && thoughtLast && thoughts[thoughts.length - 1]?._streamCompleted !== true;
      const thinking = thoughts.length ? thinkingOf(`${turnId}/thinking/${anchor}`, thoughts, writing) : undefined;
      if (!segment.length && !thinking && !finalState) return;
      // A card is part of the answer, so text before it is not demoted to commentary.
      const lastTool = segment.reduce((last, item, i) => toolTypes.has(string(item.type)) && !isStateCardItem(item) ? i : last, -1);
      const parts: MessagePart[] = [], activities: Activity[] = [];
      segment.forEach((item, i) => {
        const type = string(item.type);
        const commentary = type === 'agentMessage' && (item.phase === 'commentary' || (!label(item.phase) && i < lastTool));
        const replyId = string(item.replyId) || Object.keys(replies).find(key => list(record(record(replies[key]).provenance).sourceItemIds).includes(item.id));
        if (replyId) {
          if (shownReplies.has(replyId)) return;
          shownReplies.add(replyId);
          const reply = parseMainReply(replies[replyId], id, turnId);
          if (reply && reply.id === replyId) parts.push({id: `${turnId}/reply/${reply.id}`, text: reply.readableText,
            active: reply.lifecycle.phase === 'streaming' || reply.lifecycle.phase === 'sealed', replyState: reply.lifecycle,
            ...(reply.card ? {card: {kind: 'primary', reply: reply as MainReply & {card: NonNullable<MainReply['card']>}} as CardPresentation} : {})});
          else {
            const readableText = string(record(replies[replyId]).readableText);
            const carrier = segment.find(source => source.replyId === replyId && source.text === readableText);
            parts.push({id: `${turnId}/reply/${replyId}`, text: carrier && !readableText.startsWith('@taku.reply') ? readableText : '',
              active: false, replyState: {phase: 'failed', code: 'INVALID_REPLY_VIEW'}});
          }
          return;
        }
        if (type === 'agentMessage' && string(item.text).startsWith('@taku.reply')) {
          parts.push({id: `${turnId}/${label(item.id) || `part-${i}`}`, text: '', active: false, replyState: {phase: 'failed', code: 'INVALID_REPLY_FRAME'}});
          return;
        }
        const card = isStateCardItem(item) && itemStatus(item) === 'completed' && item.success !== false ? presentationRequest(item, id) : null;
        if (card) parts.push({id: `${turnId}/${label(item.id) || `part-${i}`}`, text: '', active: false,
          card: {kind: 'matter', request: card, sourceId: `${turnId}/${label(item.id) || `part-${i}`}`}});
        else if (toolTypes.has(type) || commentary) activities.push(activity(item, turnId, outputs, i));
        else if (type === 'agentMessage' || type === 'plan') {
          const itemActive = tail && active && item._streamCompleted !== true
            && !['completed', 'failed', 'stopped'].includes(itemStatus(item));
          const text = trimPartialSurrogate(string(item.text), itemActive);
          if (text) parts.push({id: `${turnId}/${label(item.id) || `part-${i}`}`, text, active: itemActive});
        }
      });
      const primary = parts.find(part => part.card?.kind === 'primary')?.card;
      if (parts.length || activities.length || thinking || finalState) blocks.push({id: primary?.kind === 'primary' ? `${turnId}/reply-anchor/${primary.reply.provenance.anchorOrdinal}` : `${turnId}/after/${anchor}`,
        turnId, role: 'assistant', text: parts.map(part => part.text).filter(Boolean).join('\n\n'), parts, attachments: [], activity: activities,
        ...(thinking ? {thinking} : {}), status, active: tail && active});
      segment = []; thoughts = []; thoughtLast = false;
    };
    list(turn.items).forEach((rawItem, itemIndex) => {
      const item = record(rawItem), type = string(item.type);
      if (type === 'userMessage') {
        flush(false);
        anchor = label(item.id) || `user-${itemIndex}`;
        const content = userContent(item);
        const reaction = typeof item.reaction === 'string' && item.reaction.length > 0 && item.reaction.length <= 32 ? item.reaction : undefined;
        if (content.text || content.attachments.length) blocks.push({id: `${turnId}/${anchor}`, turnId, role: 'user', ...content, parts: [], activity: [], status, active: false,
          ...(reaction ? {reaction} : {})});
      } else if (type === 'agentMessage' || type === 'plan' || toolTypes.has(type)) { segment.push(item); thoughtLast = false; }
      else if (type === 'reasoning') { thoughts.push(item); thoughtLast = true; }
      // Unknown and authentication records never become display text; reasoning shows its public summary only.
    });
    flush(true);
  }
  supersedeCards(blocks);
  const complete = root.historyComplete === true;
  const knownEmpty = root.knownEmpty === true && !blocks.length && !activeTurnId;
  const queued = task.published === true && ['queued', 'running'].includes(string(task.state)) && !activeTurnId;
  // The gateway only exposes task/request after matching request.conversationId to this thread.
  // Keep a failed TaskBroker submission separate from native turn/message history.
  const running = Boolean(activeTurnId) || blocks.some(b => b.active) || queued;
  // The failure card is for a conversation that is idle on that failure. A running turn (e.g. after
  // Continue) or a newer native turn that completed after the failed one supersedes it.
  const failedTurnId = label(record(root.runtime).turnId), lastTurn = record(turns[turns.length - 1]);
  const failedTurnIndex = turns.findIndex(turn => label(record(turn).id) === failedTurnId);
  const superseded = failedTurnIndex >= 0 && failedTurnIndex < turns.length - 1
    && itemStatus(lastTurn) === 'completed';
  const taskFailure: TaskFailure | null = !running && !superseded && id && label(task.id) && ['failed', 'needs_user'].includes(string(task.state)) ? {
    taskId: string(task.id), message: string(task.message), prompt: string(record(root.request).prompt),
    turnStarted: task.turnStarted === true || record(root.runtime).turnStarted === true,
  } : null;
  return {
    id, title: label(thread.name) || label(thread.preview), blocks,
    questions: list(native.requests).map(record).filter(q => label(q.key) && id && record(q.params).threadId === id)
      .map(q => ({key: string(q.key), method: string(q.method), params: record(q.params)})),
    running, activeTurnId, ready: native.ready === true,
    turnIds: turns.map((raw, turnIndex) => label(record(raw).id) || `turn-${turnIndex}`),
    error: string(native.error), historyComplete: complete, knownEmpty,
    historyStatus: complete ? 'complete' : root.historyStatus === 'partial' || blocks.length ? 'partial' : 'unknown',
    totalTurns: typeof root.totalTurns === 'number' ? root.totalTurns : turns.length,
    nativeOnlyRequests: typeof native.unsupportedRequestCount === 'number' ? native.unsupportedRequestCount : 0,
    queued, taskId: string(task.id), taskState: string(task.state), taskFailure,
    taskPrompt: string(record(root.request).prompt), taskTurnId: string(record(root.runtime).turnId),
  };
}


/** One live card per key in a thread: earlier copies point to the newest instead of duplicating it. */
function supersedeCards(blocks: MessageBlock[]) {
  const seen = new Set<string>();
  for (let b = blocks.length - 1; b >= 0; b--) {
    const block = blocks[b];
    if (!block.parts.some(part => part.card)) continue;
    const parts = [...block.parts];
    for (let p = parts.length - 1; p >= 0; p--) {
      const card = parts[p].card, key = card?.kind === 'primary' ? card.reply.key : card?.request.key;
      if (!key) continue;
      if (seen.has(key)) parts[p] = {...parts[p], superseded: true}; else seen.add(key);
    }
    blocks[b] = {...block, parts};
  }
}

export function projectThreads(value: unknown): ThreadSummary[] {
  const root = record(value);
  return list(root.data || root.threads).map(record).filter(t => label(t.id)).map(t => ({
    id: string(t.id), title: label(t.name) || label(t.preview), preview: label(t.preview),
    updatedAt: typeof t.updatedAt === 'number' ? t.updatedAt : 0,
  }));
}

export function projectWorkspaces(value: unknown): WorkspaceView[] {
  return list(record(value).screens).map(record).filter(s => label(s.threadId) && label(s.screenKey) && (typeof s.generation === 'string' || typeof s.generation === 'number')).map(s => ({
    threadId: string(s.threadId), screenKey: string(s.screenKey), generation: String(s.generation),
    packageName: string(s.packageName), title: label(s.appLabel) || label(s.label) || label(s.packageName),
    status: string(s.status), controlOwner: string(s.controlOwner), previewUri: string(s.previewUri),
    displayId: typeof s.displayId === 'number' ? s.displayId : null,
  }));
}
