// Actual motion.js with an in-memory DOM/transport. No browser, ADB or network.
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../tooling/go2-lab/web/motion.js', import.meta.url), 'utf8');
const motion = (patch = {}) => ({ armed: false, remainingMs: 0, busy: false,
  linearSpeed:0.08,angularSpeed:0.15,durationMs:700,
  speedLimits:{linearSpeed:{min:0.08,max:0.2,step:0.01},angularSpeed:{min:0.15,max:0.4,step:0.05}},
  cooldownMs: 0, ready: true, mode: 'normal', result: 'locked', error: null, ...patch });
const armed = () => motion({ armed: true, remainingMs: 60000 });

function fixture() {
  let now = 100, responder = async () => motion();
  const nodes = new Map(), windowEvents = {}, documentEvents = {}, ticks = [], calls = [], payloads = [];
  const element = (id) => {
    if (!nodes.has(id)) nodes.set(id, { disabled: false, checked: false, textContent: '',
      addEventListener() {}, click() { if (!this.disabled) return this.onclick?.(); } });
    return nodes.get(id);
  };
  const messages = { zh: {}, en: {} };
  const document = { hidden: false, addEventListener: (name, callback) => { documentEvents[name] = callback; } };
  const window = { addEventListener: (name, callback) => { windowEvents[name] = callback; } };
  vm.runInNewContext(source, {
    messages, translate() {}, $: element, t: (key) => messages.zh[key], document,
    window,
    performance: { now: () => now }, setInterval: (callback) => ticks.push(callback),
    request: async (path,method,payload) => { calls.push(path); payloads.push(payload); return responder(path,payload); },
  });
  return { element, calls, payloads, owner: (value) => { window.phoneControlOwner = value; }, respond: (fn) => { responder = fn; },
    tick: (ms) => { now += ms; ticks.forEach((callback) => callback()); },
    state: (value, options = {}) => windowEvents['go2-state']({ detail: {
      state: { connected: true, motion: value }, receivedAt: now, ...options } }),
    hide: () => { document.hidden = true; documentEvents.visibilitychange(); },
    page: (name) => windowEvents['go2-page']({ detail: name }),
  };
}
const flush = () => new Promise((resolve) => setImmediate(resolve));

test('phone AI owner prevents manual movement but never hides stop', () => {
  const f = fixture(); f.owner('ai'); f.state(armed());
  f.element('motion-forward').click();
  assert.deepEqual(f.calls, []);
  assert.equal(f.element('motion-forward').disabled, true);
  assert.equal(f.element('motion-stop').disabled, false);
  f.owner('manual'); f.state(armed());
  assert.equal(f.element('motion-forward').disabled, false);
  f.owner('unknown'); f.tick(1);
  assert.equal(f.element('motion-forward').disabled, true);
});

test('default locked; checking readiness alone sends no command', () => {
  const f = fixture(); f.state(motion());
  assert.equal(f.element('motion-forward').disabled, true);
  f.element('motion-ready').checked = true; f.element('motion-ready').onchange();
  assert.equal(f.element('motion-arm').disabled, false);
  assert.equal(f.element('motion-forward').disabled, true);
  assert.deepEqual(f.calls, []);
});
test('pending move prevents repeated button clicks; cooldown blocks another move', async () => {
  const f = fixture(); f.state(armed());
  let finish; f.respond(() => new Promise((resolve) => { finish = resolve; }));
  f.element('motion-forward').click(); f.element('motion-right').click();
  assert.deepEqual(f.calls, ['/api/motion/forward']);
  finish(motion({ cooldownMs: 2000 })); await flush();
  assert.equal(f.element('motion-forward').disabled, true);
  assert.equal(f.element('motion-stop').disabled, false);
});
test('stale data disables directions even while the page keeps rendering', () => {
  const f = fixture(); f.state(armed()); f.tick(1801);
  for (const d of ['forward', 'backward', 'left', 'right']) assert.equal(f.element('motion-' + d).disabled, true);
  assert.equal(f.element('motion-stop').disabled, false);
});
test('failed stop stays latched across fresh state and permits stop retry', async () => {
  const f = fixture(); f.state(armed()); f.respond(async () => { throw Error('synthetic timeout'); });
  f.element('motion-stop').click(); await flush(); f.state(armed());
  assert.equal(f.element('motion-forward').disabled, true);
  assert.match(f.element('motion-status').textContent, /未收到停止确认/);
  f.respond(async () => motion({ result: 'stop_acknowledged' }));
  f.element('motion-stop').click(); await flush();
  assert.doesNotMatch(f.element('motion-status').textContent, /未收到停止确认/);
});
test('stop supersedes a late successful move response', async () => {
  const f = fixture(); f.state(armed());
  let finish; f.respond((path) => path.endsWith('/stop') ? Promise.resolve(motion()) : new Promise((r) => { finish = r; }));
  f.element('motion-forward').click(); f.element('motion-stop').click(); await flush();
  finish(armed()); await flush();
  assert.equal(f.element('motion-forward').disabled, true);
});
test('old polling response cannot replace newer locked command result', async () => {
  const f = fixture(); f.state(armed()); f.tick(100);
  f.element('motion-stop').click(); await flush(); f.state(armed(), { receivedAt: 100 });
  assert.equal(f.element('motion-forward').disabled, true);
});
test('hiding an already armed page requests stop and clears readiness', async () => {
  const f = fixture(); f.state(armed()); f.element('motion-ready').checked = true;
  f.hide(); await flush();
  assert.deepEqual(f.calls, ['/api/motion/stop']);
  assert.equal(f.element('motion-ready').checked, false);
});
test('explicit offline event locks buttons while leaving stop available', () => {
  const f = fixture(); f.state(armed()); f.state(armed(), { offline: true });
  assert.equal(f.element('motion-forward').disabled, true);
  assert.equal(f.element('motion-stop').disabled, false);
});
for (const leave of ['hide', 'page']) test(`${leave} while arm is pending sends stop and ignores late arm`, async () => {
  const f = fixture(); f.state(motion()); f.element('motion-ready').checked = true;
  f.element('motion-ready').onchange();
  let finish;
  f.respond((path) => path.endsWith('/stop') ? Promise.resolve(motion()) : new Promise((r) => { finish = r; }));
  f.element('motion-arm').click(); f[leave]('avoidance'); await flush();
  finish(armed()); await flush();
  assert.deepEqual(f.calls, ['/api/motion/arm', '/api/motion/stop']);
  assert.equal(f.element('motion-forward').disabled, true);
  assert.equal(f.element('motion-ready').checked, false);
});

test('speed draft survives polling and prevents arming until applied', async () => {
  const f=fixture();f.state(motion());
  f.element('motion-linear').value='0.20';f.element('motion-linear').oninput();
  f.element('motion-angular').value='0.40';f.element('motion-angular').oninput();
  f.state(motion());
  assert.equal(Number(f.element('motion-linear').value),.2);
  assert.match(f.element('motion-speed-preview').textContent,/14\.0 cm/);
  f.element('motion-ready').checked=true;f.element('motion-ready').onchange();
  assert.equal(f.element('motion-arm').disabled,true);
  assert.deepEqual(f.calls,[]);
  f.respond(async (path,payload)=>motion(payload));
  await f.element('motion-speed-apply').click();await flush();
  assert.deepEqual(f.calls,['/api/motion/speed']);
  assert.equal(f.payloads[0].linearSpeed,.2);assert.equal(f.payloads[0].angularSpeed,.4);
  assert.equal(f.element('motion-ready').checked,false);
  assert.equal(f.element('motion-arm').disabled,true);
  assert.match(f.element('motion-limits').textContent,/0\.20 m\/s/);
  assert.match(f.element('motion-speed-status').textContent,/已应用/);
});
test('armed and busy states prohibit editing speeds', () => {
  for(const state of [armed(),motion({busy:true})]){
    const f=fixture();f.state(state);
    assert.equal(f.element('motion-linear').disabled,true);
    assert.equal(f.element('motion-speed-apply').disabled,true);
    f.element('motion-linear').value='.2';f.element('motion-linear').oninput();
    assert.deepEqual(f.calls,[]);
  }
});
test('speed timeout or wrong readback keeps draft locked; retry only configures', async () => {
  for(const reply of [async()=>{throw Error('timeout');},async()=>motion()]){
    const f=fixture();f.state(motion());
    f.element('motion-linear').value='.2';f.element('motion-linear').oninput();
    f.respond(reply);f.element('motion-speed-apply').click();await flush();
    f.state(motion());f.element('motion-ready').checked=true;f.element('motion-ready').onchange();
    assert.equal(f.element('motion-arm').disabled,true);
    assert.match(f.element('motion-speed-status').textContent,/未确认/);
    f.respond(async (_,payload)=>motion(payload));
    f.element('motion-speed-apply').click();await flush();
    assert.deepEqual(f.calls,['/api/motion/speed','/api/motion/speed']);
  }
});
test('disconnected robot still permits local config but never enables movement', async () => {
  const f=fixture();f.state(motion(),{state:{connected:false,motion:motion()}});
  assert.equal(f.element('motion-linear').disabled,false);
  f.element('motion-linear').value='.12';f.element('motion-linear').oninput();
  f.respond(async (_,payload)=>motion(payload));f.element('motion-speed-apply').click();await flush();
  assert.deepEqual(f.calls,['/api/motion/speed']);
  f.element('motion-ready').checked=true;f.element('motion-ready').onchange();
  assert.equal(f.element('motion-arm').disabled,true);
  assert.equal(f.element('motion-forward').disabled,true);
});

// A real observation-only connection must not offer controls that the server rejects.
test('external motion policy locks app controls even with fresh armed telemetry', () => {
  const f=fixture();f.owner('manual');f.state(armed(), {state:{connected:true,externalMotionControl:true,motion:armed()}});
  f.element('motion-forward').click();f.element('motion-arm').click();
  assert.deepEqual(f.calls,[]);
  assert.equal(f.element('motion-ready').disabled,true);
  assert.equal(f.element('motion-linear').disabled,true);
  assert.equal(f.element('motion-stop').disabled,false);
  assert.match(f.element('motion-status').textContent,/当前连接模式/);
});
