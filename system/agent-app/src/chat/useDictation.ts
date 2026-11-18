/**
 * The chat composer's dictation (Figma 1-5): the microphone starts recording, Stop ends it and
 * only a successful outcome joins the draft, where the Send button takes over. The Engine
 * operation identity keeps late terminal snapshots from an earlier recording out of a newer run.
 * Leaving the screen cancels a recording; nothing is sent by voice alone.
 */
import {useCallback, useEffect, useRef, useState} from 'react';
import {cancelVoice, finishVoice, onVoice, startVoice, voiceAvailable, type VoiceSnapshot} from '../native/voice';
import {dictationResult, failureOf, latestDictationSnapshot, type DictationFailure} from './dictationText';

export {appendDictation, type DictationFailure} from './dictationText';

export type DictationPhase = 'idle' | 'starting' | 'recording' | 'finishing';

type DictationRun = {
  generation: number;
  operationId: number | null;
  pending: Map<number, VoiceSnapshot>;
  snapshot?: VoiceSnapshot;
  stopping: boolean;
};

export type Dictation = {
  phase: DictationPhase;
  transcript: string;
  levels: number[];
  start(): void;
  stop(): void;
  cancel(): void;
};

export function useDictation(language: 'en' | 'zh', onHeard: (text: string) => void, onFailure: (reason: DictationFailure) => void): Dictation {
  const [phase, setPhase] = useState<DictationPhase>('idle');
  const [transcript, setTranscript] = useState('');
  const [levels, setLevels] = useState<number[]>([]);
  const phaseRef = useRef<DictationPhase>('idle');
  const generation = useRef(0);
  const run = useRef<DictationRun | null>(null);
  const heard = useRef(onHeard), failed = useRef(onFailure);
  useEffect(() => { heard.current = onHeard; failed.current = onFailure; }, [onHeard, onFailure]);
  const move = useCallback((next: DictationPhase) => { phaseRef.current = next; setPhase(next); }, []);

  const reset = useCallback(() => {
    run.current = null;
    move('idle'); setTranscript(''); setLevels([]);
  }, [move]);

  const accept = useCallback((incoming: VoiceSnapshot) => {
    const current = run.current;
    if (!current || incoming.operationId !== current.operationId) return;
    const s = latestDictationSnapshot(current.snapshot, incoming);
    current.snapshot = s;
    if (s.phase === 'Idle') { reset(); return; }
    setTranscript(s.transcript);
    setLevels(s.phase === 'Capturing' && !current.stopping ? s.levels : []);
    if (s.phase === 'Capturing' && !current.stopping) move('recording');
    else if (s.phase === 'Finalizing') move('finishing');
    if (s.phase !== 'Closed') return;
    const result = dictationResult(s.outcome);
    reset();
    if (result && 'text' in result) { if (result.text.trim()) heard.current(result.text); }
    else if (result) failed.current(result.reason);
  }, [move, reset]);

  const cancel = useCallback(() => {
    const current = run.current;
    if (!current) return;
    generation.current++;
    reset();
    if (current.operationId != null) void cancelVoice(current.operationId).catch(() => {});
  }, [reset]);

  useEffect(() => {
    const subscription = onVoice((s: VoiceSnapshot) => {
      const current = run.current;
      if (!current || s.operationId == null) return;
      if (current.operationId == null) {
        current.pending.set(s.operationId, latestDictationSnapshot(current.pending.get(s.operationId), s));
      } else {
        accept(s);
      }
    });
    return () => {
      subscription.remove();
      cancel();
    };
  }, [accept, cancel]);

  const start = useCallback(() => {
    if (phaseRef.current !== 'idle') return;
    if (!voiceAvailable()) { failed.current('unavailable'); return; }
    const current: DictationRun = {generation: ++generation.current, operationId: null, pending: new Map(), stopping: false};
    run.current = current;
    setTranscript(''); setLevels([]);
    move('starting');
    void startVoice(language === 'zh' ? 'zh-CN' : 'en-US').then(started => {
      if (generation.current !== current.generation || run.current !== current) {
        void cancelVoice(started.operationId).catch(() => {});
        return;
      }
      current.operationId = started.operationId;
      const pending = current.pending.get(started.operationId);
      current.pending.clear();
      accept(pending ? latestDictationSnapshot(started, pending) : started);
      if (run.current === current && current.stopping) void finishVoice(started.operationId).catch(() => {});
    }, (error: {code?: string}) => {
      if (generation.current !== current.generation || run.current !== current) return;
      reset();
      failed.current(failureOf(error?.code));
    });
  }, [language, move, accept, reset]);

  const stop = useCallback(() => {
    const current = run.current;
    if (!current || (phaseRef.current !== 'starting' && phaseRef.current !== 'recording')) return;
    current.stopping = true;
    move('finishing'); setLevels([]);
    if (current.operationId != null) void finishVoice(current.operationId).catch(() => {});
  }, [move]);
  return {phase, transcript, levels, start, stop, cancel};
}
