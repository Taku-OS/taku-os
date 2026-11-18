import test from 'node:test';
import assert from 'node:assert/strict';
import {UI_KINDS, cardState, choicePath, fieldPath, isStateCardItem, missingData, nestedSurfaces, parseUiRequest, replyText, selectedLabels, templateSpec, uiSignature} from './genui.ts';
import {compileGenerated, composeWithJev, parseUiView, rejectReason} from './genuiCompose.ts';
import {CATALOG_VERSION} from './genuiCatalog.ts';
import {projectSession} from './session.ts';
import {retainSessionBlocks} from './sessionStream.ts';

const item = (id, label, value = {type: 'text', value: null}, url = '') => ({id, label, value, detail: '', url, image: '', icon: ''});
const block = (id, kind, fields = {}) => ({id, kind, title: '', text: '', value: {type: 'text', value: null}, image: '', url: '', items: [], config: {}, ...fields});
const coat = () => ({
  version: 2, key: 'coat-compare', mode: 'panel', title: 'Wool coat', summary: 'Lowest $139',
  blocks: [
    block('offers', 'collection', {title: 'Black Friday', config: {layout: 'table'}, items: [item('uniqlo', 'Uniqlo', {type: 'money', value: '13900', currency: 'USD', scale: 2}, 'https://example.com/u'), item('zara', 'Zara', {type: 'money', value: '14900', currency: 'USD', scale: 2})]}),
    block('size', 'choices', {title: 'Size', items: [item('medium', 'M'), item('large', 'L')]}),
  ],
  actions: [{id: 'watch', label: 'Watch price', reply: 'Tell me when it drops under $145', style: 'primary'}],
});
const keepAll = async ({questions}) => ({answers: Object.fromEntries(Object.entries(questions).map(([id, q]) => {
  const keys = Object.keys(q.criteria);
  return [id, {choice: keys.find(k => k.startsWith('use:')) ?? (keys.includes('1') ? '1' : keys[0]), confidence: 0.9}];
}))});

test('state-card images require HTTPS at both block and item levels', () => {
  for (const image of ['https://example.com/photo.png', 'http://example.com/photo.png']) {
    const top = coat(); top.blocks[0].image = image;
    const nested = coat(); nested.blocks[0].items[0].image = image;
    assert.equal(parseUiRequest(top) !== null, image.startsWith('https:'));
    assert.equal(parseUiRequest(nested) !== null, image.startsWith('https:'));
  }
});

test('only valid V2 data becomes a card; old shapes cannot resume actions', () => {
  assert.deepEqual(parseUiRequest(coat()), coat());
  for (const patch of [{version: 1}, {version: undefined}, {key: 'Coat Compare'}, {mode: 'widget'}, {blocks: []}, {actions: 'none'},
    {blocks: [block('offers', 'prices')]}, {blocks: [block('offers', 'collection', {items: [item('bad', 'x', {type: 'text', value: ''}, 'javascript:alert(1)')]})]},
    {blocks: [block('same', 'text'), block('same', 'text')]}, {actions: [{id: 'pay', label: 'Pay', reply: '', style: 'primary'}]}]) {
    assert.equal(parseUiRequest({...coat(), ...patch}), null, JSON.stringify(patch));
  }
});

test('with_state_card is recognized only in the taku_phone namespace', () => {
  assert.ok(isStateCardItem({type: 'dynamicToolCall', tool: 'taku_phone__with_state_card', namespace: 'taku_phone'}));
  assert.ok(isStateCardItem({type: 'dynamicToolCall', tool: 'taku_phone.with_state_card'}));
  assert.ok(!isStateCardItem({type: 'dynamicToolCall', tool: 'other.with_state_card'}));
  assert.ok(!isStateCardItem({type: 'mcpToolCall', tool: 'taku_phone__with_state_card'}));
});

test('value, label, collection count and order updates reuse the layout signature', () => {
  const next = coat();
  next.blocks[0].items.push(item('hm', 'H&M', {type: 'money', value: '12900', currency: 'USD', scale: 2}));
  next.summary = 'Lowest $129'; next.title = ''; next.blocks[1].title = 'Choose size'; next.blocks.reverse();
  assert.equal(uiSignature(next), uiSignature(coat()));
  const sized = structuredClone(next); sized.blocks[0].config.size = 'small_size';
  assert.notEqual(uiSignature(sized), uiSignature(next), 'a changed information budget requests the corresponding layout');
  next.blocks[0].kind = 'collection';
  assert.notEqual(uiSignature(next), uiSignature(coat()));
});

test('stable field and selection IDs retain drafts across reordered source updates', () => {
  const initial = coat();
  initial.blocks.push(block('quantity', 'input', {value: {type: 'number', value: 1}, config: {editor: 'range', min: 1, max: 10}}));
  const state = cardState(initial, {fields: {quantity: {draft: 3}}, collections: {size: {selectedItemIds: ['large']}}});
  const next = structuredClone(initial); next.blocks.reverse(); next.blocks.find(b => b.id === 'size').items.reverse();
  const updated = cardState(next, state.ui);
  assert.equal(updated.ui, state.ui);
  assert.equal(updated.ui.fields.quantity.draft, 3);
  assert.deepEqual(updated.ui.collections.size.selectedItemIds, ['large']);
  assert.equal(templateSpec(next).elements['block-quantity'].props.draft.$bindState, fieldPath('quantity'));
  next.blocks.find(b => b.id === 'size').items = [];
  assert.deepEqual(cardState(next, state.ui).ui.collections.size.selectedItemIds, ['large']);
  next.blocks.find(b => b.id === 'quantity').value.value = 2;
  assert.equal(cardState(next, cardState(initial).ui).ui.fields.quantity.draft, 2, 'untouched inputs follow the source');
  assert.equal(cardState(next, state.ui).ui.fields.quantity.draft, 3, 'source updates preserve the edited draft');
  assert.equal(cardState(next, state.ui).data.blocksById.quantity.value.value, 2, 'the source remains separate from the draft');
});

test('replies resolve current labels from selected item IDs', () => {
  const next = coat(); next.blocks[1].items[1].label = 'Large';
  const state = cardState(next, {collections: {size: {selectedItemIds: ['large', 'removed']}}});
  assert.deepEqual(selectedLabels(state, [choicePath('size')]), ['Large']);
  assert.deepEqual(selectedLabels(state, ['/data/title']), []);
  assert.equal(replyText('Choose it', ['Large']), 'Choose it\nLarge');
  assert.equal(replyText('Choose it', []), 'Choose it');
});

test('the immediate template is a valid single surface for every generic role', () => {
  const all = coat();
  all.blocks = UI_KINDS.slice(0, 8).map(kind => block(kind, kind, {title: kind, text: 'note', items: [item('first', 'a'), item('second', 'b')]}));
  assert.equal(rejectReason(templateSpec(all), all), '');
  all.blocks = UI_KINDS.slice(8).map(kind => block(kind, kind, {title: kind, text: 'note'}));
  const spec = templateSpec(all);
  assert.equal(spec.elements[spec.root].type, 'Card');
  assert.equal(rejectReason(spec, all), '');
  assert.deepEqual(missingData(spec, all), []);
  assert.deepEqual(nestedSurfaces(spec), []);
});

const patch = (path, value) => JSON.stringify({op: 'add', path, value});
for (const value of [{type: 'duration', value: 60000}, {type: 'instant', value: '2026-11-03T10:00:00Z', timeZone: 'UTC'}]) {
  test(`${value.type} metrics retain supporting items through template, generation and composition`, async () => {
    const empty = {...coat(), blocks: [block('time', 'metric', {title: 'Time', value})], actions: []};
    const later = structuredClone(empty);
    later.blocks[0].items = [{...item('first', 'First milestone', {type: 'number', value: 0}, 'https://example.com/first'), detail: 'A substantive milestone detail'},
      {...item('second', 'Second milestone', {type: 'boolean', value: false}), detail: 'Another detail'}];
    assert.equal(parseUiRequest(later), later);
    assert.equal(uiSignature(later), uiSignature(empty));
    const spec = templateSpec(empty);
    assert.equal(spec.elements['block-time'].type, 'TimeDisplay');
    assert.deepEqual(spec.elements['block-time'].props.items, {$state: '/data/blocksById/time/items'});
    assert.equal(rejectReason(spec, later), '', 'the empty template remains valid when items arrive');
    const generated = Object.entries(spec.elements).map(([id, element]) => patch(`/elements/${id}`, element));
    assert.equal(compileGenerated([patch('/root', spec.root), ...generated].join('\n'), later).reason, '');
    assert.equal((await composeWithJev(later, keepAll, new AbortController().signal)).reason, '');
    const dropped = structuredClone(spec); delete dropped.elements['block-time'].props.items;
    assert.ok(missingData(dropped, later).length, 'item loss is still rejected');
  });
}

test('Jev retains bound blocks and actions without caching a state snapshot', async () => {
  const result = await composeWithJev(coat(), keepAll, new AbortController().signal);
  assert.equal(result.reason, '');
  assert.equal(result.spec.state, undefined);
  assert.deepEqual(missingData(result.spec, coat()), []);
});

test('a failing evaluator surfaces an error for the caller to fall back on', async () => {
  await assert.rejects(composeWithJev(coat(), async () => { throw new Error('JEV_DISABLED'); }, new AbortController().signal));
});

// Signals from older polyfills (RN <= 0.83 used `abort-controller`) lack throwIfAborted.
// Hide the method on a real signal so the fallback keeps its abort event and state.
function legacySignalController() {
  const controller = new AbortController();
  Object.defineProperty(controller.signal, 'throwIfAborted', {value: undefined, configurable: true, writable: true});
  assert.equal(typeof controller.signal.throwIfAborted, 'undefined');
  return controller;
}

test('Jev honors cancellation from signals without throwIfAborted', async () => {
  const controller = legacySignalController();
  const result = await composeWithJev(coat(), keepAll, controller.signal);
  assert.equal(result.reason, '');
  controller.abort();
  await assert.rejects(composeWithJev(coat(), keepAll, controller.signal), {name: 'AbortError'});
});

const water = () => ({version: 2, layout: 'generate', key: 'water', mode: 'card', title: 'Water', summary: '', actions: [], blocks: [
  block('glasses', 'input', {title: 'Glasses today', value: {type: 'number', value: 0}, config: {editor: 'range', min: 0, max: 8}}),
  block('goal', 'text', {text: 'Goal 8 glasses'}),
]});

test('generated JSONL keeps data bound and uses only declared state writes', () => {
  const good = [
    patch('/root', 'r'),
    patch('/elements/r', {type: 'Card', props: {}, children: ['c', 'n']}),
    patch('/elements/c', {type: 'Counter', props: {value: {$bindState: fieldPath('glasses')}, label: 'Glasses today'}, children: []}),
    patch('/elements/n', {type: 'Text', props: {text: {$state: '/data/blocksById/goal/text'}}, children: []}),
  ].join('\n');
  assert.equal(compileGenerated(good, water()).reason, '');
  assert.equal(compileGenerated(good.replace(fieldPath('glasses'), '/data/title'), water()).reason, 'llm-invalid-binding');
  const dropped = [patch('/root', 'r'), patch('/elements/r', {type: 'Card', props: {}, children: []})].join('\n');
  assert.equal(compileGenerated(dropped, water()).reason, 'llm-missing-data');
  assert.equal(compileGenerated('No patches.', water()).reason, 'llm-no-patches');
});

test('historical decoration is removed without changing source facts or its input', () => {
  const original = {...water(), blocks: [
    block('glasses', 'input', {value: {type: 'number', value: 0}, config: {editor: 'number', tone: 'dark', supportingColor: '#123C5F', gradient: ['#459EF6', '#F1F8FC']}}),
    block('enabled', 'input', {value: {type: 'boolean', value: false}, config: {editor: 'toggle'}}),
    block('camera', 'media', {value: {type: 'text', value: null}, config: {mediaType: 'camera', imageFilter: 'mono', filterStrength: 0, analysisPrompt: 'Frame the subject'}}),
  ]};
  const before = structuredClone(original), parsed = parseUiRequest(original);
  assert.ok(parsed);
  assert.deepEqual(original, before);
  assert.deepEqual(parsed.blocks[0].config, {editor: 'number'});
  assert.equal(parsed.blocks[0].value, original.blocks[0].value);
  assert.equal(parsed.blocks[1], original.blocks[1]);
  assert.equal(parsed.blocks[2], original.blocks[2]);
  assert.equal(parsed.blocks[0].value.value, 0);
  assert.equal(parsed.blocks[1].value.value, false);
  assert.equal(parsed.blocks[2].value.value, null);
  assert.equal(parseUiRequest(parsed), parsed, 'current requests retain their identity');
  assert.equal(rejectReason(templateSpec(parsed), parsed), '');
  assert.equal(uiSignature(original), uiSignature(parsed));
  for (const config of [{tone: 'blue'}, {tone: null}, {supportingColor: 'x'.repeat(81)}, {supportingColor: 0},
    {gradient: ['x'.repeat(81)]}, {gradient: Array(9).fill('')}, {gradient: [false]}, {gradient: null},
    {tone: 'light', unrelated: true}, {tone: 'light', editor: 'invented'}]) {
    assert.equal(parseUiRequest({...water(), blocks: [block('legacy', 'text', {config})]}), null);
  }
  for (const config of [{tone: 'light'}, {supportingColor: ''}, {gradient: []}, {gradient: Array(8).fill('x'.repeat(80))}]) {
    assert.deepEqual(parseUiRequest({...water(), blocks: [block('legacy', 'text', {config})]}).blocks[0].config, {});
  }
});

test('literal and bound raw props never reach generated or persisted layouts', () => {
  const ui = water(), clean = templateSpec(ui);
  const encode = spec => [patch('/root', spec.root), ...Object.entries(spec.elements).map(([id, element]) => patch(`/elements/${id}`, element))].join('\n');
  const persisted = spec => ({catalogVersion: CATALOG_VERSION, signature: uiSignature(ui), spec,
    metrics: {level: 'llm', fallback: '', ms: 1, jevSteps: 0, inputTokens: null, llmUsage: null}});
  assert.ok(compileGenerated(encode(clean), ui).spec);
  assert.ok(parseUiView(persisted(clean), ui));
  for (const key of ['gradient', 'tone', 'backgroundColor', 'fontSize', 'color', 'borderRadius', 'padding', 'style']) {
    for (const value of ['legacy', {$state: '/data/title'}]) {
      const bad = structuredClone(clean);
      bad.elements.root.props[key] = value;
      assert.ok(rejectReason(bad, ui));
      assert.equal(compileGenerated(encode(bad), ui).spec, null);
      assert.equal(parseUiView(persisted(bad), ui), null);
    }
  }
  for (const config of [
    {...ui.blocks[0].config, gradient: ['#fff', '#000']},
    {$state: '/data/blocksById/glasses/config', backgroundColor: '#fff'},
    {$state: '/data/blocksById/glasses'},
    {$cond: true, $then: {$state: '/data/blocksById/glasses/config'}, $else: {tone: 'dark'}},
  ]) {
    const bad = structuredClone(clean);
    bad.elements['block-glasses'].props.config = config;
    assert.equal(compileGenerated(encode(bad), ui).spec, null);
    assert.equal(parseUiView(persisted(bad), ui), null);
  }
  const malformed = structuredClone(clean);
  malformed.elements['block-goal-text'].props.text = {$state: '/data/blocksById/goal/text', fontSize: 64};
  assert.equal(compileGenerated(encode(malformed), ui).spec, null, 'the exact original expression must be admitted');
  const retired = structuredClone(clean);
  retired.elements['block-goal-text'].type = 'Paragraph';
  assert.equal(compileGenerated(encode(retired), ui).spec, null);
  assert.equal(parseUiView({...persisted(clean), catalogVersion: 'taku-ui-11-v3'}, ui), null);
  assert.equal(parseUiView({...persisted(clean), signature: 'stale'}, ui), null);
});

const stateCard = (id, args, status = 'completed') => ({id, type: 'dynamicToolCall', tool: 'taku_phone__with_state_card', namespace: 'taku_phone', status, success: status !== 'failed', arguments: args});
const thread = turns => ({thread: {id: 'thread', turns}, historyComplete: true});
const turn = (id, items) => ({id, status: 'completed', items: [{id: `${id}-u`, type: 'userMessage', content: [{type: 'text', text: 'compare'}]}, ...items]});

test('a completed V2 card renders with answer text and is not Activity', () => {
  const view = projectSession(thread([turn('t1', [{id: 'm1', type: 'agentMessage', text: 'Here are the prices.'}, stateCard('c1', coat())])]));
  const reply = view.blocks.find(b => b.role === 'assistant');
  assert.deepEqual(reply.parts.map(p => p.card ? 'card' : p.text), ['Here are the prices.', 'card']);
  assert.equal(reply.activity.length, 0);
});

test('later same-key calls supersede old cards and identity retention sees the update', () => {
  const first = projectSession(thread([turn('t1', [stateCard('c1', coat())])]));
  const update = {...coat(), summary: 'Lowest $129'};
  const second = retainSessionBlocks(projectSession(thread([turn('t1', [stateCard('c1', coat())]), turn('t2', [stateCard('c2', update), stateCard('bad', {key: 'x'}, 'failed')])])), first);
  assert.deepEqual(second.blocks.flatMap(b => b.parts.filter(p => p.card)).map(p => [p.id, !!p.superseded]), [['t1/c1', true], ['t2/c2', false]]);
  assert.equal(second.blocks.at(-1).activity.length, 1);
});
