import type {TextStyle} from 'react-native';
import design from '../../../assistant/design-system/tokens.json';

// Resolve the imported design-team handoff rather than inventing another palette.
const tokens = design.tokens as Record<string, {value: string}>;
const token = (name: string): string => {
  const value = tokens[name]?.value;
  if (!value) throw new Error(`Missing Taku token: ${name}`);
  const alias = /^var\(--tk-(.+)\)$/.exec(value);
  return alias ? token(alias[1]) : value;
};
export const colors = {
  page: token('semantic-surface-page'), surface: token('semantic-surface-card'), ink: token('semantic-text-primary'),
  secondary: token('semantic-text-secondary'), muted: token('semantic-text-disabled'), border: token('semantic-border'),
  subtle: token('content-50'), control: token('semantic-surface-control'), sage: token('color-sage'),
  peach: token('color-peach'), lavender: token('color-lavender'), mist: token('color-mist'), rose: token('color-rose'), sand: token('color-sand'),
  brand: token('color-brand'), error: token('semantic-status-error'), errorSurface: token('semantic-status-error-surface'),
  success: token('semantic-status-success-strong'), white: token('color-surface'),
};
export const layout = {gutter: 16, gap: 12, touch: 48, glyph: 20, radius: 16, buttonRadius: 6};
export type TypeRole = 'heading' | 'context' | 'body' | 'label' | 'meta' | 'caption';
export function typography(role: TypeRole = 'body', chinese = false): TextStyle {
  const size = {heading: 24, context: 18, body: 16, label: 16, meta: 14, caption: 12}[role];
  const base = {color: colors.ink, includeFontPadding: false, letterSpacing: chinese ? 0 : -size / 100};
  const font = chinese ? 'TakuChinese' : 'TakuBody';
  const medium = chinese ? 'TakuChineseMedium' : 'TakuBodyMedium';
  switch (role) {
    case 'heading': return {...base, fontFamily: chinese ? font : 'TakuDisplay', fontSize: 24, lineHeight: chinese ? 31.2 : 25.2};
    case 'context': return {...base, fontFamily: medium, fontSize: 18, lineHeight: 27.9};
    case 'label': return {...base, fontFamily: medium, fontSize: 16, lineHeight: 24};
    case 'meta': return {...base, fontFamily: font, fontSize: 14, lineHeight: 19.88};
    case 'caption': return {...base, fontFamily: font, fontSize: 12, lineHeight: 15.6};
    default: return {...base, fontFamily: font, fontSize: 16, lineHeight: 24};
  }
}
export const containsChinese = (text: string): boolean => /[\u3400-\u9fff]/.test(text);
