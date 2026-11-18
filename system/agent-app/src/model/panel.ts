/**
 * Native Panel and card-view values as the app trusts them.
 *
 * Everything here is parsed from native JSON at the boundary. A client never invents a panel,
 * a capability, or an input value, and an unknown shape fails instead of degrading to a default.
 */
import {list, record} from './session.ts';
import type {Json} from './session.ts';

/** Frozen cross-module contract. An Assistant with another version must be updated, not guessed at. */
export const PANEL_CONTRACT = 'taku.panels/1';

/** The two mutually exclusive native pin sources: a live matter, or one exact primary reply. */
export type PinSource =
  | {kind: 'matter'; matterId: string}
  | {kind: 'primary'; threadId: string; turnId: string; replyId: string; revision: number; contractHash: string};

export interface PanelRecord {
  panelId: string;
  originThreadId: string;
  matterId: string;
  sourceVersion: number;
  metadataRevision: number;
  title: string;
  position: number;
  createdAt: number;
  updatedAt: number;
}

export interface SourceState {state: 'current' | 'offline' | 'thread-unavailable' | 'unavailable'; observedAt?: number; code?: string}
export interface PanelCapabilities {canOpen: boolean; canContinue: boolean; canUnpin: boolean}
export interface UpdateReceipt {outcome: string; code?: string; updatedAt?: number}
export interface PanelDetail {
  panel: PanelRecord;
  sourceState: SourceState;
  updateReceipt: UpdateReceipt | null;
  capabilities: PanelCapabilities;
  listRevision: number;
}
export interface PanelPage {items: PanelRecord[]; nextCursor: string | null; listRevision: number}

/** The card's native pin capability, independent of request.mode. */
export interface PinCapability {canPin: boolean; panelId: string | null; source: PinSource | null; listRevision: number; code?: string}

/** card-view/read and card-view/apply share this canonical shape. */
export interface CardView {matterId: string; uiRevision: number; ui: Json; review: Json[]}
export type CardChange =
  | {kind: 'field'; id: string; value: boolean | number | string | null}
  | {kind: 'selection'; id: string; itemIds: string[]};

export interface PinReceipt {panel: PanelRecord; created: boolean; listRevision: number; replayed: boolean}
export interface UnpinReceipt {removed: boolean; listRevision: number; replayed: boolean}
export interface RenameReceipt {panel: PanelRecord; listRevision: number; replayed: boolean}
export interface MoveReceipt {listRevision: number; replayed: boolean}

const isCounter = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0;
const isText = (value: unknown): value is string => typeof value === 'string' && value.length > 0;
const absent = (what: string) => new Error(`The Assistant sent an unexpected ${what}`);

function parseSource(raw: unknown): PinSource | null {
  const value = record(raw);
  if (value.kind === 'matter') return isText(value.matterId) ? {kind: 'matter', matterId: value.matterId} : null;
  if (value.kind !== 'primary') return null;
  return isText(value.threadId) && isText(value.turnId) && isText(value.replyId)
    && isCounter(value.revision) && isText(value.contractHash)
    ? {kind: 'primary', threadId: value.threadId, turnId: value.turnId, replyId: value.replyId,
      revision: value.revision, contractHash: value.contractHash} : null;
}

function parsePanelRecord(raw: unknown): PanelRecord | null {
  const value = record(raw);
  if (!isText(value.panelId) || !isText(value.originThreadId) || !isText(value.matterId)
    || !isCounter(value.sourceVersion) || !isCounter(value.metadataRevision) || !isCounter(value.position)
    || typeof value.title !== 'string') return null;
  return {panelId: value.panelId, originThreadId: value.originThreadId, matterId: value.matterId,
    sourceVersion: value.sourceVersion, metadataRevision: value.metadataRevision, title: value.title,
    position: value.position,
    createdAt: typeof value.createdAt === 'number' ? value.createdAt : 0,
    updatedAt: typeof value.updatedAt === 'number' ? value.updatedAt : 0};
}

const SOURCE_STATES: readonly SourceState['state'][] = ['current', 'offline', 'thread-unavailable', 'unavailable'];
function parseSourceState(raw: unknown): SourceState {
  const value = record(raw);
  return {state: SOURCE_STATES.find(state => state === value.state) ?? 'unavailable',
    ...(typeof value.observedAt === 'number' ? {observedAt: value.observedAt} : {}),
    ...(isText(value.code) ? {code: value.code} : {})};
}

export function parsePinCapability(raw: unknown): PinCapability | null {
  const value = record(raw);
  if (typeof value.canPin !== 'boolean' || !isCounter(value.listRevision)) return null;
  return {canPin: value.canPin, panelId: isText(value.panelId) ? value.panelId : null,
    source: parseSource(value.source), listRevision: value.listRevision,
    ...(isText(value.code) ? {code: value.code} : {})};
}

export function parseCardView(raw: unknown, matterId?: string): CardView | null {
  const value = record(raw);
  if (!isText(value.matterId) || !isCounter(value.uiRevision) || !value.ui || typeof value.ui !== 'object'
    || matterId !== undefined && value.matterId !== matterId) return null;
  return {matterId: value.matterId, uiRevision: value.uiRevision, ui: record(value.ui),
    review: list(value.review).map(record).filter(entry => Object.keys(entry).length > 0)};
}

export function parsePanelDetail(raw: unknown): PanelDetail {
  const value = record(raw), panel = parsePanelRecord(value.panel);
  if (!panel) throw absent('panel');
  const receipt = record(value.updateReceipt), capabilities = record(value.capabilities);
  return {panel, sourceState: parseSourceState(value.sourceState),
    updateReceipt: value.updateReceipt ? {outcome: typeof receipt.outcome === 'string' ? receipt.outcome : 'unknown',
      ...(isText(receipt.code) ? {code: receipt.code} : {}),
      ...(typeof receipt.updatedAt === 'number' ? {updatedAt: receipt.updatedAt} : {})} : null,
    capabilities: {canOpen: capabilities.canOpen === true, canContinue: capabilities.canContinue === true,
      canUnpin: capabilities.canUnpin === true},
    listRevision: isCounter(value.listRevision) ? value.listRevision : 0};
}

export function parsePanelPage(raw: unknown): PanelPage {
  const value = record(raw);
  const items = list(value.items).map(parsePanelRecord);
  if (items.some(item => !item)) throw absent('panel page');
  if (!isCounter(value.listRevision)) throw absent('panel revision');
  return {items: items as PanelRecord[], nextCursor: isText(value.nextCursor) ? value.nextCursor : null,
    listRevision: value.listRevision};
}

export function parsePinReceipt(raw: unknown): PinReceipt {
  const value = record(raw), panel = parsePanelRecord(value.panel);
  if (!panel || !isCounter(value.listRevision)) throw absent('pin receipt');
  return {panel, created: value.created === true, listRevision: value.listRevision, replayed: value.replayed === true};
}

export function parseUnpinReceipt(raw: unknown): UnpinReceipt {
  const value = record(raw);
  if (!isCounter(value.listRevision)) throw absent('unpin receipt');
  return {removed: value.removed === true, listRevision: value.listRevision, replayed: value.replayed === true};
}

export function parseRenameReceipt(raw: unknown): RenameReceipt {
  const value = record(raw), panel = parsePanelRecord(value.panel);
  if (!panel || !isCounter(value.listRevision)) throw absent('rename receipt');
  return {panel, listRevision: value.listRevision, replayed: value.replayed === true};
}

export function parseMoveReceipt(raw: unknown): MoveReceipt {
  const value = record(raw);
  if (!isCounter(value.listRevision)) throw absent('move receipt');
  return {listRevision: value.listRevision, replayed: value.replayed === true};
}
