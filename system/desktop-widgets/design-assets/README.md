# Widget design resources

Source: [Taku Mobile OS, 4-1](https://www.figma.com/design/MdImIDNqePpNZ62C0GLArW/Taku-Mobile-OS?node-id=1-5326).
The SVG files are unchanged Figma MCP exports; `shared-res/drawable-nodpi` contains their
3× PNG renders for ordinary Android RemoteViews. No Figma URL is loaded at runtime.

| Asset | Figma node | Rendered slot |
| --- | --- | --- |
| clock-center | 1:5350 | 134×134, centered in the 182×182 clock |
| clock-ticks | 1:5354 | 182×182 clock face with the original 3.964 overscan |
| vinyl | 1:5696 | 56×56, offset (40,12) behind the 80×80 album |
| skip-back | 1:5703 | 24×24 centered inside the 40×40 previous button |
| play | 1:5706 | 20×20 centered inside the 40×40 play button |
| skip-forward | 1:5709 | 24×24 centered inside the 40×40 next button |
| sun | 1:5375 | 40×40 centered for clear/mainly clear; 26×26 behind the cloud for partly cloudy |

Album art, time, date and weather are live application data. Other weather conditions use
the original `PixelGlyphs` 9×9 dot grammar; the unavailable album uses its exact music pattern.
Weather codes 0/1 show the sun, 2 combines that unchanged sun asset with a foreground dot
cloud in the same 40×40 slot, and 3 shows only the cloud. Fog, rain, snow and thunderstorm
retain their separate symbols (seven weather symbols total).
Today remaining replaces Sleep as requested and retains the 24-dot presentation.

The shared fonts are byte-identical copies of `system/assistant/res/font` Space Grotesk
Regular and Hanken Grotesk Regular, covered by the existing licenses in
`system/assistant/design-system/licenses/`, also bundled in each APK under `res/raw`. The shared renderer follows the original
`taku-home/design/{Tokens,Theme,Components}.kt` values: Surface/900, Content/800,
24dp radius, 12dp inset, 40dp controls, Heading/4xl and Heading/2xl, Body/Regular/md.

To regenerate PNGs, render each original SVG at zoom 3 using `@resvg/resvg-js` (2.6.2),
with no other modifications. Font files and PNGs are checked in so normal APK/AOSP builds
do not need a renderer, network access or another dependency.
