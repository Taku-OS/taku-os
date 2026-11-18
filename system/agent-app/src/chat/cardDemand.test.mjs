import test from 'node:test';
import assert from 'node:assert/strict';
import {CARD_PRELOAD_ROWS, rowDemand, sameBand, visibleBand} from './cardDemand.ts';

test('a card on screen is visible demand', () => {
  const band = visibleBand([3, 4, 5]);
  assert.deepEqual(band, {first: 3, last: 5});
  for (const index of [3, 4, 5]) assert.equal(rowDemand(band, index), 'visible');
});

test('a card just off screen is preload, so its reader is dropped first', () => {
  const band = visibleBand([10]);
  assert.equal(rowDemand(band, 10 - CARD_PRELOAD_ROWS), 'preload');
  assert.equal(rowDemand(band, 10 + CARD_PRELOAD_ROWS), 'preload');
  assert.equal(rowDemand(band, 10 - CARD_PRELOAD_ROWS - 1), null);
  assert.equal(rowDemand(band, 10 + CARD_PRELOAD_ROWS + 1), null);
});

test('a hidden or unlaid-out list holds no demand', () => {
  assert.equal(rowDemand(visibleBand([]), 0), null);
  assert.equal(rowDemand(null, 0), null);
  assert.equal(visibleBand([null, undefined]), null);
});

test('the preload band never runs past the list', () => {
  const band = visibleBand([0, 1]);
  assert.equal(rowDemand(band, 0), 'visible');
  assert.equal(rowDemand(band, CARD_PRELOAD_ROWS + 2), null);
});

test('an unchanged band is not a new state', () => {
  assert.ok(sameBand(visibleBand([2, 3]), visibleBand([2, 3])));
  assert.ok(sameBand(null, null));
  assert.ok(!sameBand(visibleBand([2]), visibleBand([2, 3])));
  assert.ok(!sameBand(null, visibleBand([0])));
});
