import test from 'node:test';
import assert from 'node:assert/strict';
import {isTextReplySpec} from './chatReplySurface.ts';

const prose = () => ({root: 'card', elements: {
  card: {type: 'Card', props: {}, children: ['body']},
  body: {type: 'Column', props: {gap: 4}, children: ['text']},
  text: {type: 'Text', props: {text: {$state: '/data/blocksById/reply/text'}}},
}});

test('streaming and restored prose share the bubble without rewriting source bindings', () => {
  const spec = prose(), before = structuredClone(spec);
  assert.equal(isTextReplySpec(spec), true);
  assert.deepEqual(spec, before);
});

test('rich content and interactive replies retain their card surfaces', () => {
  for (const type of ['Button', 'Image', 'ChoiceGroup', 'Collection', 'Metric', 'Field', 'Card']) {
    const spec = prose();
    spec.elements.body.children.push('rich');
    spec.elements.rich = {type, props: {}};
    assert.equal(isTextReplySpec(spec), false, type);
  }
  const actionable = prose();
  actionable.elements.text.on = {press: {action: 'reply'}};
  assert.equal(isTextReplySpec(actionable), false);
});

test('missing nodes, cycles and an empty stream are not mistaken for text replies', () => {
  const missing = prose();
  delete missing.elements.text;
  assert.equal(isTextReplySpec(missing), false);
  const cycle = prose();
  cycle.elements.body.children.push('body');
  assert.equal(isTextReplySpec(cycle), false);
  assert.equal(isTextReplySpec({root: 'r', elements: {r: {type: 'Card', props: {}}}}), false);
});
