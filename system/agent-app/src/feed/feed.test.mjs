import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync, readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {ICONS, dispatchedActionIds, feedCatalog, validateFeedSpec} from './feedCatalog.ts';
import {levelAt, splitValue} from './value.ts';

const assetRoot = fileURLToPath(new URL('../../modules/taku-home/android/src/main/assets/feed/', import.meta.url));
const sample = JSON.parse(readFileSync(`${assetRoot}sample-feed.json`, 'utf8'));
const ID = /^[A-Za-z0-9._:-]{1,96}$/;
const SOURCES = new Set(['sample', 'agent', 'recommendation', 'system']);
const INTENTS = {'ask-taku': ['prompt'], 'open-app': ['package'], 'open-url': ['url'], dismiss: [], none: []};

function strings(node, found = []) {
  if (typeof node === 'string') found.push(node);
  else if (Array.isArray(node)) node.forEach(item => strings(item, found));
  else if (node && typeof node === 'object') Object.values(node).forEach(item => strings(item, found));
  return found;
}

test('image source validation admits network images and rejects unrelated URI schemes', () => {
  const spec = {root: 'image', elements: {image: {type: 'Image', props: {src: {$state: '/image'}}, children: []}}};
  for (const image of ['https://example.com/diagram.svg?revision=2#view', 'https://example.com/photo.webp', 'asset:feed/icons/heart.svg']) {
    assert.deepEqual(validateFeedSpec(spec, {image}), [], image);
  }
  for (const image of ['http://example.com/photo.png', 'http://192.168.1.20:8080/photo.png', 'http://printer.local/diagram.svg', 'data:image/png;base64,AAAA', 'file:///tmp/photo.png', 'javascript:alert(1)', 'asset:feed/../photo.png']) {
    assert.ok(validateFeedSpec(spec, {image}).some(issue => issue.element === 'image'), image);
  }
});

test('sample feed lists the design cards in order', () => {
  assert.equal(sample.schema, 'taku.feed-sample/1');
  assert.deepEqual(sample.cards.map(card => card.id), ['weather-now', 'music-now-playing', 'sleep-last-night', 'car-my-car', 'slack-uiux',
    'home-lock', 'todo-today', 'tesla-model-y', 'starbucks-reorder', 'uber-home', 'instagram-digest']);
  const ranks = sample.cards.map(card => card.rank);
  assert.deepEqual([...ranks].sort((a, b) => b - a), ranks, 'ranks descend in feed order');
  assert.deepEqual(sample.cards.filter(card => card.pinned).map(card => card.id), ['weather-now', 'music-now-playing', 'sleep-last-night', 'car-my-car']);
});

for (const card of sample.cards) {
  test(`${card.id} follows taku.feed-card/1`, () => {
    assert.equal(card.schema, 'taku.feed-card/1');
    assert.match(card.id, ID);
    assert.ok(SOURCES.has(card.source.id), 'source.id');
    assert.ok(card.source.app, 'source.app');
    assert.ok(['full', 'half'].includes(card.layout.span));
    assert.ok(['card', 'control'].includes(card.layout.surface));
    assert.equal(typeof card.rank, 'number');
    assert.ok(Number.isInteger(card.createdAt) && new Date(card.createdAt).toISOString().startsWith('2026-10-29'), 'createdAt on 2026-10-29');
    assert.ok(card.expiresAt === null || Number.isInteger(card.expiresAt));
    assert.equal(typeof card.pinned, 'boolean');
    assert.ok(card.summary?.trim(), 'summary');
    const {pin} = card;
    assert.ok(pin.label ? pin.label.length <= 24 : !!pin.value, 'pin.label, or a value instead');
    assert.ok(!pin.detail || pin.detail.length <= 32);
    assert.ok(!pin.value || pin.value.length <= 12);
    if (pin.icon.kind === 'image') assert.ok(['circle', 'rounded'].includes(pin.icon.shape ?? 'circle'));
    else { assert.equal(pin.icon.kind, 'pixel'); assert.ok(pin.icon.glyph); }
    if (pin.trailing) assert.ok(card.actions[pin.trailing.actionId], 'pin.trailing.actionId names an action');
    for (const [id, action] of Object.entries(card.actions)) {
      const fields = INTENTS[action.intent.type];
      assert.ok(fields, `${id}: intent type ${action.intent.type}`);
      for (const field of fields) assert.ok(action.intent[field], `${id}: intent.${field}`);
      if (action.intent.type === 'open-url') assert.match(action.intent.url, /^https:\/\//);
    }
    assert.equal(card.ui.catalog, 'taku-feed-1');
  });

  test(`${card.id} body validates against taku-feed-1`, () => {
    assert.equal(feedCatalog.validate(card.ui.spec).success, true);
    assert.deepEqual(validateFeedSpec(card.ui.spec, card.ui.state, new Set(Object.keys(card.actions))), []);
    const dispatched = Object.values(card.ui.spec.elements).flatMap(element => Object.values(element.on ?? {}).flatMap(binding => dispatchedActionIds(binding.params)));
    for (const id of dispatched) assert.ok(card.actions[id], `dispatch ${id} exists in actions`);
    const buttons = Object.values(card.ui.spec.elements).filter(element => ['Button', 'IconButton', 'SlideToAct'].includes(element.type));
    for (const button of buttons) assert.ok(button.on, `${button.type} ${button.props.label} dispatches an action`);
  });

  test(`${card.id} asset references exist`, () => {
    for (const ref of strings(card).filter(value => value.startsWith('asset:'))) {
      assert.match(ref, /^asset:feed\/(?!.*\.\.)/, ref);
      assert.ok(existsSync(assetRoot + ref.slice('asset:feed/'.length)), `${ref} is bundled`);
    }
  });
}

test('named feed icons are bundled', () => {
  for (const path of Object.values(ICONS)) assert.ok(existsSync(assetRoot + path), path);
});

test('the validator rejects bad props, unknown actions and bad events', () => {
  const spec = {root: 'a', elements: {a: {type: 'Column', props: {}, children: ['b', 'c']},
    b: {type: 'Text', props: {text: 5}, children: []},
    c: {type: 'Button', props: {label: 'Go'}, children: [], on: {press: {action: 'dispatch', params: {actionId: 'missing'}}, swipe: {action: 'dispatch', params: {actionId: 'x'}}}}}};
  const issues = validateFeedSpec(spec, {}, new Set(['x']));
  assert.ok(issues.some(issue => issue.element === 'b'), 'Text.text must be a string');
  assert.ok(issues.some(issue => issue.message.includes('unknown action missing')));
  assert.ok(issues.some(issue => issue.message.includes('no swipe event')));
  assert.deepEqual(validateFeedSpec({root: 'a', elements: {a: {type: 'Nope', props: {}, children: []}}}).length > 0, true);
});


test('values split digits into display type and letters into units', () => {
  const show = text => splitValue(text).map(part => `${part.kind[0]}:${part.text}`).join(' ');
  assert.equal(show('23°'), 'n:23°');
  assert.equal(show('8h21m'), 'n:8 u:h n:21 u:m');
  assert.equal(show('31%'), 'n:31 u:%');
  assert.equal(show('$5.5'), 'n:$5.5');
  assert.equal(show('4 min'), 'n:4 u: min');
  assert.equal(show('-3.5°C'), 'n:-3.5° u:C');
  assert.equal(show(''), '');
});

test('cyclic or disconnected card bodies cannot be accepted', () => {
  const cycle = {root: 'root', elements: {root: {type: 'Column', props: {}, children: ['root']}}};
  assert.ok(validateFeedSpec(cycle).some(issue => issue.message.includes('cycle')));
  const disconnected = {root: 'root', elements: {root: {type: 'Column', props: {}, children: []}, orphan: {type: 'Text', props: {text: 'Hidden'}, children: []}}};
  assert.ok(validateFeedSpec(disconnected).some(issue => issue.element === 'orphan'));
});

test('invalid prop and action branches are rejected whichever branch is initially selected', () => {
  for (const enabled of [true, false]) {
    const state = {ui: {enabled}};
    const text = {root: 'text', elements: {text: {type: 'Text', props: {text: {$cond: {$state: '/ui/enabled'}, $then: 42, $else: 'Ready'}}, children: []}}};
    assert.ok(validateFeedSpec(text, state).some(issue => issue.element === 'text'));
    const button = {root: 'button', elements: {button: {type: 'Button', props: {label: 'Go'}, children: [], on: {press: {action: 'dispatch', params: {
      actionId: {$cond: {$state: '/ui/enabled'}, $then: 'missing', $else: 'dismiss'},
    }}}}}};
    assert.ok(validateFeedSpec(button, state, new Set(['dismiss'])).some(issue => issue.message.includes('unknown action missing')));
  }
});

test('repeat children use actual item state and reject invalid item values', () => {
  const spec = {root: 'list', elements: {
    list: {type: 'Column', props: {}, repeat: {statePath: '/items'}, children: ['text']},
    text: {type: 'Text', props: {text: {$item: 'label'}}, children: []},
  }};
  assert.deepEqual(validateFeedSpec(spec, {items: [{label: 'A'}, {label: 'B'}]}), []);
  assert.ok(validateFeedSpec(spec, {items: [{label: 'A'}, {label: 7}]}).some(issue => issue.element === 'text'));
  assert.ok(validateFeedSpec(spec, {items: {label: 'A'}}).some(issue => issue.message.includes('array')));
});

test('repeat action parameters use action paths while conditional item expressions use item values', () => {
  const state = {items: [{actionId: 'review'}]};
  const params = {actionId: {$item: 'actionId'}};
  const spec = {root: 'list', elements: {
    list: {type: 'Column', props: {}, repeat: {statePath: '/items'}, children: ['button']},
    button: {type: 'Button', props: {label: 'Review'}, children: [], on: {press: {action: 'dispatch', params}}},
  }};
  assert.ok(validateFeedSpec(spec, state, new Set(['review'])).some(issue => issue.message.includes('unknown action /items/0/actionId')));
  params.actionId = {$cond: true, $then: {$item: 'actionId'}, $else: 'review'};
  assert.deepEqual(validateFeedSpec(spec, state, new Set(['review'])), []);
});

test('empty and nested empty repeats validate templates without inventing an item', () => {
  const spec = {root: 'list', elements: {
    list: {type: 'Column', props: {}, repeat: {statePath: '/items'}, children: ['nested']},
    nested: {type: 'Column', props: {}, repeat: {statePath: {$item: 'children'}}, children: ['text']},
    text: {type: 'Text', props: {text: {$item: 'label'}}, children: []},
  }};
  assert.deepEqual(validateFeedSpec(spec, {items: []}), []);
  assert.deepEqual(validateFeedSpec(spec, {items: [{children: []}]}), []);
  spec.elements.text.props.text = {$else: 7, $cond: true, $then: {$item: 'label'}};
  assert.ok(validateFeedSpec(spec, {items: []}).some(issue => issue.element === 'text'));
});

test('shared empty templates validate once per scope without expanding an acyclic graph', () => {
  const elements = {list: {type: 'Column', props: {}, repeat: {statePath: '/items'}, children: ['left-0', 'right-0']}};
  for (let level = 0; level < 30; level++) {
    const children = level === 29 ? [] : [`left-${level + 1}`, `right-${level + 1}`];
    for (const side of ['left', 'right']) elements[`${side}-${level}`] = {type: 'Column', props: {}, children};
  }
  assert.deepEqual(validateFeedSpec({root: 'list', elements}, {items: []}), []);
});

test('unknown literal action branches fail even when sibling parameters need an empty repeat item', () => {
  for (const actionId of ['missing', {$cond: true, $then: 'review', $else: 'missing'}]) {
    const spec = {root: 'list', elements: {
      list: {type: 'Column', props: {}, repeat: {statePath: '/items'}, children: ['button']},
      button: {type: 'Button', props: {label: 'Review'}, children: [], on: {press: {action: 'dispatch', params: {actionId, select: [{$item: ''}]}}}},
    }};
    assert.ok(validateFeedSpec(spec, {items: []}, new Set(['review'])).some(issue => issue.message.includes('unknown action missing')));
  }
});

test('LevelTrack maps a touch to a whole value, top = max', () => {
  assert.equal(levelAt(0, 100, 10, 25), 25);
  assert.equal(levelAt(100, 100, 10, 25), 10);
  assert.equal(levelAt(50, 100, 10, 25), 18);
  assert.equal(levelAt(-20, 100, 10, 25), 25);
  assert.equal(levelAt(10, 0, 10, 25), 10);
});
