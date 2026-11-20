export type DraftContent = {text: string; photo?: string};

export const CHAT_PHOTO_PREFIX = 'data:image/jpeg;base64,';
export const MAX_CHAT_PHOTO_LENGTH = 300000;
export function isChatPhoto(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > MAX_CHAT_PHOTO_LENGTH || !value.startsWith(CHAT_PHOTO_PREFIX)) return false;
  const base64 = value.slice(CHAT_PHOTO_PREFIX.length);
  return base64.length % 4 === 0 && /^[A-Za-z0-9+/]+={0,2}$/.test(base64);
}

export function matchesDraft(draft: DraftContent, request: DraftContent): boolean {
  return draft.text.trim() === request.text && draft.photo === request.photo;
}

export type PendingSubmission = DraftContent & {
  threadId: string;
  requestId: string;
  action: 'sendMessage' | 'steer' | 'continue';
  /** The panel this send updates. Native revalidates the source at the actual send. */
  panel?: {panelId: string; expectedSourceVersion: number; launchId: string};
  expectedTurnId?: string;
  startedAt?: number;
  baselineBlockIds?: string[];
  baselineTurnIds?: string[];
};

/** A retry must reuse the same request object and its durable gateway receipt. */
export async function requestReceiptWithRetry<T>(request: () => Promise<T>): Promise<T> {
  try { return await request(); }
  catch { return await request(); }
}

/** Explicitly leave an ambiguous request without replaying it or discarding its evidence. */
export async function preserveAndReleaseUnconfirmed(stored: string, storage: {
  readPending(): Promise<string | null>;
  readArchive(): Promise<string | null>;
  writeArchive(value: string): Promise<void>;
  removePending(): Promise<void>;
}): Promise<void> {
  if (await storage.readPending() !== stored) throw new Error('PENDING_CHANGED');
  const archive = await storage.readArchive();
  if (archive !== null && archive !== stored) throw new Error('PENDING_ARCHIVE_CONFLICT');
  if (archive === null) await storage.writeArchive(stored);
  if (await storage.readArchive() !== stored || await storage.readPending() !== stored)
    throw new Error('PENDING_ARCHIVE_UNAVAILABLE');
  await storage.removePending();
}

/** Only the authoritative receipt for this exact operation may clear its draft. */
export function isAcceptedReceipt(pending: PendingSubmission, value: Record<string, unknown>): boolean {
  const expected = pending.action === 'steer' ? 'accepted' : 'queued';
  if (value.threadId !== pending.threadId || value.requestId !== pending.requestId || value.state !== expected) return false;
  if (pending.action === 'continue' && value.panelId !== pending.panel?.panelId) return false;
  const result = value.result;
  const queuedAfterTurn = pending.action === 'steer' && result !== null && typeof result === 'object'
    && !Array.isArray(result) && (result as Record<string, unknown>).queuedAfterTurn === true;
  return !queuedAfterTurn || typeof value.taskId === 'string' && /^[0-9]+$/.test(value.taskId);
}

/** A transport error is never proof of non-delivery; only this exact durable receipt is. */
export function isNotSentReceipt(pending: PendingSubmission, value: Record<string, unknown>): boolean {
  return value.threadId === pending.threadId && value.requestId === pending.requestId
    && value.state === 'notSent' && typeof value.code === 'string' && /^[A-Z][A-Z_]{2,80}$/.test(value.code);
}

export async function retireAcceptedSubmission(pending: PendingSubmission, storage: {
  readDraft(): Promise<DraftContent>;
  writeDraft(draft: DraftContent): Promise<void>;
  removePending(): Promise<void>;
}): Promise<boolean> {
  const draft = await storage.readDraft();
  const cleared = matchesDraft(draft, pending);
  // A crash between these operations may leave the old receipt, but must never
  // leave a resendable old draft without its original request ID.
  if (cleared) await storage.writeDraft({text: ''});
  await storage.removePending();
  return cleared;
}
