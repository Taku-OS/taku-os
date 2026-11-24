'use strict';

const { spawnSync } = require('node:child_process');
const { randomUUID } = require('node:crypto');
const { readFileSync, readSync, writeSync } = require('node:fs');
const { constants } = require('node:os');
const { join } = require('node:path');
const { TextDecoder } = require('node:util');

const MAX_INPUT_BYTES = 16 * 1024;
const REQUIRED_FIELDS = ['mode', 'action', 'parameters'];
const FIELDS = [...REQUIRED_FIELDS, 'requestId'];

function fail(code, status = 2) {
  process.stderr.write(`REQUEST_FAIL ${code}\n`);
  process.exitCode = status;
}

function isArgument(value) {
  return typeof value === 'string' && !value.includes('\0');
}

function readRequest() {
  const buffer = Buffer.alloc(MAX_INPUT_BYTES + 1);
  let length = 0;
  while (length < buffer.length) {
    const count = readSync(0, buffer, length, buffer.length - length, null);
    if (count === 0) break;
    length += count;
  }
  if (length > MAX_INPUT_BYTES) throw new Error();
  const request = JSON.parse(new TextDecoder('utf-8', { fatal: true })
    .decode(buffer.subarray(0, length)));
  if (request === null || typeof request !== 'object' || Array.isArray(request)
      || !Object.keys(request).every((field) => FIELDS.includes(field))
      || !REQUIRED_FIELDS.every((field) => Object.hasOwn(request, field))
      || !['check', 'send', 'test'].includes(request.mode)
      || !isArgument(request.action)
      || (Object.hasOwn(request, 'requestId') && !isArgument(request.requestId))
      || !Array.isArray(request.parameters) || !request.parameters.every(isArgument)) {
    throw new Error();
  }
  if (!Object.hasOwn(request, 'requestId')) {
    const catalog = JSON.parse(readFileSync(join(__dirname, '..', 'capabilities.json'), 'utf8'));
    const actions = catalog?.actions;
    if (actions === null || typeof actions !== 'object' || Array.isArray(actions)
        || !Object.hasOwn(actions, request.action)
        || typeof actions[request.action]?.prefix !== 'string'
        || !/^[a-z][a-z0-9_-]{0,63}$/.test(actions[request.action].prefix)) {
      throw new Error();
    }
    request.requestId = `${actions[request.action].prefix}-${randomUUID()}`;
  }
  return request;
}

function main() {
  if (typeof process.getuid !== 'function' || process.getuid() !== 0) {
    fail('ROOT_REQUIRED');
    return;
  }
  if (process.argv.length !== 2) {
    fail('STDIN_ONLY');
    return;
  }
  let request;
  try {
    request = readRequest();
  } catch {
    fail('INVALID_REQUEST');
    return;
  }
  let result;
  try {
    writeSync(1, `${JSON.stringify({ requestId: request.requestId })}\n`);
    result = spawnSync('/system/bin/sh', [
      join(__dirname, 'control.sh'), request.mode, request.action, request.requestId,
      ...request.parameters,
    ], { shell: false, stdio: ['ignore', 'inherit', 'inherit'] });
  } catch {
    fail('RESULT_UNKNOWN_NO_RETRY', 1);
    return;
  }
  if (result.error || result.signal || !Number.isInteger(result.status)) {
    const signalNumber = constants.signals[result.signal];
    fail('RESULT_UNKNOWN_NO_RETRY', Number.isInteger(result.status) ? result.status
      : Number.isInteger(signalNumber) ? 128 + signalNumber : 1);
    return;
  }
  process.exitCode = result.status;
}

main();
