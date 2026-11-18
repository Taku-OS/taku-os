import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import {spawnSync} from 'node:child_process';

const pack = process.env.TAKU_WECHAT_REA_PACK || '/data/local/agentos/rea-pack';
const storage = process.env.TAKU_WECHAT_STORAGE || '/data/local/agentos/wechat-monitor';
const eventsPath = path.join(storage, 'events.jsonl');
const hookPath = path.join(storage, 'hook.js');
const cli = process.env.TAKU_PLUGIN_CLI || '/system_ext/bin/taku-plugin';

const tools = [
  {name: 'start_monitor', description: 'Attach to the running WeChat main process through the REA session API and observe new notifications.', inputSchema: {type: 'object', properties: {}, additionalProperties: false}},
  {name: 'read_events', description: 'Sync and read locally captured notification events, oldest first.', inputSchema: {type: 'object', properties: {limit: {type: 'integer', minimum: 1, maximum: 1000}}, additionalProperties: false}},
  {name: 'stop_monitor', description: 'Close the REA session and keep the local event file.', inputSchema: {type: 'object', properties: {}, additionalProperties: false}}
];

let active = null;
let lastCaptureError = null;

function prepareStorage() {
  fs.mkdirSync(storage, {recursive: true, mode: 0o700});
  fs.chmodSync(storage, 0o700);
  for (const file of [eventsPath]) {
    const descriptor = fs.openSync(file, 'a', 0o600);
    fs.closeSync(descriptor);
    fs.chmodSync(file, 0o600);
  }
  const source = fs.readFileSync(new URL('./hook.js', import.meta.url), 'utf8');
  if (!fs.existsSync(hookPath) || fs.readFileSync(hookPath, 'utf8') !== source) {
    fs.writeFileSync(hookPath, source, {mode: 0o600});
  }
}

function readEvents(limit = 100) {
  prepareStorage();
  const lines = fs.readFileSync(eventsPath, 'utf8').split('\n').filter(Boolean);
  return lines.slice(-limit).map(line => JSON.parse(line));
}

function appendEvents(events) {
  if (!events.length) return;
  const existing = fs.readFileSync(eventsPath, 'utf8').split('\n').filter(Boolean);
  const known = new Set(existing);
  const merged = existing.slice();
  for (const event of events) {
    const line = JSON.stringify({title: String(event.title ?? ''), text: String(event.text ?? ''), time: String(event.time ?? new Date().toISOString())});
    if (!known.has(line)) { known.add(line); merged.push(line); }
  }
  const temporary = `${eventsPath}.${process.pid}.tmp`;
  fs.writeFileSync(temporary, merged.join('\n') + (merged.length ? '\n' : ''), {mode: 0o600});
  fs.renameSync(temporary, eventsPath);
}

function childEnv() {
  const env = {...process.env};
  delete env.LD_LIBRARY_PATH;
  delete env.LD_PRELOAD;
  return env;
}

function rea(tool, args) {
  const result = spawnSync(cli, ['godmode', 'call-tool', 'org.taku.rea', tool, JSON.stringify(args)], {encoding: 'utf8', timeout: 90000, env: childEnv()});
  if (result.error) throw new Error(result.error.message);
  const output = (result.stdout || '').trim();
  if (!output) throw new Error(`empty response from ${tool}: ${(result.stderr || '').trim()}`);
  let envelope;
  try { envelope = JSON.parse(output); } catch { throw new Error(`invalid response from ${tool}: ${output.slice(0, 200)}`); }
  const payload = envelope?.result?.content?.[0]?.text;
  if (envelope?.result?.isError) throw new Error(payload || `${tool} failed`);
  if (!payload) return {};
  try { return JSON.parse(payload); } catch { return {text: payload}; }
}

function wechatPid() {
  const result = spawnSync('pidof', ['com.tencent.mm'], {encoding: 'utf8', timeout: 10000});
  const pid = Number.parseInt((result.stdout || '').trim().split(/\s+/)[0], 10);
  return Number.isInteger(pid) && pid > 0 ? pid : null;
}

async function startMonitor() {
  prepareStorage();
  if (active?.sessionId) return {running: true, pid: active.pid, sessionId: active.sessionId, alreadyRunning: true, eventsPath};
  const pid = wechatPid();
  if (!pid) throw new Error('WeChat main process com.tencent.mm is not running');
  const session = rea('start_frida_session', {mode: 'attach', device_id: 'local', pid});
  if (!session.session_id) throw new Error('REA did not return a session id');
  try {
    rea('load_frida_script', {session_id: session.session_id, source_kind: 'file', path: hookPath});
  } catch (error) {
    try { rea('close_frida_session', {session_id: session.session_id}); } catch {}
    throw error;
  }
  active = {sessionId: session.session_id, pid};
  return {running: true, pid, sessionId: session.session_id, hook: 'NotificationManager.notify', eventsPath};
}

function syncEvents() {
  if (!active?.sessionId) return;
  try {
    const status = rea('frida_session_status', {session_id: active.sessionId});
    const messages = status?.messages ?? [];
    const events = [];
    for (const message of messages) {
      const payload = message?.payload;
      if (!payload || typeof payload !== 'object') continue;
      if (payload.kind === 'capture_error') lastCaptureError = String(payload.error ?? 'capture error');
      if (payload.kind === 'event') events.push(payload);
    }
    appendEvents(events);
  } catch (error) { lastCaptureError = String(error.message || error); }
}

async function stopMonitor() {
  if (!active?.sessionId) return {running: false};
  const sessionId = active.sessionId;
  active = null;
  try { rea('close_frida_session', {session_id: sessionId}); } catch (error) { lastCaptureError = String(error.message || error); }
  return {running: false, sessionId};
}

async function callTool(name, args) {
  if (!args || typeof args !== 'object' || Array.isArray(args)) throw new Error('arguments must be an object');
  if (name === 'start_monitor' && Object.keys(args).length === 0) return startMonitor();
  if (name === 'stop_monitor' && Object.keys(args).length === 0) return stopMonitor();
  if (name === 'read_events' && Object.keys(args).every(key => key === 'limit')) {
    const limit = args.limit === undefined ? 100 : args.limit;
    if (!Number.isInteger(limit) || limit < 1 || limit > 1000) throw new Error('limit must be an integer from 1 to 1000');
    syncEvents();
    return {running: !!active, sessionId: active?.sessionId ?? null, events: readEvents(limit), lastCaptureError};
  }
  throw new Error('unknown tool or invalid arguments');
}

async function handle(request) {
  if (request.id === undefined) return;
  if (request.method === 'initialize') return {jsonrpc: '2.0', id: request.id, result: {protocolVersion: '2024-11-05', capabilities: {tools: {}}, serverInfo: {name: 'taku-wechat-monitor', version: '1.1.0'}}};
  if (request.method === 'tools/list') return {jsonrpc: '2.0', id: request.id, result: {tools}};
  if (request.method !== 'tools/call') return {jsonrpc: '2.0', id: request.id, error: {code: -32601, message: 'Method not found'}};
  try {
    const value = await callTool(request.params?.name, request.params?.arguments ?? {});
    return {jsonrpc: '2.0', id: request.id, result: {content: [{type: 'text', text: JSON.stringify(value)}]}};
  } catch (error) {
    return {jsonrpc: '2.0', id: request.id, result: {isError: true, content: [{type: 'text', text: String(error.message || error)}]}};
  }
}

let queue = Promise.resolve();
readline.createInterface({input: process.stdin}).on('line', line => {
  queue = queue.then(async () => {
    try {
      const response = await handle(JSON.parse(line));
      if (response) process.stdout.write(JSON.stringify(response) + '\n');
    } catch { process.stdout.write(JSON.stringify({jsonrpc: '2.0', id: null, error: {code: -32700, message: 'Invalid JSON'}}) + '\n'); }
  });
});
