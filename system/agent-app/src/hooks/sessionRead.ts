import {record, string, type Json} from '../model/session.ts';

/** Resume only by reading the existing thread; never submit a replacement request. */
export function sessionReadOptions(options: {
  attached: boolean;
  streamFailed: boolean;
  sideband: Json;
  cacheFirst: boolean;
  hydrate: boolean;
  ready: boolean;
  running: boolean;
}): {statusOnly: true} | {refresh: boolean} {
  const native = record(options.sideband.native);
  // A cold broker reports ready=false until a read RPC initializes the runtime.
  // Do not wait for ready here: status-only polls and subscriptions cannot initialize it.
  // The latest native active turn still prevents reloading over ongoing work.
  const recover = options.attached && options.streamFailed && !string(native.activeTurnId);
  const refresh = recover || !options.cacheFirst && options.hydrate && options.ready && !options.running;
  return options.attached && !refresh ? {statusOnly: true} : {refresh};
}
