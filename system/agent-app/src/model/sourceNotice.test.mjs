import test from 'node:test';
import assert from 'node:assert/strict';
import {sourceNotice} from './sourceNotice.ts';

const weather = extra => ({provenance: {kind: 'weather-model'}, ...extra});

test('a fresh source says nothing', () => {
  assert.equal(sourceNotice(weather({state: 'available', validUntil: 2_000}), 'en', 1_000), null);
});

test('a missing city asks for one; the code stays in the details', () => {
  const n = sourceNotice(weather({state: 'unavailable', code: 'LOCATION_OR_CITY_REQUIRED', lastSuccessfulAt: 1}), 'en', 10);
  assert.equal(n.message, 'Tell Taku which city to use.');
  assert.ok(!n.message.includes('LOCATION'));
  assert.match(n.details, /Open-Meteo · Read .* · LOCATION_OR_CITY_REQUIRED/);
  assert.equal(n.alert, true);
  assert.equal(sourceNotice(weather({state: 'unavailable', code: 'LOCATION_OR_CITY_REQUIRED'}), 'zh', 10).message, '告诉 Taku 要查哪个城市。');
});

test('unknown failures and stale results read as plain language', () => {
  assert.equal(sourceNotice(weather({state: 'unavailable', code: 'UPSTREAM_500'}), 'en', 0).message, 'Couldn’t update right now; showing the last result.');
  assert.equal(sourceNotice(weather({state: 'available', validUntil: 5}), 'en', 10).message, 'This is an earlier result and may be out of date.');
  assert.equal(sourceNotice({provenance: {kind: 'audio-session'}, state: 'unavailable'}, 'en', 0), null);
});
