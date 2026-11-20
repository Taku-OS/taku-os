/// <reference types="node" />

import {createHash} from 'node:crypto';
import {mkdir, rename, writeFile} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import process from 'node:process';
import {fileURLToPath} from 'node:url';
import {z} from 'zod';
import {CATALOG_VERSION, catalog} from '../src/model/genuiCatalog.ts';

type Rule = Record<string, unknown>;
const keywords: Record<string, true> = {
  $schema: true, additionalProperties: true, anyOf: true, const: true, enum: true, exclusiveMinimum: true,
  items: true, maxLength: true, maximum: true, minimum: true, pattern: true, properties: true, required: true, type: true,
};
const object = (value: unknown): value is Rule => !!value && typeof value === 'object' && !Array.isArray(value);

function normalize(rule: Rule, path: string): Rule {
  for (const key of Object.keys(rule)) if (!Object.hasOwn(keywords, key)) throw new Error(`Unsupported reply schema keyword ${path}.${key}`);
  if (!Object.keys(rule).length && path !== 'actions.setState.value') throw new Error(`Unconstrained reply schema ${path}`);
  if (Array.isArray(rule.type)) return {anyOf: rule.type.map((type: unknown) => normalize({...rule, type}, path))};
  const result: Rule = {};
  for (const [key, value] of Object.entries(rule)) {
    if (key === '$schema') continue;
    if (key === 'const') result.enum = [value];
    else if (key === 'properties') {
      if (!object(value)) throw new Error(`Invalid reply properties ${path}`);
      result.properties = Object.fromEntries(Object.entries(value).map(([name, child]) => {
        if (!object(child)) throw new Error(`Invalid reply schema ${path}.${name}`);
        return [name, normalize(child, `${path}.${name}`)];
      }));
    } else if (key === 'anyOf') {
      if (!Array.isArray(value) || !value.every(object)) throw new Error(`Invalid reply alternatives ${path}`);
      result.anyOf = value.map(child => normalize(child, path));
    } else if (key === 'items') {
      if (!object(value)) throw new Error(`Invalid reply item schema ${path}`);
      result.items = normalize(value, `${path}[]`);
    } else result[key] = value;
  }
  return result;
}

function freeText(rule: Rule): boolean {
  if (rule.enum) return false;
  return rule.type === 'string' || Array.isArray(rule.anyOf) && rule.anyOf.some(child => object(child) && freeText(child));
}

const contentNames: Record<string, true> = {value: true, checked: true, draft: true, items: true, options: true, config: true, selection: true};
const layoutStrings: Record<string, true> = {width: true, height: true};
function contentProps(props: Rule): string[] {
  const properties = object(props.properties) ? props.properties : {};
  return Object.entries(properties).filter(([name, rule]) => Object.hasOwn(contentNames, name)
    || !Object.hasOwn(layoutStrings, name) && object(rule) && freeText(rule)).map(([name]) => name);
}

function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (object(value)) return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  const encoded = JSON.stringify(value);
  if (encoded === undefined) throw new Error('Reply contract contains a non-JSON value');
  return encoded;
}

const componentDefinitions: Record<string, {props: z.ZodType; events?: readonly string[]; slots?: readonly string[]}> = catalog.data.components;
const actionDefinitions: Record<string, {params: z.ZodType}> = catalog.data.actions;
const components = Object.fromEntries(Object.entries(componentDefinitions).map(([name, entry]) => {
  const props = normalize(z.toJSONSchema(entry.props) as Rule, `components.${name}`);
  return [name, {props, events: [...(entry.events ?? [])], children: !!entry.slots?.length, contentProps: contentProps(props)}];
}));
const actions = Object.fromEntries(['setState', 'openURL', 'reply'].map(name => {
  const entry = actionDefinitions[name];
  if (!entry) throw new Error(`Missing primary reply action ${name}`);
  return [name, {params: normalize(z.toJSONSchema(entry.params) as Rule, `actions.${name}`)}];
}));
const base = {
  schema: 'taku.reply-contract/1', protocol: 'taku.reply/1', catalogVersion: CATALOG_VERSION,
  limits: {sourceChars: 262144, lineChars: 16384, records: 4096, elements: 96, depth: 32},
  components, actions,
};
const contractHash = createHash('sha256').update(canonical(base)).digest('hex');
const prompt = [
  'PRIMARY CONVERSATION REPLY: Your answer itself is one progressive card, using the supplied typed data and components. Do not call another model or a formatting/layout tool for this reply. Tools for actual user work, observations, confirmations and continuing Matters retain their existing authority. Never claim an external operation succeeded without its actual evidence.',
  'This contract applies only when the latest confirmed replyContract.mode is primary-card. When mode is upstream-schema, this protocol is disabled and the caller outputSchema or private task instructions remain authoritative. Do not change reasoning, commentary, tool arguments or tool results into card records.',
  `For your final answer, start with the exact line @taku.reply/1 followed by LF. Then emit strict JSONL: one complete JSON object per line, ending every line with LF. No Markdown fences, prose outside records, array wrapper, comments or duplicate keys. Internal newlines in string values are escaped. Every record has a positive integer n, starting at1 and increasing by1. Catalog version is ${CATALOG_VERSION}.`,
  'Emit a short useful content block and its components early, then add the rest. Do not wait to write the whole answer. Each complete valid record can appear immediately. Keep the same semantic IDs and component IDs. Later facts update bound data, never an existing component tree. All factual text, values, images, URLs and action labels occur once in semantic data; component content props bind them.',
  'Records and exact fields (unknown fields fail):',
  'begin: {n,type:"begin",catalogVersion,mode:"card"|"panel",title?,summary?,pending?}. First record only. The native owner supplies request version/key, thread/turn/source identities, provenance and completion. Never emit those fields.',
  'mode: use "panel" only when the user explicitly asks for a panel (or a dashboard, tracker or board to keep), or when the answer is ongoing reference worth keeping at hand, such as a trip plan, schedule, checklist, status board or tracker. Only panel replies offer Pin to Panels. Everything else, including greetings, questions, clarifications and one-off answers, uses "card".',
  'card: {n,type:"card",title?,summary?,pending?}. Supply title and/or summary. Omitted fields remain unchanged.',
  'block: {n,type:"block",id,kind?,title?,text?,value?,image?,url?,items?,config?,pending?}. Declare kind on first appearance. Use the exact UiRequest V2 roles supplied alongside this contract. A value is a COMPLETE tagged {type,value,...metadata} object. Scalars, time/progress and inputs require correct complete tagged value and editor metadata together before display. Missing optional display strings/items are empty/absent, not invented facts. Stable kind, value type/metadata and structural editor/layout config after display. No deletion or row reordering.',
  'item: {n,type:"item",blockId,id,label?,value?,detail?,url?,image?,icon?,pending?}. Parent block must exist. Initial label required. Stable IDs append once; later supplied fields update. Omitted rows/fields remain. Empty arrays do not erase known content.',
  'action: {n,type:"action",id,label?,reply?,style?}. Initial label,reply,style required; style is primary or secondary. Only a follow-up reply proposal. Never emit actionRef or a business operation. Native completion admits actual capabilities.',
  'element: {n,type:"element",id,parent,component,props,on?}. First element is Card with parent:null. Every other parent already exists; native appends this complete node as its child. Use only catalog components/exact props/events. Do not emit children,state,visible,repeat,patch paths, replacements, removal or moves. Existing component/type/parent/props/on never change; append new components when needed.',
  'done: {n,type:"done"}. End after all semantic content is represented in valid components. Do not output anything after done. Done seals presentation; native successful completion is separate. Every block and action must be represented. Keep all known content.',
  'pending is optional on begin/card/block/item and lists relative field names still expected. begin/card: title,summary,body,actions; block: title,text,value,image,url,items,config; item: label,detail,value,image,url,icon. Use only the fields owned by that record. Supplied content remains visible while pending. Unannounced optional fields are absent. Tagged null is explicit unknown, never zero/false/current time. Done settles remaining empty pending fields absent; never erase known values to settle.',
  'Tagged values: text uses string|null (max1000); number finite number|null; boolean boolean|null; money uses integer MINOR-UNIT STRING|null with ISO currency and scale0..6; duration nonnegative milliseconds|null; instant ISO offset timestamp|null with an explicit IANA timeZone. Do not send partial money/time metadata or strings for numeric editors. Display data is not evidence of actual device work.',
  'Bind content with {"$state":"/data/title"}, /data/summary, /data/blocksById/<blockId>/<field>, or /data/actionsById/<actionId>/<field>. Read complete items through /data/blocksById/<id>/items; never array indices. Inputs use {"$bindState":"/ui/fields/<blockId>/draft"}; choices use /ui/collections/<blockId>/selectedItemIds; declared group expansion is /ui/views/<group>/expanded. Only declared editable local paths may be written. No /meta,/commands or model-authored /state. Primary expressions are only $state and $bindState; no $template,$cond or arbitrary expressions.',
  'Content props named in the component manifest must bind the semantic data (draft/selection controls bind declared /ui). Literal spacing, typography, enum roles and other layout constants are allowed. Source URLs/images/icons must come from actual supplied/observed content, never invented placeholders. Exact props are checked after binding resolution.',
  'Buttons for a declared reply action bind label, variant and disabled to /data/actionsById/<id>/label, /style and /disabled respectively. The press mapping is {"action":"reply","params":{"actionId":"<id>","text":{"$state":"/data/actionsById/<id>/reply"},"select":[]}}. Native TaskBroker owns execution/confirmation; rendering or pressing a follow-up is not a verified business result. Native capabilities are unavailable before successful completion.',
  'Bounds: source262144 UTF-16 units, each line16384,4096 records,96 elements,tree depth32,8 blocks,20 items per block,32 actions. Title80,summary160,block text1000,item label80/detail200,action label40/reply300 characters. Use stable text blocks for longer prose. Exceeding bounds fails honestly; do not claim a truncated answer is complete.',
  'Minimal progressive answer (the first text becomes visible before later rows/done):',
  '@taku.reply/1',
  JSON.stringify({n: 1, type: 'begin', catalogVersion: CATALOG_VERSION, mode: 'card', title: 'Comparison'}),
  JSON.stringify({n: 2, type: 'element', id: 'root', parent: null, component: 'Card', props: {title: {$state: '/data/title'}, subtitle: {$state: '/data/summary'}}}),
  JSON.stringify({n: 3, type: 'block', id: 'answer', kind: 'text', text: 'Both options fit the information you supplied.'}),
  JSON.stringify({n: 4, type: 'element', id: 'answer-text', parent: 'root', component: 'Text', props: {text: {$state: '/data/blocksById/answer/text'}, selectable: true}}),
  JSON.stringify({n: 5, type: 'block', id: 'options', kind: 'collection', title: 'Options', pending: ['items']}),
  JSON.stringify({n: 6, type: 'element', id: 'options-list', parent: 'root', component: 'Collection', props: {title: {$state: '/data/blocksById/options/title'}, text: {$state: '/data/blocksById/options/text'}, value: {$state: '/data/blocksById/options/value'}, config: {$state: '/data/blocksById/options/config'}, items: {$state: '/data/blocksById/options/items'}, url: {$state: '/data/blocksById/options/url'}, role: 'collection'}}),
  JSON.stringify({n: 7, type: 'item', blockId: 'options', id: 'basic', label: 'Basic', value: {type: 'money', value: '1000', currency: 'USD', scale: 2}, detail: 'Lower supplied price.'}),
  JSON.stringify({n: 8, type: 'item', blockId: 'options', id: 'plus', label: 'Plus', value: {type: 'money', value: '1200', currency: 'USD', scale: 2}, detail: 'Higher supplied price.'}),
  JSON.stringify({n: 9, type: 'done'}),
  'For editable controls declare an input block with a complete tagged value/config and a Field with title,text,value,config bound to that block and draft bound to /ui/fields/<id>/draft. For a numeric range supply number|null and {editor:"range",min,max,step} together. Keep edits separate from new source values. Do not add a second prose copy: native derives Copy/search/history text from this semantic answer.',
  'CURRENT COMPONENTS (props are resolved-value JSON schemas; dynamic contentProps must bind semantic data):',
  JSON.stringify(components),
  'CURRENT ACTIONS (only declared native-guarded local paths/actions):',
  JSON.stringify(actions),
].join('\n');
const output = resolve(process.argv[2] ?? fileURLToPath(new URL('../../assistant/out/reply-assets/native/reply-contract.json', import.meta.url)));
await mkdir(dirname(output), {recursive: true});
const temporary = `${output}.${process.pid}.tmp`;
await writeFile(temporary, `${JSON.stringify({...base, contractHash, prompt})}\n`, 'utf8');
await rename(temporary, output);
console.log(`Reply contract ${CATALOG_VERSION} ${contractHash} -> ${output}`);
