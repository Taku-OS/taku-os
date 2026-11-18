import test from 'node:test';
import assert from 'node:assert/strict';
import {validateSpec} from '@json-render/core';
import {assemblySpec, fieldWaiting, parseCardAssembly, pendingAssemblyRegions, pendingItemRegions, showFieldSkeleton} from './genuiAssembly.ts';
import {templateSpec} from './genui.ts';

const assembly = fields => parseCardAssembly({version: 1, fields});
const item = (id, patch = {}) => ({id, label: id, detail: '', value: {type: 'text', value: null}, image: '', url: '', icon: '', ...patch});
const block = (id, patch = {}) => ({id, kind: 'collection', title: '', text: '', value: {type: 'text', value: null}, image: '', url: '', items: [], config: {}, ...patch});
const request = blocks => ({version: 2, key: 'progressive', mode: 'card', title: '', summary: '', blocks, actions: []});

test('readiness distinguishes unknown, explicit absence and failure from actual waits', () => {
  for (const value of ['', null, {type: 'text', value: null}, []]) {
    assert.equal(showFieldSkeleton(undefined, 'summary', value), false);
    assert.equal(showFieldSkeleton(assembly({}), 'summary', value), false);
    for (const state of ['absent', 'failed', 'ready']) assert.equal(showFieldSkeleton(assembly({summary: state}), 'summary', value), false);
    for (const state of ['pending', 'streaming']) assert.equal(showFieldSkeleton(assembly({summary: state}), 'summary', value), true);
  }
  for (const value of ['Known', 0, false, {type: 'number', value: 0}]) {
    assert.equal(showFieldSkeleton(assembly({summary: 'pending'}), 'summary', value), false);
  }
});

test('terminal readiness stops every wait without changing known or absent states', () => {
  const raw = {version: 1, fields: {title: 'ready', summary: 'pending', body: 'streaming', actions: 'absent'}};
  const stopped = parseCardAssembly(raw, true);
  assert.deepEqual(stopped.fields, {title: 'ready', summary: 'failed', body: 'failed', actions: 'absent'});
  assert.equal(fieldWaiting(stopped, 'body'), false);
  assert.equal(raw.fields.body, 'streaming', 'transport snapshots remain immutable');
  assert.equal(parseCardAssembly({version: 1, fields: {'/data/title': 'pending'}}), undefined);
  assert.equal(parseCardAssembly({version: 1, fields: {'blocks/b/items/i/detail': 'loading'}}), undefined);
  assert.equal(parseCardAssembly({version: 2, fields: {}}), undefined);
});


test('render projection keeps every reference and action while supplying semantic field regions', () => {
  const ui = {...request([block('results', {items: [item('first')]})]), actions: [{id: 'go', label: 'Go', reply: 'Go', style: 'primary'}]};
  const original = templateSpec(ui), projected = assemblySpec(original);
  assert.equal(validateSpec(projected).valid, true);
  assert.equal(original.root, 'root');
  assert.equal(projected.elements['assembly-region:block-results'].props.paths.items, 'blocks/results/items');
  assert.deepEqual(projected.elements['action-go'].on, original.elements['action-go'].on);
  assert.deepEqual(projected.elements['block-results'].props, original.elements['block-results'].props);
  assert.equal(projected.elements['assembly-region:root'].props.paths.title, 'title');
  assert.equal(projected.state, undefined);
});


test('loading regions follow displayed widget content, without phantom block scalars', () => {
  const ui = request([block('results'), block('choices', {kind: 'choices'}), block('entry', {kind: 'input'}),
    block('metric', {kind: 'metric'}), block('time', {kind: 'metric', value: {type: 'duration', value: null}})]);
  const spec = assemblySpec(templateSpec(ui));
  for (const id of ['results', 'choices', 'entry']) {
    const region = spec.elements[`assembly-region:block-${id}`].props;
    assert.equal(region.paths.value, undefined);
    assert.equal(pendingAssemblyRegions(region.widget, region.paths, {value: {type: 'text', value: null}},
      assembly({[`blocks/${id}/value`]: 'pending'})).length, 0);
  }
  for (const id of ['metric', 'time']) {
    const region = spec.elements[`assembly-region:block-${id}`].props;
    assert.equal(region.paths.value, `blocks/${id}/value`);
    assert.deepEqual(pendingAssemblyRegions(region.widget, region.paths, {value: {type: 'number', value: null}},
      assembly({[`blocks/${id}/value`]: 'pending'})), ['value']);
  }
  const timePaths = {title: 'blocks/time/title', text: 'blocks/time/text', value: 'blocks/time/value'};
  assert.deepEqual(pendingAssemblyRegions('TimeDisplay', timePaths, {title: 'Known', text: '', config: {emphasis: 'inline'}},
    assembly({'blocks/time/text': 'pending'})), []);
  assert.deepEqual(pendingAssemblyRegions('TimeDisplay', timePaths, {title: 'Known', text: '', config: {}},
    assembly({'blocks/time/text': 'pending'})), ['text']);
});

test('alternative media sources reserve one displayed image region and capture controls reserve none', () => {
  const paths = {image: 'blocks/media/image', url: 'blocks/media/url'};
  const waiting = assembly({'blocks/media/image': 'pending', 'blocks/media/url': 'pending'});
  assert.deepEqual(pendingAssemblyRegions('MediaSurface', paths, {image: '', url: '', config: {}}, waiting), ['image']);
  assert.deepEqual(pendingAssemblyRegions('MediaSurface', paths, {image: 'https://example.com/image', url: '', config: {}}, waiting), []);
  assert.deepEqual(pendingAssemblyRegions('MediaSurface', paths, {image: '', url: 'https://example.com/image', config: {}}, waiting), []);
  assert.deepEqual(pendingAssemblyRegions('MediaSurface', paths, {image: '', url: '', config: {captureSource: 'microphone'}}, waiting), []);
  assert.deepEqual(pendingAssemblyRegions('MediaSurface', paths, {image: '', url: '', config: {mediaType: 'camera'}}, waiting), ['image']);
  const settled = assembly({'blocks/media/image': 'absent', 'blocks/media/url': 'absent'});
  assert.deepEqual(pendingAssemblyRegions('MediaSurface', paths, {image: '', url: '', config: {}}, settled), []);
});

test('collection layout owns its waits while the request stays free of placeholder entities', () => {
  const source = request([block('results')]);
  const waiting = assembly({title: 'pending', summary: 'pending', body: 'streaming', 'blocks/results/items': 'pending'});
  const projected = assemblySpec(templateSpec(source));
  const root = projected.elements['assembly-region:root'].props;
  const collection = projected.elements['assembly-region:block-results'].props;
  assert.deepEqual(pendingAssemblyRegions(root.widget, root.paths, {title: '', subtitle: ''}, waiting), []);
  assert.deepEqual(pendingAssemblyRegions(collection.widget, collection.paths, {items: []}, waiting), []);
  assert.equal(root.paths.subtitle, 'summary');
  assert.equal(showFieldSkeleton(waiting, collection.paths.items, source.blocks[0].items), true);
  assert.deepEqual(source.blocks[0].items, [], 'loading geometry never invents item identity or business data');
  const known = item('first', {label: 'Known title'});
  const fields = assembly({'blocks/results/items/first/detail': 'pending', 'blocks/results/items/first/value': 'absent', 'blocks/results/items/first/image': 'absent'});
  assert.deepEqual(pendingItemRegions(known, 'blocks/results/items', fields), ['detail']);
  assert.deepEqual(pendingItemRegions({...known, detail: 'Arrived reason'}, 'blocks/results/items', fields), []);
  assert.deepEqual(pendingItemRegions(known, 'blocks/results/items', parseCardAssembly(fields, true)), []);
});

test('canonical text leaves retain progressive source regions through completion', () => {
  const ui = request([block('explanation', {kind: 'text'})]);
  const spec = assemblySpec(templateSpec(ui));
  const title = spec.elements['assembly-region:block-explanation-title'].props;
  const text = spec.elements['assembly-region:block-explanation-text'].props;
  const pending = assembly({'blocks/explanation/title': 'pending', 'blocks/explanation/text': 'streaming'});
  assert.deepEqual(pendingAssemblyRegions(title.widget, title.paths, {text: ''}, pending), ['text']);
  assert.deepEqual(pendingAssemblyRegions(text.widget, text.paths, {text: ''}, pending), ['text']);
  assert.deepEqual(pendingAssemblyRegions(text.widget, text.paths, {text: 'Arrived explanation'}, pending), []);
  assert.deepEqual(pendingAssemblyRegions(text.widget, text.paths, {text: ''}, parseCardAssembly(pending, true)), []);
});
