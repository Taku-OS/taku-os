/**
 * The chat background setting (Taku AI settings → Appearance): `Settings.Secure`
 * `taku_chat_appearance`, `dark` (default) or `light`, owned by the native ChatAppearance store so
 * the GUI and Agents (`settings get|put secure taku_chat_appearance`) share one value. This loads it
 * into the chat theme and follows every change, including an Agent's.
 */
import {requireOptionalNativeModule} from 'expo';
import {applyChatAppearance, type ChatAppearance} from '../chat/chatTheme';

type Subscription = {remove(): void};
type AppearanceModule = {
  chatAppearance(): string;
  setChatAppearance(value: string): string;
  addListener(event: 'onChatAppearance', listener: (event: {appearance?: string}) => void): Subscription;
};
const nativeModule = requireOptionalNativeModule<AppearanceModule>('TakuAgent');

export const parseChatAppearance = (value: unknown): ChatAppearance => value === 'light' ? 'light' : 'dark';

let following: Subscription | null = null;

/** Applies the stored appearance now and keeps following it; safe to call more than once. */
export function startChatAppearance(): void {
  if (!nativeModule) return;
  try { applyChatAppearance(parseChatAppearance(nativeModule.chatAppearance())); } catch {}
  following ??= nativeModule.addListener('onChatAppearance', event => applyChatAppearance(parseChatAppearance(event.appearance)));
}

/** Saves [next]; the chat follows at once. Answers whether the setting was stored. */
export function saveChatAppearance(next: ChatAppearance): boolean {
  if (!nativeModule) return false;
  try {
    const result: unknown = JSON.parse(nativeModule.setChatAppearance(next));
    if (typeof result !== 'object' || result === null || (result as {ok?: unknown}).ok !== true) return false;
    applyChatAppearance(parseChatAppearance((result as {appearance?: unknown}).appearance));
    return true;
  } catch { return false; }
}
