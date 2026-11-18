/** Taku Home (2-5) list rules, kept free of React for tests. */

/** One conversation in the Taku Home list; updatedAt may be seconds or milliseconds. */
export type TakuHomeThread = {id: string; title: string; preview: string; updatedAt: number};

/** Figma 2-5 dates: "1 Nov" (day month), "11月1日" in Chinese; thread times may be seconds or ms. */
export function shortDate(value: number, language: 'en' | 'zh'): string {
  const date = new Date(value < 100_000_000_000 ? value * 1000 : value);
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return language === 'zh' ? `${date.getMonth() + 1}月${date.getDate()}日` : `${date.getDate()} ${months[date.getMonth()]}`;
}

/** Search matches a chat's title or preview, case-insensitively. */
export function matchingChats(threads: readonly TakuHomeThread[], query: string): TakuHomeThread[] {
  const q = query.trim().toLowerCase();
  return q ? threads.filter(t => t.title.toLowerCase().includes(q) || t.preview.toLowerCase().includes(q)) : [...threads];
}
