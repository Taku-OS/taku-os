import test from 'node:test';
import assert from 'node:assert/strict';
import {liveVideoLabel, liveVideoSupported, nextVideoSource} from './liveVideo.ts';

test('video button cycles off, screen, camera, off', () => {
  assert.equal(nextVideoSource('none'), 'screen');
  assert.equal(nextVideoSource('screen'), 'camera');
  assert.equal(nextVideoSource('camera'), 'none');
});

test('video is offered only on an active, unmuted MOSS-Live connection', () => {
  assert.equal(liveVideoSupported('moss-live', 'active', false), true);
  assert.equal(liveVideoSupported('moss-live', 'active', true), false);
  assert.equal(liveVideoSupported('moss-live', 'connecting', false), false);
  assert.equal(liveVideoSupported('gemini-live', 'active', false), false);
  assert.equal(liveVideoSupported(null, 'active', false), false);
});

test('labels describe the next action', () => {
  assert.equal(liveVideoLabel('none', false), 'liveVideoUnavailable');
  assert.equal(liveVideoLabel('none', true), 'liveVideoShareScreen');
  assert.equal(liveVideoLabel('screen', true), 'liveVideoUseCamera');
  assert.equal(liveVideoLabel('camera', true), 'liveVideoStop');
});
