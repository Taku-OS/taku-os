// SPDX-License-Identifier: Apache-2.0
import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {createInterface} from 'node:readline';
import {TakuError} from '../core/protocol.mjs';

/** One persistent Android process owns one authenticated socket connection and its sessions. */
export class AudioTransport {
  constructor({transport, adb, serial, timeoutMs = 10000}) {
    this.options = {transport, adb, serial, timeoutMs}; this.pending = new Map(); this.listeners = new Set(); this.child = null;
  }
  start() {
    if (this.child) return;
    const {transport, adb, serial} = this.options;
    const command = 'bridge=/system_ext/etc/taku-audio/taku-audio-bridge.jar; [ -f "$bridge" ] || bridge=/data/local/tmp/taku-audio-bridge.jar; CLASSPATH="$bridge" app_process /system/bin org.agentos.audio.AudioPipe';
    const child = transport === 'adb' ? spawn(adb, ['-s', serial, 'shell', command], {stdio:['pipe','pipe','pipe']}) :
      spawn('/system/bin/sh', ['-c',command], {stdio:['pipe','pipe','pipe']});
    this.child = child;
    const lines = createInterface({input:child.stdout});
    lines.on('line', line => {
      if(line.length > 128000) { this.stop(new TakuError('INVALID_RESPONSE','Audio response too large')); return; }
      let value; try { value = JSON.parse(line); } catch { this.stop(new TakuError('INVALID_RESPONSE','Audio bridge returned invalid JSON')); return; }
      if(value.type === 'response') {
        const pending = this.pending.get(value.id); if(!pending) return;
        this.pending.delete(value.id); clearTimeout(pending.timer);
        value.ok ? pending.resolve(value.result) : pending.reject(new TakuError(value.error?.code ?? 'AUDIO_ERROR',value.error?.message ?? 'Audio request failed',value.error?.retryable));
      } else if(value.type === 'event') for(const listener of [...this.listeners]) listener.push(value);
      else if(value.type === 'transportError') this.stop(new TakuError('TRANSPORT_ERROR','Android audio host is unavailable',true));
    });
    // stderr may contain Android diagnostics, never return it as a public response.
    child.stderr.resume();
    child.on('error', () => this.stop(new TakuError('TRANSPORT_ERROR','Cannot start Android audio bridge',true)));
    child.on('exit', () => { if(this.child === child) this.stop(new TakuError('TRANSPORT_CLOSED','Android audio bridge disconnected',true)); });
  }
  call(method, params) {
    this.start();
    const id = randomUUID();
    return new Promise((resolve,reject) => {
      const timer = setTimeout(() => {
        // The command might already have executed: drop the connection to cancel its sessions.
        this.stop(new TakuError('TIMEOUT','Audio command timed out; connection closed to cancel its sessions',true));
      },this.options.timeoutMs);
      this.pending.set(id,{resolve,reject,timer});
      this.child.stdin.write(JSON.stringify({id,method,params})+'\n', error => { if(error) this.stop(new TakuError('TRANSPORT_ERROR','Audio input disconnected',true)); });
    });
  }
  events() {
    const queue = []; let waiting; let failure; let done = false;
    const listener = {
      push: value => {
        if(done) return;
        if(waiting) { const next = waiting; waiting = null; next.resolve({value,done:false}); }
        else if(queue.length < 128) queue.push(value);
        else this.stop(new TakuError('CONSUMER_BACKLOG','Audio event consumer is too slow'));
      },
      end: error => { failure = error; done = true; if(waiting) { const next = waiting; waiting = null; error ? next.reject(error) : next.resolve({done:true}); } }
    };
    this.listeners.add(listener);
    return {
      [Symbol.asyncIterator]() { return this; },
      next: () => {
        if(queue.length) return Promise.resolve({value:queue.shift(),done:false});
        if(failure) return Promise.reject(failure);
        if(done) return Promise.resolve({done:true});
        return new Promise((resolve,reject) => { waiting = {resolve,reject}; });
      },
      return: () => { this.listeners.delete(listener);listener.end();return Promise.resolve({done:true}); }
    };
  }
  stop(error) {
    const child = this.child; this.child = null;
    if(child) { child.stdin.end(); child.kill(); }
    for(const pending of this.pending.values()) { clearTimeout(pending.timer);pending.reject(error ?? new TakuError('TRANSPORT_CLOSED','Audio transport closed')); }
    this.pending.clear();for(const listener of this.listeners) listener.end(error);this.listeners.clear();
  }
  close() { this.stop(); }
}
