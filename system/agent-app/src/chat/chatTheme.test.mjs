import test from 'node:test';
import assert from 'node:assert/strict';
import {applyChatAppearance, chatBlur, chatColors, getChatAppearance, themed, themedStyles} from './chatTheme.ts';

test('Dark is the default and matches the design backdrop: Content/900 over a moderate blur', () => {
  assert.equal(getChatAppearance(), 'dark');
  assert.equal(chatColors.backdropTint, 'rgba(0,0,0,0.92)');
  assert.equal(chatColors.backdropTintArgb, '#EB000000');
  assert.equal(chatBlur.backdropSigma, 16);
  assert.equal(chatColors.bubble, 'rgba(255,255,255,0.8)', 'Surface/800 user bubble');
  assert.equal(chatColors.assistantBubble, 'rgba(255,255,255,0.06)', 'Surface/100 reply bubble');
});

test('every chat surface follows a switch to Light and back', () => {
  let builds = 0;
  const styles = themedStyles(() => { builds++; return {page: {backgroundColor: chatColors.backdropFallback}}; });
  const colors = themed(() => ({ink: chatColors.textPrimary}));
  assert.equal(styles.page.backgroundColor, '#121212');
  applyChatAppearance('light');
  try {
    assert.equal(chatColors.backdropTintArgb, '#3DFFFFFF', 'the earlier light chat: Surface/400 under the blur');
    assert.equal(chatColors.backdropFallback, '#E3E3E3');
    assert.equal(chatColors.textPrimary, 'rgba(0,0,0,0.92)');
    assert.equal(chatBlur.backdropSigma, 64);
    assert.equal(styles.page.backgroundColor, '#E3E3E3');
    assert.equal(colors.ink, 'rgba(0,0,0,0.92)');
    assert.deepEqual({...colors}, {ink: 'rgba(0,0,0,0.92)'}, 'a live value spreads like a plain object');
  } finally { applyChatAppearance('dark'); }
  assert.equal(styles.page.backgroundColor, '#121212');
  void styles.page;
  assert.equal(builds, 2, 'each appearance builds its sheet once');
});

/** WCAG contrast of two opaque #RRGGBB colours. */
function contrast(a, b) {
  const luminance = hex => [1, 3, 5].map(at => parseInt(hex.slice(at, at + 2), 16) / 255)
    .map(channel => channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
    .reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0);
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (light + 0.05) / (dark + 0.05);
}

test('the two Task status hues are the wireframe’s on Dark and stay readable on both canvases', () => {
  assert.equal(chatColors.statusAttention, '#F4C275', 'amber: the Task waits for the user');
  assert.equal(chatColors.statusProgress, '#B5D0EE', 'blue-grey: the Task is at work');
  const read = () => [chatColors.statusAttention, chatColors.statusProgress].map(hue => contrast(hue, chatColors.backdropFallback));
  for (const ratio of read()) assert.ok(ratio >= 4.5, `Dark: ${ratio.toFixed(2)}:1`);
  applyChatAppearance('light');
  try {
    for (const ratio of read()) assert.ok(ratio >= 4.5, `Light: ${ratio.toFixed(2)}:1`);
  } finally { applyChatAppearance('dark'); }
});
