# Figma voice artwork

Source: [Taku Mobile OS](https://www.figma.com/design/MdImIDNqePpNZ62C0GLArW/Taku-Mobile-OS?node-id=297-374).
`provenance.json` records each node, original SVG root dimensions, and source/output SHA-256.
The SVGs are unmodified Figma MCP exports. PNGs are transparent Chrome screenshots at DPR 3
of each SVG in an `object`, with a viewport equal to the ceiling of its root dimensions.
The native renderer scales the whole image to the design coordinates; it does not edit paths.
Rasterization preserves SVG filters/foreignObject unavailable in AndroidSVG. No remote asset
URL is needed at runtime. `voice-blue`/`voice-red` retain export variable names; their actual
fills are red/blue respectively, as recorded in the original SVGs. Text remains native Hanken
Grotesk with project tokens. Normal/activated entry reuse the existing bottom-glass renderer.
