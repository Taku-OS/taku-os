import test from 'node:test';
import assert from 'node:assert/strict';
import {sheetSpace} from './sheetGeometry.ts';
const full = {windowHeight: 900, rootTop: 0, rootHeight: 900, imeBottom: 0, visible: false};

test('edge-to-edge half sheet clears IME in the same geometry update', () => {
  const {available, bottom} = sheetSpace({...full, imeBottom: 350, visible: true}, 900, 24);
  assert.equal(bottom, 350);
  assert.equal(available * .55, 289.3);
  assert.equal(900 - bottom, 550);
});
test('Android already resized the root: do not avoid the same keyboard twice', () => {
  assert.deepEqual(sheetSpace({...full, rootHeight: 550, imeBottom: 350, visible: true}, 900, 24), {bottom: 0, available: 526});
});
test('a delayed JS onLayout cannot mix an old height into a new native snapshot', () => {
  const native = {...full, rootHeight: 550, imeBottom: 350, visible: true};
  assert.deepEqual(sheetSpace(native, 900, 24), sheetSpace(native, 550, 24));
  assert.deepEqual(sheetSpace(full, 550, 24), {bottom: 0, available: 876});
});
test('show/hide cycles restore the original half-sheet height every time', () => {
  const baseline = sheetSpace(full, 900, 24);
  for (let i = 0; i < 5; i++) {
    const raised = sheetSpace({...full, imeBottom: 350, visible: true}, 900, 24);
    assert.equal(raised.bottom, 350);
    assert.deepEqual(sheetSpace(full, raised.available, 24), baseline);
  }
});
test('window-relative root origin and partially resized roots calculate only actual overlap', () => {
  assert.deepEqual(sheetSpace({...full, rootTop: 24, rootHeight: 700, imeBottom: 350, visible: true}, 900, 0), {bottom: 174, available: 526});
});
test('keyboard height changes while visible update geometry without a visibility transition', () => {
  assert.equal(sheetSpace({...full, imeBottom: 300, visible: true}, 900, 24).available, 576);
  assert.equal(sheetSpace({...full, imeBottom: 400, visible: true}, 900, 24).available, 476);
});
test('floating keyboards and malformed initial geometry do not invent bottom occlusion', () => {
  assert.deepEqual(sheetSpace({...full, visible: true}, 900, 24), {bottom: 0, available: 876});
  assert.deepEqual(sheetSpace({...full, rootHeight: NaN}, 900, 24), {bottom: 0, available: 876});
});
