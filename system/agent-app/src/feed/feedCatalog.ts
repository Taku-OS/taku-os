/** `taku-feed-1`: the json-render vocabulary for Taku home feed card bodies (see modules/taku-home/CONTRACT.md). */
import {schema} from '@json-render/react-native/schema';
import {defineCatalog, getByPath, resolvePropValue, resolveActionParam, evaluateVisibility, resolveRepeatStatePath, resolveRepeatItemStatePath, VisibilityConditionStrictSchema, conditionUsesItemScope, type PromptContext, type PropResolutionContext} from '@json-render/core';
import {z} from 'zod';
import {TEXT_VARIANTS} from './feedTokens.ts';
import {IMAGE_REFERENCE} from './imageReference.ts';

export const FEED_CATALOG = 'taku-feed-1';

/** Bundled glyphs under `asset:feed/icons/`; `src` covers any other asset. */
export const ICONS = {
  sparkle: 'icons/sparkle-white.svg', 'sparkle-small': 'icons/sparkle-8.svg', locate: 'icons/locate.svg', list: 'icons/list.svg',
  lightning: 'icons/lightning-orange.svg', 'lightning-white': 'icons/lightning-white.svg', passenger: 'icons/passenger.svg',
  heart: 'icons/heart.svg', comment: 'icons/comment.svg', share: 'icons/share.svg', unlock: 'icons/unlock.svg',
  chevrons: 'icons/chevrons-fading.svg', sun: 'icons/sun-dots.svg', moon: 'icons/moon-dots.svg', play: 'icons/play-white.svg',
  check: 'icons/check.svg', clock: 'icons/clock.svg', map: 'icons/map.svg', checklist: 'icons/bulleted-list.svg',
  volume: 'icons/volume.svg', 'volume-off': 'icons/volume-off.svg', expand: 'icons/expand.svg', collapse: 'icons/collapse.svg',
  lamp: 'icons/lamp.svg', thermostat: 'icons/thermostat.svg', door: 'icons/door.svg', tv: 'icons/tv.svg', speaker: 'icons/speaker.svg',
} as const;
export type IconName = keyof typeof ICONS;
const iconNames = Object.keys(ICONS) as [IconName, ...IconName[]];

const asset = z.string().regex(IMAGE_REFERENCE, 'asset:feed/<path> or https:// URL');
const icon = z.enum(iconNames);
const fill = z.enum(['none', 'content-50', 'content-100', 'content-200', 'surface-200', 'surface-600', 'surface-800', 'surface-900', 'foreground', 'white']);
const tone = z.enum(['primary', 'secondary', 'tertiary', 'muted', 'foreground', 'inverse', 'inverseSoft', 'inverseMuted', 'brand', 'green', 'link']);
const align = z.enum(['start', 'center', 'end', 'stretch']);
const justify = z.enum(['start', 'center', 'end', 'between']);
const box = {
  gap: z.number().min(0).max(48).optional(), padding: z.number().min(0).max(48).optional(),
  paddingX: z.number().min(0).max(48).optional(), paddingY: z.number().min(0).max(48).optional(),
  align: align.optional(), justify: justify.optional(), flex: z.number().min(0).max(12).optional(),
  height: z.number().min(0).max(800).optional(), fill: fill.optional(), radius: z.number().min(0).max(9999).optional(),
  outline: z.enum(['white']).optional(),
};
const endpoint = z.object({code: z.string().max(4), time: z.string().max(8), date: z.string().max(32)});
const tag = z.object({label: z.string().max(24), tone: z.enum(['ai', 'solid', 'quiet']).optional(), icon: icon.optional()});
const post = z.object({author: z.string().max(40), avatar: asset, image: asset, likes: z.string().max(8).optional()});

export const feedComponents = {
  Column: {props: z.object(box), slots: ['default'],
    description: 'Vertical stack. Card roots are a Column with gap 12 (the native shell already draws the glass card and its 12dp padding). gap/padding in dp; fill is a token.'},
  Row: {props: z.object({...box, wrap: z.boolean().optional()}), slots: ['default'],
    description: 'Horizontal stack; children with flex share width. ButtonRow = Row gap 8 with two flex:1 Buttons (secondary left, primary right).'},
  Spacer: {props: z.object({size: z.number().min(0).max(64).optional(), flex: z.number().min(0).max(12).optional()}), slots: [],
    description: 'Fixed (size) or flexible (flex) empty space.'},
  Text: {props: z.object({text: z.string(), variant: z.enum(TEXT_VARIANTS).optional(), tone: tone.optional(),
    align: z.enum(['left', 'center', 'right']).optional(), lines: z.number().int().min(1).max(12).optional(), flex: z.number().min(0).max(12).optional()}), slots: [],
  description: 'Type-token text. variant: heading-2xl|xl|lg (Space Grotesk) or body-xl|lg|md|sm[-medium] (Hanken Grotesk); default body-md. tone: primary .92 black, secondary .80, tertiary .64 (meta), muted .48, foreground #1b1b1b, inverse white.'},
  Value: {props: z.object({text: z.string().max(16), size: z.enum(['lg', 'xl', '2xl']).optional(), tone: tone.optional()}), slots: [],
    description: 'A display value; digits/currency/degrees use heading type at size (lg 20, xl 24, 2xl 32) and letters/% use body-lg units: "23°", "8h21m", "31%", "$5.5".'},
  Icon: {props: z.object({name: icon.optional(), src: asset.optional(), size: z.number().min(6).max(64).optional(), label: z.string().optional()}), slots: [],
    description: `Bundled glyph by name (${iconNames.join(', ')}) or an SVG/bitmap asset src; size in dp (default 16).`},
  AppHeader: {props: z.object({icon: asset.optional(), wordmark: asset.optional(), wordmarkWidth: z.number().min(8).max(160).optional(), title: z.string().optional(),
    trailing: z.string().optional(), trailingIcon: icon.optional(), count: z.string().max(4).optional(), gap: z.union([z.literal(4), z.literal(8)]).optional()}), slots: [],
  description: 'Card header row: 16dp app logo (or a 16dp-high wordmark) + app title on the left; trailing meta text with optional icon ("Message 1/3", locate + address) or a heading-lg count on the right.'},
  CornerTag: {props: tag, slots: [],
    description: 'Corner tab (top-left/bottom-right radius 8). tone ai = warm gradient + small sparkle ("Relevant", agent-judged); solid = blue badge with white text ("Faster"); quiet = grey ("@You").'},
  Inset: {props: z.object({tag: tag.optional(), fill: fill.optional(), radius: z.number().min(0).max(24).optional()}), slots: ['default'],
    description: 'Recessed panel (content-100, radius 12, clipped) holding quoted content, with an optional CornerTag at its top-left.'},
  MessageQuote: {props: z.object({avatar: asset, author: z.string(), time: z.string().optional(), channel: z.string().optional(), text: z.string()}), slots: [],
    description: 'A quoted chat message: 32dp avatar, author + time, channel on the right, body text. "@Name" tokens in text render as mention chips. Use inside an Inset.'},
  Button: {props: z.object({label: z.string().max(32), variant: z.enum(['primary', 'secondary']).optional(), size: z.enum(['lg', 'md']).optional(),
    icon: z.enum(['sparkle']).optional(), flex: z.number().min(0).max(12).optional()}), slots: [], events: ['press'],
  description: 'Pill button; lg 40dp, md 32dp (fills width). primary = #1b1b1b with white text; add icon sparkle when Taku executes the action for the user. secondary = grey, hands control back to the user. on.press → dispatch.'},
  IconButton: {props: z.object({icon, label: z.string(), size: z.number().min(24).max(56).optional(), variant: z.enum(['dark', 'glass']).optional()}), slots: [], events: ['press'],
    description: 'Round button. dark (default): #1b1b1b with a white 16dp glyph (e.g. play). glass: Surface/800 with an 18dp dark glyph, for controls over a photo (volume, expand). label is the accessibility label.'},
  Tags: {props: z.object({items: z.array(z.string().max(24)).max(8)}), slots: [],
    description: 'Wrapping row of small option chips ("2% Milk", "2 Shots").'},
  Segmented: {props: z.object({options: z.array(z.object({id: z.string(), label: z.string().max(16)})).min(2).max(4), value: z.string().nullish()}), slots: [], events: ['change'],
    description: 'Single-choice segmented control. Bind value with {"$bindState":"/ui/<name>"}; pass the path in dispatch select to send the choice.'},
  ProductImage: {props: z.object({src: asset, alt: z.string().optional(), shadow: z.boolean().optional(), flex: z.number().min(0).max(12).optional()}), slots: [],
    description: 'Square product tile (content-100, radius 12) with a cut-out product image and a soft floor shadow.'},
  Image: {props: z.object({src: asset, alt: z.string().optional(), width: z.number().optional(), height: z.number().optional(), aspectRatio: z.number().optional(),
    radius: z.number().min(0).max(9999).optional(), fit: z.enum(['cover', 'contain']).optional(), flex: z.number().optional()}), slots: [],
  description: 'Bitmap image (PNG/JPEG/WebP) or SVG asset. Give width/height or aspectRatio.'},
  Avatar: {props: z.object({src: asset, size: z.number().min(12).max(64).optional(), shape: z.enum(['circle', 'rounded']).optional()}), slots: [],
    description: 'Square avatar; circle or rounded (radius 6 at 32dp).'},
  Checklist: {props: z.object({items: z.array(z.object({id: z.string(), label: z.string()})).max(12), checked: z.array(z.string()).nullish(), fade: z.boolean().optional()}),
    slots: [], events: ['change'],
    description: 'Task list rows with 20dp checkboxes; fills remaining height and fades out when it overflows (fade). Bind checked to {"$bindState":"/ui/<name>"}; on.change fires after a toggle.'},
  ProgressBar: {props: z.object({value: z.number().min(0).max(1), tone: z.enum(['green', 'brand', 'foreground']).optional()}), slots: [],
    description: '8dp rounded progress track; value 0…1.'},
  ArcGauge: {props: z.object({value: z.number().min(0).max(1), tone: z.enum(['brand', 'green', 'foreground']).optional()}), slots: ['default'],
    description: '180° gauge (140×70, 12dp round stroke) drawn from value 0…1; children are centered under the arc (e.g. Value + caption Row). Fills remaining height.'},
  MapRoute: {props: z.object({height: z.number().min(80).max(400).optional(),
    art: z.object({width: z.number(), height: z.number(), offsetX: z.number().optional(), offsetY: z.number().optional()}),
    layers: z.array(z.object({src: asset, x: z.number(), y: z.number(), width: z.number(), height: z.number()})).max(12),
    callouts: z.array(z.object({x: z.number(), y: z.number(), title: z.string().max(24), subtitle: z.string().max(32).optional(),
      eta: z.string().max(4).optional(), etaUnit: z.string().max(4).optional()})).max(4)}), slots: ['default'],
  description: 'Static map frame (radius 12, clipped): SVG art layers positioned in an art canvas centered in the frame (offset in dp), place callouts at x/y with optional dark ETA block, children overlay the bottom (e.g. an OptionRow).'},
  OptionRow: {props: z.object({image: asset, title: z.string().max(24), badgeIcon: icon.optional(), badgeText: z.string().max(6).optional(),
    meta: z.array(z.string().max(16)).max(3), price: z.string().max(12).optional(), subprice: z.string().max(16).optional(), tag: tag.optional()}), slots: [], events: ['press'],
  description: 'Choice row (ride/product): square image, title + small badge, dot-separated meta, price + sub-price on the right, optional CornerTag.'},
  MediaStack: {props: z.object({posts: z.array(post).min(1).max(3)}), slots: [], events: ['press'],
    description: 'Fanned stack of up to 3 social posts (front post, then left and right posts tilted behind). Each post: author avatar + name, square image, likes.'},
  SlideToAct: {props: z.object({label: z.string().max(40), icon: icon.optional()}), slots: [], events: ['complete'],
    description: 'Slide-to-confirm control for consequential actions: drag the thumb to the end to fire on.complete (with haptic), then it resets. Use as the root of a control-surface card.'},
  Pager: {props: z.object({index: z.number().int().min(0).nullish()}), slots: ['default'], events: ['change'],
    description: 'Horizontally swipeable pages; each child is one page (pages may contain their own AppHeader, e.g. "Message 2/3"). Bind index with {"$bindState":"/ui/page"} and show Dots bound to the same path.'},
  Cover: {props: z.object({...box, src: asset, sheen: z.boolean().optional()}), slots: ['default'],
    description: 'Full-bleed photo behind its children, for a card with layout.surface "media" (the native shell then draws no glass or padding). Box props lay the children out over the photo; sheen adds the 16 % white top sheen.'},
  Chip: {props: z.object({label: z.string().max(32), dot: z.enum(['green', 'brand']).optional()}), slots: [],
    description: 'Small Surface/800 pill for status over a photo ("Live" with a green dot, "5 Devices connected").'},
  Switch: {props: z.object({on: z.boolean(), label: z.string()}), slots: [], events: ['press'],
    description: '40×24 switch (on: #1b1b1b, off: 16 % black) for a device or setting. on.press → dispatch an action that flips it; the card is republished with the new state.'},
  LevelTrack: {props: z.object({value: z.number(), min: z.number(), max: z.number(), label: z.string()}), slots: [], events: ['change'],
    description: 'Vertical 40dp level track (temperature, brightness) filling the height of its row: #1b1b1b fill from the bottom with a white handle; dragging sets a whole value. Bind value with {"$bindState":"/ui/value"}; on.change fires on release, send the path in dispatch select.'},
  Timeline: {props: z.object({items: z.array(z.object({marker: z.string().max(3), badge: z.string().max(16), title: z.string().max(40), note: z.string().max(80).optional(),
    image: asset.optional(), sources: asset.optional()})).max(12)}), slots: [],
  description: 'Numbered timeline (itinerary days, agenda): 18dp marker on a hairline rail, then a Content/100 row with an optional square photo, a small badge ("Day 1", "10:00"), the title and a one-line note; sources is a small icon strip at the row top right.'},
  FlightRoute: {props: z.object({from: endpoint, to: endpoint, number: z.string().max(12), duration: z.string().max(12)}), slots: [],
    description: 'Flight route: departure code, heading-2xl time and date; the dashed arc with the flight number above and the duration below; the arrival.'},
  Link: {props: z.object({label: z.string().max(24)}), slots: [], events: ['press'],
    description: 'Centered body-md text action at the end of a list ("See all", "Show less").'},
  Dots: {props: z.object({count: z.number().int().min(1).max(12), index: z.number().int().min(0).nullish(), variant: z.enum(['dot', 'pill']).optional()}), slots: [],
    description: 'Page indicator, 6dp dots; the active one is #1b1b1b.'},
};

export const feedActions = {
  dispatch: {params: z.object({actionId: z.string(), select: z.array(z.string()).nullish()}),
    description: 'Run card action actionId (a key of the FeedCard actions map) through the native shell; select lists state paths whose current values are sent with it.'},
};

type Entry = {props?: z.ZodType; params?: z.ZodType; description: string; slots?: readonly string[]; events?: readonly string[]};
function feedPrompt({catalog: data, formatZodType, options}: PromptContext): string {
  const {components, actions} = data as {components: Record<string, Entry>; actions: Record<string, Entry>};
  const describe = (entries: Record<string, Entry>) => Object.entries(entries).map(([name, e]) => `- ${name} ${formatZodType(e.props ?? e.params!)}: ${e.description}${e.slots?.length ? '; has children' : ''}${e.events?.length ? `; events ${e.events.join(',')}` : ''}`).join('\n');
  return [
    options.system ?? 'Design the body of one Taku home feed card.',
    options.mode === 'standalone' ? 'Output one complete JSON object only, without prose or code fences.' : 'Output JSONL only: RFC6902 add /root and add /elements/<id> patches.',
    'The spec is a flat element graph: root is a string element ID, and elements is an object keyed by those IDs. Each element is {type,props:{...},children:["child-id",...]}. Put all component properties inside props. Never put element objects directly in root or children.',
    'The native shell draws the glass card (white .92, radius 24, 12dp padding); do not add an outer surface. Root is a Column with gap 12: AppHeader and content, followed by a ButtonRow only when the requested card has actions.',
    'Use only token props (variant, tone, fill); never raw colors or fonts. Values with units use Value. Every leaf has children:[].',
    'Interactive state lives under /ui/... and is bound with $bindState. Buttons emit dispatch with an actionId that exists in the card actions map; list state paths in select to send current choices.',
    'Only primary buttons whose action Taku performs get icon sparkle. Never show success for something that has not happened.',
    `COMPONENTS\n${describe(components)}`, `ACTIONS\n${describe(actions)}`,
    ...(options.customRules ?? []),
  ].join('\n\n');
}

export const feedCatalog = defineCatalog({...schema, defaultRules: [], promptTemplate: feedPrompt}, {components: feedComponents, actions: feedActions});
export type FeedCatalog = typeof feedCatalog;

export type FeedSpec = {root: string; elements: Record<string, {type: string; props?: Record<string, unknown>; children?: string[]; on?: Record<string, unknown>; visible?: unknown; repeat?: {statePath: string | {$item: string}; key?: string}}>};
const isObject = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

function expressionError(value: unknown, context: PropResolutionContext, depth = 0): string | undefined {
  if (depth > 64) return 'expression nesting exceeds 64 levels';
  if (Array.isArray(value)) return value.map(item => expressionError(item, context, depth + 1)).find(Boolean);
  if (!isObject(value)) return;
  const keys = Object.keys(value);
  const operator = '$cond' in value ? '$cond' : keys.find(key => key.startsWith('$'));
  if (operator === '$cond') {
    if (keys.length !== 3 || !('$then' in value) || !('$else' in value) || !VisibilityConditionStrictSchema.safeParse(value.$cond).success) return 'invalid $cond expression';
    if (conditionUsesItemScope(value.$cond as Parameters<typeof conditionUsesItemScope>[0]) && context.repeatBasePath == null) return 'item condition is outside a repeat';
    return expressionError(value.$then, context, depth + 1) ?? expressionError(value.$else, context, depth + 1);
  }
  if (operator) {
    if (keys.length !== 1) return 'expression must contain exactly one operator';
    if (!['$state', '$bindState', '$template', '$item', '$bindItem', '$index'].includes(operator)) return `unsupported expression ${operator}`;
    if (operator === '$index' ? value[operator] !== true : typeof value[operator] !== 'string') return `invalid ${operator} expression`;
    if (['$item', '$bindItem', '$index'].includes(operator) && context.repeatBasePath == null) return `${operator} is outside a repeat`;
    return;
  }
  return Object.values(value).map(item => expressionError(item, context, depth + 1)).find(Boolean);
}

function* resolvedVariants(value: unknown, context: PropResolutionContext, resolve = resolvePropValue, nestedResolve = resolvePropValue): Generator<unknown> {
  const resolved = resolve(value, context);
  yield resolved;
  if (isObject(value) && '$cond' in value) {
    yield* resolvedVariants(value.$then, context, nestedResolve, nestedResolve);
    yield* resolvedVariants(value.$else, context, nestedResolve, nestedResolve);
    return;
  }
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) {
      let first = true;
      for (const alternative of resolvedVariants(value[i], context, nestedResolve, nestedResolve)) {
        if (first) { first = false; continue; }
        const next = [...resolved as unknown[]];
        next[i] = alternative;
        yield next;
      }
    }
  } else if (isObject(value) && !Object.keys(value).some(key => key.startsWith('$'))) {
    for (const [key, item] of Object.entries(value)) {
      let first = true;
      for (const alternative of resolvedVariants(item, context, nestedResolve, nestedResolve)) {
        if (first) { first = false; continue; }
        yield {...resolved as Record<string, unknown>, [key]: alternative};
      }
    }
  }
}

const unresolvedItem = Symbol('unresolved repeat item');

function resolveEmptyValue(value: unknown, context: PropResolutionContext): unknown {
  if (Array.isArray(value)) return value.map(item => resolveEmptyValue(item, context));
  if (!isObject(value)) return value;
  if ('$item' in value || '$bindItem' in value || '$index' in value) return unresolvedItem;
  if ('$cond' in value) return resolveEmptyValue(evaluateVisibility(value.$cond as Parameters<typeof evaluateVisibility>[0], context) ? value.$then : value.$else, context);
  if (Object.keys(value).some(key => key.startsWith('$'))) return resolvePropValue(value, context);
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, resolveEmptyValue(item, context)]));
}

function unresolvedAt(value: unknown, path: PropertyKey[]): boolean {
  for (const key of path) {
    if (value === unresolvedItem) return true;
    value = Array.isArray(value) ? value[Number(key)] : isObject(value) ? value[String(key)] : undefined;
  }
  return value === unresolvedItem;
}

function* parameterVariants(value: unknown, context: PropResolutionContext, emptyItem: boolean): Generator<unknown> {
  if (!isObject(value)) { yield value; return; }
  const resolve = emptyItem ? resolveEmptyValue : resolveActionParam;
  const nestedResolve = emptyItem ? resolveEmptyValue : resolvePropValue;
  const resolved = Object.fromEntries(Object.entries(value).map(([key, item]) => [key, resolve(item, context)]));
  yield resolved;
  for (const [key, item] of Object.entries(value)) {
    let first = true;
    for (const alternative of resolvedVariants(item, context, resolve, nestedResolve)) {
      if (first) { first = false; continue; }
      yield {...resolved, [key]: alternative};
    }
  }
}

function unknownField(value: unknown, parsed: unknown): boolean {
  if (Array.isArray(value) && Array.isArray(parsed)) return value.some((item, i) => unknownField(item, parsed[i]));
  if (!isObject(value) || !isObject(parsed)) return false;
  return Object.keys(value).some(key => !Object.hasOwn(parsed, key) || unknownField(value[key], parsed[key]));
}

export type FeedIssue = {element: string; message: string};

/**
 * Full `taku-feed-1` check: structural validation from json-render plus each element's prop
 * schema, its event bindings and every dispatched actionId (when `actionIds` is given).
 */
export function validateFeedSpec(spec: unknown, state: unknown = {}, actionIds?: ReadonlySet<string>): FeedIssue[] {
  const structural = feedCatalog.validate(spec);
  if (!structural.success) return [{element: '(spec)', message: String(structural.error)}];
  if (!isObject(state)) return [{element: '(state)', message: 'state must be an object'}];
  const issues: FeedIssue[] = [];
  const {root, elements} = spec as FeedSpec;
  const reachable = new Set<string>();
  const active = new Set<string>();
  const walk = (id: string) => {
    if (active.has(id)) { issues.push({element: id, message: 'child graph contains a cycle'}); return; }
    if (reachable.has(id)) return;
    if (!elements[id]) { issues.push({element: id, message: 'referenced element is missing'}); return; }
    reachable.add(id);
    active.add(id);
    for (const child of elements[id].children ?? []) walk(child);
    active.delete(id);
  };
  walk(root);
  for (const id of Object.keys(elements)) if (!reachable.has(id)) issues.push({element: id, message: 'element is not reachable from root'});
  if (issues.length) return issues;
  let visits = 0;
  let work = 0;
  const templates = new Map<string, Set<string | undefined>>();
  const definitions: Record<string, Entry> = feedComponents;
  const check = (id: string, context: PropResolutionContext, emptyItem = false) => {
    if (issues.length >= 32) return;
    if (++work > 8192) { if (work === 8193) issues.push({element: id, message: 'validation exceeds 8192 visits'}); return; }
    if (emptyItem) {
      let scopes = templates.get(id);
      if (scopes?.has(context.repeatBasePath)) return;
      if (!scopes) { scopes = new Set(); templates.set(id, scopes); }
      scopes.add(context.repeatBasePath);
    }
    if (!emptyItem && ++visits > 2048) { if (visits === 2049) issues.push({element: id, message: 'expanded card exceeds 2048 elements'}); return; }
    const element = elements[id];
    const definition = definitions[element.type];
    if (!definition?.props) { issues.push({element: id, message: `unknown component ${element.type}`}); return; }
    const add = (message: string) => { if (issues.length < 32) issues.push({element: id, message}); };
    if (element.visible !== undefined && (!VisibilityConditionStrictSchema.safeParse(element.visible).success || conditionUsesItemScope(element.visible as Parameters<typeof conditionUsesItemScope>[0]) && context.repeatBasePath == null)) add('invalid visibility condition');
    const inspect = (value: unknown, shape: z.ZodType, references?: (resolved: unknown) => void, parameters = false) => {
      const error = expressionError(value, context);
      if (error) { add(error); return; }
      const resolve = emptyItem ? resolveEmptyValue : resolvePropValue;
      const variants = parameters ? parameterVariants(value, context, emptyItem) : resolvedVariants(value, context, resolve, resolve);
      for (const resolved of variants) {
        const parsed = shape.safeParse(resolved);
        if (!parsed.success) {
          const errors = parsed.error.issues.filter(issue => !unresolvedAt(resolved, issue.path));
          if (errors.length) add(errors.map(i => `${i.path.join('.')}: ${i.message}`).join('; '));
        }
        else if (unknownField(resolved, parsed.data)) add('unsupported property');
        references?.(resolved);
      }
    };
    inspect(element.props ?? {}, definition.props);
    if (!definition.slots?.length && (element.children?.length || element.repeat)) add(`${element.type} takes no children`);
    for (const [event, binding] of Object.entries(element.on ?? {})) {
      if (!definition.events?.includes(event)) add(`${element.type} has no ${event} event`);
      for (const b of Array.isArray(binding) ? binding : [binding]) {
        if (!isObject(b) || b.action !== 'dispatch') { add(`on.${event} must use the dispatch action`); continue; }
        inspect(b.params ?? {}, feedActions.dispatch.params, params => {
          if (actionIds && isObject(params) && typeof params.actionId === 'string' && !actionIds.has(params.actionId)) {
            add(`on.${event} dispatches unknown action ${params.actionId}`);
          }
        }, true);
      }
    }
    if (!element.repeat) {
      for (const child of element.children ?? []) check(child, context, emptyItem);
      return;
    }
    const repeat = z.object({statePath: z.union([z.string(), z.object({$item: z.string()}).strict()]), key: z.string().optional()}).strict().safeParse(element.repeat);
    if (!repeat.success) { add('invalid repeat'); return; }
    const path = resolveRepeatStatePath(repeat.data.statePath, context.repeatBasePath);
    if (path === undefined) { add('repeat item path is outside a repeat'); return; }
    const items = emptyItem ? [] : getByPath(state, path) ?? [];
    if (!Array.isArray(items)) { add('repeat state must be an array'); return; }
    if (!items.length) {
      for (const child of element.children ?? []) check(child, {stateModel: state, repeatBasePath: path}, true);
    } else items.forEach((repeatItem, repeatIndex) => {
      const childContext = {stateModel: state, repeatItem, repeatIndex, repeatBasePath: resolveRepeatItemStatePath(path, repeatIndex)};
      for (const child of element.children ?? []) check(child, childContext);
    });
  };
  check(root, {stateModel: state});
  return issues;
}

/** Every literal actionId a dispatch binding can produce (both branches of a $cond). */
export function dispatchedActionIds(params: unknown): string[] {
  const value = isObject(params) ? params.actionId : undefined;
  const leaves = (node: unknown): string[] => typeof node === 'string' ? [node] : isObject(node) && '$cond' in node ? [...leaves(node.$then), ...leaves(node.$else)] : [];
  return leaves(value);
}
