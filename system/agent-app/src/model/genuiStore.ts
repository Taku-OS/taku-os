import {createStore} from 'zustand/vanilla';
import type {StateStore} from '@json-render/core';
import {createStoreAdapter} from '@json-render/core/store-utils';
import {cardState, parseUiRequest, presentationRequest, presentationSourceId, sameMainReplySource, templateSpec, uiSignature, type CardPresentation, type UiRequest, type UiView} from './genui.ts';
import {list, record, type Json} from './session.ts';
import {parseMainReply, parseUiView} from './genuiCompose.ts';
import {CATALOG_VERSION} from './genuiCatalog.ts';
import {parseCardView, parsePinCapability, type CardChange, type CardView, type PinCapability} from './panel.ts';

export interface CardSubmission {requestId: string; fingerprint: string; phase: 'sending' | 'accepted' | 'unknown'; receipt?: Json}
export const canSyncMatter = (presentation: CardPresentation): boolean => presentation.kind === 'matter' || presentation.reply.lifecycle.phase === 'completed';

export interface GenUiCardState {
  id: string;
  threadId: string;
  presentation: CardPresentation;
  request: UiRequest;
  sourceId: string;
  snapshot: Json;
  view: UiView;
  hydrated: boolean;
  hydrationError: string;
  hydrationEdits: Record<string, true>;
  editSequence: number;
  editTokens: Record<string, number>;
  layoutResolved: boolean;
  layoutError: string;
  /** Why the last completed layout fell back to the template ('' when it did not), e.g. `jev-jev_not_configured`. */
  layoutFallback: string;
  /** The native business revision, independent of the card's own uiRevision. */
  nativeRevision: number;
  /** The newest canonical card-view revision seen. */
  uiRevision: number;
  /** The revision the local /ui edits are based on: stale on purpose when edits are pending. */
  baseUiRevision: number;
  /** Latest canonical native /ui, including values hidden by pending local edits. */
  acknowledgedUi: Json;
  review: Json[];
  pin: PinCapability | null;
  syncError: string;
  submissions: Record<string, CardSubmission>;
  lastActionId: string;
}

interface GenUiState {cards: Record<string, GenUiCardState>}

/** One owner for source projections, drafts and layouts across recycled chat rows and panels. */
export const genUiStore = createStore<GenUiState>()(() => ({cards: {}}));
const adapters = new Map<string, StateStore>();

export const cardId = (threadId: string, key: string) => `${threadId}/${key}`;
export const getCard = (id: string) => genUiStore.getState().cards[id];

export function updateCard(id: string, update: (card: GenUiCardState) => GenUiCardState): void {
  genUiStore.setState(state => {
    const current = state.cards[id];
    if (!current) return state;
    const next = update(current);
    return next === current ? state : {cards: {...state.cards, [id]: next}};
  });
}

function immediateView(request: UiRequest): UiView {
  return {catalogVersion: CATALOG_VERSION, signature: uiSignature(request), spec: templateSpec(request),
    metrics: {level: 'template', fallback: '', ms: 0, jevSteps: 0, inputTokens: null, llmUsage: null}};
}
export function ensureCard(threadId: string, presentation: CardPresentation): string {
  const request = presentationRequest(presentation), sourceId = presentationSourceId(presentation), id = cardId(threadId, request.key);
  if (getCard(id)) return id;
  const state = cardState(request), primary = presentation.kind === 'primary' ? presentation.reply.card : null;
  genUiStore.setState(current => ({cards: {...current.cards, [id]: {
    id, threadId, presentation, request, sourceId,
    snapshot: {...state, meta: {}, commands: {}, ...(primary ? {assembly: primary.assembly} : {})},
    view: primary?.view ?? immediateView(request), hydrated: false, hydrationError: '', hydrationEdits: {}, editSequence: 0, editTokens: {},
    layoutResolved: !!primary, layoutError: '', layoutFallback: '', nativeRevision: 0, uiRevision: 0, baseUiRevision: 0,
    acknowledgedUi: {}, review: [], pin: null, syncError: '', submissions: {}, lastActionId: '',
  }}}));
  return id;
}

const draftOf = (ui: Json, block: string): unknown => record(record(ui.fields)[block]).draft ?? null;
const selectionOf = (ui: Json, block: string): string[] =>
  list(record(record(ui.collections)[block]).selectedItemIds).filter((id): id is string => typeof id === 'string');

/** Local values the user changed that native has not acknowledged yet. */
function localChanges(card: GenUiCardState): CardChange[] {
  const local = record(card.snapshot.ui);
  const changes: CardChange[] = [];
  for (const block of card.request.blocks) {
    if (block.kind === 'input' && card.editTokens[`fields/${block.id}`]) {
      const draft = draftOf(local, block.id), value = typeof draft === 'string' || typeof draft === 'number' || typeof draft === 'boolean' ? draft : null;
      changes.push({kind: 'field', id: block.id, value});
    }
    if (block.kind === 'choices' && card.editTokens[`collections/${block.id}`])
      changes.push({kind: 'selection', id: block.id, itemIds: selectionOf(local, block.id)});
  }
  return changes;
}

export const cardChanges = (id: string): CardChange[] => localChanges(getCard(id));
const hasPending = (card: GenUiCardState): boolean => card.hydrated && localChanges(card).length > 0;

function mergeLocalEdits(card: GenUiCardState, canonical: Json, hydrationEdits: Record<string, true>): Json {
  const local = record(card.snapshot.ui);
  let merged: Json = canonical;
  for (const block of card.request.blocks) {
    if (block.kind === 'input' && (hydrationEdits[`fields/${block.id}`] || card.editTokens[`fields/${block.id}`])) {
      if (merged === canonical) merged = {...canonical, fields: {...record(canonical.fields)}};
      const canonicalField = record(record(canonical.fields)[block.id]), localField = record(record(local.fields)[block.id]);
      merged.fields = {...record(merged.fields), [block.id]: {...canonicalField, draft: draftOf(local, block.id),
        needsReview: canonicalField.needsReview === true || localField.needsReview === true}};
    }
    if (block.kind === 'choices' && (hydrationEdits[`collections/${block.id}`] || card.editTokens[`collections/${block.id}`])) {
      if (merged === canonical) merged = {...canonical, collections: {...record(canonical.collections)}};
      merged.collections = {...record(merged.collections), [block.id]: record(record(local.collections)[block.id])};
    }
  }
  if (Object.keys(record(local.views)).length) merged = {...merged, views: record(local.views)};
  return merged;
}

/**
 * Native card-view state is authority for drafts and selections. Edits made before the first read
 * win for their own fields; edits made after it stay pending until card-view/apply acknowledges them.
 */
export function mergeCardView(id: string, view: CardView, submitted?: GenUiCardState): boolean {
  let accepted = false;
  updateCard(id, card => {
    const older = view.uiRevision < card.uiRevision;
    if (older && !submitted) return card;
    const editTokens = {...card.editTokens};
    if (submitted) for (const [key, token] of Object.entries(submitted.editTokens))
      if (editTokens[key] === token) delete editTokens[key];
    const local = {...card, editTokens};
    const pending = hasPending(local);
    const canonical = older ? card.acknowledgedUi : cardState(card.request, view.ui).ui;
    const uiRevision = Math.max(card.uiRevision, view.uiRevision);
    accepted = true;
    return {...local, snapshot: {...card.snapshot, ui: mergeLocalEdits(local, canonical, card.hydrationEdits)},
      uiRevision, baseUiRevision: submitted && !older ? uiRevision : pending ? card.baseUiRevision : uiRevision,
      acknowledgedUi: canonical, review: older ? card.review : view.review ?? [], hydrated: true, hydrationError: '', hydrationEdits: {},
      syncError: ''};
  });
  return accepted;
}

function replaceProjection(card: GenUiCardState, request: UiRequest, sourceId: string,
  meta: Json = record(card.snapshot.meta), commands: Json = record(card.snapshot.commands)): GenUiCardState {
  const state = cardState(request, record(card.snapshot.ui), card.hydrationEdits);
  const changedLayout = uiSignature(card.request) !== uiSignature(request), keepView = parseUiView(card.view, request);
  return {...card, request, sourceId, snapshot: {...state, meta, commands},
    view: keepView ?? immediateView(request), layoutResolved: changedLayout || !keepView ? false : card.layoutResolved,
    layoutError: changedLayout || !keepView ? '' : card.layoutError};
}

/** Layout flights never own data or drafts, and an obsolete flight cannot replace the current shape. */
export function applyLayout(id: string, sourceId: string, signature: string, view: UiView, complete: boolean): boolean {
  let accepted = false;
  updateCard(id, card => {
    if (card.presentation.kind === 'primary' || card.sourceId !== sourceId || uiSignature(card.request) !== signature) return card;
    const admitted = parseUiView(view, card.request);
    if (!admitted) {
      const retained = parseUiView(card.view, card.request);
      if (!complete && retained) return card;
      return {...card, view: retained ?? immediateView(card.request), layoutResolved: retained ? card.layoutResolved : false,
        layoutError: complete ? 'invalid-layout' : card.layoutError};
    }
    accepted = true;
    const retained = admitted.metrics.level === 'template' ? parseUiView(card.view, card.request) : null;
    // The retained template view carries no reason; keep the completed layout's own.
    const layoutFallback = complete ? (admitted.metrics.level === 'template' ? admitted.metrics.fallback : '') : card.layoutFallback;
    return {...card, view: retained ?? admitted, layoutResolved: complete, layoutError: '', layoutFallback};
  });
  return accepted;
}

/** Native primary snapshots already retain accepted content. Only local preferences are projected here. */
export function applyPresentation(id: string, presentation: CardPresentation): boolean {
  let changed = false;
  updateCard(id, current => {
    const request = presentationRequest(presentation), sourceId = presentationSourceId(presentation);
    if (presentation.kind === 'primary') {
      const reply = presentation.reply, previous = current.presentation;
      if (previous.kind !== 'primary' || !sameMainReplySource(previous.reply, reply) || reply.revision <= previous.reply.revision) return current;
      changed = true;
      const state = cardState(request, record(current.snapshot.ui), current.hydrationEdits);
      const view = JSON.stringify(current.view.spec) === JSON.stringify(reply.card.view.spec) ? {...reply.card.view, spec: current.view.spec} : reply.card.view;
      return {...current, presentation, request, sourceId, view, layoutResolved: true, layoutError: '',
        nativeRevision: 0, snapshot: {...state, assembly: reply.card.assembly, meta: {}, commands: {}}, submissions: {}, lastActionId: ''};
    }
    if (current.presentation.kind === 'primary' || !sourceId || current.sourceId === sourceId) return current;
    changed = true;
    return {...replaceProjection(current, request, sourceId), presentation};
  });
  return changed;
}

/** Native snapshots carry the presentation identity; the client validates it like any other reply. */
export function parseMatterPresentation(raw: unknown, threadId: string): CardPresentation | null {
  const value = record(raw);
  if (value.kind === 'primary') {
    const reply = parseMainReply(value.reply, threadId);
    return reply?.card ? {kind: 'primary', reply: {...reply, card: reply.card}} : null;
  }
  if (value.kind !== 'matter') return null;
  const request = parseUiRequest(value.request);
  return request ? {kind: 'matter', request, sourceId: typeof value.sourceId === 'string' ? value.sourceId : ''} : null;
}

/**
 * One native snapshot carries three independently versioned projections. A UI-only or directory-only
 * change must not be discarded because the business revision did not move.
 */
export function applyMatterSnapshot(value: unknown): boolean {
  if (!value || typeof value !== 'object') return false;
  const snapshot = value as Json;
  if (snapshot.schema !== 'taku.matter/1' || typeof snapshot.matterId !== 'string'
    || typeof snapshot.revision !== 'number' || !Number.isSafeInteger(snapshot.revision)) return false;
  const id = snapshot.matterId;
  if (!getCard(id)) return false;

  const pin = parsePinCapability(snapshot.pin);
  if (pin) updateCard(id, card => {
    // Lease events and explicit reads may finish out of order. Directory state has
    // its own revision even when the business/card-view revisions are unchanged.
    if (card.pin && pin.listRevision < card.pin.listRevision) return card;
    return JSON.stringify(pin) === JSON.stringify(card.pin) ? card : {...card, pin};
  });
  const cardView = parseCardView(snapshot.cardView, id);
  if (cardView) mergeCardView(id, cardView);

  const request = parseUiRequest(snapshot.request), current = getCard(id);
  if (!request || request.key !== current.request.key) return false;
  if (snapshot.revision <= current.nativeRevision) return true;
  const meta = record(snapshot.meta), commands = record(snapshot.commands);
  const presentation = parseMatterPresentation(snapshot.presentation, current.threadId);
  if (presentation?.kind === 'primary') {
    const previous = current.presentation, reply = presentation.reply;
    if (previous.kind !== 'primary' || sameMainReplySource(previous.reply, reply) && reply.revision > previous.reply.revision)
      applyPresentation(id, presentation);
    const own = getCard(id).presentation;
    if (own.kind === 'primary') {
      const actions = own.reply.card.request.actions.map(action => {
        const native = request.actions.find(candidate => candidate.id === action.id);
        return native?.actionRef ? {...action, actionRef: native.actionRef} : action;
      });
      const overlay = {...own.reply.card.request, actions};
      updateCard(id, state => ({...state, request: overlay, nativeRevision: snapshot.revision as number,
        snapshot: {...state.snapshot, data: cardState(overlay, record(state.snapshot.ui)).data, meta, commands}, syncError: ''}));
    } else {
      updateCard(id, state => ({...state, nativeRevision: snapshot.revision as number, syncError: ''}));
    }
    return true;
  }
  if (presentation?.kind === 'matter' && current.presentation.kind === 'matter') {
    updateCard(id, state => ({...replaceProjection(state, presentation.request, presentation.sourceId || state.sourceId, meta, commands),
      nativeRevision: snapshot.revision as number, syncError: ''}));
    return true;
  }
  updateCard(id, state => ({...state, nativeRevision: snapshot.revision as number, syncError: ''}));
  return true;
}

function writablePath(id: string, path: string): void {
  const parts = path.split('/');
  const card = getCard(id);
  if (!card || parts[1] !== 'ui' || parts.length < 4) throw new Error('READ_ONLY_CARD_STATE');
  const [scope, field] = parts.slice(2);
  if (scope === 'views') return;
  const block = card.request.blocks.find(item => item.id === field);
  if (scope === 'fields' && block?.kind === 'input' && parts.length === 5 && parts[4] === 'draft') return;
  if (scope === 'collections' && block?.kind === 'choices' && parts.length === 5 && parts[4] === 'selectedItemIds') return;
  throw new Error('INVALID_CARD_INPUT');
}

/** json-render is an adapter onto Zustand, not a second mutable state store. */
export function rendererStore(id: string): StateStore {
  const existing = adapters.get(id);
  if (existing) return existing;
  const adapter = createStoreAdapter({
    getSnapshot: () => getCard(id).snapshot,
    setSnapshot: snapshot => updateCard(id, card => ({...card, snapshot})),
    subscribe: listener => genUiStore.subscribe((next, before) => {
      if (next.cards[id]?.snapshot !== before.cards[id]?.snapshot) listener();
    }),
  });
  const guarded: StateStore = {
    ...adapter,
    set(path, value) { guarded.update({[path]: value}); },
    update(updates) {
      const changes = {...updates};
      for (const path of Object.keys(updates)) {
        writablePath(id, path);
        if (path.startsWith('/ui/fields/')) changes[path.replace(/\/draft$/, '/needsReview')] = false;
      }
      updateCard(id, card => {
        const edits = {...card.hydrationEdits}, editTokens = {...card.editTokens};
        const editSequence = card.editSequence + 1;
        for (const path of Object.keys(updates)) {
          const [, , scope, key] = path.split('/');
          if (!card.hydrated) edits[`${scope}/${key}`] = true;
          if (scope === 'fields' || scope === 'collections') editTokens[`${scope}/${key}`] = editSequence;
        }
        return {...card, hydrationEdits: edits, editSequence, editTokens};
      });
      adapter.update(changes);
    },
  };
  adapters.set(id, guarded);
  return guarded;
}

export function commandValues(id: string): Json {
  const card = getCard(id), ui = card.snapshot.ui as Json;
  const fields = (ui.fields ?? {}) as Json, collections = (ui.collections ?? {}) as Json;
  const values: Json = {};
  for (const block of card.request.blocks) {
    if (block.kind === 'input') {
      const field = (fields[block.id] ?? {}) as Json;
      if (field.needsReview) throw new Error('DRAFT_REVIEW_REQUIRED');
      if (field.draft === null || field.draft === undefined) throw new Error('DRAFT_VALUE_REQUIRED');
      values[block.id] = field.draft;
    }
    if (block.kind === 'choices') values[block.id] = ((collections[block.id] ?? {}) as Json).selectedItemIds ?? [];
  }
  return values;
}
