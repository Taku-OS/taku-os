# Native assistant card

Figma file MdImIDNqePpNZ62C0GLArW, node 297:415; expand asset node 297:423.
`expand.svg` is the downloaded original 20×20 asset. `IslandAssistantCard.ExpandGlyph`
uses its exact two paths, 1.66667 stroke and round caps/joins at 20 dp; no icon substitution.
Hanken Grotesk regular/medium fonts reuse the shipped Agent font resources. Every view
and shader is drawn by SystemUI, with no Compose or SurfaceControlViewHost content.

Colors/spacing follow the existing design tokens: black .92, 40 dp corners, 16 dp padding,
18 sp body at 1.55 line height, 16 sp options at 1.5, 8 dp gaps, white .08 option surfaces.
`IslandCardEffects` moves the existing matched native AGSL edge effects into SystemUI.
The authored right-edge region remains 16 dp with flare .25 / dispersion .4. The title is
never filtered; bottom-edge fading is applied only to overflowing response content.
The design's example labels are not product defaults: only actual Agent options are shown.
