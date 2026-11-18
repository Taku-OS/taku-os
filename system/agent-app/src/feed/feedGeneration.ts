import {FEED_CATALOG, feedCatalog, validateFeedSpec} from './feedCatalog.ts';

export type GenerationInput = {kind: 'prompt'; prompt: string} | {kind: 'card'; card: unknown};
export type GenerationContext = {id: string; createdAt: number};
export type GenerateUi = (args: {
  instructions: string;
  prompt: string;
  catalogVersion: typeof FEED_CATALOG;
}) => Promise<unknown>;

export type FeedGenerationFailure = {ok: false; code: string; message: string};
type Content = {summary: string; ui: Record<string, unknown>; actions: Record<string, unknown>};
type ContentError = 'INVALID_CARD' | 'INVALID_MODEL_OUTPUT';

const MAX_BYTES = 96000;
const MAX_ELEMENTS = 96;
const SAFE_CODE = /^[A-Z][A-Z0-9_]{0,63}$/;
const isObject = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

export class FeedGenerationError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'FeedGenerationError';
    this.code = code;
  }
}

export function feedGenerationFailure(error: unknown): FeedGenerationFailure {
  if (error instanceof FeedGenerationError) return {ok: false, code: error.code, message: error.message};
  if (isObject(error) && typeof error.code === 'string' && SAFE_CODE.test(error.code)) {
    return {ok: false, code: error.code, message: 'The configured model could not generate a Feed card.'};
  }
  return {ok: false, code: 'GENERATION_FAILED', message: 'Feed generation could not be completed.'};
}

function exceedsByteLimit(text: string): boolean {
  let bytes = 0;
  for (let index = 0; index < text.length; index++) {
    const code = text.charCodeAt(index);
    if (code < 0x80) bytes++;
    else if (code < 0x800) bytes += 2;
    else if (code >= 0xd800 && code <= 0xdbff && index + 1 < text.length
      && text.charCodeAt(index + 1) >= 0xdc00 && text.charCodeAt(index + 1) <= 0xdfff) {
      bytes += 4;
      index++;
    } else bytes += 3;
    if (bytes > MAX_BYTES) return true;
  }
  return false;
}

function invalidSpec(): FeedGenerationError {
  return new FeedGenerationError('INVALID_SPEC', 'The Feed body must match taku-feed-1 and contain at most 96 elements.');
}

function validateContent(value: unknown, code: ContentError): Content {
  if (!isObject(value) || typeof value.summary !== 'string' || !value.summary.trim()) {
    throw new FeedGenerationError(code, 'Card content requires a nonblank summary.');
  }
  const {ui} = value;
  const state = isObject(ui) && ui.state === undefined && code === 'INVALID_CARD' ? {} : isObject(ui) ? ui.state : undefined;
  if (!isObject(ui) || ui.catalog !== FEED_CATALOG || !isObject(state)) {
    throw new FeedGenerationError(code, 'Card content requires a taku-feed-1 ui object with an object state.');
  }
  const actions = value.actions === undefined ? {} : value.actions;
  if (!isObject(actions)) throw new FeedGenerationError(code, 'Card actions must be an object when provided.');
  if (!isObject(ui.spec) || !isObject(ui.spec.elements) || Object.keys(ui.spec.elements).length > MAX_ELEMENTS) {
    throw invalidSpec();
  }
  try {
    if (validateFeedSpec(ui.spec, state, new Set(Object.keys(actions))).length) throw invalidSpec();
  } catch {
    throw invalidSpec();
  }
  return {summary: value.summary, ui, actions};
}

export async function prepareFeedGeneration(
  input: GenerationInput,
  context: GenerationContext,
  generateUi: GenerateUi,
): Promise<{card: Record<string, unknown>; model?: string}> {
  try {
    if (!isObject(input)) throw new FeedGenerationError('INVALID_REQUEST', 'A prompt or parsed Feed card is required.');
    if (input.kind === 'card') {
      const {card} = input;
      if (!isObject(card) || card.schema !== 'taku.feed-card/1') {
        throw new FeedGenerationError('INVALID_CARD', 'A parsed taku.feed-card/1 object is required.');
      }
      let serialized: string;
      try { serialized = JSON.stringify(card); }
      catch { throw new FeedGenerationError('INVALID_CARD', 'The Feed card must contain JSON data.'); }
      if (exceedsByteLimit(serialized)) throw new FeedGenerationError('INVALID_CARD', 'The Feed card exceeds 96000 UTF8 bytes.');
      validateContent(card, 'INVALID_CARD');
      return {card};
    }
    if (input.kind !== 'prompt' || typeof input.prompt !== 'string' || !input.prompt.trim() || input.prompt.length > 16000) {
      throw new FeedGenerationError('INVALID_REQUEST', 'The prompt must contain 1 to 16000 UTF16 units and cannot be blank.');
    }
    const instructions = feedCatalog.prompt({mode: 'standalone', customRules: [
      'Return exactly one complete JSON object with a nonblank summary string, a ui object, and optional actions:{}. The ui object contains catalog:"taku-feed-1", spec:{root,elements}, and state:{}. ui.state is required and belongs INSIDE ui, never at the top level. State and actions must be JSON objects. No Markdown, fences, JSONL, patches, or surrounding prose.',
      'Return content only. The host supplies schema, id, source, layout, rank, createdAt, expiresAt, pinned, and pin.',
      'Use at most 96 elements. Every child and dispatched actionId must exist. Each action is {label:string,intent:{type:"ask-taku",prompt:string}}, {label:string,intent:{type:"open-app",package:string,uri?:string}}, {label:string,intent:{type:"open-url",url:string}}, or {label:string,intent:{type:"dismiss"|"none"}}. open-url action URLs must use HTTPS.',
      'Remote image URLs must use HTTPS. Never rewrite an HTTP URL to HTTPS or invent a replacement; omit unsupported images. Known bundled image references use asset:feed/<path>.',
      'Use only facts supplied by the user. Do not invent facts, measurements, completed work, integrations, media URLs, or bundled asset paths. Use text and catalog glyphs when no verified media was supplied.',
      'Generation creates a card only. Never execute an action during generation or claim an action has succeeded. Actions describe explicit future user interactions.',
    ]});
    if (instructions.length > 60000) throw new FeedGenerationError('GENERATION_FAILED', 'The Feed catalog instructions exceed the generation limit.');
    const response = await generateUi({instructions, prompt: input.prompt, catalogVersion: FEED_CATALOG});
    if (!isObject(response) || typeof response.text !== 'string' || exceedsByteLimit(response.text)
      || typeof response.model !== 'string' || !response.model.trim()) {
      throw new FeedGenerationError('INVALID_MODEL_OUTPUT', 'The model must return bounded JSON text and its actual nonblank model identity.');
    }
    let parsed: unknown;
    try { parsed = JSON.parse(response.text.trim()); }
    catch { throw new FeedGenerationError('INVALID_MODEL_OUTPUT', 'The model must return exactly one complete JSON object without fences or surrounding text.'); }
    const {summary, ui, actions} = validateContent(parsed, 'INVALID_MODEL_OUTPUT');
    return {
      card: {
        schema: 'taku.feed-card/1', id: context.id, source: {id: 'agent', app: 'Taku'},
        layout: {span: 'full', surface: 'card'}, rank: 0, createdAt: context.createdAt,
        expiresAt: null, pinned: false, summary,
        pin: {label: summary.slice(0, 24), icon: {kind: 'pixel', glyph: 'list'}}, ui, actions,
      },
      model: response.model,
    };
  } catch (error) {
    const failure = feedGenerationFailure(error);
    throw new FeedGenerationError(failure.code, failure.message);
  }
}
