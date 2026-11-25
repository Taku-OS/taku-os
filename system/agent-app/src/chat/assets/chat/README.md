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

The files are the Color=Light (white) artwork. Each component's Color=Dark variant is the same
geometry in Main/Foreground (#1B1B1B), so `ChromeIcon` paints the white artwork in the active
chat palette's icon colour instead of shipping a second copy. `icons.json` holds the same SVG
source for `SvgXml`; keep it synchronized when replacing an SVG.
