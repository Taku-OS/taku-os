import test from 'node:test';
import assert from 'node:assert/strict';
import {emptyPreview, previewReducer} from './preview.ts';

const uri = 'content://org.agentos.assistant.agent-gateway/preview/thread/screen/1';
const tick = (state, now, source = uri) => previewReducer(state, {type: 'tick', now, uri: source});
const loaded = (state, now, id = state.pending.id) => previewReducer(state, {type: 'loaded', now, id, width: 1080, height: 2400});

test('a refresh retains the exact displayed frame until its replacement is loaded', () => {
  let state = loaded(tick(emptyPreview(), 0), 100);
  const first = state.visible;
  state = tick(state, 2500);
  assert.equal(state.visible, first);
  assert.notEqual(state.pending.uri, first.uri);
  const pending = state.pending;
  assert.equal(tick(state, 4900), state);
  state = loaded(state, 4900);
  assert.equal(state.visible.id, pending.id);
  assert.equal(state.pending, null);
});

test('an old frame expires even if the next request is still loading, with only one pending frame', () => {
  let state = loaded(tick(emptyPreview(), 0), 100);
  state = tick(state, 2500);
  const pending = state.pending;
  state = tick(state, 5100);
  assert.equal(state.visible, null);
  assert.equal(state.pending, pending);
  assert.equal(tick(state, 30000), state);
  state = tick(state, 32500);
  assert.equal(state.pending, null);
  assert.equal(state.failed, true);
  assert.equal(tick(state, 34000), state);
  assert.ok(tick(state, 35000).pending);
});

test('a slow real load can finish within 30 seconds without extending displayed-frame freshness', () => {
  let state = loaded(tick(emptyPreview(), 0), 100);
  state = tick(state, 2500);
  const request = state.pending;
  state = tick(state, 5100);
  assert.equal(state.visible, null);
  assert.equal(state.pending, request);
  assert.equal(tick(state, 20000), state);
  state = loaded(state, 22000);
  assert.equal(state.visible.id, request.id);
  assert.equal(state.visible.loadedAt, 22000);
  assert.equal(tick(state, 27000).visible, null);
});

test('a load arriving after its 30-second deadline is rejected even before the timer runs', () => {
  let state = tick(emptyPreview(), 0);
  const id = state.pending.id;
  state = loaded(state, 30001, id);
  assert.equal(state.visible, null);
  assert.equal(state.pending, null);
  assert.equal(state.failed, true);
  assert.equal(loaded(state, 31000, id), state);
});

test('hidden and failed previews clear both buffers and reject stale image callbacks', () => {
  let state = loaded(tick(emptyPreview(), 0), 100);
  state = tick(state, 2500);
  const oldId = state.pending.id;
  state = previewReducer(state, {type: 'hide'});
  assert.equal(state.visible, null);
  assert.equal(state.pending, null);
  assert.equal(loaded(state, 2600, oldId), state);
  state = tick(state, 2700);
  const newId = state.pending.id;
  assert.notEqual(newId, oldId);
  assert.equal(previewReducer(state, {type: 'error', now: 2800, id: oldId}), state);
  state = previewReducer(state, {type: 'error', now: 2900, id: newId});
  assert.equal(state.visible, null);
  assert.equal(state.pending, null);
  assert.equal(state.failed, true);
});

test('re-decoding identical inline bytes never makes a stale capture appear fresh', () => {
  const source = 'data:image/jpeg;base64,example';
  let state = loaded(tick(emptyPreview(), 0, source), 100);
  assert.equal(tick(state, 2500, source), state);
  state = tick(state, 5100, source);
  assert.equal(state.visible, null);
  assert.equal(state.pending, null);
});

test('a retry shows loading again and only the current frame can recover it', () => {
  let state = tick(emptyPreview(), 0);
  const failedId = state.pending.id;
  state = previewReducer(state, {type: 'error', now: 100, id: failedId});
  assert.equal(state.failed, true);
  assert.equal(tick(state, 2599), state);
  state = tick(state, 2600);
  assert.ok(state.pending);
  assert.equal(state.failed, false);
  assert.equal(loaded(state, 2700, failedId), state);
  assert.equal(previewReducer(state, {type: 'error', now: 2700, id: failedId}), state);
  state = loaded(state, 2800);
  assert.ok(state.visible);
  assert.equal(state.failed, false);
});
