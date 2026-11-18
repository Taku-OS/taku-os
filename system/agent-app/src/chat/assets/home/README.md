# Taku Home artwork

Original assets exported from [Figma frame 300:1149](https://www.figma.com/design/MdImIDNqePpNZ62C0GLArW/Taku-Mobile-OS?node-id=300-1149).

The PNG files are the panel photos and app icons. The SVG files retain the original search,
collapse, new-chat and colored panel-band geometry and paint. `assets.json` contains the
same SVG source for `SvgXml`; keep it synchronized when replacing an SVG. All artwork is
bundled locally, with no runtime dependency on temporary Figma asset URLs.

The screen uses the chat window's translucent dark tint and native backdrop blur. Panel
cards use the existing native progressive blur under their lower bands; titles and emoji
remain sharp. The displayed chat history and actions come from the existing runtime.
