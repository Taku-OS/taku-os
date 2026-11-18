import test from 'node:test';
import assert from 'node:assert/strict';
import {appendDictation, barHeights, dictationResult, failureOf} from './dictationText.ts';

test('dictation joins the draft with a space, Chinese without one', () => {
  assert.equal(appendDictation('', ' check the flight '), 'check the flight');
  assert.equal(appendDictation('Please', 'check the flight'), 'Please check the flight');
  assert.equal(appendDictation('Please  ', 'check'), 'Please check');
  assert.equal(appendDictation('帮我', '查一下航班'), '帮我查一下航班');
  assert.equal(appendDictation('Hi', '   '), 'Hi');
});

test('engine reasons map to the three toasts', () => {
  assert.equal(failureOf('POLICY_DISABLED'), 'disabled');
  assert.equal(failureOf('CONSUMER_NOT_AUTHORIZED'), 'disabled');
  assert.equal(failureOf('RESOURCE_BUSY'), 'busy');
  assert.equal(failureOf('CAPTURE_SILENCED'), 'busy');
  assert.equal(failureOf('ENGINE_UNAVAILABLE'), 'unavailable');
  assert.equal(failureOf(undefined), 'unavailable');
});

test('only a successful outcome delivers, and it delivers its own complete text', () => {
  assert.deepEqual(dictationResult({kind: 'Success', text: 'the whole tail'}), {text: 'the whole tail'});
  // A failed session with a partial preview still fails: text alone never means success.
  assert.deepEqual(dictationResult({kind: 'Failed', code: 'PROCESSING_TIMEOUT'}), {reason: 'unavailable'});
  assert.deepEqual(dictationResult({kind: 'Failed', code: 'POLICY_DISABLED'}), {reason: 'disabled'});
  assert.equal(dictationResult({kind: 'Cancelled'}), null);
  assert.equal(dictationResult(null), null);
});

test('live bars keep the Figma shape, rest at 40 % and stay within 3–16 dp', () => {
  const rest = barHeights([]);
  assert.ok(Math.abs(rest[1] - (3 + 10 * 0.4)) < 1e-9);
  const loud = barHeights([1, 1, 1, 1, 1, 1]);
  assert.ok(loud.every(h => h <= 16 && h >= 3));
  assert.equal(loud[1], 16);
  assert.ok(barHeights([0, 0, 0, 0, 0, 0]).every((h, i) => h === rest[i]));
});
