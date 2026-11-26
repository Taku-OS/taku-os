/** What the thinking row says and shows. Pure, so it is tested without a renderer. */
import type {Thinking} from '../model/session';
import {chatText} from './chatText.ts';

export interface ThinkingRun {text: string; strong: boolean}

/**
 * Reasoning summaries set their headings in bold and are otherwise prose. Only that one mark is
 * read, so the quiet text never shows stray asterisks; a heading still being written counts as bold.
 */
export function thinkingRuns(text: string): ThinkingRun[] {
  const runs: ThinkingRun[] = [];
  text.split('**').forEach((piece, index) => {
    if (piece) runs.push({text: piece, strong: index % 2 === 1});
  });
  return runs;
}

/**
 * The end of [runs], at most [limit] characters: the live window shows only the last few lines,
 * so it lays out only the last few hundred characters however long the reasoning grows.
 */
export function tailRuns(runs: readonly ThinkingRun[], limit: number): ThinkingRun[] {
  const tail: ThinkingRun[] = [];
  let room = limit;
  for (let index = runs.length - 1; index >= 0 && room > 0; index--) {
    const run = runs[index];
    if (run.text.length <= room) { tail.unshift(run); room -= run.text.length; continue; }
    let start = run.text.length - room;
    // Never open on the second half of a surrogate pair.
    const code = run.text.charCodeAt(start);
    if (code >= 0xdc00 && code <= 0xdfff) start++;
    if (start < run.text.length) tail.unshift({text: run.text.slice(start), strong: run.strong});
    room = 0;
  }
  return tail;
}

/** "21s", "1m 05s" / "21 秒", "1 分 05 秒". Under a second still reads as one. */
export function thinkingDuration(ms: number, language: 'en' | 'zh'): string {
  const total = Math.max(1, Math.round(ms / 1000)), minutes = Math.floor(total / 60), seconds = total % 60;
  if (language === 'zh') return minutes ? `${minutes} 分 ${String(seconds).padStart(2, '0')} 秒` : `${seconds} 秒`;
  return minutes ? `${minutes}m ${String(seconds).padStart(2, '0')}s` : `${seconds}s`;
}

export interface ThinkingHeader {
  /** "Thinking", "Thought for 21s" or, when no time is known, "Thought process". */
  label: string;
  /** The live count beside "Thinking", once a whole second has passed and the start is known. */
  elapsed: string;
  /** Finished reasoning with text opens to show it; live reasoning shows its own last lines. */
  expandable: boolean;
  /** What a tap does, for screen readers; empty when a tap does nothing. */
  hint: (expanded: boolean) => string;
}

/** [now] is the device clock (ms), the same clock [Thinking.startedAtMs] was read from. */
export function thinkingHeader(thinking: Thinking, language: 'en' | 'zh', now: number): ThinkingHeader {
  const t = chatText(language);
  const expandable = !thinking.active && thinking.text.length > 0;
  const hint = (expanded: boolean) => expandable ? t(expanded ? 'hideThinking' : 'showThinking') : '';
  if (thinking.active) {
    const elapsedMs = thinking.startedAtMs === undefined ? -1 : (thinking.durationMs ?? 0) + now - thinking.startedAtMs;
    return {label: t('thinking'), elapsed: elapsedMs >= 1000 ? thinkingDuration(Math.floor(elapsedMs / 1000) * 1000, language) : '', expandable, hint};
  }
  const duration = thinking.durationMs;
  return {label: duration === undefined ? t('thoughtProcess') : t('thoughtFor').replace('{duration}', () => thinkingDuration(duration, language)),
    elapsed: '', expandable, hint};
}
