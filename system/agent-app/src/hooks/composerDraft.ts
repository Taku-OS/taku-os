import AsyncStorage from '@react-native-async-storage/async-storage';
import {AgentGatewayError, callAgent, newRequestId} from '../native/agent';
import {record} from '../model/session';
import {pendingKey, retireAcceptedSubmission, unconfirmedArchivePrefix, type PendingSubmission} from './submission';

export type ComposerAttachment = {id: string; name: string; mime: string; size: number};
/** A plugin the user attached to the next message (`@` in the composer); the native draft owns it. */
export type ComposerPlugin = {id: string; name: string};
export type ComposerRecovery = {id: string; text: string; status: string; attachments: ComposerAttachment[]};
export type ComposerSnapshot = {threadId: string; revision: string; text: string; attachments: ComposerAttachment[]; plugins: ComposerPlugin[]; recovery: ComposerRecovery[]; pending?: ComposerSubmission};
export type ComposerSubmission = PendingSubmission & {draftRevision?: string; attachmentIds?: string[]; pluginIds?: string[]};
type Edit = {text: string; baseText: string; recoveryId?: string};

type ComposerParams = {
  'composer/read': {threadId: string};
  'composer/write': {threadId: string; revision: string; text: string};
  'composer/import': {threadId: string; text: string};
  'composer/move': {threadId: string; revision: string; targetThreadId: string};
  'composer/preserveDraft': {threadId: string; recoveryId: string; text: string};
};

const legacyDraftKey = (thread: string) => `taku.agent.draft.v1.${thread || 'new'}`;
const legacyDraftMatches = (text: string | null, pending: ComposerSubmission | undefined, thread: string) =>
  text !== null && pending?.threadId === thread && pending.draftRevision === undefined && text.trim() === pending.text;

export const isDraftRevisionConflict = (problem: unknown) =>
  problem instanceof AgentGatewayError && problem.code === 'DRAFT_REVISION_CONFLICT';

async function composerCall<A extends keyof ComposerParams>(
  action: A, params: ComposerParams[A],
): Promise<ComposerSnapshot> {
  const result = record(await callAgent(action, params));
  const threadId = 'targetThreadId' in params ? params.targetThreadId : params.threadId;
  return parseComposerSnapshot(result, threadId);
}

export function parseComposerSnapshot(value: unknown, threadId: string): ComposerSnapshot {
  const result = record(value);
  if (result.threadId !== threadId || typeof result.revision !== 'string' || typeof result.text !== 'string' || !Array.isArray(result.attachments))
    throw new AgentGatewayError('Invalid composer draft snapshot');
  const recovery = Array.isArray(result.recovery) ? result.recovery.map(value => {
    const item = record(value);
    if (typeof item.id !== 'string' || typeof item.text !== 'string' || typeof item.status !== 'string' || !Array.isArray(item.attachments))
      throw new AgentGatewayError('Invalid composer recovery entry');
    return {id: item.id, text: item.text, status: item.status, attachments: parseAttachments(item.attachments)};
  }) : [];
  const pending = Array.isArray(result.pendingReceipts) && result.pendingReceipts.length
    ? parsePending(result.pendingReceipts[0], threadId) : undefined;
  // A runtime from before plugin attachments reports none.
  const plugins = Array.isArray(result.plugins) ? parsePlugins(result.plugins) : [];
  return {threadId, revision: result.revision, text: result.text, attachments: parseAttachments(result.attachments), plugins, recovery, pending};
}

function parsePending(value: unknown, threadId: string): ComposerSubmission {
  const item = record(value);
  const {requestId, text, revision, action, attachmentIds, pluginIds, expectedTurnId, panelId, expectedSourceVersion} = item;
  if (item.threadId !== threadId || typeof requestId !== 'string' || typeof text !== 'string' || typeof revision !== 'string' ||
      (action !== 'sendMessage' && action !== 'steer' && action !== 'continue') || !Array.isArray(attachmentIds) ||
      !attachmentIds.every(id => typeof id === 'string')) throw new AgentGatewayError('Invalid composer request');
  const pending: ComposerSubmission = {threadId, requestId, text, action, draftRevision: revision, attachmentIds};
  if (Array.isArray(pluginIds) && pluginIds.every(id => typeof id === 'string')) pending.pluginIds = pluginIds;
  if (typeof expectedTurnId === 'string') pending.expectedTurnId = expectedTurnId;
  if (typeof panelId === 'string' && typeof expectedSourceVersion === 'number') pending.panel = {panelId, expectedSourceVersion, launchId: ''};
  return pending;
}

function parseAttachments(items: unknown[]): ComposerAttachment[] {
  return items.map(value => {
    const item = record(value);
    if (typeof item.id !== 'string' || typeof item.name !== 'string' || typeof item.mime !== 'string' || typeof item.size !== 'number')
      throw new AgentGatewayError('Invalid composer attachment');
    return {id: item.id, name: item.name, mime: item.mime, size: item.size};
  });
}

function parsePlugins(items: unknown[]): ComposerPlugin[] {
  return items.map(value => {
    const item = record(value);
    if (typeof item.id !== 'string' || typeof item.name !== 'string') throw new AgentGatewayError('Invalid composer plugin');
    return {id: item.id, name: item.name};
  });
}

export class ComposerDraft {
  private saved?: ComposerSnapshot;
  private edit?: Edit;
  private importedLegacy = false;
  private operations = Promise.resolve();
  private saving?: Promise<ComposerSnapshot>;

  private readonly threadId: string;
  constructor(threadId: string) { this.threadId = threadId; }

  get text() { return this.edit?.text ?? this.saved?.text ?? ''; }
  get snapshot() { return this.saved; }

  mutate(operation: (snapshot: ComposerSnapshot) => Promise<ComposerSnapshot | null>): Promise<boolean> {
    return this.enqueue(async () => {
      const snapshot = await this.writeEdits();
      const result = await operation(snapshot);
      if (!result) return false;
      this.acceptWrite(result);
      return true;
    });
  }

  applyDictation(text: string, expected: {revision: string; text: string}): Promise<boolean> {
    return this.enqueue(async () => {
      if (this.edit || this.saved?.revision !== expected.revision || this.saved.text !== expected.text) return false;
      try {
        this.acceptWrite(await composerCall('composer/write', {threadId: this.threadId, revision: expected.revision, text}));
        return true;
      } catch (problem) {
        if (!isDraftRevisionConflict(problem)) throw problem;
        this.saved = await this.read();
        return false;
      }
    });
  }

  private enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.operations.then(operation);
    this.operations = next.then(() => {}, () => {});
    return next;
  }

  private read() { return composerCall('composer/read', {threadId: this.threadId}); }

  restore(): Promise<{saveError?: unknown}> {
    return this.enqueue(async () => {
      const key = legacyDraftKey(this.threadId);
      const legacy = this.importedLegacy ? null : await AsyncStorage.getItem(key);
      let awaitingLegacyReceipt = false;
      if (legacy && this.threadId) {
        const stored = await AsyncStorage.getItem(pendingKey(this.threadId));
        if (stored) {
          try { awaitingLegacyReceipt = legacyDraftMatches(legacy, JSON.parse(stored), this.threadId); }
          catch { awaitingLegacyReceipt = true; }
        }
      }
      if (legacy && !awaitingLegacyReceipt) await composerCall('composer/import', {threadId: this.threadId, text: legacy});
      if (!this.importedLegacy) {
        const prefix = unconfirmedArchivePrefix(this.threadId);
        for (const name of await AsyncStorage.getAllKeys()) {
          if (!name.startsWith(prefix)) continue;
          const requestId = name.slice(prefix.length);
          if (!/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(requestId)) continue;
          const stored = await AsyncStorage.getItem(name);
          if (!stored) continue;
          let request: ReturnType<typeof record>;
          try { request = record(JSON.parse(stored)); } catch { continue; }
          if (request.threadId !== this.threadId || request.requestId !== requestId || typeof request.text !== 'string') continue;
          const migrated = await composerCall('composer/preserveDraft', {threadId: this.threadId, recoveryId: requestId, text: request.text});
          if (migrated.recovery.some(entry => entry.id === requestId && entry.text === request.text) && await AsyncStorage.getItem(name) === stored)
            await AsyncStorage.removeItem(name);
        }
      }
      this.saved = await this.read();
      const sourceChanged = legacy !== null && !awaitingLegacyReceipt && await AsyncStorage.getItem(key) !== legacy;
      if (legacy !== null && !awaitingLegacyReceipt && !sourceChanged) await AsyncStorage.removeItem(key);
      this.importedLegacy = !awaitingLegacyReceipt && !sourceChanged;
      if (this.edit) {
        try { await this.writeEdits(); }
        catch (saveError) { return {saveError}; }
      }
      return {};
    });
  }

  setText(text: string): void {
    this.edit = {text, baseText: this.edit?.baseText ?? this.saved?.text ?? ''};
  }

  private acceptWrite(snapshot: ComposerSnapshot, edit?: Edit): void {
    this.saved = snapshot;
    if (this.edit === edit) this.edit = undefined;
    else if (this.edit) this.edit.baseText = snapshot.text;
  }

  flush(): Promise<ComposerSnapshot> {
    if (!this.saving) this.saving = this.enqueue(async () => {
      try {
        let snapshot: ComposerSnapshot;
        do { snapshot = await this.writeEdits(); } while (this.edit);
        return snapshot;
      } finally { this.saving = undefined; }
    });
    return this.saving;
  }

  private async writeEdits(): Promise<ComposerSnapshot> {
    if (!this.saved) throw new AgentGatewayError('Draft is unavailable', 'DRAFT_RESTORE_UNAVAILABLE');
    let refreshed = false;
    while (this.edit) {
      const edit = this.edit;
      if (edit.text === this.saved.text) { this.edit = undefined; break; }
      if (edit.baseText !== this.saved.text) {
        edit.recoveryId ??= newRequestId();
        this.saved = await composerCall('composer/preserveDraft', {threadId: this.threadId, recoveryId: edit.recoveryId, text: edit.text});
        if (this.edit === edit) this.edit = undefined;
        throw new AgentGatewayError('Composer edit saved for recovery', 'DRAFT_REVISION_CONFLICT');
      }
      try {
        this.acceptWrite(await composerCall('composer/write', {threadId: this.threadId, revision: this.saved.revision, text: edit.text}), edit);
      } catch (problem) {
        if (!isDraftRevisionConflict(problem) || refreshed) throw problem;
        this.saved = await this.read();
        refreshed = true;
        continue;
      }
      refreshed = false;
    }
    return this.saved;
  }

  move(revision: string, targetThreadId: string): Promise<ComposerSnapshot> {
    return this.enqueue(async () => {
      const target = await composerCall('composer/move', {threadId: this.threadId, revision, targetThreadId});
      this.saved = undefined;
      return target;
    });
  }

  retireAccepted(pending: ComposerSubmission, removePending: () => Promise<void>): Promise<void> {
    return this.enqueue(async () => {
      const legacyKey = legacyDraftKey(this.threadId);
      if (pending.draftRevision === undefined && legacyDraftMatches(await AsyncStorage.getItem(legacyKey), pending, this.threadId))
        await AsyncStorage.removeItem(legacyKey);
      const snapshot = this.saved = await this.read();
      await retireAcceptedSubmission(pending, {
        readDraft: async () => !this.edit && snapshot.revision === pending.draftRevision ? snapshot.text : null,
        writeDraft: async text => {
          if (this.edit) return;
          try {
            this.acceptWrite(await composerCall('composer/write', {threadId: this.threadId, revision: snapshot.revision, text}));
          } catch (problem) {
            if (!isDraftRevisionConflict(problem)) throw problem;
            this.saved = await this.read();
          }
        },
        removePending,
      });
    });
  }
}
