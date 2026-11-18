export const PREVIEW_REFRESH_MS = 2500;
export const PREVIEW_FRESH_MS = 5000;
export const PREVIEW_LOAD_TIMEOUT_MS = 30000;

export interface PreviewFrame {
  id: string;
  uri: string;
  requestedAt: number;
  loadedAt: number | null;
  width: number;
  height: number;
}

export interface PreviewState {
  visible: PreviewFrame | null;
  pending: PreviewFrame | null;
  failed: boolean;
  nextAt: number;
  sequence: number;
  inlineConsumed: boolean;
}

export type PreviewEvent =
  | {type: 'tick'; now: number; uri: string}
  | {type: 'loaded'; id: string; now: number; width: number; height: number}
  | {type: 'error'; id: string; now: number}
  | {type: 'hide'};

export function emptyPreview(): PreviewState {
  return {visible: null, pending: null, failed: false, nextAt: 0, sequence: 0, inlineConsumed: false};
}

export function previewReducer(state: PreviewState, event: PreviewEvent): PreviewState {
  if (event.type === 'hide') return {...emptyPreview(), sequence: state.sequence, inlineConsumed: state.inlineConsumed};
  if (event.type === 'loaded') {
    if (state.pending?.id !== event.id) return state;
    if (event.now - state.pending.requestedAt >= PREVIEW_LOAD_TIMEOUT_MS) {
      return {...state, visible: null, pending: null, failed: true, nextAt: event.now + PREVIEW_REFRESH_MS};
    }
    return {...state, visible: {...state.pending, loadedAt: event.now, width: event.width, height: event.height}, pending: null, failed: false};
  }
  if (event.type === 'error') {
    if (state.pending?.id !== event.id && state.visible?.id !== event.id) return state;
    return {...state, visible: null, pending: null, failed: true, nextAt: event.now + PREVIEW_REFRESH_MS};
  }
  if (!event.uri) return state;
  if (state.pending && event.now - state.pending.requestedAt >= PREVIEW_LOAD_TIMEOUT_MS) {
    return {...state, visible: null, pending: null, failed: true, nextAt: event.now + PREVIEW_REFRESH_MS};
  }
  let next = state;
  if (state.visible?.loadedAt !== null && state.visible && event.now - state.visible.loadedAt >= PREVIEW_FRESH_MS) {
    next = {...state, visible: null};
  }
  const inline = event.uri.startsWith('data:');
  // An inline image is one captured frame. Re-decoding the same bytes cannot renew freshness.
  if (next.pending || event.now < next.nextAt || inline && next.inlineConsumed) return next;
  const sequence = next.sequence + 1, id = `${event.now}-${sequence}`;
  const uri = inline ? event.uri : `${event.uri}${event.uri.includes('?') ? '&' : '?'}frame=${id}`;
  return {...next, failed: false, pending: {id, uri, requestedAt: event.now, loadedAt: null, width: 0, height: 0},
    sequence, inlineConsumed: inline, nextAt: event.now + PREVIEW_REFRESH_MS};
}
