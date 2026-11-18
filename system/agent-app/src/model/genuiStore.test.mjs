import test from 'node:test';
import assert from 'node:assert/strict';
import {applyLayout, applyMatterSnapshot, applyPresentation, canSyncMatter, cardChanges, commandValues, ensureCard, getCard, mergeCardView, rendererStore, updateCard} from './genuiStore.ts';
import {cardState, templateSpec, uiSignature} from './genui.ts';
import {CATALOG_VERSION} from './genuiCatalog.ts';
import {cardView, mainReply, matter, matterSnapshot, primary} from './mainReplyFixtures.mjs';

const block = (id, kind, patch = {}) => ({id, kind, title: '', text: '', value: {type: 'text', value: null}, image: '', url: '', items: [], config: {}, ...patch});
const request = (key, summary = 'Current source') => ({version: 2, key, mode: 'card', title: 'Reading notes', summary,
  blocks: [block('notes', 'input', {value: {type: 'text', value: 'Source note'}}), block('topics', 'choices', {items: [
    {id: 'cinema', label: 'Cinema', value: {type: 'text', value: null}, detail: '', image: '', url: '', icon: ''},
  ]})], actions: []});
const snapshot = (id, revision, ui, extra = {}) => ({schema: 'taku.matter/1', matterId: id, revision, request: ui,
  presentation: {kind: 'matter', request: ui, sourceId: 'native-source'}, meta: {sourceId: 'native-source'}, commands: {}, ...extra});

test('primary data and terminal updates preserve the supplied view, adapter and edited preferences', () => {
  const first = mainReply('draft-thread', 'draft-lifecycle'), id = ensureCard('draft-thread', primary(first));
  const renderer = rendererStore(id), spec = getCard(id).view.spec;
  assert.equal(spec.root, 'native-card');
  renderer.set('/ui/fields/note/draft', 'My note');
  renderer.set('/ui/collections/topics/selectedItemIds', ['cinema']);
  renderer.set('/ui/views/details/expanded', true);
  const next = mainReply('draft-thread', first.key, 2, 'streaming', {request: {...first.card.request, summary: 'More information',
    blocks: first.card.request.blocks.map(block => block.id === 'note' ? {...block, value: {type: 'text', value: 'New source'}} : block)}});
  assert.equal(applyPresentation(id, primary(next)), true);
  const final = mainReply('draft-thread', first.key, 3, 'completed', {request: next.card.request});
  assert.equal(applyPresentation(id, primary(final)), true);
  assert.equal(rendererStore(id), renderer);
  assert.equal(getCard(id).view.spec, spec);
  assert.equal(renderer.get('/ui/fields/note/draft'), 'My note');
  assert.equal(renderer.get('/ui/fields/note/source/value'), 'New source');
  assert.deepEqual(renderer.get('/ui/collections/topics/selectedItemIds'), ['cinema']);
  assert.equal(renderer.get('/ui/views/details/expanded'), true);
  assert.equal(applyPresentation(id, primary(next)), false);
  assert.equal(getCard(id).presentation.reply.lifecycle.phase, 'completed');
  assert.equal(applyLayout(id, first.id, uiSignature(final.card.request), getCard(id).view, true), false);
});

test('native pin, card view and business content merge on their own revisions', () => {
  const ui = request('independent-revisions'), id = ensureCard('revisions-thread', matter(ui, 'source'));
  assert.equal(applyMatterSnapshot(snapshot(id, 3, ui, {
    pin: {canPin: true, panelId: null, source: {kind: 'matter', matterId: id}, listRevision: 4},
    cardView: cardView(id, 2, cardState(ui, {fields: {notes: {draft: 'Native draft'}}}).ui),
  })), true);
  assert.equal(getCard(id).pin.panelId, null);
  assert.equal(getCard(id).pin.canPin, true);
  assert.equal(getCard(id).uiRevision, 2);
  assert.equal(getCard(id).nativeRevision, 3);
  assert.equal(getCard(id).snapshot.ui.fields.notes.draft, 'Native draft');

  // A directory-only change keeps the business revision and must still be visible.
  assert.equal(applyMatterSnapshot(snapshot(id, 3, ui, {
    pin: {canPin: true, panelId: 'panel-1', source: {kind: 'matter', matterId: id}, listRevision: 5},
    cardView: cardView(id, 2, cardState(ui, {fields: {notes: {draft: 'Native draft'}}}).ui),
  })), true);
  assert.equal(getCard(id).pin.panelId, 'panel-1');
  assert.equal(getCard(id).pin.listRevision, 5);
  assert.equal(getCard(id).nativeRevision, 3);

  // A pre-pin read arriving after the commit event cannot undo the pin.
  applyMatterSnapshot(snapshot(id, 3, ui, {
    pin: {canPin: true, panelId: null, source: {kind: 'matter', matterId: id}, listRevision: 4},
  }));
  assert.equal(getCard(id).pin.panelId, 'panel-1');
  assert.equal(getCard(id).pin.listRevision, 5);

  // Unpin is likewise monotonic; a late old pin event cannot resurrect its page.
  applyMatterSnapshot(snapshot(id, 3, ui, {
    pin: {canPin: true, panelId: null, source: {kind: 'matter', matterId: id}, listRevision: 6},
  }));
  applyMatterSnapshot(snapshot(id, 3, ui, {
    pin: {canPin: true, panelId: 'panel-1', source: {kind: 'matter', matterId: id}, listRevision: 5},
  }));
  assert.equal(getCard(id).pin.panelId, null);
  assert.equal(getCard(id).pin.listRevision, 6);

  // A UI-only change at the same business revision still updates the shared input.
  assert.equal(applyMatterSnapshot(snapshot(id, 3, ui, {cardView: cardView(id, 7, cardState(ui, {fields: {notes: {draft: 'Agent draft'}}}).ui)})), true);
  assert.equal(getCard(id).uiRevision, 7);
  assert.equal(getCard(id).snapshot.ui.fields.notes.draft, 'Agent draft');

  // An older card view can never rewind a newer one; the snapshot is still accepted for this card.
  assert.equal(applyMatterSnapshot(snapshot(id, 3, ui, {cardView: cardView(id, 5, cardState(ui, {fields: {notes: {draft: 'Old draft'}}}).ui)})), true);
  assert.equal(getCard(id).snapshot.ui.fields.notes.draft, 'Agent draft');
  assert.equal(getCard(id).uiRevision, 7);
});

test('edits made before hydration win for their own fields while other fields take native state', () => {
  const ui = {...request('hydration-race'), blocks: [block('notes', 'input'), block('quantity', 'input', {value: {type: 'number', value: 2}, config: {editor: 'number'}}),
    block('enabled', 'input', {value: {type: 'boolean', value: true}, config: {editor: 'toggle'}}), block('topics', 'choices', {items: []})]};
  const id = ensureCard('hydration-thread', matter(ui, 'source')), renderer = rendererStore(id);
  renderer.set('/ui/fields/notes/draft', 'Typed before load');
  const canonical = cardState(ui, {fields: {notes: {draft: 'Native draft'}, quantity: {draft: 0}, enabled: {draft: false}},
    collections: {topics: {selectedItemIds: []}}}).ui;
  assert.equal(mergeCardView(id, cardView(id, 1, canonical)), true);
  assert.equal(renderer.get('/ui/fields/notes/draft'), 'Typed before load');
  assert.equal(renderer.get('/ui/fields/quantity/draft'), 0);
  assert.equal(renderer.get('/ui/fields/enabled/draft'), false);
  assert.deepEqual(renderer.get('/ui/collections/topics/selectedItemIds'), []);
  assert.equal(getCard(id).hydrated, true);
  assert.deepEqual(cardChanges(id), [{kind: 'field', id: 'notes', value: 'Typed before load'}]);
});

test('card changes carry the exact false, zero and empty values', () => {
  const ui = {version: 2, key: 'typed-values', mode: 'card', title: 'Values', summary: '', actions: [],
    blocks: [block('quantity', 'input', {value: {type: 'number', value: 3}, config: {editor: 'number'}}),
      block('enabled', 'input', {value: {type: 'boolean', value: true}, config: {editor: 'toggle'}}),
      block('notes', 'input'), block('topics', 'choices', {items: [{id: 'selected', label: 'Selected', value: {type: 'text', value: null}, detail: '', image: '', url: '', icon: ''}]})]};
  const id = ensureCard('values-thread', matter(ui, 'source')), renderer = rendererStore(id);
  mergeCardView(id, cardView(id, 1, cardState(ui, {collections: {topics: {selectedItemIds: ['selected']}}}).ui));
  assert.deepEqual(cardChanges(id), []);
  renderer.set('/ui/fields/quantity/draft', 0);
  renderer.set('/ui/fields/enabled/draft', false);
  renderer.set('/ui/fields/notes/draft', '');
  renderer.set('/ui/collections/topics/selectedItemIds', []);
  assert.deepEqual(cardChanges(id).sort((a, b) => a.id.localeCompare(b.id)), [
    {kind: 'field', id: 'enabled', value: false},
    {kind: 'field', id: 'notes', value: ''},
    {kind: 'field', id: 'quantity', value: 0},
    {kind: 'selection', id: 'topics', itemIds: []},
  ]);
});

for (const [editor, value, config, confirmed] of [
  ['text', {type: 'text', value: null}, {editor: 'text'}, ''],
  ['number', {type: 'number', value: null}, {editor: 'number'}, 0],
  ['range', {type: 'number', value: null}, {editor: 'range', min: 10, max: 20}, 10],
  ['toggle', {type: 'boolean', value: null}, {editor: 'toggle'}, false],
  ['time', {type: 'instant', value: null, timeZone: 'UTC'}, {editor: 'time'}, '2026-11-04T00:00:00Z'],
]) test(`an unknown ${editor} draft cannot be submitted until explicitly confirmed`, () => {
  const ui = {...request(`unset-${editor}`), blocks: [block('entry', 'input', {value, config})]};
  const id = ensureCard('unset-fields-thread', matter(ui, `source-${editor}`)), store = rendererStore(id);
  assert.throws(() => commandValues(id), /DRAFT_VALUE_REQUIRED/);
  store.set('/ui/fields/entry/draft', confirmed);
  assert.deepEqual(commandValues(id), {entry: confirmed});
  assert.equal(store.get('/data/blocksById/entry/value/value'), null, 'confirming a draft never rewrites its unknown source');
  store.set('/ui/fields/entry/draft', null);
  assert.throws(() => commandValues(id), /DRAFT_VALUE_REQUIRED/);
});

test('a rejected final layout retains known content and settles into an explicit error', () => {
  const ui = request('rejected-layout'), sourceId = 'current-layout-source';
  const id = ensureCard('layout-error-thread', matter(ui, sourceId)), store = rendererStore(id);
  store.set('/ui/fields/notes/draft', 'Keep this draft');
  const known = getCard(id).view, signature = uiSignature(ui);
  const rejected = {...known, spec: {root: 'missing', elements: {}}};
  assert.equal(applyLayout(id, sourceId, signature, rejected, true), false);
  assert.equal(getCard(id).view, known);
  assert.equal(getCard(id).layoutResolved, false);
  assert.ok(getCard(id).layoutError);
  assert.equal(store.get('/ui/fields/notes/draft'), 'Keep this draft');
  const next = {...ui, blocks: [...ui.blocks, block('detail', 'text', {text: 'New information'})]};
  applyPresentation(id, matter(next, 'new-layout-source'));
  assert.equal(getCard(id).layoutError, '');
  assert.equal(applyLayout(id, sourceId, signature, rejected, true), false);
  assert.equal(getCard(id).layoutError, '', 'an obsolete failure cannot poison the new request');
  const accepted = {catalogVersion: CATALOG_VERSION, signature: uiSignature(next), spec: templateSpec(next), metrics: known.metrics};
  assert.equal(applyLayout(id, 'new-layout-source', uiSignature(next), accepted, true), true);
  assert.equal(getCard(id).layoutResolved, true);
  assert.equal(getCard(id).layoutError, '');
  assert.equal(store.get('/ui/fields/notes/draft'), 'Keep this draft');
});

test('current catalog and signature fence every layout flight and retained view', () => {
  const ui = request('retained-catalog-fence'), signature = uiSignature(ui);
  const id = ensureCard('retained-catalog-thread', matter(ui, 'source'));
  rendererStore(id).set('/ui/fields/notes/draft', 'Keep edits');
  const canonical = structuredClone(getCard(id).view);
  const obsolete = {...canonical, catalogVersion: 'taku-ui-11-v3', metrics: {...canonical.metrics, level: 'jev'}};
  assert.equal(applyLayout(id, 'source', signature, obsolete, false), false);
  assert.equal(applyLayout(id, 'source', signature, {...canonical, signature: 'stale'}, true), false);
  updateCard(id, card => ({...card, view: obsolete}));
  assert.equal(applyLayout(id, 'source', signature, canonical, true), true);
  assert.equal(getCard(id).view.catalogVersion, CATALOG_VERSION, 'final fallback cannot retain a historical progressive layout');
  updateCard(id, card => ({...card, view: obsolete}));
  assert.equal(applyLayout(id, 'source', signature, obsolete, true), false);
  assert.equal(getCard(id).view.catalogVersion, CATALOG_VERSION, 'a rejected candidate cannot retain a historical view');
  updateCard(id, card => ({...card, view: obsolete}));
  applyPresentation(id, matter({...ui, summary: 'Updated source'}, 'new-source'));
  assert.equal(getCard(id).view.catalogVersion, CATALOG_VERSION, 'source-only revisions re-admit the retained view');
  assert.equal(getCard(id).snapshot.ui.fields.notes.draft, 'Keep edits');
  assert.equal(getCard(id).snapshot.data.summary, 'Updated source');
});

test('historical native snapshots normalize presentation without changing receipts', () => {
  const ui = request('historical-native'), id = ensureCard('historical-native-thread', matter(ui, 'source'));
  rendererStore(id).set('/ui/fields/notes/draft', 'Keep edits');
  const historical = request(ui.key, 'Fresh native source');
  historical.blocks[0].config = {tone: 'dark', supportingColor: '#123', gradient: ['#123', '#456']};
  const incoming = snapshot(id, 7, historical, {commands: {sent: {state: 'queued', requestId: 'sent'}}});
  const before = structuredClone(incoming);
  assert.equal(applyMatterSnapshot(incoming), true);
  assert.deepEqual(getCard(id).request.blocks[0].config, {});
  assert.equal(getCard(id).snapshot.ui.fields.notes.draft, 'Keep edits');
  assert.equal(getCard(id).snapshot.commands.sent.requestId, 'sent');
  assert.equal(getCard(id).nativeRevision, 7);
  assert.deepEqual(incoming, before);
});

test('an unsynced primary presentation never gains a subscription and keeps local drafts', () => {
  for (const phase of ['failed', 'interrupted', 'streaming']) {
    const reply = mainReply(`terminal-${phase}`, phase, 1, phase), id = ensureCard(`terminal-${phase}`, primary(reply));
    rendererStore(id).set('/ui/fields/note/draft', 'Keep my draft');
    assert.equal(canSyncMatter(getCard(id).presentation), false);
    applyMatterSnapshot(matterSnapshot(reply));
    assert.equal(getCard(id).presentation.reply.lifecycle.phase, phase);
    assert.equal(getCard(id).snapshot.ui.fields.note.draft, 'Keep my draft');
  }
});

test('editing back to the acknowledged value survives an in-flight write and its subscription snapshot', () => {
  const ui = request('edit-during-write'), id = ensureCard('write-thread', matter(ui, 'source'));
  const canonical = (revision, value) => cardView(id, revision, cardState(ui, {fields: {notes: {draft: value}}}).ui);
  mergeCardView(id, canonical(1, 'A'));
  const renderer = rendererStore(id);
  renderer.set('/ui/fields/notes/draft', 'B');
  const submitted = getCard(id);
  renderer.set('/ui/fields/notes/draft', 'A');
  mergeCardView(id, canonical(2, 'B'));
  assert.equal(renderer.get('/ui/fields/notes/draft'), 'A');
  mergeCardView(id, canonical(2, 'B'), submitted);
  assert.deepEqual(cardChanges(id), [{kind: 'field', id: 'notes', value: 'A'}]);
  mergeCardView(id, canonical(3, 'A'), getCard(id));
  assert.equal(renderer.get('/ui/fields/notes/draft'), 'A');
  assert.deepEqual(cardChanges(id), []);
});

test('an older write receipt releases only its own edits and keeps a newer native value', () => {
  const ui = request('older-write-receipt'), id = ensureCard('receipt-thread', matter(ui, 'source'));
  const canonical = (revision, value) => cardView(id, revision, cardState(ui, {fields: {notes: {draft: value}}}).ui);
  mergeCardView(id, canonical(1, 'A'));
  const renderer = rendererStore(id);
  renderer.set('/ui/fields/notes/draft', 'B');
  const submitted = getCard(id);
  mergeCardView(id, canonical(3, 'C'));
  mergeCardView(id, canonical(2, 'B'), submitted);
  assert.equal(renderer.get('/ui/fields/notes/draft'), 'C');
  assert.equal(getCard(id).uiRevision, 3);
  assert.deepEqual(cardChanges(id), []);
});
