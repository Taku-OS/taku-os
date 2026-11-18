# Bundled fonts

Three Latin WOFF2 files are included, with hashes in [manifest.json](https://github.com/Taku-OS/taku-os-mobile-design-system/blob/355e8c2d4edc436b6664e30c3edb396f732ad76c/skill/taku-design-system/assets/fonts/manifest.json). Chinese is in `noto-sans-sc/` (see below).

- **Space Grotesk**: display text; extracted unchanged from the supplied v26 prototype.
- **Hanken Grotesk**: body and controls; extracted unchanged from the supplied v26 prototype.
- **Doto**: numeric Board roles only; extracted unchanged from Stax v9.2. Digits use weight 900 and ROND 100. Punctuation and units do not use Doto.

Doto usage is defined in [numeric composition](https://github.com/Taku-OS/taku-os-mobile-design-system/blob/355e8c2d4edc436b6664e30c3edb396f732ad76c/skill/taku-design-system/references/numeric.md). Preserve all included SIL OFL license notices when distributing the fonts. Production weight coverage must be verified on the target platform.

The Latin WOFF2 files are subsets (Basic Latin and Latin-1). Glyph widths match the full Google Fonts releases exactly. Characters outside the subset, such as pinyin tone marks, fall through to Noto Sans SC, which covers them. Android TTF resources are included under `native-assets/android/res/font/`, with notices in `native-assets/android/licenses/`. Full native variable fonts for other platforms are not bundled.

**Noto Sans SC**: `noto-sans-sc/`, 101 variable-weight WOFF2 chunks split by `unicode-range`, from `@fontsource-variable/noto-sans-sc` 5.3.1 (Google Fonts v41, font version 2.004). Hashes in [its manifest](https://github.com/Taku-OS/taku-os-mobile-design-system/blob/355e8c2d4edc436b6664e30c3edb396f732ad76c/skill/taku-design-system/assets/fonts/noto-sans-sc/manifest.json), license in [OFL.txt](https://github.com/Taku-OS/taku-os-mobile-design-system/blob/355e8c2d4edc436b6664e30c3edb396f732ad76c/skill/taku-design-system/assets/fonts/noto-sans-sc/OFL.txt). `fonts.css` also declares `Space Zh` and `Hanken Zh`: the same Latin files without curly quotes, dashes and ellipsis, used inside Chinese text so those marks come from Noto Sans SC.

Sources: [Space Grotesk OFL](https://github.com/google/fonts/blob/main/ofl/spacegrotesk/OFL.txt), [Hanken Grotesk OFL](https://github.com/google/fonts/blob/main/ofl/hankengrotesk/OFL.txt), [Doto OFL](https://raw.githubusercontent.com/google/fonts/main/ofl/doto/OFL.txt), [Doto upstream](https://github.com/oliverlalan/Doto). Doto's metadata confirms ROND 0–100 and weight 100–900. License texts were retrieved on 2026-10-23.
