#!/usr/bin/env python3
"""Re-encode Figma MCP downloads for the bundled sample feed.

Usage: python3 -I import-figma-assets.py FIGMA_DOWNLOAD_DIR
FIGMA_DOWNLOAD_DIR holds the untrusted `feed/` and `0-0/` downloads. Images are
decoded with Pillow, cropped/resized to 3x their design size and re-encoded; SVGs
are copied byte-for-byte. Nothing from the download directory is executed.
"""
import pathlib, shutil, sys
from PIL import Image

src = pathlib.Path(sys.argv[1]).resolve()
out = pathlib.Path(__file__).resolve().parent.parent / 'android/src/main/assets/feed'

def fit(path, dest, size, square=False, crop_top=False, fmt='webp', aspect=None):
    im = Image.open(src / path)
    im.load()
    if square or aspect:
        ratio = aspect or 1.0
        w, h = im.size
        if w / h > ratio:
            nw = int(h * ratio); left = (w - nw) // 2; im = im.crop((left, 0, left + nw, h))
        else:
            nh = int(w / ratio); top = 0 if crop_top else (h - nh) // 2; im = im.crop((0, top, w, top + nh))
    im.thumbnail(size, Image.LANCZOS)
    target = out / dest
    target.parent.mkdir(parents=True, exist_ok=True)
    if fmt == 'jpg':
        im.convert('RGB').save(target, 'JPEG', quality=88, optimize=True, progressive=True)
    else:
        im.save(target, 'WEBP', quality=88, method=6)

fit('0-0/wallpaper.png', 'home/wallpaper.jpg', (1240, 2760), aspect=412 / 917, fmt='jpg')
fit('feed/header/avatar-user.png', 'home/avatar-user.webp', (144, 144), square=True)
fit('0-0/avatar-jack.png', 'slack/avatar-jack.webp', (96, 96), square=True, crop_top=True)
fit('feed/slack/avatar-mary.png', 'slack/avatar-mary.webp', (96, 96), square=True, crop_top=True)
fit('feed/slack/relevant-tag-blur-bg.png', 'slack/relevant-tag-bg.webp', (210, 150))
fit('feed/pills/album-cover-brat-layer1.png', 'music/cover-brat.webp', (144, 144), square=True)
fit('feed/pills/album-cover-brat-layer2.png', 'music/cover-brat-overlay.webp', (144, 144), square=True)
fit('feed/starbucks/product-iced-latte.png', 'starbucks/iced-latte.webp', (540, 540), square=True)
fit('feed/uber/car-uberx.png', 'uber/car-uberx.webp', (160, 160), square=True)
for name in ('post-malicool', 'post-dizzy', 'post-back-right'):
    fit(f'feed/instagram/{name}.png', f'instagram/{name}.webp', (600, 600), square=True)
for name in ('avatar-dizzy', 'avatar-malicool'):
    fit(f'feed/instagram/{name}.png', f'instagram/{name}.webp', (72, 72), square=True)

svgs = {
    'feed/_shared/icon-ai-sparkle-16-white.svg': 'icons/sparkle-white.svg',
    'feed/_shared/icon-locate-16.svg': 'icons/locate.svg',
    'feed/slack/logo-slack-16.svg': 'slack/logo.svg',
    'feed/slack/icon-ai-sparkle-8.svg': 'icons/sparkle-8.svg',
    'feed/slide-to-lock/icon-unlock-24.svg': 'icons/unlock.svg',
    'feed/slide-to-lock/chevrons-fading.svg': 'icons/chevrons-fading.svg',
    'feed/todo/icon-bulleted-list-16.svg': 'icons/list.svg',
    'feed/tesla/logo-tesla-16.svg': 'tesla/logo.svg',
    'feed/tesla/icon-lightning-12.svg': 'icons/lightning-orange.svg',
    'feed/starbucks/logo-starbucks-16.svg': 'starbucks/logo.svg',
    'feed/starbucks/product-shadow-ellipse.svg': 'starbucks/shadow-ellipse.svg',
    'feed/uber/logo-uber-wordmark.svg': 'uber/wordmark.svg',
    'feed/uber/icon-passenger-16.svg': 'icons/passenger.svg',
    'feed/uber/icon-lightning-12-white.svg': 'icons/lightning-white.svg',
    'feed/uber/map-green-areas.svg': 'uber/map-green-areas.svg',
    'feed/uber/map-street-lines-bg.svg': 'uber/map-street-lines.svg',
    'feed/uber/map-water-bottom.svg': 'uber/map-water.svg',
    'feed/uber/map-main-roads.svg': 'uber/map-main-roads.svg',
    'feed/uber/map-route-line.svg': 'uber/map-route.svg',
    'feed/instagram/logo-instagram-16.svg': 'instagram/logo.svg',
    'feed/instagram/icon-heart-16.svg': 'icons/heart.svg',
    'feed/instagram/icon-comment-16.svg': 'icons/comment.svg',
    'feed/instagram/icon-share-16.svg': 'icons/share.svg',
    'feed/pills/icon-sun-dot-matrix-32.svg': 'icons/sun-dots.svg',
    'feed/pills/icon-moon-dot-matrix-32.svg': 'icons/moon-dots.svg',
    'feed/pills/icon-play-16-white.svg': 'icons/play-white.svg',
}
for s, d in svgs.items():
    (out / d).parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src / s, out / d)
print(out)
