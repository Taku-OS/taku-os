import {requireOptionalNativeModule} from 'expo';
import {AgentGatewayError} from './agent';

export type ComposerPickerKind = 'file' | 'image' | 'camera';
export type ComposerAttachment = {id: string; name: string; mime: string; size: number};
export type ComposerAttachmentEnvelope =
  | {ok: true; result: {threadId: string; revision: string; text: string; attachments: ComposerAttachment[]};
      /** Some selections were attached and a later one was refused with this code. */
      partial?: string}
  | {ok: false; code: string; message?: string; revision?: string};

type NativeComposerPicker = {
  pickComposerAttachment(kind: ComposerPickerKind, threadId: string, expectedRevision: string): Promise<string | null>;
  addListener(event: 'onComposerAttachment', listener: (event: {threadId?: string}) => void): {remove(): void};
};

const nativePicker = requireOptionalNativeModule<NativeComposerPicker>('TakuAgent');

export async function pickComposerAttachment(
  kind: ComposerPickerKind, threadId: string, expectedRevision: string,
): Promise<ComposerAttachmentEnvelope | null> {
  if (!nativePicker?.pickComposerAttachment) {
    throw new AgentGatewayError('The attachment picker requires the installed Android app', 'NATIVE_UNAVAILABLE');
  }
  let envelope: string | null;
  try {
    envelope = await nativePicker.pickComposerAttachment(kind, threadId, expectedRevision);
  } catch (error) {
    const code = error != null && typeof error === 'object' && 'code' in error && typeof error.code === 'string'
      ? error.code : 'PICKER_UNAVAILABLE';
    const message = error instanceof Error ? error.message : 'Android could not pick an attachment';
    throw new AgentGatewayError(message, code);
  }
  return envelope === null ? null : JSON.parse(envelope) as ComposerAttachmentEnvelope;
}

/**
 * A selection was attached to [threadId]'s saved draft after the conversation screen that asked
 * for it had been recreated, so the pick's own promise may have had no listener.
 */
export const onComposerAttachment = (listener: (threadId: string) => void): {remove(): void} =>
  nativePicker?.addListener?.('onComposerAttachment', event => { if (typeof event.threadId === 'string') listener(event.threadId); })
    ?? {remove() {}};

/** A small JPEG of an image attachment in [threadId]'s draft, served by the system gateway. */
export const attachmentPreviewUri = (threadId: string, attachmentId: string): string =>
  `content://org.agentos.assistant.agent-gateway/attachment/${encodeURIComponent(attachmentId)}?threadId=${encodeURIComponent(threadId)}`;
