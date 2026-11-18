import {schema} from '@json-render/react-native/schema';
import {defineCatalog} from '@json-render/core';
import {z} from 'zod';
import {TEXT_VARIANTS} from '../feed/feedTokens.ts';
import {ICONS} from '../feed/feedCatalog.ts';
import {validUiValue} from './genui.ts';

export const CATALOG_VERSION = 'taku-chat-feed-1';
const value = z.strictObject({type: z.enum(['text', 'number', 'money', 'boolean', 'instant', 'duration']), value: z.union([z.string(), z.number(), z.boolean(), z.null()]), unit: z.string().optional(), currency: z.string().optional(), scale: z.number().optional(), timeZone: z.string().optional()}).refine(validUiValue);
const item = z.strictObject({id: z.string(), label: z.string(), value, detail: z.string(), url: z.string(), image: z.string(), icon: z.string()});
const items = z.array(item);
const config = z.strictObject({layout: z.enum(['list', 'horizontal', 'table', 'grid']).optional(), emphasis: z.enum(['hero', 'inline']).optional(), size: z.enum(['full_size', 'medium_size', 'small_size']).optional(), group: z.string().optional(), icon: z.string().optional(), editor: z.enum(['text', 'number', 'range', 'toggle', 'time']).optional(), min: z.number().optional(), max: z.number().optional(), step: z.number().positive().optional(), multi: z.boolean().optional(), mediaType: z.enum(['image', 'audio', 'video', 'camera']).optional(), captureSource: z.enum(['microphone', 'playback']).optional(), language: z.string().optional(), targetLanguage: z.string().optional(), recordAudio: z.boolean().optional(), imageFilter: z.enum(['warm', 'mono']).optional(), filterStrength: z.number().min(0).max(1).optional(), analysisPrompt: z.string().max(1000).optional()});
const content = {title: z.string().optional(), text: z.string().optional(), value: value.optional(), config: config.optional()};
const space = z.union([z.literal(0), z.literal(2), z.literal(4), z.literal(6), z.literal(8), z.literal(12), z.literal(16)]);
const radius = z.union([z.literal(0), z.literal(2), z.literal(4), z.literal(6), z.literal(8), z.literal(12), z.literal(24), z.literal(9999)]);
const flex = z.number().min(0).max(12).optional();
const tone = z.enum(['primary', 'secondary', 'tertiary', 'muted', 'foreground', 'inverse', 'inverseSoft', 'inverseMuted', 'brand', 'green', 'link']);
const icon = z.enum(Object.keys(ICONS) as [keyof typeof ICONS, ...(keyof typeof ICONS)[]]);
const box = {gap: space.optional(), padding: space.optional(), paddingX: space.optional(), paddingY: space.optional(), align: z.enum(['start', 'center', 'end', 'stretch']).optional(), justify: z.enum(['start', 'center', 'end', 'between']).optional(), wrap: z.boolean().optional(), flex};
const control = {disabled: z.boolean().optional(), busy: z.boolean().optional(), selected: z.boolean().optional()};
const textInput = {value: z.string().nullish(), label: z.string().optional(), placeholder: z.string().optional(), disabled: z.boolean().optional()};
const components = {
  Card: {props: z.strictObject({title: z.string().optional(), subtitle: z.string().optional()}), slots: ['default'], description: 'Single canonical Feed card surface.'},
  Column: {props: z.strictObject(box), slots: ['default'], description: 'Vertical structural stack with Feed spacing.'},
  Row: {props: z.strictObject(box), slots: ['default'], description: 'Horizontal structural stack; wrap long controls.'},
  Spacer: {props: z.strictObject({size: space.optional(), flex}), slots: [], description: 'Fixed or flexible empty space.'},
  ScrollContainer: {props: z.strictObject({horizontal: z.boolean().optional(), showsIndicator: z.boolean().optional()}), slots: ['default'], description: 'Scrollable content region.'},
  SafeArea: {props: z.strictObject({}), slots: ['default'], description: 'System safe-area structural wrapper.'},
  Pressable: {props: z.strictObject({...control, label: z.string().optional(), role: z.enum(['button', 'link']).optional(), expanded: z.boolean().optional()}), slots: ['default'], events: ['press', 'longPress'], description: 'Accessible controlled press target.'},
  Text: {props: z.strictObject({text: z.string(), variant: z.enum(TEXT_VARIANTS).optional(), tone: tone.optional(), align: z.enum(['left', 'center', 'right']).optional(), lines: z.number().int().min(1).max(12).optional(), selectable: z.boolean().optional(), flex}), slots: [], description: 'Feed text using named typography and tone.'},
  Value: {props: z.strictObject({text: z.string(), size: z.enum(['lg', 'xl', '2xl']).optional(), tone: tone.optional(), selectable: z.boolean().optional()}), slots: [], description: 'Typed display text with Feed value hierarchy.'},
  AppHeader: {props: z.strictObject({title: z.string().optional(), icon: z.string().optional(), wordmark: z.string().optional(), wordmarkWidth: z.number().min(8).max(160).optional(), trailing: z.string().optional(), trailingIcon: icon.optional(), count: z.string().optional(), gap: z.union([z.literal(4), z.literal(8)]).optional()}), slots: [], description: 'Feed app identity and trailing metadata.'},
  Inset: {props: z.strictObject({fill: z.enum(['none', 'content-50', 'content-100', 'content-200', 'surface-600', 'surface-800', 'surface-900', 'white']).optional(), radius: radius.optional()}), slots: ['default'], description: 'Canonical light Feed inset.'},
  Image: {props: z.strictObject({src: z.string(), alt: z.string().optional(), width: z.union([z.number().positive(), z.string().regex(/^\d+(?:\.\d+)?%$/)]).optional(), height: z.union([z.number().positive(), z.string().regex(/^\d+(?:\.\d+)?%$/)]).optional(), aspectRatio: z.number().positive().optional(), radius: radius.optional(), fit: z.enum(['cover', 'contain']).optional()}), slots: [], description: 'Supplied image with explicit content geometry.'},
  Avatar: {props: z.strictObject({src: z.string().optional(), initials: z.string().optional(), size: z.enum(['sm', 'md', 'lg']).optional()}), slots: [], description: 'Supplied avatar or initials.'},
  Tags: {props: z.strictObject({items: z.array(z.string())}), slots: [], description: 'Wrapping source labels.'},
  CornerTag: {props: z.strictObject({label: z.string(), tone: z.enum(['ai', 'solid', 'quiet']).optional(), icon: icon.optional()}), slots: [], description: 'Canonical Feed corner label.'},
  Button: {props: z.strictObject({...control, label: z.string(), variant: z.enum(['primary', 'secondary']).optional(), size: z.enum(['lg', 'md']).optional(), icon: z.literal('sparkle').optional(), flex}), slots: [], events: ['press'], description: 'Controlled Feed button; native action authority remains with the current declared action.'},
  ItemRow: {props: z.strictObject({title: z.string(), subtitle: z.string().optional(), leading: z.string().optional(), trailing: z.string().optional(), showChevron: z.boolean().optional()}), slots: [], events: ['press'], description: 'Source item identity, description and trailing value.'},
  Divider: {props: z.strictObject({direction: z.enum(['horizontal', 'vertical']).optional()}), slots: [], description: 'Canonical Feed separator.'},
  TextInput: {props: z.strictObject({...textInput, keyboardType: z.enum(['default', 'email-address', 'numeric', 'phone-pad', 'number-pad', 'decimal-pad', 'url']).optional(), secureTextEntry: z.boolean().optional(), multiline: z.boolean().optional()}), slots: [], events: ['change', 'submit'], description: 'Controlled text draft; bind value to a declared field.'},
  Switch: {props: z.strictObject({checked: z.boolean().nullish(), label: z.string().optional(), disabled: z.boolean().optional()}), slots: [], events: ['change'], description: 'Controlled boolean draft; null stays unknown.'},
  Checkbox: {props: z.strictObject({checked: z.boolean().nullish(), label: z.string().optional(), disabled: z.boolean().optional()}), slots: [], events: ['change'], description: 'Controlled checkbox.'},
  Slider: {props: z.strictObject({value: z.number().nullish(), label: z.string().optional(), min: z.number().optional(), max: z.number().optional(), step: z.number().positive().optional(), disabled: z.boolean().optional()}), slots: [], events: ['change'], description: 'Controlled numeric range; null stays unknown.'},
  SearchBar: {props: z.strictObject(textInput), slots: [], events: ['change', 'submit'], description: 'Controlled search draft.'},
  Counter: {props: z.strictObject({value: z.number().nullish(), label: z.string().optional(), step: z.number().positive().optional()}), slots: [], description: 'Controlled quantity editor; null stays unknown until explicit interaction.'},
  Spinner: {props: z.strictObject({size: z.enum(['small', 'large']).optional()}), slots: [], description: 'Active loading indicator.'},
  ProgressBar: {props: z.strictObject({value: z.number().min(0).max(1).nullable(), tone: z.enum(['green', 'brand', 'foreground']).optional()}), slots: [], description: 'Feed progress track; null is unknown.'},
  Modal: {props: z.strictObject({visible: z.boolean(), title: z.string().optional(), animationType: z.enum(['none', 'slide', 'fade']).optional()}), slots: ['default'], events: ['close'], description: 'Controlled native sheet.'},
  Status: {props: z.strictObject({text: z.string(), state: z.enum(['info', 'pending', 'error', 'review'])}), slots: [], description: 'Explicit source or operation status.'},
  Metric: {props: z.strictObject({...content, items: items.optional(), expanded: z.boolean().optional()}), slots: ['default'], description: 'Typed scalar with supporting values and detail children. Bind group expansion under /ui/views/<group>/expanded.'},
  Collection: {props: z.strictObject({...content, items, url: z.string().optional(), role: z.enum(['collection', 'timeline', 'sources']).optional()}), slots: [], description: 'Stable-ID collection with descriptions, typed values and supplied source links.'},
  ChoiceGroup: {props: z.strictObject({...content, options: items, selection: z.array(z.string()).nullish(), multi: z.boolean().nullish()}), slots: [], description: 'Controlled selection of stable item IDs.'},
  TimeDisplay: {props: z.strictObject({...content, items: items.optional()}), slots: [], description: 'Explicit-zone instant or duration with supporting items; bind items even when empty.'},
  RouteView: {props: z.strictObject({...content, items, image: z.string().optional(), url: z.string().optional()}), slots: [], description: 'Supplied ordered stops, times and route image; no simulated live tracking.'},
  MediaSurface: {props: z.strictObject({...content, image: z.string().optional(), url: z.string().optional()}), slots: [], description: 'Real native image, audio/video, camera or explicitly started capture session; preserve media ownership.'},
  Overlay: {props: z.strictObject({...content, image: z.string().optional()}), slots: ['default'], description: 'Content and child layout over a supplied image.'},
  Progress: {props: z.strictObject(content), slots: [], description: 'Typed numeric progress using config min/max; unknown stays unknown.'},
  Field: {props: z.strictObject({...content, draft: z.union([z.string(), z.number(), z.boolean(), z.null()])}), slots: [], description: 'Controlled typed draft; bind under /ui/fields/<id>/draft.'},
};
const actions = {
  setState: {params: z.strictObject({statePath: z.string(), value: z.unknown()}), description: 'Update a declared editable field, selection or expansion path through the guarded local store.'},
  openURL: {params: z.strictObject({url: z.string()}), description: 'Open a supplied safe source URL through the application.'},
  reply: {params: z.strictObject({actionId: z.string(), text: z.string(), select: z.array(z.string()).nullish()}), description: 'Dispatch the current declared action by stable ID through the application and native TaskBroker.'},
};
type Entry = {props?: z.ZodType; params?: z.ZodType; description: string; slots?: readonly string[]; events?: readonly string[]};
type Context = {catalog: unknown; formatZodType: (shape: z.ZodType) => string};
function cardPrompt({catalog: data, formatZodType}: Context): string {
  const {components, actions: actionDefs} = data as {components: Record<string, Entry>; actions: Record<string, Entry>};
  const describe = (entries: Record<string, Entry>) => Object.entries(entries).map(([name, e]) => `- ${name} ${formatZodType(e.props ?? e.params!)}: ${e.description}${e.slots?.length ? '; has children' : ''}${e.events?.length ? `; events ${e.events.join(',')}` : ''}`).join('\n');
  return [
    'Design one useful phone card using the existing data. Output JSONL only: RFC6902 add /root and add /elements/<id> patches. Never emit /state patches.',
    'Use one Card root and only the declared components and exact props. Drawing follows Feed typography, surfaces and controls. Structural stacks use named spacing; content widgets are not separate card surfaces.',
    'Read facts with {"$state":"/data/blocksById/<blockId>/..."}. Read actions from /data/actionsById/<actionId>. Never bind array indices or copy live values into literals.',
    'Inputs use {"$bindState":"/ui/fields/<blockId>/draft"}; choices use {"$bindState":"/ui/collections/<blockId>/selectedItemIds"}. Selection values are stable item IDs. Never write /data, /meta or /commands.',
    'Buttons emit reply with actionId, current bound reply text and declared selection paths; disabled binds /data/actionsById/<id>/disabled. TaskBroker owns business execution and confirmation. No synthetic playback, camera preview, transport tracking or success.',
    'Show every block and action. Keep titles/notes bound even when empty. At most 96 elements; leaves have children:[].',
    `COMPONENTS\n${describe(components)}`, `ACTIONS\n${describe(actionDefs)}`,
  ].join('\n\n');
}
export const catalog = defineCatalog({...schema, defaultRules: [], promptTemplate: cardPrompt}, {components, actions});
export type TakuCatalog = typeof catalog;
export const CARD_ACTIONS = new Set(catalog.actionNames);
let cachedPrompt = '';
export function generationInstructions(): string {
  if (!cachedPrompt) cachedPrompt = catalog.prompt();
  return cachedPrompt;
}
