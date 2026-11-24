import test from 'node:test';
import assert from 'node:assert/strict';
import {LIVE_LIGHT_HEIGHT_RATIO, LIVE_LIGHT_OPACITY, liveLightStyle, shouldShowLiveLight} from './liveGlow.ts';

test('live light field is disabled outside Live mode and enabled only when Live Talk is active', () => {
  assert.equal(shouldShowLiveLight(false), false);
  assert.equal(shouldShowLiveLight(true), true);
});

test('live light field uses softened opacity and restrained vertical reach', () => {
  assert.ok(LIVE_LIGHT_OPACITY > 0 && LIVE_LIGHT_OPACITY <= 0.5);
  assert.ok(LIVE_LIGHT_HEIGHT_RATIO > 0 && LIVE_LIGHT_HEIGHT_RATIO < 357 / 412);
  const style = liveLightStyle(412);
  assert.equal(style.position, 'absolute');
  assert.equal(style.left, 0);
  assert.equal(style.right, 0);
  assert.equal(style.bottom, 0);
  assert.equal(style.height, 280);
  assert.equal(style.opacity, LIVE_LIGHT_OPACITY);
});
