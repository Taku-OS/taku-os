import test from 'node:test';
import assert from 'node:assert/strict';
import {insertDictation, barHeights, dictationResult, failureOf} from './dictationText.ts';

test('dictation replaces only the captured selection and places the caret after the insert', () => {
  assert.deepEqual(insertDictation('say OLD now', {start: 4, end: 7}, ' new words '), {text: 'say new words now', caret: 13});
  assert.deepEqual(insertDictation('saynow', {start: 3, end: 3}, 'this'), {text: 'say this now', caret: 9});
  assert.deepEqual(insertDictation('帮我航班', {start: 2, end: 2}, '查一下'), {text: '帮我查一下航班', caret: 5});
  assert.deepEqual(insertDictation('Hi  ', {start: 4, end: 4}, 'there'), {text: 'Hi  there', caret: 9});
  assert.deepEqual(insertDictation('Hi', {start: 0, end: 2}, '   '), {text: 'Hi', caret: 2});
});

test('engine reasons map to the three toasts', () => {
  assert.equal(failureOf('POLICY_DISABLED'), 'disabled');
  assert.equal(failureOf('CONSUMER_NOT_AUTHORIZED'), 'disabled');
  assert.equal(failureOf('RESOURCE_BUSY'), 'busy');
  assert.equal(failureOf('CAPTURE_SILENCED'), 'busy');
  assert.equal(failureOf('ENGINE_UNAVAILABLE'), 'unavailable');
  assert.equal(failureOf(undefined), 'unavailable');
});

test('a missing fal key is its own failure, from the engine or from Home', () => {
  assert.equal(failureOf('PROVIDER_NOT_CONFIGURED'), 'falKeyMissing');
  assert.equal(failureOf('VOICE_FAL_KEY_MISSING'), 'falKeyMissing');
  assert.equal(dictationResult({kind: 'Failed', code: 'PROVIDER_NOT_CONFIGURED'}).reason, 'falKeyMissing');
  assert.equal(failureOf('PROVIDER_AUTH_FAILED'), 'unavailable', 'a saved but rejected key is not a missing one');
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
