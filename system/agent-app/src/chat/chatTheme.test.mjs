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
