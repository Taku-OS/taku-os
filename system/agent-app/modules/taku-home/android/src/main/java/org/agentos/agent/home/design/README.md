# Taku design system (native)

Shared by every native Compose screen of TakuOS home. Card bodies and other AI-generated UI
use the React Native `taku-feed-1` catalog (`system/agent-app/src/feed/feedTokens.ts`), which
maps the same Figma variables.

## Rules

* Screens never write colour values. They use `TakuTheme.colors.<role>`; only
  `Tokens.kt` holds values (`TakuPalette` = Figma variables, private to this package).
  `DesignSystemTest` fails on colour literals under `ui/`.
* Text uses `TakuTheme.type.<Figma style>`; spacing, radii, sizes, blur, elevation and motion
  come from `TakuSpacing`, `TakuRadius`, `TakuSize`, `TakuBlur`, `TakuElevation`, `TakuMotion`.
  Geometry that belongs to one composition (e.g. the clock digits' offsets) stays local.
* Add a role or component when a screen needs it, not before.

## Theme

`TakuTheme(dark = false) { … }` provides `TakuColors.light` (the Figma frames) or
`TakuColors.dark`. Home is light-only in the design; the dark palette is a first pass for the
same roles, so switching later is a change to `TakuColors.dark` and the `dark` argument.

| Role group | Roles |
|---|---|
| Text / icons | `textPrimary` (Content/900), `textSecondary` (/800), `textTertiary` (/700), `textPlaceholder` (/600), `textOnInverse`, `icon`, `iconOnInverse`, `iconDisabled`, `iconFaint` |
| Surfaces | `canvas`, `surfaceCard` (Surface/900), `surfaceRaised` (/800), `surfaceGlass` (/600), `surfaceFrosted` (/500), `surfaceInverse` (Main/Foreground), `surfaceMuted` (Main/Surface/Secondary), `surfaceIcon`, `fillSubtle` (Content/50), `fillPlaceholder` (/100), `fillControl` (/200) |
| Lines | `strokeHairline`, `strokeFrosted`, `strokeDropTarget`, `strokeDropTargetActive` |
| Effects | `scrim`, `scrimLight`, `shadowMenu`, `shadowBadge`, `textGlow`, `edgeFade`, `glassRim`, `dataInk` |
| Accents | `accentPositive` (green/300), `accentBrand` (brand/200), `accentSend` (Main/Brand) |
| AI entry / island | `islandSurface` (Content/900), `islandSolid` (camera pill), `islandText`, `islandTextMuted`, `islandInk`, `islandChip`, `islandChipText`, `islandTextStrong`, `islandField`, `islandPlaceholder`, `islandAction`/`islandActionText`, `islandBrand`, `islandActionDisabled`/`islandActionDisabledText` (onboarding card, Figma 1155:527/619/846) — dark in both themes; `voiceAurora` (1-1 blobs), `workingGlow` (1-4 edge) |

## Components

| Component | Use |
|---|---|
| `GlassSurface` | Wallpaper blurred σ16 under a role fill, any shape (cards, widgets, pills, app plates) |
| `Backdrop` | The pre-blurred wallpaper that glass samples |
| `TakuButton` | `Primary` (agent action), `Secondary`, `Glass` (floating menu), `Frosted` (quiet destructive); `Large` 40 / `Medium` 32; optional icon |
| `TakuIconButton` | `Inverse` (play), `Subtle` (prev/next), `Badge` (24 dp edit "−") |
| `TakuSwitch` | 40×24 switch; display-only or toggleable |
| `TakuTextField` | Single-line pill input (folder names) |
| `AnchoredMenu` + `MenuAction` | Pill actions anchored below/above an element (card and icon menus) |
| `Scrim`, `TakuDialog` | Dismissable dimming layer; centred frosted panel (folders) |
| `PageDots`, `Vinyl`, `PixelGlyph`, `TakuIcon` | Launcher dots, record behind album art, dot-matrix glyphs, Figma vector icons |
| `rememberAppear`, `Modifier.staggeredAppear` | Shared entrance motion |
| `WorkingDots` (`ui/assistant`) | Composer recognition indicator only. SystemUI owns island eyes, cards and compact animation. |

Launcher dropdowns use `LauncherGlass`: a live scene layer with local σ8 dp blur, 24% milky
tint, rounded-edge refraction and a subtle glass rim. The home scene stays sharp under a light
scrim; the pressed app/folder plate is excluded using its measured bounds. Menu rows share
one surface without separators, with original Lucide SVG icons in a leading 24 dp column
and a 16 dp label gap. Source revision and licenses are in `assets/launcher/menu`.
Removal uses `accentDestructive`. Selectors are not yet built; segmented
controls in cards remain part of the React Native catalog.

The central voice transcript explicitly uses bundled `noto_sans_sc_regular` when it contains Han
characters, with zero letter spacing. Latin-only transcripts retain Hanken Regular 18/27.9 and
its design tracking; no device-font fallback determines the Chinese face.
