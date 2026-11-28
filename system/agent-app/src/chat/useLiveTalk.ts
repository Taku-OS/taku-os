import {useCallback, useEffect, useRef, useState} from 'react';
import {requireOptionalNativeModule} from 'expo';

type Caption = {text: string; final: boolean};
export type LiveTalkState = {
  sessionId: string;
  sequence: number;
  generation: number;
  threadId: string | null;
  lifecycle: 'connecting' | 'active' | 'disconnected' | 'closed' | 'failed';
  provider: string | null;
  muted: boolean;
  capturing: boolean;
  speech: boolean;
  playback: {state: 'speaking' | 'stopped' | 'interrupted'; responseId: string | null};
  input: (Caption & {itemId: string}) | null;
  output: (Caption & {responseId: string; source: 'generated'}) | null;
  levels: number[];
  task: {requestId: string | null; taskId: string | null; turnId: string | null; state: string; version: number; error: string | null} | null;
  /** Shared screen or camera; `masked` means frames are withheld while the device is locked. */
  video?: {source: 'none' | 'screen' | 'camera'; active: boolean; masked: boolean; reason: string | null};
  error: string | null;
  /** The setup page that fixes `error`: `fal` when Live Talk needs a fal key. */
  setup?: 'fal' | null;
};
type Snapshot = {liveTalk: LiveTalkState | null};
type Bridge = {
  liveState(): Promise<string>;
  liveAction(method: string, params: string): Promise<string>;
  addListener(event: 'onLiveTalk', listener: (event: {json: string}) => void): {remove(): void};
};
const native = requireOptionalNativeModule<Bridge>('TakuVoice');

export function useLiveTalk(threadId: string, loading: boolean, selectThread: (id: string) => void) {
  const [state, setState] = useState<LiveTalkState | null>(null);
  const latest = useRef<LiveTalkState | null>(null);
  const accept = useCallback((snapshot: Snapshot) => {
    const next = snapshot?.liveTalk;
    if (!next || (latest.current && next.sequence <= latest.current.sequence)) return;
    latest.current = next;
    setState(next);
  }, []);
  const action = useCallback(async (method: string, params: object) => {
    if (!native) throw new Error('Live Talk is not available');
    const response = JSON.parse(await native.liveAction(method, JSON.stringify(params)));
    if (!response?.ok) throw Object.assign(new Error(response?.message || 'Live Talk did not respond'), {code: response?.code});
    accept(response.result);
  }, [accept]);
  const stopSession = useCallback((sessionId: string) => {
    void action('assistant/live-stop', {sessionId}).catch(() => {});
  }, [action]);
  useEffect(() => {
    if (!native) return;
    let mounted = true;
    const subscription = native.addListener('onLiveTalk', event => accept(JSON.parse(event.json)));
    void native.liveState().then(json => { if (mounted) accept(JSON.parse(json)); }).catch(() => {});
    return () => { mounted = false; subscription.remove(); };
  }, [accept]);
  const open = !!state && state.lifecycle !== 'closed';
  useEffect(() => {
    if (!loading && !threadId && open && state?.threadId) selectThread(state.threadId);
  }, [loading, open, state?.threadId, threadId, selectThread]);
  // Switching conversations keeps Live Talk running for its own thread; only an explicit stop ends it.
  const active = open && (!threadId || state?.threadId === threadId);
  return {
    state, active,
    start: () => action('assistant/live-start', {threadId}),
    stop: () => { if (active && state) stopSession(state.sessionId); },
    setVideo: (source: 'none' | 'screen' | 'camera') => state && active
      ? action('assistant/live-set-video', {sessionId: state.sessionId, source}) : Promise.resolve(),
    toggleMic: () => state && active
      ? action('assistant/live-set-mic', {sessionId: state.sessionId, muted: !state.muted}) : Promise.resolve(),
  };
}
