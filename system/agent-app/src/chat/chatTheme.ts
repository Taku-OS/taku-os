/**
 * Figma "Taku Mobile OS" roles: dark translucent Taku Home, chat and Live Talk.
 * The legacy light palette preserves native Tokens.kt / Theme.kt values for light controls.
 */
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

/** Figma 300:661 / 297:433; retain a visible blurred backdrop per the product correction. */
export const chatColors = {
  ...homeColors,
  textPrimary: surface(0.92), textSecondary: surface(0.8), textTertiary: surface(0.64), textPlaceholder: surface(0.48),
  textError: '#FFB4AB', icon: '#FFFFFF', iconDisabled: surface(0.16),
  bubble: surface(0.8), assistantBubble: surface(0.06), userText: content(0.8),
  composer: surface(0.08), chip: surface(0.08), placeholder: surface(0.36),
  surfaceRaised: '#262626', surfaceGlass: surface(0.08), fillControl: surface(0.08), hairline: surface(0.16),
  // One native tint over the existing cross-window blur, never another opaque React layer.
  backdropTint: content(0.8), backdropTintArgb: '#CC000000', backdropFallback: '#121212',
};

/**
 * 2-1 blur σ (dp; Figma blur R = 2σ): the chat window's system backdrop blur (Backdrop Blur/3xl,
 * R128), the only blur over what lies behind the chat, the list's progressive edge blur and the
 * list blur under the long-press menu (0-3 Overlay, backdrop-blur 4).
 */
export const chatBlur = {backdropSigma: 64, edgeSigma: 12, menuSigma: 4};

/** The chat's fixed title (2-1 shows the conversation name there; product keeps "Taku"). */
export const chatTitle = 'Taku';

/** Figma 2-1 geometry (dp). */
export const chatMetrics = {side: 16, header: 64, itemGap: 24, replyGap: 12, composerBottom: 40, bubbleMinInset: 48, composerMax: 380};

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
