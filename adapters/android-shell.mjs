// SPDX-License-Identifier: Apache-2.0
import {AudioTransport} from './audio-transport.mjs';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {TakuError} from '../core/protocol.mjs';
import {AUDIO_METHODS} from '../core/audio-method-names.mjs';
const execute = promisify(execFile);
const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";
export class AndroidShell {
  constructor({transport = 'adb', adb = process.env.ADB ?? 'adb', serial = process.env.TAKU_OS_SERIAL, timeoutMs = 10000} = {}) {
    if (!['adb', 'device'].includes(transport)) throw new TakuError('INVALID_TRANSPORT', 'Use adb or device');
    if (transport === 'adb' && (!serial || !/^[A-Za-z0-9._:-]+$/.test(serial))) throw new TakuError('DEVICE_REQUIRED', 'Explicit --serial / TAKU_OS_SERIAL required');
    this.transport = transport; this.adb = adb; this.serial = serial; this.timeoutMs = timeoutMs;
  }
  audioTransport() { return this.audio ??= new AudioTransport(this); }
  audioEvents() { return this.audioTransport().events(); }
  close() { this.audio?.close(); }
  async call(method, params = {}) {
    if(AUDIO_METHODS.includes(method)) return this.audioTransport().call(method,params);
    if (!['system.info', 'displays.status', 'tasks.status'].includes(method)) throw new TakuError('METHOD_NOT_FOUND', 'Unsupported probe');
    const script = fileURLToPath(new URL('../core/device-probe.sh', import.meta.url));
    let command, args;
    if (this.transport === 'adb') {
      // Only static, checked-in probe + closed method name. No arbitrary user shell.
      const source = await readFile(script, 'utf8');
      command = this.adb; args = ['-s', this.serial, 'shell', 'sh -c ' + quote(source) + ' probe ' + quote(method)];
    } else { command = '/system/bin/sh'; args = [script, method]; }
    try {
      const {stdout} = await execute(command, args, {timeout: this.timeoutMs, maxBuffer: 1024 * 1024, encoding: 'utf8'});
      try { return JSON.parse(stdout.trim()); } catch { throw new TakuError('INVALID_RESPONSE', 'Device did not return JSON'); }
    } catch (error) {
      if (error instanceof TakuError) throw error;
      try { const remote = JSON.parse(error.stdout?.trim()); if (remote.code) throw new TakuError(remote.code, remote.message, remote.retryable); } catch (remote) { if (remote instanceof TakuError) throw remote; }
      if (error.killed) throw new TakuError('TIMEOUT', 'Android probe timed out', true);
      throw new TakuError('TRANSPORT_ERROR', 'Android transport failed; verify explicit device, adb authorization and shell access', true);
    }
  }
}
