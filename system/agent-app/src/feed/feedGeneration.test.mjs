import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {Buffer} from 'node:buffer';
import {FeedGenerationError, feedGenerationFailure, prepareFeedGeneration} from './feedGeneration.ts';

const context = {id: 'agent-generated:12345678-1234-4234-8234-123456789abc', createdAt: 1793836800000};
const model = 'configured-model/actual-release';
const sample = JSON.parse(readFileSync(new URL('../../modules/taku-home/android/src/main/assets/feed/sample-feed.json', import.meta.url), 'utf8'));

function content() {
  return {
    summary: 'Review the supplied checklist with Taku',
    ui: {
      catalog: 'taku-feed-1',
      spec: {root: 'card', elements: {
        card: {type: 'Column', props: {gap: 12}, children: ['title', 'checklist', 'review']},
        title: {type: 'Text', props: {text: {$state: '/data/title'}, variant: 'heading-lg'}, children: []},
        checklist: {type: 'Checklist', props: {items: {$state: '/data/items'}, checked: {$bindState: '/ui/done'}}, children: [],
          on: {change: {action: 'dispatch', params: {actionId: 'review', select: ['/ui/done']}}}},
        review: {type: 'Button', props: {label: 'Review', variant: 'primary', icon: 'sparkle'}, children: [],
          on: {press: {action: 'dispatch', params: {actionId: 'review', select: ['/ui/done']}}}},
      }},
      state: {data: {title: 'Before leaving', items: [{id: 'keys', label: 'Take keys'}, {id: 'ticket', label: 'Take ticket'}]}, ui: {done: ['keys']}},
    },
    actions: {review: {label: 'Review', intent: {type: 'ask-taku', prompt: 'Review the checklist choices I selected.'}}},
  };
}

function fileCard(body = content()) {
  return {
    schema: 'taku.feed-card/1', id: 'original-file-card', source: {id: 'recommendation', app: 'Checklist', package: 'org.example.checklist'},
    layout: {span: 'half', surface: 'card'}, rank: 42.5, createdAt: 1793750400000, expiresAt: null, pinned: true,
    summary: body.summary, pin: {label: 'Checklist', detail: 'Before leaving', icon: {kind: 'pixel', glyph: 'list'}, trailing: {icon: 'play', actionId: 'review'}},
    ui: body.ui, actions: body.actions, glance: {kind: 'custom-file-data', value: 7},
  };
}

function freeze(value) {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(freeze);
    Object.freeze(value);
  }
  return value;
}

function rejectWith(code) {
  return error => {
    assert.ok(error instanceof FeedGenerationError);
    assert.equal(error.code, code);
    assert.ok(error.message.length <= 256);
    return true;
  };
}

function fromText(text, identity = model) {
  return prepareFeedGeneration({kind: 'prompt', prompt: 'Create my supplied checklist'}, context, async () => ({text, model: identity}));
}

function withoutModel() {
  assert.fail('A parsed file must never invoke the model');
}

test('description content becomes the complete host-owned envelope and keeps the actual model identity', async () => {
  const body = content();
  const input = {kind: 'prompt', prompt: '  Create a checklist for keys and ticket.\nKeep my wording.  '};
  const actualModel = ' actual-provider/model@2026-11 ';
  let requests = 0;
  const prepared = await prepareFeedGeneration(input, context, async args => {
    requests++;
    assert.equal(args.prompt, input.prompt);
    assert.equal(args.catalogVersion, 'taku-feed-1');
    assert.ok(args.instructions.length <= 60000);
    return {text: ` \n${JSON.stringify({...body, id: 'model-cannot-choose-id', pinned: true, rank: 999})}\n `, model: actualModel};
  });
  assert.equal(requests, 1);
  assert.equal(prepared.model, actualModel);
  assert.equal(prepared.card.id, context.id);
  assert.equal(prepared.card.pinned, false);
  assert.equal(prepared.card.summary, body.summary);
  assert.deepEqual(prepared.card.ui, body.ui);
  assert.deepEqual(prepared.card.actions, body.actions);
});

test('a content response without actions gets an empty map and keeps its complete UI state', async () => {
  const body = content();
  body.ui.spec = {root: 'label', elements: {label: {type: 'Text', props: {text: 'Take keys'}, children: []}}};
  delete body.actions;
  const prepared = await fromText(JSON.stringify(body));
  assert.deepEqual(prepared.card.actions, {});
  assert.deepEqual(prepared.card.ui, body.ui);
});

test('a parsed full Feed file keeps every original field without a model request', async () => {
  const card = freeze(JSON.parse(JSON.stringify(fileCard())));
  const before = JSON.stringify(card);
  const prepared = await prepareFeedGeneration({kind: 'card', card}, context, withoutModel);
  assert.equal(prepared.card, card);
  assert.deepEqual(Object.keys(prepared), ['card']);
  assert.equal(JSON.stringify(card), before);
  assert.equal(prepared.card.id, 'original-file-card');
  assert.equal(prepared.card.pinned, true);
  assert.equal(prepared.card.rank, 42.5);
});

test('a real bundled Feed card uses the same body validator and preserves its pin, actions, media, and state', async () => {
  const card = freeze(sample.cards.find(candidate => candidate.id === 'slack-uiux'));
  assert.ok(card);
  const before = JSON.stringify(card);
  const prepared = await prepareFeedGeneration({kind: 'card', card}, context, withoutModel);
  assert.equal(prepared.card, card);
  assert.equal(JSON.stringify(prepared.card), before);
  assert.equal(prepared.model, undefined);
});

test('a static file can omit initial state while model output must declare it', async () => {
  const body = {summary: 'Take keys', ui: {catalog: 'taku-feed-1', spec: {root: 'label', elements: {label: {type: 'Text', props: {text: 'Take keys'}, children: []}}}}};
  await assert.rejects(fromText(JSON.stringify(body)), rejectWith('INVALID_MODEL_OUTPUT'));
  const card = fileCard(body);
  const prepared = await prepareFeedGeneration({kind: 'card', card}, context, withoutModel);
  assert.deepEqual(prepared.card, card);
  assert.equal(Object.hasOwn(prepared.card.ui, 'state'), false);
});

for (const [name, response] of [
  ['absent result', undefined], ['null result', null], ['array result', []], ['string result', '{}'],
  ['missing text', {model}], ['nonstring text', {text: {}, model}], ['missing model', {text: '{}'}],
  ['null model', {text: '{}', model: null}], ['numeric model', {text: '{}', model: 5}], ['blank model', {text: '{}', model: ' \n '}],
]) {
  test(`model output rejects ${name} without inventing content or model identity`, async () => {
    await assert.rejects(prepareFeedGeneration({kind: 'prompt', prompt: 'A checklist'}, context, async () => response), rejectWith('INVALID_MODEL_OUTPUT'));
  });
}

for (const [name, makeText] of [
  ['empty text', () => ' \n '], ['fenced JSON', json => `\`\`\`json\n${json}\n\`\`\``],
  ['two JSON objects', json => `${json}\n${json}`], ['surrounding prose', json => `Here is your card:\n${json}`],
  ['truncated JSON', json => json.slice(0, -1)], ['JSONL patches', () => '{"op":"add","path":"/root","value":"card"}\n{"op":"add","path":"/elements/card","value":{}}'],
]) {
  test(`the entire model response must parse, rejecting ${name}`, async () => {
    await assert.rejects(fromText(makeText(JSON.stringify(content()))), rejectWith('INVALID_MODEL_OUTPUT'));
  });
}

for (const value of [null, [], true, 9, 'a card']) {
  test(`model JSON must be a content object, rejecting ${JSON.stringify(value)}`, async () => {
    await assert.rejects(fromText(JSON.stringify(value)), rejectWith('INVALID_MODEL_OUTPUT'));
  });
}

for (const [name, mutate] of [
  ['missing summary', body => { delete body.summary; }], ['blank summary', body => { body.summary = ' \n '; }],
  ['nonstring summary', body => { body.summary = 5; }], ['missing ui', body => { delete body.ui; }],
  ['array ui', body => { body.ui = []; }], ['wrong catalog', body => { body.ui.catalog = 'general-genui'; }],
  ['array state', body => { body.ui.state = []; }],
  ['null state', body => { body.ui.state = null; }], ['array actions', body => { body.actions = []; }],
  ['null actions', body => { body.actions = null; }],
]) {
  test(`invalid content shape rejects ${name} for both input routes`, async () => {
    const body = content();
    mutate(body);
    await assert.rejects(fromText(JSON.stringify(body)), rejectWith('INVALID_MODEL_OUTPUT'));
    const card = {...fileCard(), ...body};
    if (!Object.hasOwn(body, 'ui')) delete card.ui;
    if (!Object.hasOwn(body, 'summary')) delete card.summary;
    await assert.rejects(prepareFeedGeneration({kind: 'card', card}, context, withoutModel), rejectWith('INVALID_CARD'));
  });
}

for (const [name, mutate] of [
  ['nonobject spec', body => { body.ui.spec = []; }],
  ['missing elements', body => { delete body.ui.spec.elements; }],
  ['missing root', body => { body.ui.spec.root = 'absent'; }],
  ['unknown component', body => { body.ui.spec.elements.title.type = 'GeneralGenUiPanel'; }],
  ['invalid literal props', body => { body.ui.spec.elements.title.props.text = 7; }],
  ['invalid bound state', body => { body.ui.state.data.items = 'not checklist items'; }],
  ['dangling child', body => { body.ui.spec.elements.card.children.push('absent'); }],
  ['unsupported event', body => { body.ui.spec.elements.review.on.swipe = body.ui.spec.elements.review.on.press; }],
  ['unknown dispatch action', body => { body.ui.spec.elements.review.on.press.params.actionId = 'absent'; }],
  ['invalid conditional action branch', body => { body.ui.spec.elements.review.on.press.params.actionId = {$cond: true, $then: 'review', $else: 'absent'}; }],
  ['cycle', body => { body.ui.spec.elements.card.children.push('card'); }],
]) {
  test(`shared body validation rejects ${name} for descriptions and parsed files`, async () => {
    const body = content();
    mutate(body);
    await assert.rejects(fromText(JSON.stringify(body)), rejectWith('INVALID_SPEC'));
    await assert.rejects(prepareFeedGeneration({kind: 'card', card: fileCard(body)}, context, withoutModel), rejectWith('INVALID_SPEC'));
  });
}

for (const [name, card] of [
  ['file path', '/tmp/card.json'], ['JSON string', JSON.stringify(fileCard())], ['wrapper', {card: fileCard()}],
  ['array', [fileCard()]], ['null', null], ['wrong schema', {...fileCard(), schema: 'taku.panel/1'}],
]) {
  test(`file generation requires the actual parsed FeedCard, rejecting ${name}`, async () => {
    await assert.rejects(prepareFeedGeneration({kind: 'card', card}, context, withoutModel), rejectWith('INVALID_CARD'));
  });
}

test('a non-JSON file candidate produces a bounded card error', async () => {
  for (const value of [1n, fileCard()]) {
    const card = fileCard();
    card.extra = value;
    if (typeof value === 'object') value.self = value;
    await assert.rejects(prepareFeedGeneration({kind: 'card', card}, context, withoutModel), rejectWith('INVALID_CARD'));
  }
});

function elementCountBody(count) {
  const body = content();
  const children = Array.from({length: count - 1}, (_, index) => `text-${index}`);
  body.ui.spec = {root: 'card', elements: {card: {type: 'Column', props: {gap: 12}, children},
    ...Object.fromEntries(children.map(id => [id, {type: 'Text', props: {text: id}, children: []}])),
  }};
  return body;
}

test('the 96 element body limit applies equally to descriptions and full files', async () => {
  const atLimit = elementCountBody(96);
  assert.equal(Object.keys((await fromText(JSON.stringify(atLimit))).card.ui.spec.elements).length, 96);
  assert.equal((await prepareFeedGeneration({kind: 'card', card: fileCard(atLimit)}, context, withoutModel)).card.ui.spec.elements.card.children.length, 95);
  const overLimit = elementCountBody(97);
  await assert.rejects(fromText(JSON.stringify(overLimit)), rejectWith('INVALID_SPEC'));
  await assert.rejects(prepareFeedGeneration({kind: 'card', card: fileCard(overLimit)}, context, withoutModel), rejectWith('INVALID_SPEC'));
});

function fillByteBudget(value, key) {
  value[key] = '';
  const remaining = 96000 - Buffer.byteLength(JSON.stringify(value), 'utf8');
  value[key] = '🙂'.repeat(Math.floor(remaining / 4)) + 'x'.repeat(remaining % 4);
  assert.equal(Buffer.byteLength(JSON.stringify(value), 'utf8'), 96000);
  return value;
}

test('model response size uses UTF8 bytes and counts the entire response before trimming', async () => {
  const body = fillByteBudget(content(), 'summary');
  const text = JSON.stringify(body);
  assert.ok(text.length < 96000);
  assert.equal((await fromText(text)).card.summary, body.summary);
  await assert.rejects(fromText(`${text} `), rejectWith('INVALID_MODEL_OUTPUT'));
  body.summary += '🙂';
  await assert.rejects(fromText(JSON.stringify(body)), rejectWith('INVALID_MODEL_OUTPUT'));
});

test('parsed file size uses the same 96000 UTF8 byte budget and preserves Unicode content', async () => {
  const card = fillByteBudget(fileCard(), 'notes');
  assert.equal((await prepareFeedGeneration({kind: 'card', card}, context, withoutModel)).card, card);
  card.notes += 'x';
  await assert.rejects(prepareFeedGeneration({kind: 'card', card}, context, withoutModel), rejectWith('INVALID_CARD'));
});

test('prompt length uses UTF16 units and invalid prompts never invoke the model', async () => {
  let requests = 0;
  const generate = async () => { requests++; return {text: JSON.stringify(content()), model}; };
  await prepareFeedGeneration({kind: 'prompt', prompt: '🙂'.repeat(8000)}, context, generate);
  for (const prompt of ['', ' \n ', '🙂'.repeat(8000) + 'x', 5, null]) {
    await assert.rejects(prepareFeedGeneration({kind: 'prompt', prompt}, context, generate), rejectWith('INVALID_REQUEST'));
  }
  for (const input of [null, {}, {kind: 'file', path: '/tmp/card.json'}]) {
    await assert.rejects(prepareFeedGeneration(input, context, generate), rejectWith('INVALID_REQUEST'));
  }
  assert.equal(requests, 1);
});

test('gateway errors keep a safe code without leaking diagnostics or retrying', async () => {
  const secret = 'https://user:private-password@example.invalid/?api_key=private-token';
  let requests = 0;
  await assert.rejects(prepareFeedGeneration({kind: 'prompt', prompt: 'A checklist'}, context, async () => {
    requests++;
    throw Object.assign(new Error(secret.repeat(200)), {code: 'MODEL_ROUTE_UNAVAILABLE'});
  }), error => {
    rejectWith('MODEL_ROUTE_UNAVAILABLE')(error);
    assert.ok(!error.message.includes(secret));
    assert.ok(!error.message.includes('private-token'));
    assert.deepEqual(feedGenerationFailure(error), {ok: false, code: 'MODEL_ROUTE_UNAVAILABLE', message: error.message});
    return true;
  });
  assert.equal(requests, 1);
});

test('unsafe or absent gateway codes produce a bounded credential-free failure receipt', async () => {
  for (const code of ['private-token\nAUTH', 'x'.repeat(1000), 'https://user:secret@example.invalid', undefined]) {
    const error = Object.assign(new Error('Bearer private-token'), {code});
    const receipt = feedGenerationFailure(error);
    assert.equal(receipt.ok, false);
    assert.equal(receipt.code, 'GENERATION_FAILED');
    assert.ok(!receipt.message.includes('private-token'));
    await assert.rejects(prepareFeedGeneration({kind: 'prompt', prompt: 'A checklist'}, context, async () => { throw error; }), rejectWith('GENERATION_FAILED'));
  }
});

test('invalid body diagnostics cannot echo untrusted content', async () => {
  const body = content();
  const secret = 'private-token-should-never-appear';
  body.ui.spec.elements.title.type = secret.repeat(1000);
  await assert.rejects(fromText(JSON.stringify(body)), error => {
    rejectWith('INVALID_SPEC')(error);
    assert.ok(!error.message.includes(secret));
    return true;
  });
});

test('excessive distinct empty repeat scopes fail for both generation inputs', async () => {
  const body = content();
  const elements = {list: {type: 'Column', props: {}, repeat: {statePath: '/items'}, children: ['left-0', 'right-0']}};
  for (let level = 0; level < 30; level++) {
    const children = level === 29 ? [] : [`left-${level + 1}`, `right-${level + 1}`];
    for (const side of ['left', 'right']) elements[`${side}-${level}`] = {type: 'Column', props: {}, repeat: {statePath: {$item: side}}, children};
  }
  body.ui = {catalog: 'taku-feed-1', state: {items: []}, spec: {root: 'list', elements}};
  body.actions = {};
  await assert.rejects(fromText(JSON.stringify(body)), rejectWith('INVALID_SPEC'));
  await assert.rejects(prepareFeedGeneration({kind: 'card', card: fileCard(body)}, context, withoutModel), rejectWith('INVALID_SPEC'));
});

test('file and model-output generation reject cleartext image URLs', async () => {
  for (const src of ['http://192.168.1.20:8080/photo.png', 'http://printer.local/diagram.svg?revision=2#view']) {
    const body = content();
    body.ui.spec.elements.card.children.push('photo');
    body.ui.spec.elements.photo = {type: 'Image', props: {src, height: 120}, children: []};
    await assert.rejects(prepareFeedGeneration({kind: 'card', card: fileCard(body)}, context, withoutModel), rejectWith('INVALID_SPEC'));
    await assert.rejects(fromText(JSON.stringify(body)), rejectWith('INVALID_SPEC'));
  }
});
