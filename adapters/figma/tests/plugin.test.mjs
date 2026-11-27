import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../plugin/code.js', import.meta.url), 'utf8');
function fixture(fileKey = 'SyntheticFile') {
  const messages = []; let pageSelections = 0;
  const start = {type: 'FRAME', id: '1:2', name: 'Start', width: 390, height: 720,
    getPluginData: key => key === 'key' ? 'Start' : '', findAll: () => []};
  const page = {id: '1:1', children: [start], selection: [], getPluginData: key =>
    key === 'owner' ? 'taku.figma-desktop.v1' : key === 'job' ? 'synthetic-job' : ''};
  const figma = {fileKey, showUI() {}, root: {children: [page]}, currentPage: page,
    setCurrentPageAsync: async () => { pageSelections++; }, viewport: {scrollAndZoomIntoView() {}},
    ui: {postMessage: message => messages.push(message)}};
  vm.runInNewContext(source, {figma, __html__: '', Map, Date});
  const request = {requestId: 'r1', job: 'synthetic-job', fileKey: 'SyntheticFile', body: {action: 'inspect'}};
  return {figma, start, messages, request, pageSelections: () => pageSelections};
}

test('plugin validates the actual file and returns exact inspect identity', async () => {
  const f = fixture(); await f.figma.ui.onmessage({type: 'execute', request: f.request});
  assert.equal(f.messages[0].type, 'ack');
  const ack = f.messages[0].ack;
  assert.equal(ack.requestId, 'r1'); assert.equal(ack.fileKey, 'SyntheticFile');
  assert.equal(ack.executedBy, 'desktop-plugin'); assert.equal(ack.action, 'inspect');
  assert.equal(ack.startFrameId, ack.selectedFrameId);
});

test('lost UI response replays cached receipt without repeating the operation', async () => {
  const f = fixture(); const message = {type: 'execute', request: f.request};
  await f.figma.ui.onmessage(message); await f.figma.ui.onmessage(message);
  assert.equal(f.pageSelections(), 1);
  assert.equal(f.messages.length, 2);
  assert.equal(f.messages[0].ack, f.messages[1].ack);
});

test('wrong file and unavailable identity fail before page or node changes', async () => {
  for (const key of ['OtherFile', undefined]) {
    const f = fixture(key); if (key === undefined) delete f.figma.fileKey;
    await f.figma.ui.onmessage({type: 'execute', request: f.request});
    assert.equal(f.messages[0].type, 'error'); assert.equal(f.pageSelections(), 0);
    if (key === undefined) assert.match(f.messages[0].error, /FILE_IDENTITY_UNAVAILABLE/);
  }
});

test('an error receipt is replayed without repeating a potentially partial operation', async () => {
  const f = fixture(); f.request.body = {action: 'apply', frame: 'Unknown', nodes: []};
  await f.figma.ui.onmessage({type: 'execute', request: f.request});
  await f.figma.ui.onmessage({type: 'execute', request: f.request});
  assert.equal(f.messages[0].type, 'error');
  assert.equal(f.messages[0], f.messages[1]);
  assert.equal(f.pageSelections(), 1);
});

test('request ID reuse with changed data cannot reuse a successful receipt', async () => {
  const f = fixture(); await f.figma.ui.onmessage({type: 'execute', request: f.request});
  await f.figma.ui.onmessage({type: 'execute', request: {...f.request, job: 'changed'}});
  assert.equal(f.messages[1].type, 'error');
  assert.match(f.messages[1].error, /reused with different data/);
  assert.equal(f.pageSelections(), 1);
});

test('large snapshots retain a bounded exact execution receipt', async () => {
  const f = fixture();
  f.start.findAll = () => Array.from({length: 1000}, () => ({type: 'TEXT', id: '1:3', name: 'Text',
    characters: '界'.repeat(600), getPluginData: () => 'SyntheticText'}));
  await f.figma.ui.onmessage({type: 'execute', request: f.request});
  const ack = f.messages[0].ack;
  assert.equal(ack.requestId, 'r1'); assert.equal(ack.snapshotTruncated, true);
  assert.equal(ack.frames.length, 0);
  assert.ok(Buffer.byteLength(JSON.stringify(ack), 'utf8') < 262144);
});
