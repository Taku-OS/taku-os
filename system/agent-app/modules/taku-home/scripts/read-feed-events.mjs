#!/usr/bin/env node
import {spawn} from 'node:child_process';
import {Buffer} from 'node:buffer';
import {join} from 'node:path';
import {Transform} from 'node:stream';
import {pipeline} from 'node:stream/promises';
import {parseArgs} from 'node:util';

const EVENTS_URI = 'content://org.agentos.agent.home-feed/events';
const MAX_URI_BYTES = 16 * 1024;
const MAX_CONTROL_BYTES = 4096;
const MAX_DIAGNOSTIC_BYTES = 8192;
const CARD_ID = /^[A-Za-z0-9._:-]{1,96}$/;
const CURSOR = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}:(0|[1-9][0-9]*)$/;
const OPERATIONS = new Set(['action', 'publish', 'generate', 'remove', 'restore', 'pin', 'unpin', 'reorder-pins', 'retract', 'reset']);
const USAGE = `Usage:
  node system/agent-app/modules/taku-home/scripts/read-feed-events.mjs --serial SERIAL [OPTIONS]
  node system/agent-app/modules/taku-home/scripts/read-feed-events.mjs --help

Options:
  --after CURSOR       Exclude this cursor. Default: 0. Also accepts now.
  --follow             Replay, then wait for new events. Default: finite replay.
  --card-id ID         Match this card. Repeat for up to 128 IDs.
  --card-prefix TEXT   Match a literal, case-sensitive ID prefix.
  --origin gui|agent   Match the operation's entry point.
  --op OPERATION       action, publish, generate, remove, restore, pin, unpin,
                       reorder-pins, retract, or reset.
  --adb PATH           Select the adb executable.
  --help               Show this help.

--serial is required. --card-id and --card-prefix are mutually exclusive.
All options except --card-id can appear only once. Other filter dimensions
combine with AND. Repeated --card-id values combine with OR.

stdout contains the device's raw NDJSON bytes, including ready and checkpoint
frames. Output honors backpressure without accumulating complete event lines.
Safe, bounded adb diagnostics go to stderr. Missing ready/final checkpoint frames,
provider errors, failed adb, and failed output streams exit nonzero.
Ctrl-C exits 130 and closes adb. A closed stdout pipe closes adb and exits 0.

A follow connection has a 300-second lease. There is no automatic reconnect or
acknowledgement. To resume, pass your consumer's durably processed cursor to
--after. A stdout write and ready.through do not acknowledge business processing.
Process earlier matching events before saving checkpoint.through. Delivery can
repeat after a disconnect. Immediate notification requires a running listener.

adb defaults to ADB, then platform-tools/adb under ANDROID_SDK_ROOT or ANDROID_HOME,
then adb on PATH. --adb overrides these defaults.`;

class CallerError extends Error {}

class FeedFrames extends Transform {
  constructor(follow) {
    super();
    this.follow = follow;
    this.prefix = Buffer.alloc(MAX_CONTROL_BYTES);
    this.lineBytes = 0;
    this.openingHead = null;
    this.lastCheckpoint = null;
  }

  completeLine() {
    let frame;
    if (this.lineBytes <= MAX_CONTROL_BYTES) {
      try {
        frame = JSON.parse(this.prefix.toString('utf8', 0, this.lineBytes));
      } catch {
        throw new CallerError('Malformed event stream frame.');
      }
    }
    if (this.openingHead === null) {
      if (frame?.type !== 'ready' || frame.schema !== 'taku.feed-event-stream/1'
        || typeof frame.after !== 'string' || !CURSOR.test(frame.after)
        || typeof frame.through !== 'string' || !CURSOR.test(frame.through)
        || frame.follow !== this.follow || frame.leaseMillis !== 300000
        || !Number.isSafeInteger(frame.legacyImportedCount) || frame.legacyImportedCount < 0
        || typeof frame.legacyHistoryMayBeIncomplete !== 'boolean') {
        throw new CallerError('Missing or invalid opening ready frame.');
      }
      this.openingHead = frame.through;
    } else if (frame?.type === 'checkpoint') {
      if (typeof frame.through !== 'string' || !CURSOR.test(frame.through)) {
        throw new CallerError('Invalid checkpoint frame.');
      }
      this.lastCheckpoint = frame.through;
    } else {
      if (this.lineBytes <= MAX_CONTROL_BYTES && frame?.type !== 'event') {
        throw new CallerError('Unexpected event stream frame.');
      }
      this.lastCheckpoint = null;
    }
    this.lineBytes = 0;
  }

  _transform(chunk, encoding, callback) {
    try {
      let start = 0;
      while (start < chunk.length) {
        const newline = chunk.indexOf(10, start);
        const end = newline === -1 ? chunk.length : newline;
        const count = Math.min(end - start, MAX_CONTROL_BYTES - this.lineBytes);
        if (count > 0) chunk.copy(this.prefix, this.lineBytes, start, start + count);
        this.lineBytes = Math.min(MAX_CONTROL_BYTES + 1, this.lineBytes + end - start);
        if (newline === -1) break;
        this.completeLine();
        start = newline + 1;
      }
      callback(null, chunk);
    } catch (error) {
      callback(error);
    }
  }

  _flush(callback) {
    if (this.openingHead === null) {
      callback(new CallerError('No event stream ready frame was received.'));
    } else if (this.lineBytes !== 0 || this.lastCheckpoint === null
      || (!this.follow && this.lastCheckpoint !== this.openingHead)) {
      callback(new CallerError('Event stream ended without a complete final checkpoint.'));
    } else if (this.follow) {
      callback(new CallerError('Follow connection ended.'));
    } else {
      callback();
    }
  }
}

function adbDiagnostics(bytes, truncated) {
  const text = bytes.toString('utf8');
  const errors = text.split(/\r?\n/).filter(line => line.trim()
    && !/^\* daemon (?:not running; starting now at tcp:\d+|started successfully)(?: \*)?$/.test(line));
  if (!errors.length && !truncated) return null;
  const known = text.match(/\b(?:INVALID_ARGUMENT|INVALID_CURSOR|CURSOR_EPOCH_MISMATCH|CURSOR_AHEAD|STREAM_BUSY|STREAM_LEASE_EXPIRED|STREAM_SLOW_CONSUMER|STATE_READ_FAILED|STATE_WRITE_FAILED|EVENT_TOO_LARGE|FileNotFoundException|IOException|SecurityException|IllegalArgumentException|device offline|no devices\/emulators found|unauthorized)\b/g) ?? [];
  const summary = [...new Set(known)].slice(0, 4).join(', ');
  return summary ? `adb reported ${summary}` : 'adb reported an error; raw diagnostics were withheld';
}

function parseOptions() {
  let parsed;
  try {
    parsed = parseArgs({
      options: {
        serial: {type: 'string'},
        after: {type: 'string', default: '0'},
        follow: {type: 'boolean', default: false},
        'card-id': {type: 'string', multiple: true},
        'card-prefix': {type: 'string'},
        origin: {type: 'string'},
        op: {type: 'string'},
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
  const seen = new Set();
  for (const token of parsed.tokens) {
    if (token.name !== 'card-id' && seen.has(token.name)) {
      throw new CallerError('Supply each option except --card-id only once.');
    }
    seen.add(token.name);
  }
  const options = parsed.values;
  if (options.help) return null;
  if (!options.serial || !/^[A-Za-z0-9._:-]+$/.test(options.serial)) {
    throw new CallerError('Supply an explicit valid --serial. Use --help for usage.');
  }
  if (options.after !== '0' && options.after !== 'now' && !CURSOR.test(options.after)) {
    throw new CallerError('--after must be 0, now, or a complete UUID:decimalSequence cursor returned by this device.');
  }
  const ids = options['card-id'] ?? [];
  if (ids.length > 128 || ids.some(id => !CARD_ID.test(id))) {
    throw new CallerError('--card-id accepts up to 128 IDs of 1 to 96 letters, digits, dots, underscores, colons, or hyphens.');
  }
  if (Object.hasOwn(options, 'card-prefix')) {
    if (!CARD_ID.test(options['card-prefix'])) {
      throw new CallerError('--card-prefix requires 1 to 96 valid card ID characters.');
    }
    if (ids.length) throw new CallerError('--card-id and --card-prefix are mutually exclusive.');
  }
  if (Object.hasOwn(options, 'origin') && options.origin !== 'gui' && options.origin !== 'agent') {
    throw new CallerError('--origin must be gui or agent.');
  }
  if (Object.hasOwn(options, 'op') && !OPERATIONS.has(options.op)) {
    throw new CallerError('Unknown --op. Use --help for the supported operations.');
  }
  if (Object.hasOwn(options, 'adb') && !options.adb) {
    throw new CallerError('--adb requires a nonempty executable path.');
  }
  return options;
}

function eventUri(options) {
  const query = new URLSearchParams({after: options.after, follow: options.follow ? '1' : '0'});
  for (const id of options['card-id'] ?? []) query.append('cardId', id);
  if (Object.hasOwn(options, 'card-prefix')) query.set('cardIdPrefix', options['card-prefix']);
  if (options.origin) query.set('origin', options.origin);
  if (options.op) query.set('op', options.op);
  const uri = `${EVENTS_URI}?${query}`;
  if (Buffer.byteLength(uri, 'utf8') > MAX_URI_BYTES) {
    throw new CallerError(`Event URI exceeds ${MAX_URI_BYTES} UTF-8 bytes. No device call was made.`);
  }
  return uri;
}

function shellQuote(value) {
  return `'${value.replaceAll("'", "'\\''")}'`;
}

async function readEvents(adb, serial, uri, follow) {
  const child = spawn(adb, ['-s', serial, 'shell', '-T', 'content', 'read', '--uri', shellQuote(uri)], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const stderr = Buffer.alloc(MAX_DIAGNOSTIC_BYTES);
  let stderrBytes = 0;
  let stderrTruncated = false;
  child.stderr.on('data', chunk => {
    const count = Math.min(chunk.length, MAX_DIAGNOSTIC_BYTES - stderrBytes);
    chunk.copy(stderr, stderrBytes, 0, count);
    stderrBytes += count;
    stderrTruncated ||= count < chunk.length;
  });
  let startError;
  const closed = new Promise(resolve => {
    child.once('error', error => {
      startError = error;
      child.stdout.destroy(error);
    });
    child.once('close', (code, signal) => resolve({code, signal}));
  });
  let cancellationCode;
  let killTimer;
  const stop = code => {
    cancellationCode ??= code;
    child.stdout.destroy();
    child.kill('SIGTERM');
    if (!killTimer) {
      killTimer = setTimeout(() => child.kill('SIGKILL'), 1000);
      killTimer.unref();
    }
  };
  child.stderr.on('error', () => {
    stderrTruncated = true;
    stop(1);
  });
  const interrupt = () => stop(130);
  const terminate = () => stop(143);
  process.on('SIGINT', interrupt);
  process.on('SIGTERM', terminate);
  let outputError;
  let result;
  try {
    try {
      await pipeline(child.stdout, new FeedFrames(follow), process.stdout, {end: false});
    } catch (error) {
      if (cancellationCode === undefined) {
        outputError = error;
        stop(error.code === 'EPIPE' ? 0 : 1);
      }
    }
    result = await closed;
  } finally {
    clearTimeout(killTimer);
    process.removeListener('SIGINT', interrupt);
    process.removeListener('SIGTERM', terminate);
  }
  if (startError) {
    throw new CallerError('Cannot start adb. Supply an executable with --adb or configure adb on PATH.');
  }
  const diagnostic = adbDiagnostics(stderr.subarray(0, stderrBytes), stderrTruncated);
  const detail = diagnostic ? ` ${diagnostic}.` : '';
  if (cancellationCode !== undefined) {
    if (cancellationCode === 1) {
      const message = outputError instanceof CallerError ? outputError.message : 'Event transport failed.';
      throw new CallerError(`${message}${detail} Resume from your consumer's durably processed cursor.`);
    }
    return cancellationCode;
  }
  if (result.code !== 0) {
    const reason = result.signal ? `signal ${result.signal}` : `exit code ${result.code}`;
    throw new CallerError(`adb failed with ${reason}.${detail} Resume from your consumer's durably processed cursor.`);
  }
  if (diagnostic) {
    throw new CallerError(`${diagnostic}. Resume from your consumer's durably processed cursor.`);
  }
  return 0;
}

async function main() {
  const options = parseOptions();
  if (!options) {
    process.stdout.on('error', error => {
      if (error.code !== 'EPIPE') process.exitCode = 1;
    });
    process.stdout.write(`${USAGE}\n`);
    return;
  }
  const uri = eventUri(options);
  const sdk = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME;
  const adb = options.adb || process.env.ADB || (sdk ? join(sdk, 'platform-tools', 'adb') : 'adb');
  process.exitCode = await readEvents(adb, options.serial, uri, options.follow);
}

main().catch(error => {
  console.error(`read-feed-events: ${error instanceof CallerError ? error.message : 'Unexpected caller failure; no automatic reconnect was attempted.'}`);
  process.exitCode = 1;
});
