// SPDX-License-Identifier: Apache-2.0
import {randomUUID} from 'node:crypto';
import {PROTOCOL, TakuError} from '../core/protocol.mjs';
import {TakuFacade} from '../core/facade.mjs';
import {AndroidShell} from '../adapters/android-shell.mjs';
export class TakuClient {
  constructor(options = {}) { this.facade = new TakuFacade(options.transportInstance ?? (options.transport ? new AndroidShell(options) : undefined)); }
  audioEvents() {
    if(!this.facade.transport?.audioEvents) throw new TakuError('UNSUPPORTED_TRANSPORT','Transport does not provide audio events');
    return this.facade.transport.audioEvents();
  }
  close() { this.facade.transport?.close?.(); }
  async call(method, params = {}) {
    const answer = await this.facade.handle({protocol: PROTOCOL, type: 'request', id: randomUUID(), method, params});
    if (!answer.ok) throw new TakuError(answer.error.code, answer.error.message, answer.error.retryable);
    return answer.result;
  }
}
