/** Resolves one card's layout: Jev composition, LLM JSONL compilation, and the shared acceptance checks. */
import {autoFixSpec, compileSpecStream, experimental_composeSpec, resolvePropValue, validateSpec, VisibilityConditionStrictSchema, type Experimental_CompositionEvaluator, type PropResolutionContext, type Spec} from '@json-render/core';
import type {z} from 'zod';
import {CARD_ACTIONS, CATALOG_VERSION, catalog} from './genuiCatalog.ts';
import {cardState, compositionCandidates, compositionPrompt, invalidBindings, missingData, nestedSurfaces, parseUiRequest, templateSpec, uiSignature, unknownActions, type MainReply, type UiRequest, type UiView} from './genui.ts';
import {parseCardAssembly} from './genuiAssembly.ts';

// Hermes has no structuredClone and the composer clones specs with it; specs are plain JSON, so this is exact.
const global = globalThis as {structuredClone?: (value: unknown) => unknown};
if (typeof global.structuredClone !== 'function') global.structuredClone = value => JSON.parse(JSON.stringify(value));

const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);

function validExpressions(value: unknown, depth = 0): boolean {
  if (depth > 64) return false;
  if (Array.isArray(value)) return value.every(item => validExpressions(item, depth + 1));
  if (!object(value)) return true;
  const keys = Object.keys(value);
  if ('$cond' in value) return keys.length === 3 && '$then' in value && '$else' in value
    && VisibilityConditionStrictSchema.safeParse(value.$cond).success
    && validExpressions(value.$then, depth + 1) && validExpressions(value.$else, depth + 1);
  const operator = keys.find(key => key.startsWith('$'));
  if (operator) return keys.length === 1 && ['$state', '$bindState', '$template'].includes(operator) && typeof value[operator] === 'string';
  return Object.values(value).every(item => validExpressions(item, depth + 1));
}

function* resolvedProps(value: unknown, context: PropResolutionContext): Generator<unknown> {
  const resolved = resolvePropValue(value, context);
  yield resolved;
  if (object(value) && '$cond' in value) {
    yield* resolvedProps(value.$then, context);
    yield* resolvedProps(value.$else, context);
  } else if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index++) {
      let first = true;
      for (const alternative of resolvedProps(value[index], context)) {
        if (first) { first = false; continue; }
        const next = [...resolved as unknown[]];
        next[index] = alternative;
        yield next;
      }
    }
  } else if (object(value) && !Object.keys(value).some(key => key.startsWith('$'))) {
    for (const [key, item] of Object.entries(value)) {
      let first = true;
      for (const alternative of resolvedProps(item, context)) {
        if (first) { first = false; continue; }
        yield {...resolved as Record<string, unknown>, [key]: alternative};
      }
    }
  }
}

function exactProps(spec: Spec, request: UiRequest): boolean {
  const context = {stateModel: cardState(request)};
  const definitions: Record<string, {props: z.ZodType & {shape: Record<string, z.ZodType>}}> = catalog.data.components;
  for (const element of Object.values(spec.elements)) {
    const shape = definitions[element.type]?.props;
    if (!shape || !object(element.props) || Object.keys(element.props).some(key => !Object.hasOwn(shape.shape, key))
      || !validExpressions(element.props)) return false;
    for (const resolved of resolvedProps(element.props, context)) if (!shape.safeParse(resolved).success) return false;
  }
  return true;
}

/** One admission boundary for stored, cached, generated and retained layouts. */
export function parseUiView(raw: unknown, request: UiRequest, partial = false): UiView | null {
  if (!object(raw) || raw.catalogVersion !== CATALOG_VERSION || raw.signature !== uiSignature(request)) return null;
  const metrics = raw.metrics;
  if (!object(metrics) || !['primary', 'cache', 'jev', 'llm', 'template'].includes(String(metrics.level))
    || typeof metrics.fallback !== 'string' || typeof metrics.ms !== 'number' || !Number.isFinite(metrics.ms)
    || typeof metrics.jevSteps !== 'number' || !Number.isFinite(metrics.jevSteps)
    || metrics.inputTokens !== null && (typeof metrics.inputTokens !== 'number' || !Number.isFinite(metrics.inputTokens))
    || metrics.llmUsage !== null && !object(metrics.llmUsage)) return null;
  if (metrics.level === 'primary' && (metrics.ms < 0 || metrics.inputTokens !== null || metrics.llmUsage !== null || metrics.jevSteps !== 0 || metrics.fallback !== '')) return null;
  try { return rejectReason(raw.spec as Spec, request, partial) ? null : raw as unknown as UiView; }
  catch { return null; }
}

/** Native snapshots are the only primary input. The client never interprets reply wire records. */
export function parseMainReply(raw: unknown, threadId: string, turnId?: string): MainReply | null {
  if (!object(raw) || Object.keys(raw).some(key => !['schema', 'id', 'key', 'revision', 'readableText', 'provenance', 'lifecycle', 'card'].includes(key))
    || raw.schema !== 'taku.main-reply/1' || typeof raw.id !== 'string' || !raw.id || raw.id.length > 500
    || typeof raw.key !== 'string' || !/^[a-z0-9-]{1,64}$/.test(raw.key)
    || !Number.isSafeInteger(raw.revision) || Number(raw.revision) < 1
    || typeof raw.readableText !== 'string' || raw.readableText.length > 262144) return null;
  const provenance = raw.provenance, lifecycle = raw.lifecycle;
  if (!object(provenance) || Object.keys(provenance).some(key => !['kind', 'threadId', 'turnId', 'anchorOrdinal', 'anchorHash', 'sourceItemIds', 'contractHash'].includes(key))
    || provenance.kind !== 'primary-assistant-reply' || provenance.threadId !== threadId
    || typeof provenance.turnId !== 'string' || !provenance.turnId || turnId !== undefined && provenance.turnId !== turnId
    || !Number.isSafeInteger(provenance.anchorOrdinal) || Number(provenance.anchorOrdinal) < 0
    || typeof provenance.anchorHash !== 'string' || !/^[a-f0-9]{64}$/.test(provenance.anchorHash)
    || typeof provenance.contractHash !== 'string' || !/^[a-f0-9]{64}$/.test(provenance.contractHash)
    || !Array.isArray(provenance.sourceItemIds) || provenance.sourceItemIds.length > 4096
    || provenance.sourceItemIds.some(id => typeof id !== 'string' || !id) || new Set(provenance.sourceItemIds).size !== provenance.sourceItemIds.length
    || !object(lifecycle) || Object.keys(lifecycle).some(key => !['phase', 'code'].includes(key))
    || !['streaming', 'sealed', 'completed', 'failed', 'interrupted'].includes(String(lifecycle.phase))
    || lifecycle.code !== undefined && (typeof lifecycle.code !== 'string' || !/^[A-Z][A-Z0-9_]*$/.test(lifecycle.code))) return null;
  if (raw.card === null) return ['sealed', 'completed'].includes(String(lifecycle.phase)) ? null : raw as unknown as MainReply;
  if (!object(raw.card) || Object.keys(raw.card).some(key => !['request', 'view', 'assembly'].includes(key))) return null;
  const request = parseUiRequest(raw.card.request), assembly = parseCardAssembly(raw.card.assembly);
  if (!request || request.key !== raw.key || request.actions.some(action => action.actionRef !== undefined) || !assembly
    || !parseUiView(raw.card.view, request, !['sealed', 'completed'].includes(String(lifecycle.phase)))
    || (raw.card.view as UiView).metrics.level !== 'primary') return null;
  return raw as unknown as MainReply;
}

/** Why a candidate layout is not shown; empty means accepted. */
export function rejectReason(spec: Spec | null, r: UiRequest, partial = false): string {
  if (!parseUiRequest(r)) return 'invalid-request';
  if (!spec || !object(spec.elements) || !spec.elements[spec.root]) return 'empty';
  if (!validateSpec(spec).valid) return 'invalid-structure';
  const reachable = new Set<string>(), pending = [spec.root];
  while (pending.length) {
    const key = pending.pop()!;
    if (reachable.has(key)) continue;
    reachable.add(key);
    pending.push(...(spec.elements[key]?.children ?? []));
  }
  if (reachable.size !== Object.keys(spec.elements).length) return 'unreachable-elements';
  if (!catalog.validate(spec).success) return 'invalid-props';
  if (unknownActions(spec, CARD_ACTIONS).length) return 'unknown-action';
  if (invalidBindings(spec, r, partial).length) return 'invalid-binding';
  if (!exactProps(spec, r)) return 'invalid-props';
  if (!partial && missingData(spec, r).length) return 'missing-data';
  if (nestedSurfaces(spec).length) return 'nested-surface';
  if (spec.elements[spec.root].type !== 'Card') return 'missing-surface';
  return '';
}

export interface JevResult {spec: Spec | null; reason: string; steps: number; inputTokens: number | null}

/** Jev's node_N keys are transport identities; bindings identify the stable semantic template slots. */
export function semanticSpec(spec: Spec, request: UiRequest): Spec | null {
  const template = templateSpec(request);
  const recipe = (element: Spec['elements'][string]) => JSON.stringify([element.type, element.props, element.on ?? null, element.visible ?? null],
    (_key, value) => value && typeof value === 'object' && !Array.isArray(value)
      ? Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b))) : value);
  const identities = new Map(Object.entries(template.elements).map(([key, element]) => [recipe(element), key]));
  const keys = new Map<string, string>(), used = new Set<string>();
  for (const [key, element] of Object.entries(spec.elements)) {
    const semantic = identities.get(recipe(element));
    if (!semantic || used.has(semantic)) return null;
    keys.set(key, semantic); used.add(semantic);
  }
  const root = keys.get(spec.root);
  if (!root) return null;
  const elements: Spec['elements'] = {};
  for (const [key, element] of Object.entries(spec.elements)) {
    if (element.children?.some(child => !keys.has(child))) return null;
    elements[keys.get(key)!] = {...element, children: element.children?.map(child => keys.get(child)!) ?? []};
  }
  return {root, elements};
}

/** A safe intermediate structure may omit data. Fill those slots from the immediate template. */
export function completeProgressSpec(spec: Spec, request: UiRequest): Spec | null {
  const normalized = semanticSpec(spec, request);
  if (!normalized || rejectReason(normalized, request, true)) return null;
  const template = templateSpec(request), elements = {...normalized.elements};
  for (const [key, element] of Object.entries(template.elements)) if (!elements[key]) elements[key] = element;
  for (const [key, element] of Object.entries(template.elements)) {
    const current = elements[key], children = [...(current.children ?? [])];
    for (const child of element.children ?? []) if (!Object.values(elements).some(parent => parent.children?.includes(child))) children.push(child);
    if (children.length !== (current.children?.length ?? 0)) elements[key] = {...current, children};
  }
  const completed = {root: normalized.root, elements};
  return rejectReason(completed, request) ? null : completed;
}

export async function composeWithJev(r: UiRequest, evaluate: Experimental_CompositionEvaluator, signal: AbortSignal,
  onProgress?: (spec: Spec, steps: number) => void): Promise<JevResult> {
  // RN 0.88's AbortSignal has throwIfAborted; older polyfills (RN <= 0.83 abort-controller) do not.
  // Adapt only our signal; preserve the original abort event and cancellation state.
  if (typeof signal.throwIfAborted !== 'function') Object.defineProperty(signal, 'throwIfAborted', {value: () => {
    if (signal.aborted) {
      const error = new Error('Composition aborted');
      error.name = 'AbortError';
      throw signal.reason ?? error;
    }
  }});
  let steps = 0;
  for await (const event of experimental_composeSpec({
    catalog, candidates: compositionCandidates(r), prompt: compositionPrompt(r), evaluate, signal,
    strategy: 'batch', maxElements: 96, initialState: cardState(r),
  })) {
    if (event.type === 'step') {
      steps++;
      const progress = completeProgressSpec(event.spec, r);
      if (progress) onProgress?.(progress, steps);
      continue;
    }
    // State is the card's own store; a cached layout must not carry an old snapshot of it.
    const spec = event.spec && semanticSpec(event.spec, r);
    const reason = event.stopReason !== 'finish' ? `jev-${event.stopReason}` : rejectReason(spec, r);
    return {spec: reason ? null : spec, reason, steps, inputTokens: event.inputTokens};
  }
  return {spec: null, reason: 'jev-no-result', steps, inputTokens: null};
}

/** LLM output is untrusted text: only well-formed patch lines count, then the same checks as Jev. */
export function compileGenerated(text: string, r: UiRequest): {spec: Spec | null; reason: string} {
  const lines = text.split('\n').map(line => line.trim()).filter(line => line.startsWith('{'));
  if (!lines.length) return {spec: null, reason: 'llm-no-patches'};
  let compiled: Partial<Spec>;
  try { compiled = compileSpecStream<Record<string, unknown>>(lines.join('\n')) as Partial<Spec>; }
  catch { return {spec: null, reason: 'llm-unparsable'}; }
  if (!compiled?.root || !compiled.elements) return {spec: null, reason: 'llm-empty'};
  const fixed = autoFixSpec({root: compiled.root, elements: compiled.elements}, {lossy: false}).spec;
  const spec = {root: fixed.root, elements: fixed.elements};
  const reason = rejectReason(spec, r);
  return {spec: reason ? null : spec, reason: reason && `llm-${reason}`};
}
