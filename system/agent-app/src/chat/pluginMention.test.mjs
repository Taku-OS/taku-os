import test from 'node:test';
import assert from 'node:assert/strict';
import {insertMentionTrigger, matchingPlugins, mentionAt, removeMention} from './pluginMention.ts';

test('an @ at a word start opens a mention; its query is what follows', () => {
  assert.deepEqual(mentionAt('@', 1), {start: 0, end: 1, query: ''});
  assert.deepEqual(mentionAt('ask @fig', 8), {start: 4, end: 8, query: 'fig'});
  assert.deepEqual(mentionAt('line\n@no', 8), {start: 5, end: 8, query: 'no'});
});

test('an @ inside a word, after whitespace, or behind the caret is not a mention', () => {
  assert.equal(mentionAt('mail@example', 12), null);
  assert.equal(mentionAt('@fig ma', 7), null);
  assert.equal(mentionAt('@fig', 0), null);
  assert.equal(mentionAt('plain', 5), null);
});

test('the caret decides which mention is open', () => {
  assert.deepEqual(mentionAt('@fig and more', 4), {start: 0, end: 4, query: 'fig'});
});

test('removing a mention keeps the rest of the text and puts the caret at the @', () => {
  assert.deepEqual(removeMention('ask @fig now', {start: 4, end: 8, query: 'fig'}), {text: 'ask  now', caret: 4});
  assert.deepEqual(removeMention('@', {start: 0, end: 1, query: ''}), {text: '', caret: 0});
});

test('the Plugins menu types an @ that opens the suggestions', () => {
  assert.deepEqual(insertMentionTrigger('', 0), {text: '@', caret: 1});
  assert.deepEqual(insertMentionTrigger('hello', 5), {text: 'hello @', caret: 7});
  assert.deepEqual(insertMentionTrigger('hello ', 6), {text: 'hello @', caret: 7});
  const typed = insertMentionTrigger('hello', 5);
  assert.deepEqual(mentionAt(typed.text, typed.caret), {start: 6, end: 7, query: ''});
});

test('plugins filter by name or id, names that start with the query first', () => {
  const plugins = [{id: 'notion', name: 'Notion'}, {id: 'figma', name: 'Figma'}, {id: 'g-cal', name: 'Google Calendar'}, {id: 'config', name: 'Settings'}];
  assert.deepEqual(matchingPlugins(plugins, '').map(p => p.id), ['figma', 'g-cal', 'notion', 'config']);
  assert.deepEqual(matchingPlugins(plugins, 'fi').map(p => p.id), ['figma', 'config']);
  assert.deepEqual(matchingPlugins(plugins, 'CAL').map(p => p.id), ['g-cal']);
  assert.deepEqual(matchingPlugins(plugins, 'zzz'), []);
});
