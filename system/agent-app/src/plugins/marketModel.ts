/** Plugin Market overview (Figma 4-1) rules, kept free of React for tests. */
import type {PluginSummary} from './contracts';

export type MarketSection = {key: string; title: string; popular: boolean; items: PluginSummary[]};

/**
 * Installed plugins and catalog entries, one row per plugin id (the installed record wins), in
 * sections: "Popular" for recommended entries first, then one section per category, A–Z. A query
 * filters by name, author, description, category, tags and capabilities first.
 */
export function marketSections(installed: readonly PluginSummary[], catalog: readonly PluginSummary[], query: string, popularTitle: string): MarketSection[] {
  const byId = new Map<string, PluginSummary>();
  for (const item of catalog) byId.set(item.id, item);
  for (const item of installed) byId.set(item.id, {...byId.get(item.id), ...item, installed: true});
  const needle = query.trim().toLocaleLowerCase();
  const matches = (item: PluginSummary) => !needle || [item.name, item.author, item.description, item.category, ...item.tags, ...item.capabilities]
    .some(value => value.toLocaleLowerCase().includes(needle));
  const sorted = [...byId.values()].filter(matches).sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  const sections: MarketSection[] = [];
  const popular = sorted.filter(item => item.recommended);
  if (popular.length) sections.push({key: 'popular', title: popularTitle, popular: true, items: popular});
  const categories = new Map<string, PluginSummary[]>();
  for (const item of sorted) {
    if (item.recommended) continue;
    const category = item.category.trim() || 'Other';
    categories.set(category, [...categories.get(category) ?? [], item]);
  }
  for (const [title, items] of [...categories.entries()].sort(([a], [b]) => a.localeCompare(b)))
    sections.push({key: `category:${title}`, title, popular: false, items});
  return sections;
}

/** The Installed strip: up to [slots] marks, the last slot a "+N" when more are installed. */
export function installedStrip<T>(installed: readonly T[], slots = 7): {shown: T[]; more: number} {
  if (installed.length <= slots) return {shown: [...installed], more: 0};
  return {shown: installed.slice(0, slots - 1), more: installed.length - (slots - 1)};
}

/** A section shows four rows until "See all" opens the rest. */
export const SECTION_PREVIEW = 4;

/**
 * A skill as people read it: a registered `skills/<name>/SKILL.md` path shows as its folder name
 * (issue #52), any other registration as given.
 */
export function skillLabel(skill: string): string {
  const match = /(?:^|\/)([^/]+)\/SKILL\.md$/i.exec(skill.trim());
  return match ? match[1] : skill.trim();
}
