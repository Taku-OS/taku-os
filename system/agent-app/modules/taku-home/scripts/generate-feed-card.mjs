#!/usr/bin/env node
import {execFile} from 'node:child_process';
import {Buffer} from 'node:buffer';
import {readFile} from 'node:fs/promises';
import {join} from 'node:path';
import {parseArgs, TextDecoder} from 'node:util';

const MAX_REQUEST_BYTES = 96000;
const ADB_TIMEOUT_MS = 120000;
const REMOTE_PROGRAM = 'IFS= read -r feed_request || exit 1\nexec content call --uri content://org.agentos.agent.home-feed --method feed/generate --arg "$feed_request"';
const USAGE = `Usage:
  node system/agent-app/modules/taku-home/scripts/generate-feed-card.mjs --serial SERIAL --prompt TEXT [--adb PATH]
  node system/agent-app/modules/taku-home/scripts/generate-feed-card.mjs --serial SERIAL --json-file FILE [--adb PATH]
  node system/agent-app/modules/taku-home/scripts/generate-feed-card.mjs --help

Supply exactly one of --prompt and --json-file. FILE must contain the full inner
UTF-8 taku.feed-card/1 JSON object, without a request wrapper. The provider checks
the card schema and creates a new card. Supplied strings and JSON values are sent
without modification. Requests over ${MAX_REQUEST_BYTES} compact UTF-8 bytes are rejected.

adb defaults to ADB, then platform-tools/adb under ANDROID_SDK_ROOT or ANDROID_HOME,
then adb on PATH. --adb overrides these defaults. The caller does not prepare the
runtime or retry a failed call. A timeout or disconnect leaves the outcome unknown.

Success prints {"ok":true,"result":{"id":"...","replaced":false}}.
Errors go to stderr and exit nonzero. Provider errors include their error code;
provider messages and raw device output are omitted to avoid exposing input data.`;

class CallerError extends Error {}

function parseOptions() {
  let parsed;
  try {
    parsed = parseArgs({
      options: {
        serial: {type: 'string'},
        prompt: {type: 'string'},
        'json-file': {type: 'string'},
        adb: {type: 'string'},
        help: {type: 'boolean'},
      },
      strict: true,
      allowPositionals: false,
      tokens: true,
    });
  } catch {
    throw new CallerError('Invalid command line. Use --help for usage.');
  }
  if (parsed.values.help) return null;
  if (new Set(parsed.tokens.map(token => token.name)).size !== parsed.tokens.length) {
    throw new CallerError('Supply each option only once. Use --help for usage.');
  }
  const options = parsed.values;
  if (!options.serial || !/^[A-Za-z0-9._:-]+$/.test(options.serial)) {
    throw new CallerError('Supply an explicit valid --serial. Use --help for usage.');
  }
  if (Object.hasOwn(options, 'prompt') === Object.hasOwn(options, 'json-file')) {
    throw new CallerError('Supply exactly one of --prompt and --json-file.');
  }
  if (Object.hasOwn(options, 'prompt') && !options.prompt.trim()) {
    throw new CallerError('--prompt must contain non-whitespace text.');
  }
  if (Object.hasOwn(options, 'json-file') && !options['json-file']) {
    throw new CallerError('--json-file requires a nonempty file path.');
  }
  if (Object.hasOwn(options, 'adb') && !options.adb) {
    throw new CallerError('--adb requires a nonempty executable path.');
  }
  return options;
}

async function cardRequest(path) {
  let bytes;
  try {
    bytes = await readFile(path);
  } catch {
    throw new CallerError('Cannot read --json-file. Supply an existing readable file.');
  }
  let text;
  let card;
  try {
    text = new TextDecoder('utf-8', {fatal: true}).decode(bytes);
    card = JSON.parse(text);
  } catch {
    throw new CallerError('--json-file must contain valid UTF-8 JSON.');
  }
  if (!card || typeof card !== 'object' || Array.isArray(card) || card.schema !== 'taku.feed-card/1') {
    throw new CallerError('--json-file must contain the inner taku.feed-card/1 object, without a request wrapper.');
  }
  // Removing only JSON whitespace preserves numeric values beyond JavaScript's safe integer range.
  const compact = text.replace(/("(?:[^"\\]|\\.)*")|[ \t\r\n]+/g, (match, string) => string ?? '');
  return `{"card":${compact}}`;
}

function decodeResult(stdout) {
  const match = /^Result: Bundle\[\{result=([^\r\n]*)\}\]\r?$/m.exec(stdout);
  let response;
  try {
    response = match ? JSON.parse(match[1]) : null;
  } catch {
    throw new CallerError('Unreadable feed/generate response. Outcome unknown; inspect feed/list before calling again.');
  }
  if (!response || typeof response !== 'object' || typeof response.ok !== 'boolean') {
    throw new CallerError('Missing feed/generate result. Outcome unknown; inspect feed/list before calling again.');
  }
  if (!response.ok) {
    const code = typeof response.code === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(response.code)
      ? ` (${response.code})` : '';
    throw new CallerError(`feed/generate returned ok:false${code}.`);
  }
  const result = response.result;
  if (!result || typeof result.id !== 'string' || !/^[A-Za-z0-9._:-]{1,96}$/.test(result.id) || result.replaced !== false) {
    throw new CallerError('Unexpected feed/generate success result. Outcome unknown; inspect feed/list before calling again.');
  }
  return {ok: true, result: {id: result.id, replaced: false, ...(typeof result.model === 'string' ? {model: result.model} : {})}};
}

function generate(adb, serial, request) {
  return new Promise((resolve, reject) => {
    const child = execFile(adb, ['-s', serial, 'shell', '-T', REMOTE_PROGRAM], {
      encoding: 'utf8',
      timeout: ADB_TIMEOUT_MS,
      killSignal: 'SIGKILL',
      maxBuffer: 1024 * 1024,
    }, (error, stdout) => {
      if (error) {
        if (error.code === 'ENOENT' || error.code === 'EACCES') {
          reject(new CallerError('Cannot start adb. Supply an executable with --adb or configure adb on PATH.'));
        } else {
          reject(new CallerError('adb failed or timed out. Outcome unknown; inspect feed/list before calling again. No retry was attempted.'));
        }
        return;
      }
      try {
        resolve(decodeResult(stdout));
      } catch (error) {
        reject(error);
      }
    });
    child.stdin.on('error', () => {
      reject(new CallerError('adb input disconnected. Outcome unknown; inspect feed/list before calling again. No retry was attempted.'));
    });
    child.stdin.end(`${request}\n`);
  });
}

async function main() {
  const options = parseOptions();
  if (!options) {
    console.log(USAGE);
    return;
  }
  const request = Object.hasOwn(options, 'prompt')
    ? JSON.stringify({prompt: options.prompt})
    : await cardRequest(options['json-file']);
  if (Buffer.byteLength(request, 'utf8') > MAX_REQUEST_BYTES) {
    throw new CallerError(`Request exceeds the ${MAX_REQUEST_BYTES}-byte Feed generation limit. No device call was made.`);
  }
  const sdk = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME;
  const adb = options.adb || process.env.ADB || (sdk ? join(sdk, 'platform-tools', 'adb') : 'adb');
  console.log(JSON.stringify(await generate(adb, options.serial, request)));
}

main().catch(error => {
  console.error(`generate-feed-card: ${error instanceof CallerError ? error.message : 'Unexpected caller failure; no automatic retry was attempted.'}`);
  process.exitCode = 1;
});
