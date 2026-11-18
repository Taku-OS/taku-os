# Launcher icon assets

`phone.svg`, `browser.svg`, `message.svg`, and `camera.svg` are the unmodified
Figma exports from Taku Mobile OS, frame `1:5326` (4-1), nodes `1:5714`,
`1:5719`, `1:5725`, and `1:5730`. The 40 px message glyph is centered in a
48 dp slot; the other exports retain their 48 px viewports.

The remaining icons cover installed utilities that have no artwork in that
frame. They are Google Material Icons (filled, 24 px), from
https://github.com/google/material-design-icons/tree/master/src, distributed
under Apache-2.0 (see LICENSE-material.txt). Their 24 px artwork is centered
in a 32 px viewport to match the visual weight of the Figma icons.

Source names: calendar_today, access_time, contacts, folder, photo, settings,
search, sim_card, calculate, android, chat, view_quilt, memory, flash_on,
keyboard, apps. The app catalog renders these local vectors off the main
thread and caches the resulting bitmaps, using the design's #1B1B1B ink.
Third-party app icons remain supplied by their installed packages.
