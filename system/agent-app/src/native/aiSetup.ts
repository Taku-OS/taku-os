/**
 * Reopens a Taku Home setup page that onboarding let the user skip: Jev (Figma 1379:2556 "2-3")
 * or fal (1379:2782 "2-5"). Absent on web and in tests, where it reports `false`.
 */
import {requireOptionalNativeModule} from 'expo';

type TakuHomeSetup = {openAiSetup(service: string): string};
const nativeModule = requireOptionalNativeModule<TakuHomeSetup>('TakuHome');

export type AiSetupService = 'jev' | 'fal';

export function openAiSetup(service: AiSetupService): boolean {
  try {
    const result: unknown = JSON.parse(nativeModule?.openAiSetup(service) ?? '{}');
    return typeof result === 'object' && result !== null && (result as {ok?: unknown}).ok === true;
  } catch { return false; }
}
