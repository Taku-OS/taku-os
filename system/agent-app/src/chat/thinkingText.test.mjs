import test from 'node:test';
import assert from 'node:assert/strict';
import {tailRuns, thinkingDuration, thinkingHeader, thinkingRuns} from './thinkingText.ts';

const text = runs => runs.map(run => run.text).join('');
const thinking = patch => ({id: 'turn/thinking/u', text: '', active: false, ...patch});

test('a summary reads as prose with bold headings and never shows its asterisks', () => {
  assert.deepEqual(thinkingRuns('**Weighing the week**\n\nMornings are free. **Next** step.'), [
    {text: 'Weighing the week', strong: true}, {text: '\n\nMornings are free. ', strong: false},
    {text: 'Next', strong: true}, {text: ' step.', strong: false}]);
  // A heading still being written is already a heading; an unfinished marker leaves no stray mark.
  assert.deepEqual(thinkingRuns('Done.\n\n**Compar'), [{text: 'Done.\n\n', strong: false}, {text: 'Compar', strong: true}]);
  assert.deepEqual(thinkingRuns('Done.\n\n**'), [{text: 'Done.\n\n', strong: false}]);
  assert.deepEqual(thinkingRuns('先比较两条路线 2 * 3 = 6'), [{text: '先比较两条路线 2 * 3 = 6', strong: false}]);
  assert.deepEqual(thinkingRuns(''), []);
});

test('the live window lays out only the end of the reasoning, with each run keeping its weight', () => {
  const runs = thinkingRuns(`${'Earlier thought. '.repeat(200)}**Latest heading**\n\nThe newest lines.`);
  const tail = tailRuns(runs, 40);
  assert.equal(text(tail).length, 40);
  assert.equal(text(tail), 'ought. Latest heading\n\nThe newest lines.');
  assert.deepEqual(tail.map(run => run.strong), [false, true, false]);
  // The whole text when it is short; nothing at all for no room.
  assert.deepEqual(tailRuns(thinkingRuns('**Short**'), 600), [{text: 'Short', strong: true}]);
  assert.deepEqual(tailRuns(runs, 0), []);
  // A cut never opens on half of an emoji.
  const emoji = tailRuns([{text: 'ab🙂cd', strong: false}], 3);
  assert.deepEqual(emoji, [{text: 'cd', strong: false}]);
  assert.deepEqual(tailRuns([{text: 'ab🙂cd', strong: false}], 4), [{text: '🙂cd', strong: false}]);
});

test('durations read naturally in both languages and never as zero', () => {
  assert.equal(thinkingDuration(21_400, 'en'), '21s');
  assert.equal(thinkingDuration(21_500, 'en'), '22s');
  assert.equal(thinkingDuration(300, 'en'), '1s');
  assert.equal(thinkingDuration(59_600, 'en'), '1m 00s');
  assert.equal(thinkingDuration(72_000, 'en'), '1m 12s');
  assert.equal(thinkingDuration(605_000, 'en'), '10m 05s');
  assert.equal(thinkingDuration(21_400, 'zh'), '21 秒');
  assert.equal(thinkingDuration(72_000, 'zh'), '1 分 12 秒');
});

test('live reasoning says Thinking and counts whole seconds only from a known start', () => {
  const live = thinking({active: true, text: 'Comparing', startedAtMs: 10_000});
  assert.deepEqual({...thinkingHeader(live, 'en', 10_400), hint: undefined}, {label: 'Thinking', elapsed: '', expandable: false, hint: undefined});
  assert.equal(thinkingHeader(live, 'en', 22_900).elapsed, '12s');
  assert.equal(thinkingHeader(live, 'zh', 22_900).label, '思考中');
  assert.equal(thinkingHeader(live, 'zh', 22_900).elapsed, '12 秒');
  assert.equal(thinkingHeader(live, 'en', 85_000).elapsed, '1m 15s');
  // A clock read before the start (a stale tick) shows no count rather than a wrong one.
  assert.equal(thinkingHeader(live, 'en', 9_000).elapsed, '');
  // Thinking again after a tool counts on from the time already spent.
  assert.equal(thinkingHeader(thinking({active: true, startedAtMs: 10_000, durationMs: 3_000}), 'en', 12_500).elapsed, '5s');
  // No reported start: no count, only the word.
  assert.equal(thinkingHeader(thinking({active: true, text: 'Comparing'}), 'en', 99_000).elapsed, '');
  assert.equal(thinkingHeader(live, 'en', 22_900).hint(false), '', 'live reasoning has nothing to open');
});

test('finished reasoning names its time when known and opens only when there is text', () => {
  const done = thinkingHeader(thinking({text: 'Mornings are free.', durationMs: 21_400}), 'en', 0);
  assert.equal(done.label, 'Thought for 21s');
  assert.equal(done.elapsed, '');
  assert.equal(done.expandable, true);
  assert.equal(done.hint(false), 'Show thinking');
  assert.equal(done.hint(true), 'Hide thinking');
  const zh = thinkingHeader(thinking({text: '上午有空。', durationMs: 72_000}), 'zh', 0);
  assert.equal(zh.label, '已思考 1 分 12 秒');
  assert.equal(zh.hint(false), '展开思考过程');
  // Without a reported time the row says what it holds, in either language.
  assert.equal(thinkingHeader(thinking({text: 'Mornings are free.'}), 'en', 0).label, 'Thought process');
  assert.equal(thinkingHeader(thinking({text: '上午有空。'}), 'zh', 0).label, '思考过程');
  // Time without text (the text was not kept): the row states the time and does not open.
  const timeOnly = thinkingHeader(thinking({durationMs: 2_000}), 'en', 0);
  assert.equal(timeOnly.label, 'Thought for 2s');
  assert.equal(timeOnly.expandable, false);
  assert.equal(timeOnly.hint(false), '');
});
