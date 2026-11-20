import type {MenuNutrition} from './menuNutrition';

export type MenuBox = {left: number; top: number; right: number; bottom: number};
type MenuCurrency = 'CAD' | 'SGD' | 'USD';

export type MenuTextRecognition = {
  width: number;
  height: number;
  lines: {text: string; box: MenuBox; words: {text: string; box: MenuBox}[]}[];
};

export type MenuVenue = {
  instagramHandle?: string;
  name: string;
  address: string;
  officialUrl: string;
  currency?: MenuCurrency;
  pickupUrl?: string;
  pickupDishNames?: string[];
};

export type MenuDishEvidence = {
  facts: {en: string; zh: string};
  menuUrl: string;
  ingredients?: {en: string; zh: string};
  tasteTags?: readonly {en: string; zh: string}[];
  videoDishAliases?: readonly string[];
  videoSourcePages?: readonly string[];
  socialPostUrls?: readonly string[];
};

type MenuPickLabel = {en: string; zh: string};
export type MenuPickLabels = {picks?: MenuPickLabel; lighter?: MenuPickLabel};

export type MenuDish = {id: string; name: string; price: string; currency?: MenuCurrency; description?: string; box: MenuBox; evidence?: MenuDishEvidence; suggestions?: MenuPickLabels; nutrition?: MenuNutrition};

type TextLine = MenuTextRecognition['lines'][number];
type Price = {text: string; box: MenuBox};
type MenuLine = {line: TextLine; name?: string; price?: Price};
type DishRow = {name: string; line: TextLine; price: Price};

const currency = String.raw`(?:(?:CA|C|US|S)\s*\$|\$|CAD|USD|SGD)`;
const amount = String.raw`(?:\d{1,3}(?:,\d{3})+|\d{1,4})(?:\.\d{1,2})?`;
const decimal = String.raw`\d{1,3}\.\d{1,2}`;
const markedPrice = String.raw`(?:${currency}\s*${amount}|${amount}\s*(?:CAD|USD|SGD)|${decimal})`;
const priceLabel = String.raw`(?:${markedPrice}|\d{1,2})`;
const onlyPrice = new RegExp(`^${priceLabel}$`, 'i');
const trailingPrice = new RegExp(String.raw`^(.+?)(?:\s+|\.{2,}\s*|…+\s*|(?=(?:(?:CA|C|US|S)\s*)?\$))(${priceLabel})$`, 'i');
const priorPrice = new RegExp(String.raw`(?:\$|(?:^|\s)${markedPrice}(?=$|\s|[,/])|(?:^|\s)\d{1,2}\s*[/|])`, 'i');

const section = String.raw`(?:menu|breakfast|brunch|lunch|dinner|starters|appetizers|small plates|soups|salads|bowls|sandwiches|burgers|pizza|pizzas|pasta|mains|entrees|entrées|sides|desserts|drinks|beverages|cocktails|beer|wine|specials|kids(?:'|’)?(?: menu)?)`;
const sectionHeading = new RegExp(String.raw`^${section}(?:\s*(?:\+|&|/|and)\s*${section})*$`, 'i');
const noteLead = /^(?:[*†‡+]|(?:add(?:-ons?)?|available|choose|contains?|prices?|please|substitute|consuming|extra|upgrade|additional)\b|(?:all|each) (?:dishes|items|entrees|prices)\b)/i;
const descriptionLead = /^(?:and|or|with|served|topped|made with|includes?)\b/i;
const quantityEnd = /\b(?:cal(?:orie)?s?|kcal|oz|g|mg|ml|pieces?|pcs|no)\.?$/i;

export function menuPriceCurrency(price: string): MenuCurrency | undefined {
  if (/CAD|^CA?\s*\$/i.test(price)) return 'CAD';
  if (/SGD|^S\s*\$/i.test(price)) return 'SGD';
  if (/USD|^US\s*\$/i.test(price)) return 'USD';
}

export function formatMenuPrice({price, currency}: Pick<MenuDish, 'price' | 'currency'>): string {
  if (!currency || menuPriceCurrency(price)) return price;
  return `${price.replace(/^\$\s*/, '')} ${currency}`;
}

function height(box: MenuBox) {
  return box.bottom - box.top;
}

function centerY(box: MenuBox) {
  return (box.top + box.bottom) / 2;
}

function hasSourceBox({left, top, right, bottom}: MenuBox, {width, height}: MenuTextRecognition) {
  return left >= 0 && top >= 0 && right <= width && bottom <= height && left < right && top < bottom;
}

function isDishName(text: string) {
  return /\p{L}/u.test(text) && text.length <= 76 && text.split(/\s+/).length <= 10
    && !/[,;:.!?]$|[,;:]|\s[–—]\s/.test(text) && !sectionHeading.test(text) && !noteLead.test(text)
    && !descriptionLead.test(text) && !quantityEnd.test(text) && !priorPrice.test(text);
}

function hasEarlierColumnPrice(line: TextLine) {
  return line.words.slice(1, -2).some((word, index) => {
    const next = line.words[index + 2];
    return onlyPrice.test(word.text.trim()) && /\p{L}/u.test(next.text)
      && next.box.left - word.box.right > height(line.box) * 2.5;
  });
}

function parseLine(line: TextLine): MenuLine {
  const text = line.text;
  if (onlyPrice.test(text)) return {line, price: {text, box: line.box}};

  const match = trailingPrice.exec(text);
  if (match) {
    const name = match[1].replace(/(?:\s+[-–—]|\.{2,}|…+)\s*$/, '').trim();
    if (isDishName(name) && !hasEarlierColumnPrice(line)) {
      return {line, name, price: {text: match[2], box: line.box}};
    }
    return {line};
  }

  return isDishName(text) ? {line, name: text} : {line};
}

function sharesRow(first: MenuBox, second: MenuBox) {
  const smallerHeight = Math.min(height(first), height(second));
  const overlap = Math.min(first.bottom, second.bottom) - Math.max(first.top, second.top);
  return overlap >= smallerHeight * 0.6
    && Math.abs(centerY(first) - centerY(second)) <= smallerHeight * 0.55;
}

// Intervening text blocks a match even when it cannot be a dish or price.
function nearestOnRow(anchor: TextLine, lines: MenuLine[], direction: 'left' | 'right') {
  const candidates = lines.flatMap((entry) => {
    const {line} = entry;
    if (line === anchor || !sharesRow(anchor.box, line.box)) return [];
    const gap = direction === 'left' ? anchor.box.left - line.box.right : line.box.left - anchor.box.right;
    return gap >= -height(anchor.box) * 0.12 ? [{entry, gap}] : [];
  }).sort((a, b) => a.gap - b.gap);

  const [nearest, next] = candidates;
  if (next && next.gap - nearest.gap < height(anchor.box) * 0.2) return undefined;
  return nearest?.entry;
}

function isDescription(entry: MenuLine) {
  const {text} = entry.line;
  return !entry.price && !trailingPrice.test(text) && !noteLead.test(text) && !sectionHeading.test(text)
    && /\p{L}/u.test(text) && !quantityEnd.test(text) && !priorPrice.test(text)
    && (/[,;]|\.$/.test(text) || descriptionLead.test(text) || /^\p{Ll}/u.test(text));
}

function dishDescription(row: DishRow, lines: MenuLine[], dishLines: Set<TextLine>) {
  const {box} = row.line;
  const rowHeight = height(box);
  const descriptions = [];
  let bottom = box.bottom;
  for (const entry of lines) {
    const next = entry.line.box;
    if (centerY(next) <= centerY(box) || Math.abs(next.left - box.left) > rowHeight * 1.35) continue;
    if (next.top - bottom > rowHeight || dishLines.has(entry.line) || !isDescription(entry)) break;
    if (next.top < bottom - rowHeight * 0.3 || height(next) > rowHeight * 1.12) break;
    descriptions.push(entry.line.text);
    bottom = next.bottom;
    if (descriptions.length === 2) break;
  }
  return descriptions.length ? descriptions.join(' ') : undefined;
}

function rowBox(name: MenuBox, price: MenuBox): MenuBox {
  return {
    left: Math.min(name.left, price.left),
    top: Math.min(name.top, price.top),
    right: Math.max(name.right, price.right),
    bottom: Math.max(name.bottom, price.bottom),
  };
}

export function parseMenuDishes(result: MenuTextRecognition): MenuDish[] {
  if (!Number.isFinite(result.width) || !Number.isFinite(result.height)) return [];

  const lines = result.lines.filter((line) => hasSourceBox(line.box, result) && /[\p{L}\d$]/u.test(line.text))
    .map((line) => parseLine({...line, text: line.text.trim()}))
    .sort((a, b) => centerY(a.line.box) - centerY(b.line.box) || a.line.box.left - b.line.box.left);
  const inlineRows: DishRow[] = [];
  const splitRows: DishRow[] = [];
  const prices: Price[] = [];

  for (const {name, line, price} of lines) {
    if (!price) continue;
    prices.push(price);
    if (name) {
      inlineRows.push({name, line, price});
      continue;
    }
    const nearest = nearestOnRow(line, lines, 'left');
    if (nearest?.name && !nearest.price && nearestOnRow(nearest.line, lines, 'right')?.line === line) {
      splitRows.push({name: nearest.name, line: nearest.line, price});
    }
  }

  const rows = inlineRows.concat(splitRows.filter((row) => {
    const nameBox = row.line.box;
    const priceBox = row.price.box;
    const columnTolerance = Math.max(result.width * 0.025, height(priceBox));
    const samePriceColumn = (a: Price, b: Price) => Math.abs(a.box.right - b.box.right) <= columnTolerance;
    const crossesPriceColumn = prices.some((price) => price.box.right >= nameBox.right - columnTolerance
      && priceBox.left - price.box.right > result.width * 0.15
      && prices.some((other) => other !== price && samePriceColumn(price, other)));
    if (crossesPriceColumn) return false;

    // A widely detached number needs another row with the same name and price alignment.
    const gap = priceBox.left - nameBox.right;
    if (gap <= result.width * 0.55) return true;
    return splitRows.concat(inlineRows).some((other) => other !== row && samePriceColumn(other.price, row.price)
      && Math.abs(other.line.box.left - nameBox.left) <= columnTolerance);
  })).sort((a, b) => centerY(a.line.box) - centerY(b.line.box) || a.line.box.left - b.line.box.left);

  const dishLines = new Set(rows.map(({line}) => line));
  return rows.map((row) => {
    const box = rowBox(row.line.box, row.price.box);
    const description = dishDescription(row, lines, dishLines);
    const currency = menuPriceCurrency(row.price.text);
    return {
      id: `${box.left}-${box.top}-${box.right}-${box.bottom}`,
      name: row.name,
      price: row.price.text,
      ...(currency && {currency}),
      ...(description && {description}),
      box,
    };
  });
}
