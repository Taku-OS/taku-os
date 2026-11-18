import type {Spec, UIElement} from '@json-render/core';
import type {UiItem} from './genui.ts';

export type FieldReadiness = 'pending' | 'streaming' | 'ready' | 'absent' | 'failed';
export interface CardAssembly {version: 1; fields: Readonly<Record<string, FieldReadiness>>}
const states: Record<string, true> = {pending: true, streaming: true, ready: true, absent: true, failed: true};
const pathPattern = /^(title|summary|body|actions|blocks\/[a-z0-9-]+\/(title|text|value|image|url|items|config)|blocks\/[a-z0-9-]+\/items\/[a-z0-9-]+\/(label|detail|value|image|url|icon))$/;

/** Readiness is outer metadata. Missing keys, empty strings and null never imply waiting. */
export function parseCardAssembly(raw: unknown, terminal = false): CardAssembly | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return;
  const a = raw as Record<string, unknown>;
  if (a.version !== 1 || !a.fields || typeof a.fields !== 'object' || Array.isArray(a.fields)
    || Object.keys(a).some(key => !['version', 'fields'].includes(key))) return;
  const fields: Record<string, FieldReadiness> = {};
  const entries = Object.entries(a.fields);
  if (entries.length > 1600) return;
  for (const [path, state] of entries) {
    if (!pathPattern.test(path) || typeof state !== 'string' || !Object.hasOwn(states, state)) return;
    fields[path] = terminal && (state === 'pending' || state === 'streaming') ? 'failed' : state as FieldReadiness;
  }
  return {version: 1, fields};
}
export const fieldWaiting = (assembly: CardAssembly | undefined, path: string) =>
  assembly?.fields[path] === 'pending' || assembly?.fields[path] === 'streaming';
export const hasContent = (value: unknown): boolean => value !== null && value !== undefined && value !== ''
  && (Array.isArray(value) ? value.length > 0 : typeof value === 'object' ? 'value' in value ? hasContent(value.value) : Object.keys(value).length > 0 : true);
export const showFieldSkeleton = (assembly: CardAssembly | undefined, path: string, value: unknown) =>
  fieldWaiting(assembly, path) && !hasContent(value);


// Only content actually displayed by a registered widget owns a loading region.
const renderedProps: Record<string, readonly string[]> = {
  Card: ['title', 'subtitle'], Text: ['text'], Value: ['text'], AppHeader: ['title', 'trailing', 'count'], Status: ['text'],
  Button: ['label'], CornerTag: ['label'], Tags: ['items'], ItemRow: ['leading', 'title', 'subtitle', 'trailing'],
  Image: ['src'], Avatar: ['src', 'initials'], Counter: ['label', 'value'], Slider: ['label', 'value'], ProgressBar: ['value'],
  SearchBar: ['value', 'placeholder'], Switch: ['label', 'checked'], Checkbox: ['label', 'checked'], TextInput: ['label', 'value', 'placeholder'],
  Metric: ['title', 'text', 'value', 'items'], Collection: ['title', 'text', 'items', 'url'],
  ChoiceGroup: ['title', 'text', 'options'], Field: ['title', 'text'], TimeDisplay: ['title', 'text', 'value', 'items'],
  RouteView: ['title', 'text', 'value', 'items', 'image', 'url'], MediaSurface: ['title', 'text', 'value', 'image', 'url'],
  Overlay: ['title', 'text', 'value', 'image'], Progress: ['title', 'text', 'value'],
};

/** These widgets place waits in their real typography and row slots. */
const inlineRegions: Record<string, readonly string[]> = {
  Card: ['title', 'subtitle'], Collection: ['title', 'text', 'items'],
  TimeDisplay: ['items'],
};
export function pendingItemRegions(item: UiItem, path: string | undefined, assembly?: CardAssembly): string[] {
  if (!path) return [];
  return ['label', 'detail', 'value', 'image'].filter(field => showFieldSkeleton(assembly, `${path}/${item.id}/${field}`, item[field as keyof UiItem]));
}

export function pendingAssemblyRegions(widget: string, paths: Record<string, string>, values: Record<string, unknown>, assembly?: CardAssembly): string[] {
  const config = values.config && typeof values.config === 'object' ? values.config : {};
  const mediaType = 'mediaType' in config ? config.mediaType : 'image';
  const capture = 'captureSource' in config && !!config.captureSource;
  return Object.entries(paths).filter(([prop, path]) => {
    if (!renderedProps[widget]?.includes(prop) || inlineRegions[widget]?.includes(prop)) return false;
    if (widget === 'TimeDisplay' && prop === 'text' && 'emphasis' in config && config.emphasis === 'inline') return false;
    if (widget === 'Avatar' && prop === 'initials' && hasContent(values.src)) return false;
    if (widget === 'MediaSurface' && (prop === 'image' || prop === 'url')) {
      if (capture || mediaType === 'camera' && prop === 'url') return false;
      if (mediaType === 'image') {
        if (hasContent(values.image) || hasContent(values.url)) return false;
        // Image and URL are alternative sources for one image surface.
        if (prop === 'url' && showFieldSkeleton(assembly, paths.image, values.image)) return false;
      }
    }
    return showFieldSkeleton(assembly, path, values[prop]);
  }).map(([prop]) => prop);
}
/** Render-only region projection: business specs, bindings and action authority stay untouched. */
export function assemblySpec(spec: Spec): Spec {
  const elements: Record<string, UIElement> = {};
  let prefix = 'assembly-region:';
  while (Object.keys(spec.elements).some(key => key.startsWith(prefix))) prefix = `@${prefix}`;
  const regionKey = (key: string) => `${prefix}${key}`;
  for (const [key, element] of Object.entries(spec.elements)) {
    const paths: Record<string, string> = {};
    for (const [prop, value] of Object.entries(element.props)) {
      if (!renderedProps[element.type]?.includes(prop)) continue;
      const path = value && typeof value === 'object' && '$state' in value ? value.$state : undefined;
      if (typeof path !== 'string') continue;
      const match = /^\/data\/blocksById\/([a-z0-9-]+)\/(title|text|value|image|url|items|config)$/.exec(path);
      if (match) paths[prop] = `blocks/${match[1]}/${match[2]}`;
      else if (path === '/data/title' || path === '/data/summary') paths[prop] = path.slice(6);
    }
    const children = element.children?.map(regionKey);
    const slots = element.slots && Object.fromEntries(Object.entries(element.slots).map(([slot, keys]) => [slot, keys.map(regionKey)]));
    elements[key] = {...element, ...(children ? {children} : {}), ...(slots ? {slots} : {})};
    elements[regionKey(key)] = {type: 'AssemblyRegion', props: {widget: element.type, paths, values: element.props}, children: [key]};
  }
  return {root: regionKey(spec.root), elements};
}
