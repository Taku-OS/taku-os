// SPDX-License-Identifier: Apache-2.0
import {AUDIO_METHODS, validateAudioParams} from './audio-methods.mjs';
import {readFile} from 'node:fs/promises';
import {validateRequest, response, failure, TakuError} from './protocol.mjs';
const json = async path => JSON.parse(await readFile(new URL(path, import.meta.url), 'utf8'));
export class TakuFacade {
  constructor(transport) { this.transport = transport; }
  async handle(request) {
    const id = typeof request?.id === 'string' && request.id.length > 0 && request.id.length <= 128 ? request.id : 'invalid';
    try {
      validateRequest(request);
      if(AUDIO_METHODS.includes(request.method)) {
        validateAudioParams(request.method,request.params);
        if(!this.transport) throw new TakuError('DEVICE_REQUIRED','Provide Android transport');
        return response(id, await this.transport.call(request.method,request.params));
      }
      let result;
      switch (request.method) {
        case 'methods.list': result = await json('./methods.json'); break;
        case 'extensions.list': result = await json('../spec/extensions.json'); break;
        case 'capabilities.list':
          try { result = await json('../spec/capabilities.json'); }
          catch { throw new TakuError('INVENTORY_UNAVAILABLE', 'Capability inventory has not been generated or is invalid'); }
          break;
        case 'system.info': case 'displays.status': case 'tasks.status':
          if (!this.transport) throw new TakuError('DEVICE_REQUIRED', 'Provide Android transport');
          result = await this.transport.call(request.method); break;
        default: throw new TakuError('METHOD_NOT_FOUND', 'Method is not implemented by current core');
      }
      return response(id, result);
    } catch (error) { return failure(id, error); }
  }
}
