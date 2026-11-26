# Chat chrome icons

Original vectors exported from the Figma "Chat Polish" section
([1-0 chat](https://www.figma.com/design/MdImIDNqePpNZ62C0GLArW/Taku-Mobile-OS?node-id=1628-1774),
[1-1 +menu](https://www.figma.com/design/MdImIDNqePpNZ62C0GLArW/Taku-Mobile-OS?node-id=1482-1018)):

| File | Figma component |
| --- | --- |
| `burger.svg` | Burger (1482:2555), Color=Light frame 1482:2557 |
| `collapse.svg` | Expand and Collapse, Type=Collapse (1:3410) |
| `camera.svg` | Camera (1482:2653) |
| `photos.svg` | Photos (1482:2660) |
| `files.svg` | File attachment (1482:2669) |
| `plugins.svg` | Plugins (1482:2575) |
| `panels.svg` / `panelsSmall.svg` | Panels (1482:2564), 24 dp and the 18 dp panel-chip glyph (1482:2639) |
| `settings.svg` | Settings (339:31) |
| `search.svg` / `searchField.svg` | Search (1:3393), 24 dp and the 18 dp Surface/500 field glyph (2-2) |
| `close.svg` / `closeSmall.svg` | Close (297:355), 24 dp and the 8 dp chip close (1482:2641) |
| `newChat.svg` | New Chat (300:653), Color=Dark (#1B1B1B) as on the white New Chat pill |
| `chevron.svg` | Chevron back (1482:1498) |
| `more.svg` | More (1482:800) |
| `edit.svg` / `aiEdit.svg` / `trash.svg` | Edit (1482:935), AI Edit and Trash (#FF4856) menu glyphs, 20 dp (3-1-2) |

Sources are from frames 1-0, 1-1, 2-1, 2-2, 3-1-2, 3-2-1 and 4-1. The files are the Color=Light
(white) artwork, except `newChat.svg` (Color=Dark) and the red Trash. Each component's Color=Dark variant is the same
geometry in Main/Foreground (#1B1B1B), so `ChromeIcon` paints the white (or #1B1B1B) artwork in the active
chat palette's icon colour instead of shipping a second copy. `icons.json` holds the same SVG
source for `SvgXml`; keep it synchronized when replacing an SVG.
