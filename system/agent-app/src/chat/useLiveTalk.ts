import {useCallback, useEffect, useState} from 'react';
import {requireOptionalNativeModule} from 'expo';

export type LiveTalkState = {
  voiceContinuous: boolean; voicePaused: boolean; threadId: string | null; error: string | null;
  voice: {phase: string; ready: boolean; listening: boolean; transcript: string; levels: number[]};
};
type Bridge = {
  liveState(): Promise<string>;
  liveAction(method: string, params: string): Promise<string>;
  addListener(event: 'onLiveTalk', listener: (event: {json: string}) => void): {remove(): void};
};
const native = requireOptionalNativeModule<Bridge>('TakuVoice');

export function useLiveTalk(threadId: string, loading: boolean, selectThread: (id: string) => void) {
  const [state, setState] = useState<LiveTalkState | null>(null);
  const action = useCallback(async (method: string, params = {}) => {
    if (!native) return;
    const response = JSON.parse(await native.liveAction(method, JSON.stringify(params)));
    if (!response.ok) throw new Error(response.message || response.code);
  }, []);
  const stop = useCallback(() => { void action('assistant/live-stop').catch(() => {}); }, [action]);
  useEffect(() => {
    if (!native) return;
    let mounted = true, revision = 0;
    const accept = (json: string) => {
      if (!mounted) return;
      const next: LiveTalkState = JSON.parse(json);
      setState(next);
    };
    const subscription = native.addListener('onLiveTalk', event => { revision++; accept(event.json); });
    void native.liveState().then(json => { if (revision === 0) accept(json); }).catch(() => {});
    // Android owns foreground lifetime: React's shared host briefly reports background
    // between HomeActivity and MainActivity during the same-recording handoff.
    return () => { mounted = false; subscription.remove(); };
  }, [stop]);
  useEffect(() => {
    // The first utterance lazily creates a thread for an empty chat. A mounted old
    // chat must neither navigate nor stop the desktop's pending native handoff.
    if (!loading && !threadId && state?.voiceContinuous && state.threadId) selectThread(state.threadId);
  }, [loading, state?.voiceContinuous, state?.threadId, threadId, selectThread]);
  return {
    state, active: state?.voiceContinuous ?? false,
    start: () => action('assistant/live-start', {threadId}), stop,
    toggleMic: () => action('assistant/live-toggle-mic'),
  };
}
