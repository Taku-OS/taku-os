/**
 * `@` plugin mentions in the composer (Figma 1-2 / 1-3). Typing `@` at a word start opens the
 * plugin suggestions; the characters after it filter them. Picking one removes the typed
 * `@query` and attaches the plugin to the draft instead, where it shows as a chip.
 */
export type PluginMention = {start: number; end: number; query: string};

/** The `@query` ending at [caret], when it starts a word and holds no whitespace. */
export function mentionAt(text: string, caret: number): PluginMention | null {
  const end = Math.max(0, Math.min(caret, text.length));
  const before = text.slice(0, end);
  const match = /(^|\s)@([^\s@]{0,64})$/.exec(before);
  if (!match) return null;
  const start = end - match[2].length - 1;
  return {start, end, query: match[2]};
}

/** [text] without the mention; the caret goes where the `@` was. */
export function removeMention(text: string, mention: PluginMention): {text: string; caret: number} {
  return {text: text.slice(0, mention.start) + text.slice(mention.end), caret: mention.start};
}

/** [text] with an `@` typed at [caret], spaced from a preceding word so it opens the suggestions. */
export function insertMentionTrigger(text: string, caret: number): {text: string; caret: number} {
  const at = Math.max(0, Math.min(caret, text.length));
  const before = text.slice(0, at);
  const trigger = before === '' || /\s$/.test(before) ? '@' : ' @';
  return {text: before + trigger + text.slice(at), caret: at + trigger.length};
}

/** Plugins whose name or id contains [query] (case-insensitive), names starting with it first. */
export function matchingPlugins<T extends {id: string; name: string}>(plugins: readonly T[], query: string): T[] {
  const needle = query.trim().toLocaleLowerCase();
  const rank = (plugin: T) => {
    const name = plugin.name.toLocaleLowerCase();
    return !needle ? 0 : name.startsWith(needle) ? 0 : name.includes(needle) ? 1 : plugin.id.toLocaleLowerCase().includes(needle) ? 2 : -1;
  };
  return plugins.map(plugin => ({plugin, rank: rank(plugin)})).filter(entry => entry.rank >= 0)
    .sort((left, right) => left.rank - right.rank || left.plugin.name.localeCompare(right.plugin.name) || left.plugin.id.localeCompare(right.plugin.id))
    .map(entry => entry.plugin);
}
