import test from 'node:test';
import assert from 'node:assert/strict';
import {matchingChats, recentTime, shortDate} from './homeModel.ts';

const threads = [
  {id: 'a', title: 'Weekend ideas', preview: 'Outdoor', updatedAt: 1793975562},
  {id: 'b', title: 'Kyoto', preview: 'Trip plan', updatedAt: 1793968446},
];

test('search matches titles and previews', () => {
  assert.deepEqual(matchingChats(threads, 'trip').map(t => t.id), ['b']);
  assert.deepEqual(matchingChats(threads, '  ').map(t => t.id), ['a', 'b']);
});

test('dates read like 2-5 in both languages; seconds and milliseconds', () => {
  const day = new Date(2026, 10, 6, 12).getTime();
  assert.equal(shortDate(day, 'en'), '6 Nov');
  assert.equal(shortDate(day / 1000, 'zh'), '11月6日');
});

test('sidebar search times read minutes, then hours, then the short date', () => {
  const now = Date.UTC(2026, 10, 25, 12, 0, 0);
  assert.equal(recentTime(now - 2 * 60_000, now, 'en'), '2 mins');
  assert.equal(recentTime(now - 30_000, now, 'en'), '1 min');
  assert.equal(recentTime(now + 60_000, now, 'en'), '1 min');
  assert.equal(recentTime((now - 3 * 3_600_000) / 1000, now, 'en'), '3 hrs');
  assert.equal(recentTime(now - 2 * 60_000, now, 'zh'), '2 分钟');
  assert.equal(recentTime(now - 26 * 3_600_000, now, 'en'), shortDate(now - 26 * 3_600_000, 'en'));
});
