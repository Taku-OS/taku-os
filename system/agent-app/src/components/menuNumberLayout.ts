type Cell = {key: string; text: string; digit: boolean};
type Column = Cell & {carry: number};

function parseCells(value: string): Cell[] {
  const cells: Cell[] = [];
  const parts = /([0-9][0-9,]*)(?:\.([0-9]+))?/g;
  let end = 0;
  let endpoint = 0;
  for (const match of value.matchAll(parts)) {
    if (end < match.index) cells.push({key: `text:${endpoint}`, text: value.slice(end, match.index), digit: false});
    const [, whole, fraction] = match;
    let place = whole.replaceAll(',', '').length - 1;
    for (const char of whole) {
      if (char === ',') {
        cells.push({key: `${endpoint}:group:${place}`, text: char, digit: false});
      } else {
        cells.push({key: `${endpoint}:whole:${place}`, text: char, digit: true});
        place--;
      }
    }
    if (fraction) {
      cells.push({key: `${endpoint}:decimal`, text: '.', digit: false});
      for (let index = 0; index < fraction.length; index++) {
        cells.push({key: `${endpoint}:fraction:${index}`, text: fraction[index], digit: true});
      }
    }
    end = match.index + match[0].length;
    endpoint++;
  }
  if (end < value.length && endpoint) cells.push({key: 'suffix', text: value.slice(end), digit: false});
  return cells;
}

export function menuNumberLayout(values: readonly string[]) {
  const formats = new Map(values.map(value => [value, new Map(parseCells(value).map(cell => [cell.key, cell]))]));
  const cells: Cell[] = [];
  for (const format of formats.values()) {
    // Every format orders the same place keys left to right. Insert missing columns before the next shared column.
    let cursor = cells.length;
    for (const cell of [...format.values()].reverse()) {
      const existing = cells.findIndex(item => item.key === cell.key);
      if (existing >= 0) cursor = existing;
      else cells.splice(cursor, 0, cell);
    }
  }
  const columns: Column[] = cells.map(cell => {
    const place = Number(cell.key.split(':')[2]);
    return {...cell, carry: cell.key.includes(':whole:') ? Math.max(0, place - 1) : 0};
  });
  return {columns, values: formats};
}
