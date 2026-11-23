# Taku Agent React Native design adapter

The source of values remains `system/assistant/design-system/tokens.json`, imported
from Taku mobile design-system handoff v0.8 at commit
`355e8c2d4edc436b6664e30c3edb396f732ad76c`. The handoff identifies its token set as
v0.7.0 and supports light appearance. The adapter resolves those semantic colors;
it does not claim a newly approved dark theme.

Typography, spacing and icon mappings follow the imported handoff. The chat and
workspace presentation follow `system/assistant/design-system/interaction-contract.md`.
The later user refinements keep copy controls compact, retain History/New in the
header, use one icon to switch the current conversation's presentation, and omit
the floating character and pencil shortcut.

Fonts and icon paths are copied from the existing licensed Android resources.
Their upstream OFL and Material Symbols licenses are preserved in `licenses/`.
The supplied design handoff does not grant an overall license for Taku branding.
No reference screenshot is shipped or rendered as application UI.

Android fonts are embedded with the Expo SDK 58 `expo-font` config plugin. The
Android-specific `useTakuFonts` module intentionally has no Metro font imports,
so each font is packaged once in native resources. Web/iOS retain runtime loading.
UI aliases and the supplied Noto subset remain unchanged. Markdown always uses
`TakuChinese` from its first frame; code uses `TakuCode` (JetBrains Mono Regular).
The existing Noto subset does not cover all CJK characters, so missing glyphs
continue to use the device fallback.

JetBrains Mono Regular is the upstream static TTF from
https://github.com/JetBrains/JetBrainsMono/blob/master/fonts/ttf/JetBrainsMono-Regular.ttf
(SHA256 `e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1`,
270224 bytes); its OFL is preserved in `licenses/JetBrainsMono-OFL.txt`.

Hanken Grotesk SemiBold (`fonts/hanken_grotesk_semibold.ttf`, family
`TakuBodySemiBold`, Android only) is the weight-600 instance of Google Fonts'
`HankenGrotesk[wght].ttf` v3.013, cut with the fontTools instancer exactly as the
bundled Regular and Medium were (the same recipe reproduces those two byte for
byte). Source commit, hashes and steps are in
`fonts/hanken_grotesk_semibold.source.json`; it shares `licenses/HankenGrotesk-OFL.txt`.
Home uses it only for the onboarding device code (Figma Sub Heading/lg).
