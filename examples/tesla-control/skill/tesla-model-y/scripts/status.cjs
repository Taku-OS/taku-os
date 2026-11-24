'use strict';

const fs = require('node:fs/promises');
const { join } = require('node:path');

const SKILL_DIR = join(__dirname, '..');
const APP_CACHE = '/data/user/0/com.teslamotors.tesla/cache';
const REQUEST_ID = /^([a-z][a-z0-9_-]{0,63})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/;
const FAILURE_RESULTS = new Set([
  'UNKNOWN_ERROR', 'TIMEOUT', 'UNSUPPORTED_COMMAND', 'BLUETOOTH_DISABLED',
  'BLE_SERVICE_DISCONNECTED', 'REQUEST_TARGETS_NON_SELECTED_VEHICLE', 'SOURCE_PUBLIC_KEY_NOT_FOUND',
]);
const MAX_FILE_BYTES = 8 * 1024 * 1024;

class StatusError extends Error {}

function unknown(diagnostic, matched = false) {
  return { appOutcome: 'unknown', appResult: null, diagnostic, matched };
}

function combineResults(results) {
  const matched = results.filter((result) => result.matched);
  if (matched.length === 0) return unknown('NO_MATCHING_TELEMETRY');
  const unique = new Map(matched.map((result) => [JSON.stringify(result), result]));
  return unique.size === 1 ? unique.values().next().value
    : unknown('CONFLICTING_TELEMETRY', true);
}

function parseResult(text, traceId, sourceContext) {
  const payloads = [];
  if (typeof text !== 'string' || !/^[0-9a-f]{32}$/.test(traceId)
      || typeof sourceContext !== 'string' || sourceContext.length === 0) {
    return unknown('INVALID_MATCH_CRITERIA');
  }
  const documents = text.trimStart().startsWith('{') ? [text]
    : text.split(/\r?\n/).flatMap((line) => {
      const match = line.match(/\[OwnerAPI\]\s+POST\b[^\r\n]*\/api\/1\/logs\s*=>\s*(\{.*\})\s*(?:\(\d+-byte body\))?\s*$/);
      return match ? [match[1]] : [];
    });
  for (const document of documents) {
    try {
      const value = JSON.parse(document);
      if (Array.isArray(value?.details?.batched)) payloads.push(...value.details.batched);
    } catch {}
  }
  const results = [];
  for (const event of payloads) {
    if (event?.type !== 'mobile-app-command-telemetry-native'
        || event.trace_id !== traceId || event.source_context !== sourceContext) continue;
    const result = typeof event.result === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(event.result)
      ? event.result : null;
    const item = {
      appOutcome: result === 'SUCCESS' ? 'succeeded'
        : FAILURE_RESULTS.has(result) ? 'failed' : 'unknown',
      appResult: result,
      trace_id: traceId,
      matched: true,
    };
    if (item.appOutcome === 'unknown') item.diagnostic = 'UNRECOGNIZED_APP_RESULT';
    if (['OWNER_API', 'BLUETOOTH'].includes(event.transport)) item.transport = event.transport;
    if (Number.isSafeInteger(event.command_status)) item.command_status = event.command_status;
    if (Number.isFinite(event.duration_ms) && event.duration_ms >= 0) item.duration_ms = event.duration_ms;
    if (typeof event.reason === 'string' && event.reason.length <= 96
        && /^(?:[A-Z_]+|[a-z]+(?: [a-z]+){0,12})$/.test(event.reason)) item.reason = event.reason;
    results.push(item);
  }
  return combineResults(results);
}

async function readBounded(path) {
  const info = await fs.lstat(path);
  if (!info.isFile() || info.size > MAX_FILE_BYTES) throw new StatusError('UNREADABLE_OR_OVERSIZED_FILE');
  const content = await fs.readFile(path);
  if (content.length > MAX_FILE_BYTES) throw new StatusError('OVERSIZED_FILE');
  return content.toString('utf8');
}

function field(text, name) {
  const values = text.split(/\r?\n/).filter((line) => line.startsWith(name + '='));
  if (values.length !== 1) throw new StatusError('INVALID_REQUEST_RECORD');
  return values[0].slice(name.length + 1);
}

async function requestContext(requestId, match) {
  const state = join(SKILL_DIR, 'state', 'flash-once.' + requestId);
  const [receipt, attempt, catalogText] = await Promise.all([
    readBounded(state + '.receipt'), readBounded(state + '.attempted/attempt.txt'),
    readBounded(join(SKILL_DIR, 'capabilities.json')),
  ]);
  const action = field(receipt, 'ACTION');
  const mode = field(receipt, 'MODE');
  if (!/^[a-z][a-z0-9_]{0,63}$/.test(action)
      || !['send', 'test'].includes(mode)
      || field(receipt, 'REQUEST_ID') !== requestId
      || field(attempt, 'request_id') !== requestId
      || field(attempt, 'action') !== action || field(attempt, 'mode') !== mode) {
    throw new StatusError('REQUEST_RECORD_MISMATCH');
  }
  const catalog = JSON.parse(catalogText);
  const definition = Object.hasOwn(catalog.actions || {}, action) ? catalog.actions[action] : null;
  if (catalog.schema !== 'taku.tesla-capabilities/1' || definition?.prefix !== match[1]
      || typeof definition.sourceContext !== 'string' || definition.sourceContext.length === 0) {
    throw new StatusError('ACTION_CONTEXT_UNAVAILABLE');
  }
  const summaries = receipt.split(/\r?\n/).flatMap((line) => {
    try {
      const value = JSON.parse(line);
      return value?.action === action && value.mode === mode ? [value] : [];
    } catch { return []; }
  });
  if (summaries.length !== 1 || summaries[0].broadcastSubmitted !== true
      || !['TRANSPORT_OWNER_API', 'TRANSPORT_BLUETOOTH'].includes(summaries[0].transport)) {
    throw new StatusError('SUBMITTED_REQUEST_DETAILS_UNAVAILABLE');
  }
  return { action, sourceContext: definition.sourceContext, traceId: match[2].replaceAll('-', ''),
    requestedTransport: summaries[0].transport };
}

async function scanDirectory(directory, accepts, context) {
  let entries;
  try {
    entries = await fs.readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') return unknown('NO_MATCHING_TELEMETRY');
    throw error;
  }
  const results = [];
  for (const entry of entries) {
    if (!entry.isFile() || !accepts(entry.name)) continue;
    try {
      const text = await readBounded(join(directory, entry.name));
      results.push(parseResult(text, context.traceId, context.sourceContext));
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
  }
  return combineResults(results);
}

async function scan(context) {
  const cached = await scanDirectory(join(APP_CACHE, 'files'),
    (name) => /^RemoteLogWorker-lookup-key-.+\.txt$/.test(name), context);
  if (cached.matched) return cached;
  return scanDirectory(join(APP_CACHE, 'logs/main'), (name) => name.endsWith('.txt'), context);
}

async function main() {
  const args = process.argv.slice(2);
  const once = args.includes('--once');
  const ids = args.filter((argument) => argument !== '--once');
  const match = ids.length === 1 ? ids[0].match(REQUEST_ID) : null;
  const output = { requestId: match ? ids[0] : null, action: null,
    appOutcome: 'unknown', appResult: null, vehicleOutcome: 'unverified' };
  if (!match || args.length !== (once ? 2 : 1)) {
    process.exitCode = 2;
    console.log(JSON.stringify({ ...output, diagnostic: 'INVALID_ARGUMENTS' }));
    return;
  }
  if (typeof process.getuid !== 'function' || process.getuid() !== 0) {
    process.exitCode = 2;
    console.log(JSON.stringify({ ...output, diagnostic: 'ROOT_REQUIRED' }));
    return;
  }
  try {
    const context = await requestContext(ids[0], match);
    output.action = context.action;
    output.requestedTransport = context.requestedTransport;
    const deadline = Date.now() + (once ? 0 : 20000);
    let result;
    do {
      result = await scan(context);
      if (result.matched || Date.now() >= deadline) break;
      await new Promise((resolve) => setTimeout(resolve, Math.min(1000, deadline - Date.now())));
    } while (true);
    const { matched, ...safeResult } = result;
    console.log(JSON.stringify({ ...output, ...safeResult }));
  } catch (error) {
    process.exitCode = 1;
    console.log(JSON.stringify({ ...output,
      diagnostic: error instanceof StatusError ? error.message : 'READ_OR_FORMAT_FAILED' }));
  }
}

module.exports = { parseResult };
if (require.main === module) main();
