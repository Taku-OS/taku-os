import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, existsSync} from 'node:fs';
import {createRequire} from 'node:module';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import ts from 'typescript';
import * as core from '@json-render/core';
import {hasUnavailableActions, hasUnavailableBinding, isActionUnavailable} from './actionAvailability.ts';

const availability = {
  legacy: {available: false, code: 'UNSUPPORTED_ACTION'},
  open: {available: true},
};
const dispatch = actionId => ({action: 'dispatch', params: {actionId}});

test('mixed action availability does not disable supported or unknown actions', () => {
  assert.equal(hasUnavailableActions(availability), true);
  assert.equal(hasUnavailableActions({open: {available: true}}), false);
  assert.equal(hasUnavailableActions({}), false);
  for (const id of ['open', 'missing', undefined, null, 0]) {
    assert.equal(isActionUnavailable(id, availability), false);
    assert.equal(hasUnavailableBinding({press: dispatch(id)}, availability, {stateModel: {}}), false);
  }
  assert.equal(isActionUnavailable('legacy', availability), true);
  assert.equal(hasUnavailableBinding({press: dispatch('legacy')}, availability, {stateModel: {}}), true);
});

test('only dispatch bindings are blocked, including arrays and more than one event', () => {
  const ctx = {stateModel: {}};
  for (const on of [null, undefined, false, {}, {press: null}, {press: {action: 'setState', params: {actionId: 'legacy'}}}]) {
    assert.equal(hasUnavailableBinding(on, availability, ctx), false);
  }
  assert.equal(hasUnavailableBinding({press: [dispatch('open'), dispatch('legacy')]}, availability, ctx), true);
  assert.equal(hasUnavailableBinding({press: dispatch('open'), change: dispatch('legacy')}, availability, ctx), true);
});

test('conditional and state action IDs follow the current branch rather than all possible IDs', () => {
  const actionId = {$cond: {$state: '/ui/useLegacy'}, $then: 'legacy', $else: {$state: '/ui/action'}};
  for (const [useLegacy, action, blocked] of [[false, 'open', false], [true, 'open', true], [false, 'legacy', true]]) {
    const ctx = {stateModel: {ui: {useLegacy, action}}};
    assert.equal(core.resolveActionParam(actionId, ctx), blocked ? 'legacy' : 'open');
    assert.equal(hasUnavailableBinding({press: dispatch(actionId)}, availability, ctx), blocked);
  }
});

test('repeat IDs use the renderer action-path semantics, while conditional item values select one branch', () => {
  const stateModel = {items: [{actionId: 'open', blocked: false}, {actionId: 'legacy', blocked: true}]};
  const paths = {'/items/0/actionId': {available: true}, '/items/1/actionId': {available: false}};
  for (const index of [0, 1]) {
    const ctx = {stateModel, repeatItem: stateModel.items[index], repeatIndex: index, repeatBasePath: `/items/${index}`};
    assert.equal(core.resolveActionParam({$item: 'actionId'}, ctx), `/items/${index}/actionId`);
    assert.equal(hasUnavailableBinding({press: dispatch({$item: 'actionId'})}, paths, ctx), index === 1);
    const conditional = {$cond: {$item: 'blocked'}, $then: {$item: 'actionId'}, $else: 'open'};
    assert.equal(hasUnavailableBinding({press: dispatch(conditional)}, availability, ctx), index === 1);
  }
});

// Render the real TSX registry and visual primitives with React's server renderer.
// Only native views/animation and json-render's adapter/hooks are doubles. The adapter
// deliberately does NOT pass `element` to catalog callbacks: that is the real library
// boundary which previously made an incorrectly placed availability wrapper fail.
const require = createRequire(import.meta.url);
const feedDirectory = dirname(fileURLToPath(import.meta.url));
const runtime = {nodes: [], elements: [], responders: [], state: {}, repeat: undefined, writes: [], emits: []};
const host = name => function NativeHost(props) {
  runtime.nodes.push({name, props});
  return React.createElement('div', {'data-native': name}, props.children);
};
const native = Object.fromEntries(['View', 'Text', 'TextInput', 'Switch', 'Pressable', 'ScrollView', 'Image', 'ActivityIndicator'].map(name => [name, host(name)]));
class AnimatedValue {
  constructor(value) { this.value = value; }
  setValue(value) { this.value = value; }
  interpolate() { return 0; }
}
Object.assign(native, {
  Platform: {OS: 'android'},
  StyleSheet: {create: value => value, absoluteFill: {}, hairlineWidth: 1},
  Animated: {Value: AnimatedValue, View: host('Animated.View'), spring: () => ({start() {}}), timing: () => ({start() {}})},
  PanResponder: {create: callbacks => {runtime.responders.push(callbacks); return {panHandlers: callbacks};}},
});
const react = {...React, createElement(type, props, ...children) {
  runtime.elements.push({type, props});
  return React.createElement(type, props, ...children);
}};
const adapter = {
  defineRegistry(_catalog, {components}) {
    return {registry: Object.fromEntries(Object.entries(components).map(([name, Component]) => [name,
      function CatalogAdapter({element, emit, children, bindings}) {
        const props = core.resolveElementProps(element.props, {stateModel: runtime.state,
          repeatItem: runtime.repeat?.item, repeatIndex: runtime.repeat?.index, repeatBasePath: runtime.repeat?.basePath});
        return React.createElement(Component, {props, emit, bindings}, children);
      }]))};
  },
  useStateStore: () => ({state: runtime.state}),
  useRepeatScope: () => runtime.repeat,
  useBoundProp: (fallback, binding) => [binding ? core.getByPath(runtime.state, binding) ?? fallback : fallback,
    value => runtime.writes.push({binding, value})],
};
const svg = {__esModule: true, default: host('Svg'), ...Object.fromEntries(['Defs', 'LinearGradient', 'Path', 'Rect', 'Stop', 'SvgAst'].map(name => [name, host(name)]))};
const cache = new Map();
function loadSource(filename) {
  if (cache.has(filename)) return cache.get(filename).exports;
  if (filename.endsWith('.json')) return JSON.parse(readFileSync(filename, 'utf8'));
  const module = {exports: {}};
  cache.set(filename, module);
  const localRequire = specifier => {
    if (specifier === 'react') return react;
    if (specifier === 'react-native') return native;
    if (specifier === '@json-render/core') return core;
    if (specifier === '@json-render/react-native') return adapter;
    if (specifier === 'react-native-svg') return svg;
    if (specifier === '@react-native-community/slider') return host('Slider');
    if (specifier === 'expo-haptics') return {AndroidHaptics: {Confirm: 'confirm'}, NotificationFeedbackType: {Success: 'success'},
      performAndroidHapticsAsync: async () => {}, notificationAsync: async () => {}};
    if (specifier === './assets') return {imageUri: () => null, isSvg: () => false, useSvgAsset: () => null};
    if (specifier === '../components/Icon') return {Icon: host('Icon')};
    if (specifier.startsWith('.')) {
      const base = resolve(dirname(filename), specifier);
      const target = [base, `${base}.tsx`, `${base}.ts`, `${base}.json`].find(path => existsSync(path));
      if (!target) throw new Error(`Unresolved test import ${specifier}`);
      return loadSource(target);
    }
    return require(specifier);
  };
  const compiled = ts.transpileModule(readFileSync(filename, 'utf8'), {fileName: filename,
    compilerOptions: {jsx: ts.JsxEmit.React, module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true}});
  new Function('require', 'module', 'exports', compiled.outputText)(localRequire, module, module.exports);
  return module.exports;
}
const {feedRegistry, FeedActionAvailability} = loadSource(resolve(feedDirectory, 'feedRegistry.tsx'));
const controls = {
  Button: {label: 'Confirm'}, IconButton: {icon: 'play', label: 'Play'},
  Segmented: {options: [{id: 'one', label: 'One'}, {id: 'two', label: 'Two'}], value: 'one'},
  Checklist: {items: [{id: 'one', label: 'One'}], checked: [], fade: false},
  OptionRow: {title: 'Option', image: 'https://example.com/image.png', meta: []},
  MediaStack: {posts: [{author: 'Author', avatar: 'https://example.com/avatar.png', image: 'https://example.com/post.png', likes: '1'}]},
  SlideToAct: {label: 'Slide'}, Pager: {index: 0}, Switch: {on: false, label: 'Power'},
  LevelTrack: {value: 5, min: 0, max: 10, label: 'Level'}, Link: {label: 'Open'},
};
function renderControl(name, actionId, state = {}, repeat) {
  Object.assign(runtime, {nodes: [], elements: [], responders: [], state, repeat, writes: [], emits: []});
  const on = {[name === 'SlideToAct' ? 'complete' : ['Segmented', 'Checklist', 'Pager', 'LevelTrack'].includes(name) ? 'change' : 'press']: dispatch(actionId)};
  renderToStaticMarkup(React.createElement(FeedActionAvailability.Provider, {value: availability},
    React.createElement(feedRegistry[name], {element: {type: name, props: controls[name], on},
      emit: event => runtime.emits.push(event)}, name === 'Pager' ? React.createElement('div', null, 'Page') : undefined)));
  return runtime;
}

for (const name of Object.keys(controls)) {
  test(`real ${name} renderer distinguishes unavailable and supported dispatch`, () => {
    for (const [actionId, blocked] of [['legacy', true], ['open', false]]) {
      const rendered = renderControl(name, actionId);
      if (name === 'Pager') {
        // SSR has no layout pass, so Pager has not yet created its ScrollView. Assert
        // the real wrapped component's input rather than pretending native paging ran.
        const pager = rendered.elements.find(node => node.type?.name === 'Pager');
        assert.ok(pager, 'production Pager component rendered');
        assert.equal(pager.props.disabled, blocked);
      } else {
        const actionable = rendered.nodes.filter(node => node.props.accessibilityState &&
          (node.name === 'Pressable' || node.props.accessibilityRole === 'adjustable'));
        assert.ok(actionable.length > 0, `${name} exposes an actual control`);
        for (const {name: nativeName, props} of actionable) {
          assert.equal(Boolean(props.accessibilityState.disabled), blocked, 'accessibility state');
          if (nativeName === 'Pressable') assert.equal(Boolean(props.disabled), blocked, 'native disabled prop');
        }
      }
    }
  });
}

test('real wrapper updates conditional and state bindings without disabling an unrelated action', () => {
  const conditional = {$cond: {$state: '/ui/old'}, $then: 'legacy', $else: 'open'};
  for (const old of [false, true, false]) {
    const rendered = renderControl('Button', conditional, {ui: {old}});
    assert.equal(rendered.nodes.find(node => node.name === 'Pressable').props.disabled, old);
  }
  for (const action of ['legacy', 'open']) {
    const rendered = renderControl('Button', {$state: '/ui/action'}, {ui: {action}});
    assert.equal(rendered.nodes.find(node => node.name === 'Pressable').props.disabled, action === 'legacy');
  }
});

test('real wrapper reads the current repeated item when a conditional resolves its action value', () => {
  const actionId = {$cond: {$item: 'blocked'}, $then: {$item: 'actionId'}, $else: 'open'};
  for (const [index, item] of [{blocked: false, actionId: 'legacy'}, {blocked: true, actionId: 'legacy'}].entries()) {
    const rendered = renderControl('IconButton', actionId, {}, {item, index, basePath: `/items/${index}`});
    assert.equal(rendered.nodes.find(node => node.name === 'Pressable').props.disabled, item.blocked);
  }
});

test('disabled LevelTrack rejects responder and accessibility callbacks; enabled control emits', () => {
  const blocked = renderControl('LevelTrack', 'legacy');
  const props = blocked.nodes.find(node => node.props.accessibilityRole === 'adjustable').props;
  assert.equal(props.onStartShouldSetResponder(), false);
  assert.equal(props.onMoveShouldSetResponder(), false);
  assert.equal(props.accessibilityActions, undefined);
  props.onResponderGrant({nativeEvent: {locationY: 10}});
  props.onResponderMove({nativeEvent: {locationY: 20}});
  props.onResponderRelease({nativeEvent: {locationY: 30}});
  props.onAccessibilityAction({nativeEvent: {actionName: 'increment'}});
  assert.deepEqual(blocked.emits, []);
  assert.deepEqual(blocked.writes, []);
  const enabled = renderControl('LevelTrack', 'open');
  const active = enabled.nodes.find(node => node.props.accessibilityRole === 'adjustable').props;
  assert.equal(active.onStartShouldSetResponder(), true);
  active.onAccessibilityAction({nativeEvent: {actionName: 'increment'}});
  assert.deepEqual(enabled.emits, ['change']);
});

test('disabled SlideToAct cannot claim a drag or accessibility activation; enabled activation emits', async () => {
  const blocked = renderControl('SlideToAct', 'legacy');
  const props = blocked.nodes.find(node => node.props.accessibilityRole === 'adjustable').props;
  const responder = blocked.responders[0];
  assert.equal(responder.onStartShouldSetPanResponder(), false);
  assert.equal(responder.onMoveShouldSetPanResponder({}, {dx: 100, dy: 0}), false);
  assert.equal(props.accessibilityActions, undefined);
  responder.onPanResponderMove({}, {dx: 100, dy: 0});
  responder.onPanResponderRelease({}, {dx: 1000, dy: 0});
  props.onAccessibilityAction();
  await Promise.resolve();
  assert.deepEqual(blocked.emits, []);
  const enabled = renderControl('SlideToAct', 'open');
  assert.equal(enabled.responders[0].onStartShouldSetPanResponder(), true);
  enabled.nodes.find(node => node.props.accessibilityRole === 'adjustable').props.onAccessibilityAction();
  await Promise.resolve();
  assert.deepEqual(enabled.emits, ['complete']);
});
