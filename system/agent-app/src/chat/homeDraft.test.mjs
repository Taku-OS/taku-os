import test from 'node:test';
import assert from 'node:assert/strict';
import {appendHomeDrafts} from './homeDraft.ts';

test('consecutive drafts each appear once (issue #35 item 2)', () => {
  const afterA = appendHomeDrafts('', ['Review-A:']);
  assert.equal(afterA, 'Review-A:');
  assert.equal(appendHomeDrafts(afterA, ['Review-B:']), 'Review-A:\n\nReview-B:');
});

test('keeps unsent user text and does not resurrect sent or deleted drafts', () => {
  assert.equal(appendHomeDrafts('my note', ['Review-B:']), 'my note\n\nReview-B:');
  // The composer was cleared (sent or deleted) after A; only B arrives.
  assert.equal(appendHomeDrafts('', ['Review-B:']), 'Review-B:');
});

test('several queued drafts apply in order; blanks are ignored', () => {
  assert.equal(appendHomeDrafts('', ['A', '  ', 'B']), 'A\n\nB');
  assert.equal(appendHomeDrafts('x', []), 'x');
});
