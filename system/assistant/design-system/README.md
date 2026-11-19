# Taku mobile design-system resources

This directory records the official design-team handoff used by the Assistant's
Chat UI. Its source is [Taku-OS/taku-os-mobile-design-system](https://github.com/Taku-OS/taku-os-mobile-design-system)
at commit `355e8c2d4edc436b6664e30c3edb396f732ad76c` (mobile DS v0.8,
2026-10-27). The upstream `SOURCE.json` calls this package
`ready-for-developer-review`.

The handoff version is **0.8**, while its unchanged `tokens.json` identifies its
token set as **0.7.0**, dated 2026-10-26. These are the values in the supplied
package; this import does not silently rewrite either version.

## Imported files

- All 227 files from `native-assets/android/res/` are copied into `../res/`.
  The import checked both paths and named XML resources: no existing resources
  were overwritten. `source.json` records every copied file's SHA-256.
- `../src/org/agentos/assistant/TakuText.java` is the upstream helper with only
  its package changed from `com.taku.design` to `org.agentos.assistant`.
- `ChatDesign.java` maps explicit Chat text roles to the upstream appearances
  and caches the bundled Latin/Chinese fallback font families. It preserves the
  caller's text color and does not change layout or select a theme.
- `tokens.json`, `upstream-SOURCE.json`, the icon provenance manifest and the
  third-party notices retain the original handoff information. Font licenses
  and the Material Symbols license are in `licenses/`. The imported notice
  links resolve to the pinned upstream handoff because their original relative
  targets are not bundled here. The notice text and license files are retained.

The package supports **light appearance only**. Its imported theme is not
applied globally by this change. Existing dark surfaces keep their own palette;
do not claim the design team supplied or approved a dark variant.

## Using the resource roles

Set the view's content and intended semantic color, then call
`ChatDesign.text(view, ChatDesign.Role.BODY)` (or its explicit role). Call again
after changing between Chinese and Latin content. `CONTEXT` is the current Chat
name; `HEADING` is a principal result title; `META` is process or preview text;
`CAPTION` is a timestamp or source. The roles are not interchangeable merely to
fit content into less space.

Use one 16dp screen gutter and a 12dp Chat item gap. Body and input text use
16sp/24sp. Main actions use the ink button, with at most one per card; orange
is reserved for small status accents. UI icon controls use a 48dp target and a
20dp glyph. Keep brand and app icons, including `ic_tako_mark`, untinted.

The composer remains within the host's available height. Its text area grows
from 48dp to 144dp, or at most 96dp when the available host height is below
500dp. Keyboard insets must be applied once by the host.

## Updating and validation

Generated XML must remain identical to the upstream export. Make token changes
in the upstream source, regenerate there, then review the full import and
update `source.json`; do not hand-edit generated XML in the app.

Upstream contract validation checks package consistency, not Android behavior.
The design handoff itself did not run Android SDK compilation or real-device
rendering. Application compilation, bilingual layouts, keyboard, safe areas,
font scaling and actual Chat state restoration must be verified separately.

Retain the files in `licenses/` when distributing these fonts and icons; see
`THIRD_PARTY_NOTICES.md` for font, icon, and trademark terms. The two upstream
reference screenshots are internal references and are not included in this app import.
