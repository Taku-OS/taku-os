export type PendingSubmission = {
  threadId: string;
  requestId: string;
  text: string;
  action: 'sendMessage' | 'steer' | 'continue';
  /** The panel this send updates. Native revalidates the source at the actual send. */
  panel?: {panelId: string; expectedSourceVersion: number; launchId: string};
  expectedTurnId?: string;
  startedAt?: number;
  baselineBlockIds?: string[];
  baselineTurnIds?: string[];
};

export const pendingKey = (threadId: string) => `taku.agent.pending.v1.${threadId}`;
export const unconfirmedArchivePrefix = (threadId: string) => `taku.agent.unconfirmed.v1.${threadId}.`;

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
  readDraft(): Promise<string | null>;
  writeDraft(text: string): Promise<void>;
  removePending(): Promise<void>;
}): Promise<boolean> {
  const draft = await storage.readDraft();
  const cleared = typeof draft === 'string' && draft.trim() === pending.text;
  // A crash between these operations may leave the old receipt, but must never
  // leave a resendable old draft without its original request ID.
  if (cleared) await storage.writeDraft('');
  await storage.removePending();
  return cleared;
}
