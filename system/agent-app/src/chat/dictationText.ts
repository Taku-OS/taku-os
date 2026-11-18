/** Pure pieces of the chat's dictation (Figma 1-5); see useDictation and DictationBar. */
import type {VoicePhase, VoiceSnapshot} from '../native/voice';

/** Engine and session codes → a short reason for the toast. */
export type DictationFailure = 'unavailable' | 'disabled' | 'busy';

export function failureOf(code: string | null | undefined): DictationFailure {
  if (code === 'POLICY_DISABLED' || code === 'POLICY_REVOKED' || code === 'CONSUMER_NOT_AUTHORIZED' || code === 'PERMISSION_DENIED') return 'disabled';
  if (code === 'RESOURCE_BUSY' || code === 'CAPTURE_SILENCED') return 'busy';
  return 'unavailable';
}

/** Chinese runs together; other scripts get a space. */
export function appendDictation(draft: string, heard: string): string {
  const text = heard.trim();
  if (!text) return draft;
  const base = draft.replace(/\s+$/, '');
  if (!base) return text;
  return /[㐀-鿿　-〿＀-￯]$/.test(base) || /^[㐀-鿿]/.test(text) ? base + text : `${base} ${text}`;
}

/** The outcome fields of a closed snapshot; `null` means it never reported how it ended. */
export type DictationOutcome =
  | {kind: 'Success'; text: string}
  | {kind: 'Failed'; code: string}
  | {kind: 'Cancelled'}
  | null;

/**
 * What the chat may do with a closed recognition. Only a `Success` outcome delivers, and it
 * delivers its own complete text: a nonempty preview on a failed or cancelled session never
 * reaches the draft.
 */
export function dictationResult(outcome: DictationOutcome): {text: string} | {reason: DictationFailure} | null {
  if (!outcome) return null;
  if (outcome.kind === 'Success') return {text: outcome.text};
  if (outcome.kind === 'Failed') return {reason: failureOf(outcome.code)};
  return null;
}

const phaseOrder: Record<VoicePhase, number> = {Preparing: 0, Capturing: 1, Finalizing: 2, Closed: 3, Idle: 4};

/** A start response and queued events can cross; neither may undo a terminal or capture state. */
export function latestDictationSnapshot(previous: VoiceSnapshot | undefined, incoming: VoiceSnapshot): VoiceSnapshot {
  return previous && phaseOrder[previous.phase] > phaseOrder[incoming.phase] ? previous : incoming;
}

/** Figma 1-5 resting heights of the six live bars (dp), scaled by the latest levels (0–1). */
const SHAPE = [8, 13, 6, 8, 11, 4];
const UNIT = 3;

export function barHeights(levels: number[]): number[] {
  return SHAPE.map((shape, i) => {
    const level = levels[levels.length - SHAPE.length + i];
    const scale = level === undefined ? 0.4 : 0.4 + level * 1.2;
    return Math.max(UNIT, Math.min(16, UNIT + (shape - UNIT) * scale));
  });
}
