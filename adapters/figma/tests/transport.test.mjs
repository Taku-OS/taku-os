import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../plugin/ui.html', import.meta.url), 'utf8');
const source = html.match(/<script id="bridge-transport">([\s\S]*?)<\/script>/)[1];
const BridgeTransport = vm.runInNewContext(`${source}\nBridgeTransport`, {});
const request = {requestId: 'synthetic-r1', job: 'synthetic-job', body: {action: 'inspect'}};
const ack = {...request, action: 'inspect', executedAt: '2030-01-01T00:00:00Z'};

function fixture() {
  const sent = [], calls = [], messages = [];
  let now = 0, failReceipt = false, state = {request};
  const transport = new BridgeTransport({
    call: async (path, data) => {
      calls.push({path, data});
      if (path === '/ack' && failReceipt) throw Error('Synthetic dropped HTTP receipt');
      return path === '/state' ? state : {ok: true};
    },
    send: message => sent.push(message), status: message => messages.push(message),
    now: () => now, retryMs: 10,
  });
  return {transport, sent, calls, messages, advance: ms => { now += ms; },
    failReceipt: value => { failReceipt = value; }, setState: value => { state = value; }};
}

test('lost plugin response retries the exact request without replacing its identity', async () => {
  const f = fixture(); f.transport.connect();
  await f.transport.tick(); await f.transport.tick();
  assert.equal(f.sent.length, 1);
  f.advance(11); await f.transport.tick();
  assert.equal(f.sent.length, 2);
  assert.equal(f.sent[0].request, f.sent[1].request);
});

test('a lost HTTP ack is retained and resent, without executing again', async () => {
  const f = fixture(); f.transport.connect(); await f.transport.tick();
  f.transport.stop(); // Capture a response while stopped; receipt must survive.
  f.transport.receive({type: 'ack', ack});
  f.failReceipt(true); f.transport.connect(); await f.transport.tick();
  assert.ok(f.transport.receipt);
  f.failReceipt(false); await f.transport.tick();
  assert.equal(f.transport.receipt, null);
  assert.equal(f.transport.last, request.requestId);
  assert.equal(f.calls.filter(value => value.path === '/ack').length, 2);
  assert.equal(f.sent.length, 1);
});

test('Stop and Connect resume a pending request instead of trapping it forever', async () => {
  const f = fixture(); f.transport.connect(); await f.transport.tick();
  f.transport.stop(); f.advance(11); await f.transport.tick();
  assert.equal(f.sent.length, 1);
  f.transport.connect(); await f.transport.tick();
  assert.equal(f.sent.length, 2);
});

test('stale receipts and already acknowledged relay operations are not executed', async () => {
  const f = fixture(); f.transport.connect(); await f.transport.tick();
  f.transport.receive({type: 'ack', ack: {...ack, requestId: 'stale'}});
  assert.equal(f.transport.receipt, null);
  f.setState({request, ack}); f.advance(11); await f.transport.tick();
  assert.equal(f.sent.length, 1);
  assert.equal(f.transport.last, request.requestId);
});

test('expired sessions stop polling instead of presenting connected success', async () => {
  const messages = [];
  const transport = new BridgeTransport({call: async () => { const e = Error('Session expired'); e.status = 401; throw e; },
    send: () => assert.fail('must not execute'), status: message => messages.push(message)});
  transport.connect(); await transport.tick();
  assert.equal(transport.running, false);
  assert.equal(messages.at(-1), 'Session expired');
});
