/**
 * Typed access to `TakuVoice` (modules/taku-home): dictation through the shared Taku Audio
 * Engine, the same recogniser as the home's hold-to-talk. Absent on web, in tests and on images
 * without the engine.
 */
import {requireOptionalNativeModule} from 'expo';

type Subscription = {remove(): void};

/** The Engine's recognition phase for one operation; only `Closed` carries an outcome. */
export type VoicePhase = 'Idle' | 'Preparing' | 'Capturing' | 'Finalizing' | 'Closed';

/** How recognition ended. Only `Success` may put text in the draft. */
export type VoiceOutcome =
  | {kind: 'Success'; text: string}
  | {kind: 'Failed'; code: string; message: string | null}
  | {kind: 'Cancelled'; reason: string};

/** The fixed session facts the Engine reports for this operation. */
export type VoiceSession = {
  settingsScope: string | null;
  settingsRevision: number | null;
  requestedProfileId: string | null;
  profileId: string | null;
  language: string | null;
  processing: string | null;
  fallbackUsed: boolean;
  fallbackReason: string | null;
  maxCaptureMs: number | null;
  drainTimeoutMs: number | null;
  drainDeadlineMonotonicMs: number | null;
};

/** One recognition snapshot. `operationId` identifies the recording; `transcript` is the
 * complete successful text or the unsettled preview. */
export type VoiceSnapshot = {
  owner: string;
  threadId: string;
  active: boolean;
  operationId: number | null;
  sessionId: string | null;
  phase: VoicePhase;
  listening: boolean;
  ready: boolean;
  transcript: string;
  preview: string;
  levels: number[];
  /** Each entry is one measured PCM level (0..1) from the Engine, appended while capturing. */
  levelSource: 'pcm-rms';
  final: boolean;
  error: string | null;
  outcome: VoiceOutcome | null;
  session: VoiceSession | null;
};

/** The definitive identity and current state of the operation created by `start`. */
export type VoiceStartSnapshot = VoiceSnapshot & {operationId: number};

export type ReplySpeech = {
  threadId: string;
  replyId: string;
  phase: 'preparing' | 'speaking' | 'failed';
  error: string | null;
};

type SpeechEvent = {speech: ReplySpeech | null};

type TakuVoiceModule = {
  isAvailable(): boolean;
  openSettings(): Promise<void>;
  enterChat(owner: string, threadId: string): Promise<void>;
  leaveChat(owner: string): Promise<void>;
  start(owner: string, threadId: string): Promise<VoiceStartSnapshot>;
  speak(threadId: string, replyId: string, text: string): Promise<void>;
  stopSpeech(threadId: string, replyId: string): Promise<void>;
  finish(operationId: number): Promise<void>;
  cancel(operationId: number): Promise<void>;
  addListener(event: 'onVoice', listener: (snapshot: VoiceSnapshot) => void): Subscription;
  addListener(event: 'onReplySpeech', listener: (event: SpeechEvent) => void): Subscription;
};

const nativeModule = requireOptionalNativeModule<TakuVoiceModule>('TakuVoice');

export function voiceAvailable(): boolean {
  try { return nativeModule?.isAvailable() ?? false; } catch { return false; }
}

export async function openVoiceSettings(): Promise<void> {
  if (!nativeModule) throw new Error('Voice settings are not available');
  await nativeModule.openSettings();
}

export async function startVoice(owner: string, threadId: string): Promise<VoiceStartSnapshot> {
  if (!nativeModule) throw Object.assign(new Error('Voice input is not available'), {code: 'VOICE_UNAVAILABLE'});
  return nativeModule.start(owner, threadId);
}

export async function enterVoiceChat(owner: string, threadId: string): Promise<void> { await nativeModule?.enterChat(owner, threadId); }
export async function leaveVoiceChat(owner: string): Promise<void> { await nativeModule?.leaveChat(owner); }

export async function speakReply(threadId: string, replyId: string, text: string): Promise<void> {
  if (!nativeModule) throw new Error('Reply playback is not available');
  await nativeModule.speak(threadId, replyId, text);
}

export async function stopReplySpeech(threadId: string, replyId: string): Promise<void> {
  await nativeModule?.stopSpeech(threadId, replyId);
}

export function onReplySpeech(listener: (speech: ReplySpeech | null) => void): Subscription {
  return nativeModule?.addListener('onReplySpeech', event => listener(event.speech)) ?? {remove() {}};
}

export async function finishVoice(operationId: number): Promise<void> { await nativeModule?.finish(operationId); }

export async function cancelVoice(operationId: number): Promise<void> { await nativeModule?.cancel(operationId); }

export function onVoice(listener: (snapshot: VoiceSnapshot) => void): Subscription {
  return nativeModule?.addListener('onVoice', listener) ?? {remove() {}};
}
