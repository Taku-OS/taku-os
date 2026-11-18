/** V2 presentation data and stable state projections shared by every generated layout. */
import type {Experimental_CompositionCandidate, Spec, UIElement} from '@json-render/core';
import type {CardAssembly} from './genuiAssembly.ts';

type Json = Record<string, unknown>;
const record = (v: unknown): Json => v !== null && typeof v === 'object' && !Array.isArray(v) ? v as Json : {};
const list = (v: unknown): unknown[] => Array.isArray(v) ? v : [];
const bounded = (v: unknown, max: number): v is string => typeof v === 'string' && v.length <= max;
const id = (v: unknown): v is string => bounded(v, 64) && /^[a-z0-9-]+$/.test(v);
const imageUrl = (v: unknown): v is string => bounded(v, 500) && (!v || /^https:\/\/\S+$/.test(v));
const httpUrl = (v: unknown): v is string => bounded(v, 500) && (!v || /^https?:\/\/\S+$/.test(v));
const uniqueIds = (values: unknown[]) => values.every(v => id(record(v).id)) && new Set(values.map(v => record(v).id)).size === values.length;

export const UI_KINDS = ['metric', 'collection', 'choices', 'timeline', 'route', 'media', 'progress', 'input', 'text', 'sources', 'overlay'] as const;
export type UiKind = typeof UI_KINDS[number];
export interface UiValue {type: 'text' | 'number' | 'money' | 'boolean' | 'instant' | 'duration'; value: string | number | boolean | null; unit?: string; currency?: string; scale?: number; timeZone?: string}
export interface UiItem {id: string; label: string; value: UiValue; detail: string; url: string; image: string; icon: string}
export interface UiConfig {layout?: 'list' | 'horizontal' | 'table' | 'grid'; emphasis?: 'hero' | 'inline'; size?: 'full_size' | 'medium_size' | 'small_size'; group?: string; icon?: string; editor?: 'text' | 'number' | 'range' | 'toggle' | 'time'; min?: number; max?: number; step?: number; multi?: boolean; mediaType?: 'image' | 'audio' | 'video' | 'camera'; captureSource?: 'microphone' | 'playback'; language?: string; targetLanguage?: string; recordAudio?: boolean; imageFilter?: 'warm' | 'mono'; filterStrength?: number; analysisPrompt?: string}
export interface UiBlock {id: string; kind: UiKind; title: string; text: string; value: UiValue; image: string; url: string; items: UiItem[]; config: UiConfig}
export interface UiAction {id: string; label: string; reply: string; style: 'primary' | 'secondary'; actionRef?: string}
export interface UiRequest {version: 2; key: string; mode: 'card' | 'panel'; layout?: 'compose' | 'generate'; title: string; summary: string; blocks: UiBlock[]; actions: UiAction[]}
export type UiLevel = 'primary' | 'cache' | 'jev' | 'llm' | 'template';
export interface UiMetrics {level: UiLevel; fallback: string; ms: number; jevSteps: number; inputTokens: number | null; llmUsage: Json | null}
export interface UiView {catalogVersion: string; signature: string; spec: Spec; metrics: UiMetrics}

export interface MainReply {
  schema: 'taku.main-reply/1';
  id: string;
  key: string;
  revision: number;
  readableText: string;
  provenance: {kind: 'primary-assistant-reply'; threadId: string; turnId: string; anchorOrdinal: number; anchorHash: string; sourceItemIds: string[]; contractHash: string};
  lifecycle: {phase: 'streaming' | 'sealed' | 'completed' | 'failed' | 'interrupted'; code?: string};
  card: {request: UiRequest; view: UiView; assembly: CardAssembly} | null;
}
export type PrimaryCardPresentation = {kind: 'primary'; reply: MainReply & {card: NonNullable<MainReply['card']>}};
export type CardPresentation = PrimaryCardPresentation | {kind: 'matter'; request: UiRequest; sourceId: string};
export const presentationRequest = (presentation: CardPresentation): UiRequest => presentation.kind === 'primary' ? presentation.reply.card.request : presentation.request;
export const presentationSourceId = (presentation: CardPresentation): string => presentation.kind === 'primary' ? presentation.reply.id : presentation.sourceId;
export function sameMainReplySource(before: MainReply, after: MainReply): boolean {
  return before.id === after.id && before.key === after.key
    && before.provenance.threadId === after.provenance.threadId && before.provenance.turnId === after.provenance.turnId
    && before.provenance.anchorOrdinal === after.provenance.anchorOrdinal && before.provenance.anchorHash === after.provenance.anchorHash
    && before.provenance.contractHash === after.provenance.contractHash;
}

export function validUiValue(raw: unknown): raw is UiValue {
  const v = record(raw);
  if (Object.keys(v).some(k => !['type', 'value', 'unit', 'currency', 'scale', 'timeZone'].includes(k))) return false;
  if (v.unit !== undefined && !bounded(v.unit, 40)) return false;
  if (v.currency !== undefined && (typeof v.currency !== 'string' || !/^[A-Z]{3}$/.test(v.currency))) return false;
  if (v.scale !== undefined && (!Number.isInteger(v.scale) || Number(v.scale) < 0 || Number(v.scale) > 6)) return false;
  if (v.timeZone !== undefined) {
    if (!bounded(v.timeZone, 80) || !v.timeZone) return false;
    try { new Intl.DateTimeFormat('en', {timeZone: v.timeZone}); } catch { return false; }
  }
  switch (v.type) {
    case 'text': return v.value === null || bounded(v.value, 1000);
    case 'number': return v.value === null || typeof v.value === 'number' && Number.isFinite(v.value);
    case 'boolean': return v.value === null || typeof v.value === 'boolean';
    case 'money': return typeof v.currency === 'string' && typeof v.scale === 'number' && (v.value === null || bounded(v.value, 200) && /^-?(0|[1-9][0-9]*)$/.test(v.value));
    case 'duration': return v.value === null || typeof v.value === 'number' && Number.isFinite(v.value) && v.value >= 0;
    case 'instant': {
      if (!v.timeZone) return false;
      try { new Intl.DateTimeFormat('en', {timeZone: String(v.timeZone)}); } catch { return false; }
      return v.value === null || bounded(v.value, 200) && /^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(v.value) && Number.isFinite(Date.parse(v.value));
    }
    default: return false;
  }
}

function validConfig(raw: unknown): raw is UiConfig {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return false;
  const c = record(raw);
  if (Object.keys(c).some(k => !['layout', 'emphasis', 'size', 'group', 'icon', 'editor', 'min', 'max', 'step', 'multi', 'mediaType', 'captureSource', 'language', 'targetLanguage', 'recordAudio', 'imageFilter', 'filterStrength', 'analysisPrompt'].includes(k))) return false;
  for (const [key, options] of Object.entries({layout: ['list', 'horizontal', 'table', 'grid'], emphasis: ['hero', 'inline'], size: ['full_size', 'medium_size', 'small_size'], editor: ['text', 'number', 'range', 'toggle', 'time'], mediaType: ['image', 'audio', 'video', 'camera'], captureSource: ['microphone', 'playback'], imageFilter: ['warm', 'mono']}))
    if (c[key] !== undefined && !options.includes(String(c[key]))) return false;
  if (c.group !== undefined && !id(c.group) || c.icon !== undefined && !bounded(c.icon, 40)) return false;
  if (['min', 'max', 'step'].some(k => c[k] !== undefined && (typeof c[k] !== 'number' || !Number.isFinite(c[k])))) return false;
  if (c.multi !== undefined && typeof c.multi !== 'boolean') return false;
  if (c.recordAudio !== undefined && typeof c.recordAudio !== 'boolean') return false;
  if (['language', 'targetLanguage'].some(k => c[k] !== undefined && !bounded(c[k], 80))) return false;
  if (c.analysisPrompt !== undefined && !bounded(c.analysisPrompt, 1000)) return false;
  if (c.filterStrength !== undefined && (typeof c.filterStrength !== 'number' || !Number.isFinite(c.filterStrength) || c.filterStrength < 0 || c.filterStrength > 1)) return false;
  if (c.step !== undefined && Number(c.step) <= 0) return false;
  if (c.min !== undefined && c.max !== undefined && Number(c.min) >= Number(c.max)) return false;
  if (c.editor === 'range' && (c.min === undefined || c.max === undefined)) return false;
  return true;
}

function historicalConfig(raw: unknown): unknown {
  const config = record(raw);
  if (!Object.hasOwn(config, 'tone') && !Object.hasOwn(config, 'supportingColor') && !Object.hasOwn(config, 'gradient')) return raw;
  if (Object.hasOwn(config, 'tone') && config.tone !== 'light' && config.tone !== 'dark'
    || Object.hasOwn(config, 'supportingColor') && !bounded(config.supportingColor, 80)
    || Object.hasOwn(config, 'gradient') && (!Array.isArray(config.gradient) || config.gradient.length > 8 || !config.gradient.every(value => bounded(value, 80)))) return raw;
  return Object.fromEntries(Object.entries(config).filter(([key]) => key !== 'tone' && key !== 'supportingColor' && key !== 'gradient'));
}

/** Historical V1 records stay historical: no inferred identities or executable upgrades. */
export function parseUiRequest(raw: unknown): UiRequest | null {
  const r = record(raw);
  if (Object.keys(r).some(k => !['version', 'key', 'mode', 'layout', 'title', 'summary', 'blocks', 'actions'].includes(k)) || r.layout !== undefined && r.layout !== 'compose' && r.layout !== 'generate') return null;
  if (r.version !== 2 || !id(r.key) || !['card', 'panel'].includes(String(r.mode)) || !bounded(r.title, 80) || !bounded(r.summary, 160)) return null;
  if (!Array.isArray(r.blocks) || r.blocks.length < 1 || r.blocks.length > 8 || !uniqueIds(r.blocks) || !Array.isArray(r.actions) || r.actions.length > 32 || !uniqueIds(r.actions)) return null;
  let blocks = r.blocks;
  for (let index = 0; index < r.blocks.length; index++) {
    const original = record(r.blocks[index]), config = historicalConfig(original.config);
    const b = config === original.config ? original : {...original, config};
    if (b !== original) {
      if (blocks === r.blocks) blocks = [...r.blocks];
      blocks[index] = b;
    }
    if (Object.keys(b).some(k => !['id', 'kind', 'title', 'text', 'value', 'image', 'url', 'items', 'config'].includes(k))) return null;
    if (!UI_KINDS.includes(b.kind as UiKind) || !bounded(b.title, 80) || !bounded(b.text, 1000) || !validUiValue(b.value) || !imageUrl(b.image) || !httpUrl(b.url) || !validConfig(b.config) || !Array.isArray(b.items) || b.items.length > 20 || !uniqueIds(b.items)) return null;
    for (const rawItem of b.items) {
      const i = record(rawItem);
      if (Object.keys(i).some(k => !['id', 'label', 'value', 'detail', 'url', 'image', 'icon'].includes(k))) return null;
      if (!bounded(i.label, 80) || !validUiValue(i.value) || !bounded(i.detail, 200) || !imageUrl(i.image) || !httpUrl(i.url) || !bounded(i.icon, 40)) return null;
    }
    if (b.kind === 'input') {
      const editor = b.config.editor ?? 'text';
      if ((editor === 'number' || editor === 'range') && b.value.type !== 'number' || editor === 'toggle' && b.value.type !== 'boolean' || editor === 'time' && b.value.type !== 'instant' || editor === 'text' && b.value.type !== 'text') return null;
    }
  }
  for (const rawAction of r.actions) {
    const a = record(rawAction);
    if (Object.keys(a).some(k => !['id', 'label', 'reply', 'style', 'actionRef'].includes(k))) return null;
    if (!bounded(a.label, 40) || !a.label.trim() || !bounded(a.reply, 300) || !a.reply.trim() || !['primary', 'secondary'].includes(String(a.style)) || a.actionRef !== undefined && (!bounded(a.actionRef, 200) || !a.actionRef.trim())) return null;
  }
  return (blocks === r.blocks ? raw : {...r, blocks}) as UiRequest;
}

export function isStateCardItem(item: Json): boolean {
  const tool = typeof item.tool === 'string' ? item.tool : '';
  const supported = /(^|__|\.|\/)with_state_card$/.test(tool) || /(^|__|\.|\/)matter$/.test(tool) && record(item.arguments).action === 'present';
  return item.type === 'dynamicToolCall' && supported && (tool.startsWith('taku_phone') || item.namespace === 'taku_phone');
}

/** Values, labels, ordering and collection cardinality never request another layout. */
export function uiSignature(r: UiRequest): string {
  return JSON.stringify({version: 2, mode: r.mode, layout: r.layout ?? 'compose', blocks: r.blocks.map(b => ({id: b.id, kind: b.kind, type: b.value.type,
    config: Object.fromEntries(Object.entries(b.config).filter(([k]) => ['layout', 'emphasis', 'size', 'group', 'editor', 'multi', 'mediaType', 'captureSource'].includes(k)).sort(([a], [z]) => a.localeCompare(z)))})).sort((a, b) => a.id.localeCompare(b.id)),
  actions: r.actions.map(a => a.id).sort()});
}
export const choicePath = (blockId: string) => `/ui/collections/${blockId}/selectedItemIds`;
export const fieldPath = (blockId: string) => `/ui/fields/${blockId}/draft`;
const blockPath = (blockId: string) => `/data/blocksById/${blockId}`;
const bind = (path: string) => ({$state: path});
const contentGroups = (r: UiRequest) => r.blocks.filter((b, i) => b.kind === 'metric' && !['instant', 'duration'].includes(b.value.type)
  && b.config.group && b.config.size && r.blocks.findIndex(other => other.config.group === b.config.group) === i)
  .map(primary => ({primary, members: r.blocks.filter(b => b.config.group === primary.config.group)}));
const expandedPath = (group: string) => `/ui/views/${group}/expanded`;

export interface UiData extends UiRequest {blocksById: Record<string, UiBlock>; actionsById: Record<string, UiAction & {disabled: boolean}>}
export function cardState(r: UiRequest, previousUi: Json = {}, hydrationEdits?: Readonly<Record<string, true>>): {data: UiData; ui: Json} {
  const fields: Json = {}, collections: Json = {};
  let views = previousUi.views;
  for (const {primary} of contentGroups(r)) {
    const group = primary.config.group!, old = record(record(views)[group]);
    if (typeof old.expanded !== 'boolean') views = {...record(views), [group]: {...old, expanded: false}};
  }
  for (const b of r.blocks) {
    if (b.kind === 'input') {
      const old = record(record(previousUi.fields)[b.id]), source = record(old.source);
      const compatible = ['type', 'unit', 'currency', 'scale', 'timeZone'].every(key => source[key] === b.value[key as keyof UiValue]);
      const edited = 'draft' in old && (hydrationEdits?.[`fields/${b.id}`] || !('value' in source) || old.draft !== source.value || old.needsReview === true);
      const draft = edited ? old.draft : b.value.value;
      const valid = draft === null || (b.value.type === 'number' ? typeof draft === 'number' && Number.isFinite(draft) : b.value.type === 'boolean' ? typeof draft === 'boolean' : typeof draft === 'string');
      const needsReview = edited && (!valid || old.needsReview === true || 'type' in source && !compatible);
      fields[b.id] = compatible && source.value === b.value.value && old.draft === draft && old.needsReview === needsReview
        ? old : {...old, draft, source: b.value, needsReview};
    }
    if (b.kind === 'choices') {
      const old = record(record(previousUi.collections)[b.id]);
      const selected = list(old.selectedItemIds).filter((i): i is string => id(i));
      const selectedItemIds = [...new Set(selected)].slice(0, b.config.multi ? undefined : 1);
      collections[b.id] = JSON.stringify(selectedItemIds) === JSON.stringify(old.selectedItemIds) ? old : {selectedItemIds};
    }
  }
  const sameFields = Object.keys(fields).length === Object.keys(record(previousUi.fields)).length && Object.entries(fields).every(([key, v]) => record(previousUi.fields)[key] === v);
  const sameCollections = Object.keys(collections).length === Object.keys(record(previousUi.collections)).length && Object.entries(collections).every(([key, v]) => record(previousUi.collections)[key] === v);
  const ui = sameFields && sameCollections && views === previousUi.views && previousUi.fields && previousUi.collections ? previousUi : {...previousUi, fields: sameFields && previousUi.fields ? previousUi.fields : fields, collections: sameCollections && previousUi.collections ? previousUi.collections : collections, ...(views ? {views} : {})};
  return {data: {...r, blocksById: Object.fromEntries(r.blocks.map(b => [b.id, b])), actionsById: Object.fromEntries(r.actions.map(a => [a.id, {...a, disabled: !a.actionRef}]))}, ui};
}
export function replyText(text: string, selections: string[]): string { return selections.length ? `${text}\n${selections.filter(Boolean).join(', ')}` : text; }
export function selectedLabels(state: Json, paths: readonly string[]): string[] {
  const blocks = record(record(state.data).blocksById), collections = record(record(state.ui).collections), labels: string[] = [];
  for (const path of paths) {
    const match = /^\/ui\/collections\/([a-z0-9-]+)\/selectedItemIds$/.exec(path);
    if (!match) continue;
    const items = list(record(blocks[match[1]]).items);
    for (const selected of list(record(collections[match[1]]).selectedItemIds)) {
      const item = items.map(record).find(i => i.id === selected);
      if (typeof item?.label === 'string') labels.push(item.label);
    }
  }
  return labels;
}

function blockElement(b: UiBlock): UIElement {
  const p = blockPath(b.id), props = {title: bind(`${p}/title`), text: bind(`${p}/text`), value: bind(`${p}/value`), config: bind(`${p}/config`)};
  switch (b.kind) {
    case 'metric': return {type: b.value.type === 'instant' || b.value.type === 'duration' ? 'TimeDisplay' : 'Metric', props: {...props, items: bind(`${p}/items`)}};
    case 'collection': case 'timeline': case 'sources': return {type: 'Collection', props: {...props, items: bind(`${p}/items`), url: bind(`${p}/url`), role: b.kind}};
    case 'choices': return {type: 'ChoiceGroup', props: {...props, options: bind(`${p}/items`), selection: {$bindState: choicePath(b.id)}, multi: b.config.multi ?? false}};
    case 'input': return {type: 'Field', props: {...props, draft: {$bindState: fieldPath(b.id)}}};
    case 'route': return {type: 'RouteView', props: {...props, items: bind(`${p}/items`), image: bind(`${p}/image`), url: bind(`${p}/url`)}};
    case 'media': return {type: 'MediaSurface', props: {...props, image: bind(`${p}/image`), url: bind(`${p}/url`)}};
    case 'progress': return {type: 'Progress', props};
    case 'overlay': return {type: 'Overlay', props: {...props, image: bind(`${p}/image`)}, children: []};
    case 'text': return {type: 'Column', props: {gap: 4}, visible: {$state: `${p}/id`, eq: b.id}, children: []};
  }
}
function actionElement(r: UiRequest, a: UiAction): UIElement {
  const p = `/data/actionsById/${a.id}`;
  return {type: 'Button', props: {label: bind(`${p}/label`), variant: bind(`${p}/style`), disabled: bind(`${p}/disabled`)}, on: {press: {action: 'reply', params: {actionId: a.id, text: bind(`${p}/reply`), select: r.blocks.filter(b => b.kind === 'choices').map(b => choicePath(b.id))}}}};
}
const leaf = (e: UIElement): UIElement => ({...e, children: e.children ?? []});

/** A meaningful immediate render, with one outer surface for every combination of roles. */
export function templateSpec(r: UiRequest): Spec {
  const groups = contentGroups(r), owners = new Map(groups.flatMap(g => g.members.map(b => [b.id, g.primary.id] as const)));
  const children: string[] = [], elements: Record<string, UIElement> = {
    root: {type: 'Card', props: {title: bind('/data/title'), subtitle: bind('/data/summary')}, children},
  };
  for (const b of r.blocks) {
    const key = `block-${b.id}`, owner = owners.get(b.id);
    if (!owner || owner === b.id) children.push(key);
    elements[key] = leaf(blockElement(b));
    if (b.kind === 'text') {
      elements[key].children = [`${key}-title`, `${key}-text`];
      elements[`${key}-title`] = leaf({type: 'Text', props: {text: bind(`${blockPath(b.id)}/title`), variant: 'body-lg-medium'}});
      elements[`${key}-text`] = leaf({type: 'Text', props: {text: bind(`${blockPath(b.id)}/text`), selectable: true}});
    }
  }
  for (const {primary, members} of groups) {
    const element = elements[`block-${primary.id}`];
    element.props.expanded = {$bindState: expandedPath(primary.config.group!)};
    element.children = members.slice(1).map(b => `block-${b.id}`);
  }
  if (r.actions.length) {
    children.push('actions');
    elements.actions = {type: 'Row', props: {gap: 8, wrap: true}, children: r.actions.map(a => `action-${a.id}`)};
    for (const a of r.actions) elements[`action-${a.id}`] = leaf(actionElement(r, a));
  }
  return {root: 'root', elements};
}
export function nestedSurfaces(spec: Spec): string[] {
  return Object.entries(spec.elements).filter(([key, e]) => e.type === 'Card' && key !== spec.root).map(([key]) => key);
}
export function compositionCandidates(r: UiRequest): Experimental_CompositionCandidate[] {
  const template = templateSpec(r), parents = new Map<string, string>();
  for (const [key, element] of Object.entries(template.elements)) for (const child of element.children ?? []) parents.set(child, key);
  return Object.entries(template.elements).map(([key, element]) => {
    const atomic = {...element}; delete atomic.children;
    const description = `${key}: ${element.type}. ${key === 'root' ? 'The single outer card surface.' : `Required parent: ${parents.get(key)}. Preserve this containment so grouped details and controls remain accessible.`} Preserve its current bound content and actions.`;
    return {id: key, description,
      element: atomic, root: key === 'root', ...(key.startsWith('block-') ? {resource: key} : {})};
  });
}
export function compositionPrompt(r: UiRequest): string {
  return `Compose one phone ${r.mode} with one Card root. Preserve every block and action using the offered stable bindings. For each config.group with size small_size or medium_size, keep its first Metric as parent of that group's detail blocks and preserve its expanded binding. full_size is primary content, medium_size supports an explanation, small_size embeds a compact summary in another task. Content widgets belong inside the shared surface. Never invent external capabilities.`;
}
export function generationPrompt(r: UiRequest): string {
  return [`Design one ${r.mode}; root is a single Card. Every fact reads from /data/blocksById/<id>, every action from /data/actionsById/<id>.`,
    'Fields bind /ui/fields/<blockId>/draft; choices bind /ui/collections/<blockId>/selectedItemIds. IDs are identity; labels and indices are not. Keep every block accessible. A sized config.group uses its first Metric as parent of its details, with expanded bound to /ui/views/<group>/expanded. Preserve the requested size: full_size for primary intent, medium_size for explanation, small_size embedded in a larger task. Buttons use reply with actionId and the bound current reply.',
    JSON.stringify({blocks: r.blocks, actions: r.actions})].join('\n');
}

function bindings(value: unknown, into: {read: Set<string>; write: Set<string>}) {
  if (Array.isArray(value)) { value.forEach(v => bindings(v, into)); return; }
  const v = record(value);
  if (typeof v.$state === 'string') into.read.add(v.$state);
  if (typeof v.$bindState === 'string') into.write.add(v.$bindState);
  for (const inner of Object.values(v)) if (inner && typeof inner === 'object') bindings(inner, into);
}
const specBindings = (spec: Spec) => {
  const into = {read: new Set<string>(), write: new Set<string>()};
  for (const e of Object.values(spec.elements)) { bindings(e.props, into); bindings(e.on, into); bindings(e.visible, into); }
  return into;
};
export function missingData(spec: Spec, r: UiRequest): string[] {
  const {read, write} = specBindings(spec), paths = [...read, ...write];
  const has = (p: string) => paths.some(x => x === p || x.startsWith(`${p}/`));
  const missing = r.blocks.filter(b => {
    const p = blockPath(b.id);
    switch (b.kind) {
      case 'input': return !write.has(fieldPath(b.id));
      case 'choices': return !has(`${p}/items`) || !write.has(choicePath(b.id));
      case 'collection': case 'timeline': case 'route': case 'sources': return !has(`${p}/items`);
      case 'metric': return !has(`${p}/value`) || b.items.length > 0 && !has(`${p}/items`);
      case 'progress': return !has(`${p}/value`);
      case 'media': return !has(`${p}/image`) || !has(`${p}/url`);
      case 'overlay': return !has(`${p}/image`) || !has(`${p}/text`);
      case 'text': return !has(`${p}/text`);
    }
  }).map(b => `block ${b.id}`);
  const actions = new Set<string>();
  for (const e of Object.values(spec.elements)) for (const binding of Object.values(e.on ?? {})) for (const b of Array.isArray(binding) ? binding : [binding])
    if (b.action === 'reply' && typeof record(b.params).actionId === 'string') actions.add(String(record(b.params).actionId));
  for (const a of r.actions) if (!has(`/data/actionsById/${a.id}/reply`) || !actions.has(a.id)) missing.push(`action ${a.id}`);
  return missing;
}
/** Every state read must resolve; writes are confined to declared editable fields and selections. */
export function invalidBindings(spec: Spec, r: UiRequest, partial = false): string[] {
  const state = cardState(r), {read, write} = specBindings(spec), bad: string[] = [];
  const groups = contentGroups(r);
  const allowed = new Set([...r.blocks.flatMap(b => b.kind === 'input' ? [fieldPath(b.id)] : b.kind === 'choices' ? [choicePath(b.id)] : []),
    ...groups.map(({primary}) => expandedPath(primary.config.group!))]);
  for (const p of read) {
    let current: unknown = state;
    for (const segment of p.split('/').slice(1)) current = record(current)[segment];
    if (!p.startsWith('/data/') && !allowed.has(p) || current === undefined || /^\/data\/(blocks|actions)\/\d/.test(p) || /\/items\/\d+(\/|$)/.test(p)) bad.push(p);
  }
  for (const p of write) if (!allowed.has(p)) bad.push(p);
  for (const e of Object.values(spec.elements)) for (const binding of Object.values(e.on ?? {})) for (const b of Array.isArray(binding) ? binding : [binding]) {
    const params = record(b.params);
    if (b.action === 'setState' && !allowed.has(String(params.statePath))) bad.push(String(params.statePath));
    if (b.action === 'reply' && (typeof params.actionId !== 'string' || !r.actions.some(a => a.id === params.actionId))) bad.push('actionId');
    if (b.action === 'reply' && record(params.text).$state !== `/data/actionsById/${params.actionId}/reply`) bad.push('action-reply');
    if (b.action === 'reply' && e.type === 'Button' && record(e.props.disabled).$state !== `/data/actionsById/${params.actionId}/disabled`) bad.push('action-disabled');
  }
  for (const {primary, members} of groups.filter(g => g.primary.config.size !== 'full_size')) {
    const parent = Object.entries(spec.elements).find(([, element]) => element.type === 'Metric' && record(element.props.value).$state === `${blockPath(primary.id)}/value`);
    if (!parent && partial) continue;
    if (!parent || record(parent[1].props.expanded).$bindState !== expandedPath(primary.config.group!)) {bad.push('group-expansion');continue;}
    const descendants = new Set<string>(), pending = [...(parent[1].children ?? [])];
    while (pending.length) {
      const key = pending.pop()!;
      if (descendants.has(key)) continue;
      descendants.add(key);pending.push(...(spec.elements[key]?.children ?? []));
    }
    for (const member of members.slice(1)) for (const [key, element] of Object.entries(spec.elements)) {
      const paths = {read: new Set<string>(), write: new Set<string>()};bindings(element.props, paths);
      if ([...paths.read].some(path => path.startsWith(`${blockPath(member.id)}/`)) && !descendants.has(key)) bad.push('group-details');
    }
  }
  return bad;
}
export function unknownActions(spec: Spec, allowed: ReadonlySet<string>): string[] {
  const bad: string[] = [];
  for (const e of Object.values(spec.elements)) for (const binding of Object.values(e.on ?? {})) for (const b of Array.isArray(binding) ? binding : [binding]) if (!allowed.has(b.action)) bad.push(b.action);
  return bad;
}
