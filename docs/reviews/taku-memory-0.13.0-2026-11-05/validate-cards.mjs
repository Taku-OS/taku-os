// 用 Taku 首页自己的校验检查卡片：json-render 正文走 src/feed/feedCatalog.ts 的 validateFeedSpec，
// 信封按 modules/taku-home/CONTRACT.md 与 FeedCard.kt 检查。在首页工程 system/agent-app 目录里（已 npm ci）运行，Node ≥ 22.18：
//   node <本文件> feed-cards.json [更多.json]
// 每个文件是卡片数组，或 feed-e2e.json 这样带 steps[].cards 的记录。
import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
const {validateFeedSpec} = await import(pathToFileURL(process.cwd() + '/src/feed/feedCatalog.ts').href);
const ID = /^[A-Za-z0-9._:-]{1,96}$/, SOURCES = new Set(['sample', 'agent', 'recommendation', 'system']);
const INTENTS = {'ask-taku': ['prompt'], 'open-app': ['package'], 'open-url': ['url'], dismiss: [], none: []};
let bad = 0, n = 0;
for (const f of process.argv.slice(2)) {
  const doc = JSON.parse(readFileSync(f, 'utf8'));
  const cards = Array.isArray(doc) ? doc : (doc.steps ?? []).flatMap(s => s.cards ?? []);
  for (const card of cards) {
    n++; const issues = [];
    if (card.schema !== 'taku.feed-card/1') issues.push('schema');
    if (!ID.test(card.id)) issues.push('id');
    if (!SOURCES.has(card.source?.id) || !card.source?.app) issues.push('source');
    if (!card.summary) issues.push('summary');
    if (!card.pin || !(card.pin.label || card.pin.value) || (card.pin.label || '').length > 24 || (card.pin.detail || '').length > 32 || !card.pin.icon) issues.push('pin');
    for (const [k, a] of Object.entries(card.actions || {})) {
      const req = INTENTS[a.intent?.type];
      if (!req) issues.push('intent ' + k); else for (const r of req) if (!a.intent[r]) issues.push(`intent ${k}.${r}`);
    }
    if (card.ui?.catalog !== 'taku-feed-1') issues.push('catalog');
    for (const i of validateFeedSpec(card.ui?.spec, card.ui?.state ?? {}, new Set(Object.keys(card.actions || {})))) issues.push(`${i.element}: ${i.message}`);
    if (issues.length) { bad++; console.log('INVALID', card.id, issues.join(' | ')); }
  }
}
console.log(`${n} cards, ${bad} invalid`);
process.exit(bad ? 1 : 0);
