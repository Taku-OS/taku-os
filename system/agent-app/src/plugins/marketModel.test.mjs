import test from 'node:test';
import assert from 'node:assert/strict';
import {installedStrip, marketSections} from './marketModel.ts';

const plugin = (id, name, extra = {}) => ({id, name, author: 'A', version: '1', sourceId: 'local', trust: 'local', description: `${name} tools`,
  category: 'Local', tags: [], capabilities: [], permissions: [], installed: false, ...extra});

test('installed plugins group by category, A-Z, with recommended entries under Popular first', () => {
  const installed = [plugin('notion', 'Notion', {category: 'Productivity', installed: true}), plugin('figma', 'Figma', {category: 'Design', installed: true})];
  const catalog = [plugin('linear', 'Linear', {category: 'Productivity'}), plugin('tripsy', 'Tripsy', {category: 'Life', recommended: true})];
  const sections = marketSections(installed, catalog, '', 'Popular');
  assert.deepEqual(sections.map(s => s.title), ['Popular', 'Design', 'Productivity']);
  assert.deepEqual(sections[2].items.map(i => i.id), ['linear', 'notion']);
  assert.equal(sections[2].items.find(i => i.id === 'notion').installed, true);
});

test('an installed record replaces its catalog entry and keeps catalog fields it lacks', () => {
  const sections = marketSections([plugin('figma', 'Figma', {installed: true, enabled: true})], [plugin('figma', 'Figma', {recommended: true})], '', 'Popular');
  assert.equal(sections.length, 1);
  assert.equal(sections[0].popular, true);
  assert.equal(sections[0].items[0].installed, true);
});

test('a query filters across name, description and category; blank categories read Other', () => {
  const installed = [plugin('figma', 'Figma', {installed: true, category: ''}), plugin('notion', 'Notion', {installed: true, category: 'Docs'})];
  assert.deepEqual(marketSections(installed, [], 'docs', 'Popular').map(s => s.items.map(i => i.id)), [['notion']]);
  assert.deepEqual(marketSections(installed, [], '', 'Popular').map(s => s.title), ['Docs', 'Other']);
  assert.deepEqual(marketSections(installed, [], 'zzz', 'Popular'), []);
});

test('the installed strip shows seven marks, or six and +N', () => {
  assert.deepEqual(installedStrip([1, 2, 3]), {shown: [1, 2, 3], more: 0});
  assert.deepEqual(installedStrip([1, 2, 3, 4, 5, 6, 7]), {shown: [1, 2, 3, 4, 5, 6, 7], more: 0});
  assert.deepEqual(installedStrip([1, 2, 3, 4, 5, 6, 7, 8, 9]), {shown: [1, 2, 3, 4, 5, 6], more: 3});
});
