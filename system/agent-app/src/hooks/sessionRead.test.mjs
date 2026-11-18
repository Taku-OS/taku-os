import test from 'node:test';
import assert from 'node:assert/strict';
import {sessionReadOptions} from './sessionRead.ts';
import {emptySessionStream, reduceSessionEvent, sessionRootForDisplay} from '../model/sessionStream.ts';
import {projectSession} from '../model/session.ts';

const base = {attached: true, streamFailed: false, sideband: {native: {ready: true}}, cacheFirst: false, hydrate: false, ready: true, running: false};
test('a healthy subscription only polls sideband; unattached clients retain full polling fallback', () => {
  assert.deepEqual(sessionReadOptions(base), {statusOnly: true});
  assert.deepEqual(sessionReadOptions({...base, attached: false}), {refresh: false});
});
test('a cold restarted broker rehydrates the same thread even if the old displayed turn looked running', () => {
  assert.deepEqual(sessionReadOptions({...base, streamFailed: true, running: true}), {refresh: true});
});
test('recovery never reloads history over an active native turn', () => {
  assert.deepEqual(sessionReadOptions({...base, streamFailed: true, running: true, sideband: {native: {ready: true, activeTurnId: 'active'}}}), {statusOnly: true});
  assert.deepEqual(sessionReadOptions({...base, streamFailed: true, sideband: {native: {ready: false, activeTurnId: 'active'}}}), {statusOnly: true});
});
test('partial history hydrates only after the cache frame and while the session is idle', () => {
  assert.deepEqual(sessionReadOptions({...base, hydrate: true}), {refresh: true});
  assert.deepEqual(sessionReadOptions({...base, hydrate: true, cacheFirst: true}), {statusOnly: true});
  assert.deepEqual(sessionReadOptions({...base, hydrate: true, running: true}), {statusOnly: true});
});


test('fresh empty stream snapshot waits for native ready, fetches history, then returns to sideband polls', () => {
  const snapshot = (seq, turns) => ({schema: 'taku.session-events/1', threadId: 'thread', brokerEpoch: 'epoch', seq,
    method: 'session/snapshot', params: {thread: {id: 'thread', turns}}});
  let stream = reduceSessionEvent(emptySessionStream('thread'), snapshot(1, []));
  let sideband = {};
  let displayed = projectSession(sessionRootForDisplay(stream, sideband));
  const options = cacheFirst => sessionReadOptions({...base, cacheFirst, sideband,
    hydrate: !displayed.historyComplete && !displayed.knownEmpty, ready: displayed.ready, running: displayed.running});
  assert.equal(displayed.ready, false);
  assert.deepEqual(options(true), {statusOnly: true});
  sideband = {native: {ready: true}, statusOnly: true};
  displayed = projectSession(sessionRootForDisplay(stream, sideband));
  assert.equal(displayed.ready, true);
  assert.deepEqual(options(false), {refresh: true});
  const turns = [{id: 'turn', status: 'completed', items: [{id: 'item-0', type: 'agentMessage', text: 'Historical answer'}]}];
  stream = reduceSessionEvent(stream, snapshot(2, turns));
  sideband = {thread: {id: 'thread', turns}, native: {ready: true}, historyComplete: true};
  displayed = projectSession(sessionRootForDisplay(stream, sideband));
  assert.equal(displayed.blocks[0].text, 'Historical answer');
  assert.deepEqual(options(false), {statusOnly: true});
});


test('a cold broker with no active turn is initialized by read even when native ready remains false', () => {
  assert.deepEqual(sessionReadOptions({...base, streamFailed: true, ready: false, running: true,
    sideband: {native: {ready: false}}}), {refresh: true});
  // A healthy subscription with a transient unavailable sideband does not restart reads.
  assert.deepEqual(sessionReadOptions({...base, ready: false, sideband: {native: {ready: false}}}), {statusOnly: true});
});
