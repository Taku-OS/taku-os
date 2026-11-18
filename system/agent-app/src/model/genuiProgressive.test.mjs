import test from 'node:test';
import assert from 'node:assert/strict';
import {applyLayout, applyPresentation, ensureCard, getCard, rendererStore} from './genuiStore.ts';
import {completeProgressSpec, composeWithJev, rejectReason, semanticSpec} from './genuiCompose.ts';
import {templateSpec, uiSignature} from './genui.ts';
import {CATALOG_VERSION} from './genuiCatalog.ts';
import {mainReply, matter, primary} from './mainReplyFixtures.mjs';

const block = (id, kind = 'text', patch = {}) => ({id, kind, title: '', text: '', value: {type: 'text', value: null}, image: '', url: '', items: [], config: {}, ...patch});
const request = key => ({version: 2, key, mode: 'card', title: 'Known title', summary: '', blocks: [block('note', 'input', {value: {type: 'text', value: 'Source'}})], actions: []});
const view = (ui, spec = templateSpec(ui), level = 'jev') => ({catalogVersion: CATALOG_VERSION, signature: uiSignature(ui), spec,
  metrics: {level, fallback: '', ms: 1, jevSteps: 1, inputTokens: null, llmUsage: null}});
const transportKeys = spec => {
  const keys = new Map(Object.keys(spec.elements).map((key, index) => [key, `node_${index}`]));
  return {root: keys.get(spec.root), elements: Object.fromEntries(Object.entries(spec.elements).map(([key, element]) =>
    [keys.get(key), {...element, children: element.children?.map(child => keys.get(child)) ?? []}]))};
};

test('partial layout acceptance remains strict about structure, references and action authority', () => {
  const ui = request('partial-acceptance'), full = templateSpec(ui);
  const partial = {root: 'root', elements: {root: {...full.elements.root, children: []}}};
  assert.equal(rejectReason(partial, ui, true), '');
  assert.equal(rejectReason(partial, ui), 'missing-data');
  const progress = completeProgressSpec(transportKeys(partial), ui);
  assert.ok(progress);
  assert.equal(rejectReason(progress, ui), '');
  assert.ok(progress.elements['block-note'], 'missing partial data never hides a known editable slot');
  const dangling = structuredClone(partial); dangling.elements.root.children = ['missing'];
  assert.equal(rejectReason(dangling, ui, true), 'invalid-structure');
  assert.equal(completeProgressSpec(dangling, ui), null);
  const unknown = structuredClone(full); unknown.elements['block-note'].on = {press: {action: 'invented-capability'}};
  assert.equal(rejectReason(unknown, ui, true), 'unknown-action');
  const forged = structuredClone(full); forged.elements['block-note'].on = {press: {action: 'reply', params: {actionId: 'invented', text: 'Execute'}}};
  assert.equal(rejectReason(forged, ui, true), 'invalid-binding');
});

test('transport keys normalize to distinct stable semantic identities, including multiple text containers', () => {
  const ui = {...request('semantic-identities'), blocks: [block('first', 'text', {text: 'One'}), block('second', 'text', {text: 'Two'})]};
  const original = templateSpec(ui), normalized = semanticSpec(transportKeys(original), ui);
  assert.deepEqual(normalized, original);
  assert.equal(rejectReason(normalized, ui), '');
  const next = structuredClone(ui); next.blocks[0].text = 'Updated';
  assert.deepEqual(Object.keys(semanticSpec(transportKeys(templateSpec(next)), next).elements), Object.keys(original.elements));
});

test('Matter cards accept progressive layouts and stale flights cannot erase the current layout or drafts', () => {
  const ui = request('layout-race'), sourceId = 'turn/matter/layout-race';
  const id = ensureCard('progressive-layout-thread', matter(ui, sourceId));
  const store = rendererStore(id);
  store.set('/ui/fields/note/draft', 'My draft');
  const first = view(ui);
  assert.equal(applyLayout(id, sourceId, uiSignature(ui), first, false), true);
  assert.equal(getCard(id).view, first);
  assert.equal(getCard(id).layoutResolved, false);
  const next = {...ui, blocks: [...ui.blocks, block('explanation', 'text', {text: 'Known explanation'})]};
  applyPresentation(id, matter(next, `${sourceId}/next`));
  const second = view(next);
  assert.equal(applyLayout(id, `${sourceId}/next`, uiSignature(next), second, false), true);
  assert.equal(applyLayout(id, sourceId, uiSignature(ui), first, true), false);
  assert.equal(getCard(id).view, second);
  assert.equal(getCard(id).snapshot.ui.fields.note.draft, 'My draft');
  assert.equal(applyLayout(id, 'another-source', uiSignature(next), second, true), false);
  assert.equal(applyLayout(id, `${sourceId}/next`, uiSignature(next), view(next, templateSpec(next), 'template'), true), true);
  assert.equal(getCard(id).view, second, 'a failed final composition retains accepted progress');
  assert.equal(getCard(id).layoutResolved, true);
  assert.equal(rendererStore(id), store);
});

test('primary readiness is read-only and terminal snapshots settle the same supplied view', () => {
  const first = mainReply('readiness-thread', 'finite-readiness'), id = ensureCard('readiness-thread', primary(first));
  const store = rendererStore(id), spec = getCard(id).view.spec;
  assert.throws(() => store.set('/assembly/fields/body', 'ready'), /READ_ONLY_CARD_STATE/);
  const stopped = mainReply('readiness-thread', first.key, 2, 'interrupted');
  applyPresentation(id, primary(stopped));
  assert.equal(getCard(id).snapshot.assembly, stopped.card.assembly);
  assert.equal(getCard(id).view.spec, spec);
  assert.equal(applyPresentation(id, primary(first)), false);
  assert.equal(getCard(id).snapshot.assembly.fields.body, 'failed');
});

test('Jev publishes complete accepted snapshots before returning a final layout', async () => {
  const ui = {...request('jev-progress'), blocks: [block('explanation', 'text', {text: 'Known content'})]};
  const events = [];
  const evaluate = async ({questions}) => ({answers: Object.fromEntries(Object.entries(questions).map(([id, question]) => {
    const keys = Object.keys(question.criteria);
    return [id, {choice: keys.find(key => key.startsWith('use:')) ?? (keys.includes('1') ? '1' : keys[0]), confidence: 0.9}];
  }))});
  const result = await composeWithJev(ui, evaluate, new AbortController().signal, (spec, steps) => {
    assert.equal(rejectReason(spec, ui), '');
    assert.ok(spec.elements['block-explanation']);
    assert.equal(spec.state, undefined);
    events.push({spec, steps});
  });
  assert.ok(events.length > 0);
  assert.ok(events.every((event, index) => event.steps > (events[index - 1]?.steps ?? 0)));
  assert.equal(result.reason, '');
  assert.equal(result.spec.root, 'root');
});

test('primary collection fields arrive through the supplied layout and preserve an edited sibling draft', () => {
  const ui = {...request('collection-fields'), title: '', blocks: [block('results', 'collection'), ...request('collection-fields').blocks]};
  const initial = mainReply('collection-fields-thread', ui.key, 1, 'streaming', {request: ui});
  const id = ensureCard('collection-fields-thread', primary(initial));
  const store = rendererStore(id), spec = getCard(id).view.spec;
  store.set('/ui/fields/note/draft', 'Keep my edits');
  const first = {id: 'first', label: 'Known recommendation', detail: '', value: {type: 'text', value: null}, image: '', url: '', icon: ''};
  const labeled = {...ui, title: 'Recommendations', blocks: [{...ui.blocks[0], items: [first]}, ui.blocks[1]]};
  assert.equal(applyPresentation(id, primary(mainReply('collection-fields-thread', ui.key, 2, 'streaming', {request: labeled}))), true);
  assert.equal(getCard(id).view.spec, spec);
  const complete = {...labeled, summary: 'From the available source', blocks: [{...labeled.blocks[0], items: [{...first, detail: 'Reason arrived'}]}, {...ui.blocks[1], value: {type: 'text', value: 'New source'}}]};
  assert.equal(applyPresentation(id, primary(mainReply('collection-fields-thread', ui.key, 3, 'completed', {request: complete}))), true);
  assert.equal(getCard(id).view.spec, spec, 'field readiness never replaces stable semantic containers');
  assert.equal(rendererStore(id), store);
  assert.equal(store.get('/data/blocksById/results/items')[0].detail, 'Reason arrived');
  assert.equal(store.get('/data/summary'), 'From the available source');
  assert.equal(store.get('/ui/fields/note/draft'), 'Keep my edits');
  assert.deepEqual(Object.keys(getCard(id).snapshot.data.blocksById), ['results', 'note']);
});
