import test from 'node:test';
import assert from 'node:assert/strict';
import {matchingChats, shortDate} from './homeModel.ts';

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
