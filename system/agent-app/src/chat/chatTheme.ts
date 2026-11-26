/**
 * Figma "Taku Mobile OS" roles: dark translucent Taku Home, chat and Live Talk.
 * The legacy light palette preserves native Tokens.kt / Theme.kt values for light controls.
 *
 * The chat has two backgrounds (Taku AI settings → Appearance, `Settings.Secure`
 * `taku_chat_appearance`): Dark (Figma 300:661, the default) and Light (the earlier light chat).
 * [chatColors] and [chatBlur] always read the active one, and [themedStyles] builds each sheet per
 * appearance, so screens keep their usual `chatColors.x` / `styles.x` and follow a switch on their
 * next render (the chat remounts on a switch; see AgentScreen).
 */
import {useSyncExternalStore} from 'react';
import type {TextStyle} from 'react-native';

const content = (a: number) => `rgba(0,0,0,${a})`;
const surface = (a: number) => `rgba(255,255,255,${a})`;

export const homeColors = {
  textPrimary: content(0.92), textSecondary: content(0.8), textTertiary: content(0.64), textPlaceholder: content(0.48),
  textError: '#B3261E', textOnInverse: '#FFFFFF',
  icon: '#1B1B1B', iconOnInverse: '#FFFFFF', iconDisabled: content(0.16),
  bubble: content(0.06), composer: surface(0.92), chip: '#FFFFFF', placeholder: content(0.36),
  surfaceRaised: surface(0.8), surfaceGlass: surface(0.48), fillControl: content(0.08), hairline: content(0.08),
  inverse: '#1B1B1B', send: '#FF5A1F', shadow: content(0.16),
  /** Under the window blur (Surface/400) and the opaque fallback (Main/Canvas). */
  backdropTint: surface(0.24), backdropFallback: '#E3E3E3',
  /** Surface/400 as the window's #AARRGGBB tint under the system blur. */
  backdropTintArgb: '#3DFFFFFF',
};

/**
 * Figma 300:661 / 297:433. The backdrop is the design's "backdrop blur" layer: Content/900 (92 %
 * black) over a moderate blur, measured against the frame's own render (scale 0.083 of the blurred
 * background, best fit σ 12–16). A lighter tint over a far heavier blur averaged bright wallpapers
 * into flat grey.
 */
const dark = {
  ...homeColors,
  textPrimary: surface(0.92), textSecondary: surface(0.8), textTertiary: surface(0.64), textPlaceholder: surface(0.48),
  textError: '#FFB4AB', icon: '#FFFFFF', iconDisabled: surface(0.16),
  bubble: surface(0.8), assistantBubble: surface(0.06), userText: content(0.8),
  composer: surface(0.08), chip: surface(0.08), placeholder: surface(0.36),
  surfaceRaised: '#262626', surfaceGlass: surface(0.08), fillControl: surface(0.08), hairline: surface(0.16),
  // One native tint over the existing cross-window blur, never another opaque React layer.
  backdropTint: content(0.92), backdropTintArgb: '#EB000000', backdropFallback: '#121212',
  errorSurface: '#402322',
  /** Stop: a solid Main/Surface/Default disc with a Main/Foreground square, clear on the dark glass. */
  stopFill: '#FFFFFF', stopGlyph: '#1B1B1B',
  /** Reaction badge (300:682): Surface/50 glass inside a 2 dp Content/900 ring that cuts it from the bubble. */
  reactionFill: surface(0.04), reactionRing: content(0.92),
  /**
   * Task status (Zile's Single Chat wireframe, sampled from its frames): amber where a Task waits
   * for the user, blue-grey while it works. The only two hues the chat adds; a finished Task is
   * plain tertiary text.
   */
  statusAttention: '#F4C275', statusProgress: '#B5D0EE',
  /** The Task card's outline, and the dim over the conversation beside the open drawer. */
  cardOutline: surface(0.1), scrim: content(0.56),
  /** The choice being sent from a Task card: the user bubble's own pair. */
  chosen: surface(0.8), onChosen: content(0.8),
};

export type ChatColors = typeof dark;

/**
 * The earlier light chat (taku-os-legacy vela/TAKU-new-UI b8a1368c92): Surface/400 over the window
 * blur, Main/Canvas where the device cannot blur, dark text. Its user bubble is Content/100 with
 * Content/800 text; replies, now in bubbles, sit on Surface/600 glass.
 */
const light: ChatColors = {
  ...homeColors,
  bubble: content(0.06), assistantBubble: surface(0.48), userText: content(0.8),
  errorSurface: '#F9DEDC',
  /** On the light background the stop disc inverts: Main/Foreground with a white square. */
  stopFill: '#1B1B1B', stopGlyph: '#FFFFFF',
  /** On the light background the ring is the light canvas and the badge a white glass. */
  reactionFill: surface(0.8), reactionRing: '#E3E3E3',
  /** The same two statuses, deepened to read on the light canvas. */
  statusAttention: '#8A5008', statusProgress: '#3F6189',
  cardOutline: content(0.08), scrim: content(0.32),
  chosen: '#1B1B1B', onChosen: '#FFFFFF',
};

export type ChatAppearance = 'dark' | 'light';
const palettes: Record<ChatAppearance, ChatColors> = {dark, light};

let active: ChatAppearance = 'dark';
const listeners = new Set<() => void>();

export const getChatAppearance = (): ChatAppearance => active;

/** Applies [next] to every chat surface; the stored setting is changed through native/chatAppearance. */
export function applyChatAppearance(next: ChatAppearance): void {
  if (next === active) return;
  active = next;
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** The active appearance; re-renders the caller when it changes. */
export const useChatAppearance = (): ChatAppearance => useSyncExternalStore(subscribe, getChatAppearance);

/** A read-only view whose properties always come from the active appearance's value. */
function live<T extends object>(pick: () => T): T {
  return new Proxy({} as T, {
    get: (_, key) => (pick() as Record<PropertyKey, unknown>)[key],
    has: (_, key) => key in pick(),
    ownKeys: () => Reflect.ownKeys(pick()),
    getOwnPropertyDescriptor: (_, key) => {
      const value = (pick() as Record<PropertyKey, unknown>)[key];
      return value === undefined && !(key in pick()) ? undefined : {value, enumerable: true, configurable: true, writable: false};
    },
  });
}

/** The active appearance's palette. */
export const chatColors: ChatColors = live(() => palettes[active]);

/**
 * A value derived from [chatColors] (a style sheet, a colour map), built once per appearance on
 * first use and read through like the plain object.
 */
export function themed<T extends object>(build: () => T): T {
  const built: Partial<Record<ChatAppearance, T>> = {};
  return live(() => built[active] ??= build());
}

/** `StyleSheet.create` for chat surfaces: one sheet per appearance, the active one read through. */
export const themedStyles = themed;

/**
 * 2-1 blur σ (dp; Figma blur R = 2σ) for the active background: the chat window's system backdrop
 * blur, the only blur over what lies behind the chat, the list's progressive edge blur and the
 * list blur under the long-press menu (0-3 Overlay, backdrop-blur 4).
 */
export const chatBlur = live(() => blurs[active]);
const blurs: Record<ChatAppearance, {backdropSigma: number; edgeSigma: number; menuSigma: number}> = {
  // Dark: the design's moderate blur keeps what lies behind faintly legible (Backdrop Blur/lg, R32).
  dark: {backdropSigma: 16, edgeSigma: 12, menuSigma: 4},
  // Light: the earlier light chat's Backdrop Blur/3xl (R128), a frosted white.
  light: {backdropSigma: 64, edgeSigma: 12, menuSigma: 4},
};

/**
 * The main conversation's name, wherever it is a title or a destination: the chat's title row, the
 * drawer and the composer. A Task shows its own title instead. Running text still says "Taku".
 */
export const chatTitle = 'TAKU';

/** Figma 2-1 geometry (dp); `strip` is the row of Task capsules under the title (38 dp capsules, 8 dp below). */
export const chatMetrics = {side: 16, header: 64, itemGap: 24, replyGap: 12, composerBottom: 40, bubbleMinInset: 48, composerMax: 380, strip: 46};

/** Motion (design-system tokens.json): the drawer's own curve and duration, and the shared press fade. */
export const chatMotion = {drawerMs: 320, drawerEase: [0.22, 1, 0.36, 1] as const, colorMs: 200};

type Weight = 'regular' | 'medium';
const latin = {regular: 'TakuBody', medium: 'TakuBodyMedium'};
const han = {regular: 'TakuChinese', medium: 'TakuChineseMedium'};

/** Taku type scale (Theme.kt): size, line-height multiplier, -1% tracking. */
function style(size: number, line: number, weight: Weight, chinese: boolean): TextStyle {
  return {fontFamily: (chinese ? han : latin)[weight], fontSize: size, lineHeight: Math.round(size * line * 10) / 10,
    letterSpacing: chinese ? 0 : -size / 100, includeFontPadding: false, color: chatColors.textPrimary};
}

/** Space Grotesk headings: md 16 (0 %), xl 24 (−1 %), 2xl 32 (−2 %); line height 1.05. */
function heading(size: number, tracking: number, chinese: boolean): TextStyle {
  return {fontFamily: chinese ? han.regular : 'TakuDisplay', fontSize: size, lineHeight: Math.round(size * 1.05 * 10) / 10,
    letterSpacing: chinese ? 0 : -size * tracking, includeFontPadding: false, color: chatColors.textPrimary};
}

export const chatType = {
  headingMd: (chinese = false) => heading(16, 0, chinese),
  headingXl: (chinese = false) => heading(24, 0.01, chinese),
  heading2xl: (chinese = false) => heading(32, 0.02, chinese),
  headingLg: (chinese = false): TextStyle => ({...style(20, 1.05, 'regular', chinese), fontFamily: chinese ? han.regular : 'TakuDisplay'}),
  bodyXlMedium: (chinese = false) => style(18, 1.55, 'medium', chinese),
  bodyLg: (chinese = false) => style(16, 1.5, 'regular', chinese),
  bodyLgMedium: (chinese = false) => style(16, 1.5, 'medium', chinese),
  bodyMd: (chinese = false) => style(14, 1.42, 'regular', chinese),
  bodyMdMedium: (chinese = false) => style(14, 1.42, 'medium', chinese),
  bodySm: (chinese = false) => style(12, 1.3, 'regular', chinese),
  bodySmMedium: (chinese = false) => style(12, 1.3, 'medium', chinese),
};

export const homeType = Object.fromEntries(Object.entries(chatType).map(([key, fn]) => [key, (chinese = false) => ({...fn(chinese), color: homeColors.textPrimary})])) as typeof chatType;
