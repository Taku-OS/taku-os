import {useEffect, useLayoutEffect, useMemo} from 'react';
import {useStore} from 'zustand';
import AsyncStorage from '@react-native-async-storage/async-storage';
import type {Spec} from '@json-render/react-native';
import type {Experimental_CompositionEvaluation, Experimental_CompositionEvaluator} from '@json-render/core';
import {callAgent, newRequestId} from '../native/agent';
import {CATALOG_VERSION, generationInstructions} from '../model/genuiCatalog';
import {compileGenerated, composeWithJev, parseUiView} from '../model/genuiCompose';
import {generationPrompt, templateSpec, uiSignature, type CardPresentation, type UiMetrics, type UiRequest, type UiView} from '../model/genui';
import {record, type Json} from '../model/session';
import {applyLayout, applyMatterSnapshot, applyPresentation, canSyncMatter, commandValues, ensureCard, getCard, genUiStore, rendererStore, updateCard} from '../model/genuiStore';
import {flushCardView, scheduleCardViewSync} from '../panels/cardViewSync';
import {getPanel, openPanel, pinPanel, unpinPanel} from '../panels/client';
import {openMatterSurface} from '../panels/matterDemand';

const layoutKey = (signature: string) => `taku.genui.layout.v3.${CATALOG_VERSION}.${signature}`;
let obsoleteLayoutsPruned = false;
async function pruneObsoleteLayouts(): Promise<void> {
  if (obsoleteLayoutsPruned) return;
  obsoleteLayoutsPruned = true;
  try {
    const keys = (await AsyncStorage.getAllKeys()).filter(key => key.startsWith('taku.genui.layout.v2.')).slice(0, 128);
    if (keys.length) await AsyncStorage.multiRemove(keys);
  } catch {}
}
const hydrations = new Map<string, Promise<void>>();
/**
 * A finished reply's saved state (edits, pin capability) can lag the reply itself: the native
 * record appears once the reply is projected (e.g. after a pending panel update). Its content is
 * already complete and shown, so a failed load is retried quietly instead of reported.
 */
const QUIET_HYDRATION_DELAYS = [1000, 3000, 8000, 20000];
const hydrationRetries = new Map<string, number>();
const resolutions = new Map<string, Promise<void>>();
let inferenceQueue: Promise<unknown> = Promise.resolve();

const errorCode = (error: unknown) => typeof record(error).code === 'string' ? String(record(error).code)
  : error instanceof Error ? error.message : 'UNAVAILABLE';
const serial = <T>(run: () => Promise<T>): Promise<T> => {
  const next = inferenceQueue.then(run, run);
  inferenceQueue = next.catch(() => {});
  return next;
};

function beforeDeadline<T>(promise: Promise<T>, deadline: number, abort?: AbortController): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => { abort?.abort(); reject(new Error('timeout')); }, Math.max(0, deadline - Date.now()));
    promise.then(value => { clearTimeout(timer); resolve(value); }, error => { clearTimeout(timer); reject(error); });
  });
}

const evaluate: Experimental_CompositionEvaluator = ({state, questions, signal}) => {
  if (signal.aborted) return Promise.reject(new Error('aborted'));
  return new Promise<Experimental_CompositionEvaluation>((resolve, reject) => {
    const onAbort = () => reject(new Error('aborted'));
    signal.addEventListener('abort', onAbort, {once: true});
    callAgent<Experimental_CompositionEvaluation>('decide', {state, questions})
      .then(value => { signal.removeEventListener('abort', onAbort); resolve(value); },
        error => { signal.removeEventListener('abort', onAbort); reject(error); });
  });
};

async function resolveLayout(request: UiRequest, onProgress?: (view: UiView) => void): Promise<UiView> {
  void pruneObsoleteLayouts();
  const started = Date.now(), signature = uiSignature(request);
  const deadline = started + (request.layout === 'generate' ? 12000 : 8000);
  const base = {jevSteps: 0, inputTokens: null, llmUsage: null};
  const view = (spec: Spec, metrics: Omit<UiMetrics, 'ms'>): UiView | null =>
    parseUiView({catalogVersion: CATALOG_VERSION, signature, spec, metrics: {...metrics, ms: Date.now() - started}}, request);
  try {
    const cached = await AsyncStorage.getItem(layoutKey(signature));
    const admitted = cached ? parseUiView(JSON.parse(cached), request) : null;
    if (admitted) return {...admitted, metrics: {...admitted.metrics, level: 'cache', ms: Date.now() - started}};
    if (cached) await AsyncStorage.removeItem(layoutKey(signature));
  } catch { /* Recompose when the cache is unavailable or invalid. */ }
  let reason: string;
  try {
    if (request.layout === 'generate') {
      const pending = serial(() => {
        if (Date.now() >= deadline) throw new Error('timeout');
        return callAgent<{text: string; usage?: Json}>('generateUi', {requestId: newRequestId(),
          instructions: generationInstructions(), prompt: generationPrompt(request), catalogVersion: CATALOG_VERSION});
      });
      const generated = await beforeDeadline(pending, deadline);
      const compiled = compileGenerated(generated.text, request);
      const admitted = compiled.spec && view(compiled.spec, {...base, level: 'llm', fallback: '', llmUsage: generated.usage ?? null});
      if (admitted) return admitted;
      reason = compiled.reason || 'invalid-layout';
    } else {
      const controller = new AbortController();
      const pending = serial(() => {
        if (Date.now() >= deadline) throw new Error('timeout');
        return composeWithJev(request, evaluate, controller.signal, (spec, steps) => {
          const admitted = view(spec, {...base, level: 'jev', fallback: '', jevSteps: steps});
          if (admitted) onProgress?.(admitted);
        });
      });
      const composed = await beforeDeadline(pending, deadline, controller);
      const admitted = composed.spec && view(composed.spec, {...base, level: 'jev', fallback: '', jevSteps: composed.steps, inputTokens: composed.inputTokens});
      if (admitted) {
        void AsyncStorage.setItem(layoutKey(signature), JSON.stringify(admitted)).catch(() => {});
        return admitted;
      }
      reason = composed.reason || 'invalid-layout';
    }
  } catch (error) { reason = `${request.layout === 'generate' ? 'llm' : 'jev'}-${errorCode(error).toLowerCase()}`; }
  const fallback = view(templateSpec(request), {...base, level: 'template', fallback: reason});
  if (!fallback) throw new Error('INVALID_TEMPLATE');
  return fallback;
}

/** Native matter snapshots are the whole hydration: content, card view and pin capability together. */
async function hydrate(id: string): Promise<void> {
  if (getCard(id).hydrated) return;
  const existing = hydrations.get(id);
  if (existing) return existing;
  const pending = (async () => {
    try {
      const result = await callAgent<{snapshot: unknown}>('readMatter', {matterId: id});
      if (!applyMatterSnapshot(result.snapshot)) throw new Error('SOURCE_UNAVAILABLE');
      hydrationRetries.delete(id);
      updateCard(id, card => card.hydrationError ? {...card, hydrationError: ''} : card);
    } catch (error) {
      if (getCard(id).presentation.kind === 'primary') {
        // Nothing the user sees is missing: no error, no Retry; Pin waits until the load succeeds.
        const attempt = hydrationRetries.get(id) ?? 0;
        if (attempt < QUIET_HYDRATION_DELAYS.length) {
          hydrationRetries.set(id, attempt + 1);
          setTimeout(() => { void hydrate(id); }, QUIET_HYDRATION_DELAYS[attempt]);
        }
        return;
      }
      updateCard(id, card => ({...card, hydrationError: errorCode(error)}));
    }
  })();
  hydrations.set(id, pending);
  try { await pending; }
  finally { if (hydrations.get(id) === pending) hydrations.delete(id); }
}

async function refresh(id: string): Promise<void> {
  if (!getCard(id).hydrated) return;
  try {
    const result = await callAgent<{snapshot: unknown}>('readMatter', {matterId: id});
    applyMatterSnapshot(result.snapshot);
  } catch (error) { updateCard(id, card => ({...card, syncError: errorCode(error)})); }
}

async function compose(id: string): Promise<void> {
  if (getCard(id).presentation.kind === 'primary' || resolutions.has(id) || getCard(id).layoutResolved || getCard(id).layoutError) return;
  const pending = (async () => {
    // A newer structural signature can arrive while an older composition is queued.
    // Drain the latest request here so the effect cannot lose that update behind the old flight.
    while (getCard(id).presentation.kind === 'matter' && !getCard(id).layoutResolved && !getCard(id).layoutError) {
      const current = getCard(id), request = current.request, signature = uiSignature(request), sourceId = current.sourceId;
      const view = await resolveLayout(request, progress => { applyLayout(id, sourceId, signature, progress, false); });
      if (applyLayout(id, sourceId, signature, view, true)) return;
      const latest = getCard(id);
      if (latest.sourceId !== sourceId || uiSignature(latest.request) !== signature) continue;
      return;
    }
  })();
  resolutions.set(id, pending);
  try { await pending; }
  finally { if (resolutions.get(id) === pending) resolutions.delete(id); }
}

async function dispatchCard(id: string, actionId: string) {
  if (!getCard(id).hydrated) {
    await hydrate(id);
    if (!getCard(id).hydrated) return;
  }
  const current = getCard(id), action = current.request.actions.find(item => item.id === actionId);
  if (!action?.actionRef) throw new Error('CAPABILITY_UNAVAILABLE');
  let values: Json;
  try { values = commandValues(id); }
  catch (error) { updateCard(id, card => ({...card, syncError: errorCode(error)})); return; }
  const fingerprint = JSON.stringify([action.actionRef, Object.entries(values).sort(([a], [b]) => a.localeCompare(b))]);
  const previous = current.submissions[actionId];
  if (previous?.fingerprint === fingerprint && previous.phase === 'sending') return;
  const receipt = record(record(current.snapshot.commands)[previous?.requestId ?? ''] ?? previous?.receipt);
  const refreshAgain = receipt.effect === 'source-refresh-requested' && Date.now() >= Number(receipt.nextEligibleAt);
  const requestId = previous?.fingerprint === fingerprint && !refreshAgain && receipt.state !== 'notSent' ? previous.requestId : newRequestId();
  updateCard(id, card => ({...card, lastActionId: actionId, syncError: '',
    submissions: {...card.submissions, [actionId]: {requestId, fingerprint, phase: 'sending'}}}));
  try {
    const result = await callAgent('dispatchMatter', {matterId: id, requestId,
      actionRef: action.actionRef, expectedRevision: current.nativeRevision, values});
    updateCard(id, card => ({...card, submissions: {...card.submissions,
      [actionId]: {requestId, fingerprint, phase: 'accepted', receipt: result}}}));
  } catch (error) {
    updateCard(id, card => ({...card, submissions: {...card.submissions,
      [actionId]: {requestId, fingerprint, phase: 'unknown', receipt: {code: errorCode(error)}}}}));
  }
  await refresh(id);
}


export function useGenUiCard(threadId: string, presentation: CardPresentation, active = true, preload = false) {
  const id = ensureCard(threadId, presentation);
  const card = useStore(genUiStore, state => state.cards[id]);
  const store = useMemo(() => rendererStore(id), [id]);
  const spec = card.view.spec;
  const matterAdmitted = canSyncMatter(card.presentation), primary = card.presentation.kind === 'primary';
  useLayoutEffect(() => {
    if (!active) return;
    if (applyPresentation(id, presentation) && getCard(id).hydrated) void refresh(id);
  }, [active, id, presentation]);
  useEffect(() => {
    if (!active || !matterAdmitted) return;
    void hydrate(id);
  }, [active, id, matterAdmitted]);
  // Each mounted card owns one surface; the shared coordinator unions them into <=2 native leases.
  useEffect(() => {
    if (!active || !card.hydrated || !matterAdmitted) return;
    const surface = openMatterSurface(`card:${id}`);
    surface.setDemand(preload ? {preload: [id]} : {visible: [id]});
    return () => surface.release();
  }, [active, id, card.hydrated, matterAdmitted, preload]);
  useEffect(() => {
    if (active && !primary && card.hydrated && !card.layoutResolved) void compose(id);
  }, [active, id, primary, card.hydrated, card.layoutResolved, card.request]);
  return {
    store, request: card.request, presentation: card.presentation, spec, matterAdmitted, hydrated: card.hydrated,
    syncError: card.syncError, hydrationError: card.hydrationError, layoutError: card.layoutError,
    submission: card.submissions[card.lastActionId],
    meta: record(card.snapshot.meta), commands: record(card.snapshot.commands), pin: card.pin, review: card.review,
    async retry() {
      if (!getCard(id).hydrated) await hydrate(id);
      updateCard(id, current => current.syncError ? {...current, syncError: ''} : current);
      if (getCard(id).layoutError) {
        updateCard(id, current => ({...current, layoutError: ''}));
        await compose(id);
      }
      scheduleCardViewSync(id, true);
      await flushCardView(id);
      await refresh(id);
    },
    /** Only a committed native receipt reports Pinned; a failure leaves the previous capability. */
    async pinToPanels() {
      const pin = getCard(id).pin;
      if (!pin?.canPin || !pin.source) throw new Error('REPLY_INCOMPLETE');
      const receipt = await pinPanel(pin.source);
      updateCard(id, current => ({...current, pin: current.pin && receipt.listRevision >= current.pin.listRevision
        ? {...current.pin, panelId: receipt.panel.panelId, listRevision: receipt.listRevision} : current.pin}));
      return receipt.panel;
    },
    async unpin() {
      const pin = getCard(id).pin;
      if (!pin?.panelId) throw new Error('PANEL_NOT_FOUND');
      const current = await getPanel(pin.panelId);
      const receipt = await unpinPanel(pin.panelId, current.listRevision);
      updateCard(id, current => ({...current, pin: current.pin && receipt.listRevision >= current.pin.listRevision
        ? {...current.pin, panelId: null, listRevision: receipt.listRevision} : current.pin}));
      await refresh(id);
      return receipt;
    },
    async openPinned() {
      const pin = getCard(id).pin;
      if (!pin?.panelId) throw new Error('PANEL_NOT_FOUND');
      await openPanel(pin.panelId);
    },
    dispatch: (actionId: string) => dispatchCard(id, actionId),
  };
}
