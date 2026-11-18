// SPDX-License-Identifier: Apache-2.0
import {AUDIO_METHODS} from './audio-method-names.mjs';
export const PROTOCOL = 'taku/0.1';
export class TakuError extends Error {
  constructor(code, message, retryable = false) { super(message); this.code = code; this.retryable = retryable; }
}
export function validateRequest(request) {
  if (!request || typeof request !== 'object' || Array.isArray(request) || request.protocol !== PROTOCOL || request.type !== 'request' || typeof request.id !== 'string' || request.id.length < 1 || request.id.length > 128 || typeof request.method !== 'string' || !/^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$/.test(request.method) || !request.params || typeof request.params !== 'object' || Array.isArray(request.params)) throw new TakuError('INVALID_REQUEST', 'Expected taku/0.1 request with id, method and object params');
  if (Object.keys(request).some(key => !['protocol','type','id','method','params'].includes(key))) throw new TakuError('INVALID_REQUEST', 'Unknown request field');
  if (!AUDIO_METHODS.includes(request.method) && Object.keys(request.params).length) throw new TakuError('INVALID_PARAMS', 'Current methods take no params');
}
export function response(id, result) { return {protocol: PROTOCOL, type: 'response', id, ok: true, result}; }
export function failure(id, error) { return {protocol: PROTOCOL, type: 'response', id, ok: false, error: {code: error.code ?? 'INTERNAL_ERROR', message: error instanceof TakuError ? error.message : 'Unexpected implementation failure', retryable: error.retryable ?? false}}; }
