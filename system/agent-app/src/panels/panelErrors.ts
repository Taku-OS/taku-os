import {record} from '../model/session';
import {chatText} from '../chat/chatText';

/** The gateway's error code, or a message when the failure carries none. */
export const panelErrorCode = (error: unknown): string => {
  const value = record(error).code;
  return typeof value === 'string' && value ? value : error instanceof Error ? error.message : 'UNAVAILABLE';
};

/** User-facing text for a Panel directory failure; [fallback] names the action that failed. */
export function panelErrorText(code: string, language: 'en' | 'zh', fallback: Parameters<ReturnType<typeof chatText>>[0] = 'panelsError'): string {
  const t = chatText(language);
  if (!code) return '';
  if (code === 'PANEL_CONTRACT_MISMATCH') return t('panelsUpdateRequired');
  if (code === 'PANEL_NOT_FOUND') return t('panelMissing');
  if (code === 'LIST_CHANGED' || code === 'IDEMPOTENCY_CONFLICT' || code === 'VERSION_CONFLICT') return t('panelStaleState');
  if (code === 'INVALID_ARGUMENT') return t('panelTitleInvalid');
  if (code === 'SOURCE_THREAD_NOT_FOUND') return t('panelSourceThreadUnavailable');
  return t(fallback);
}
