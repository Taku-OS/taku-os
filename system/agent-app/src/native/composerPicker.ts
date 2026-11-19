import {requireOptionalNativeModule} from 'expo';
import {AgentGatewayError} from './agent';

export type ComposerPickerKind = 'file' | 'image' | 'camera';
export type ComposerAttachment = {id: string; name: string; mime: string; size: number};
export type ComposerAttachmentEnvelope =
  | {ok: true; result: {threadId: string; revision: string; text: string; attachments: ComposerAttachment[]}}
  | {ok: false; code: string; message?: string; revision?: string};

type NativeComposerPicker = {
  pickComposerAttachment(kind: ComposerPickerKind, threadId: string, expectedRevision: string): Promise<string | null>;
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
