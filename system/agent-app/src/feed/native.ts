/** Typed access to the `TakuHome` native module (modules/taku-home); absent on web and in tests. */
import {requireOptionalNativeModule} from 'expo';

type Subscription = {remove(): void};
type TakuHomeModule = {
  openPanel(panelId: string): Promise<string>;
  beginFeedGeneration(attemptId: string): boolean;
  completeFeedGeneration(attemptId: string, receiptJson: string): boolean;
  getCard(cardId: string): string | null;
  readAssetText(path: string): string | null;
  reportCardHeight(cardId: string, heightDp: number): void;
  dispatchAction(cardId: string, actionId: string, valuesJson: string): Promise<string>;
  addListener(event: 'onCardChanged', listener: (event: {cardId: string}) => void): Subscription;
};

const nativeModule = requireOptionalNativeModule<TakuHomeModule>('TakuHome');

export const hasTakuHome = (): boolean => nativeModule != null;

export type DispatchResult = {ok: true; result: Record<string, unknown>} | {ok: false; code: string; message: string};

export function beginFeedGeneration(attemptId: string): boolean {
  return nativeModule?.beginFeedGeneration(attemptId) ?? false;
}

export function completeFeedGeneration(attemptId: string, receiptJson: string): boolean {
  return nativeModule?.completeFeedGeneration(attemptId, receiptJson) ?? false;
}

export function getCard(cardId: string): string | null {
  return nativeModule?.getCard(cardId) ?? null;
}

export function readAssetText(path: string): string | null {
  try { return nativeModule?.readAssetText(path) ?? null; } catch { return null; }
}

export function reportCardHeight(cardId: string, heightDp: number): void {
  nativeModule?.reportCardHeight(cardId, heightDp);
}

export async function dispatchAction(cardId: string, actionId: string, values: Record<string, unknown>): Promise<DispatchResult> {
  if (!nativeModule) return {ok: false, code: 'NATIVE_UNAVAILABLE', message: 'Taku Home is only available on the device'};
  try {
    const parsed = JSON.parse(await nativeModule.dispatchAction(cardId, actionId, JSON.stringify(values)));
    return parsed?.ok === true ? {ok: true, result: parsed.result ?? {}}
      : {ok: false, code: String(parsed?.code ?? 'ACTION_FAILED'), message: String(parsed?.message ?? 'The action failed')};
  } catch (error) {
    return {ok: false, code: 'ACTION_FAILED', message: error instanceof Error ? error.message : String(error)};
  }
}

export function onCardChanged(cardId: string, listener: () => void): Subscription {
  return nativeModule?.addListener('onCardChanged', event => { if (event.cardId === cardId) listener(); }) ?? {remove() {}};
}
