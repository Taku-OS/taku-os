/** Splits a display value into number and unit runs: "8h21m" → 8 · h · 21 · m. */
export type ValuePart = {text: string; kind: 'number' | 'unit'};

// Letters, percent signs and whitespace read as units (Body type); digits, currency,
// degree signs and punctuation stay with the number (Display type), as in the design.
const UNIT = /[\p{L}%\s]/u;

export function splitValue(text: string): ValuePart[] {
  const parts: ValuePart[] = [];
  for (const char of text) {
    const kind = UNIT.test(char) ? 'unit' : 'number';
    const last = parts[parts.length - 1];
    if (last?.kind === kind) last.text += char;
    else parts.push({text: char, kind});
  }
  return parts;
}

/** LevelTrack: a whole value at [y] on a vertical track of [height]; the top is [max], the bottom [min]. */
export function levelAt(y: number, height: number, min: number, max: number): number {
  if (height <= 0) return min;
  const t = 1 - Math.min(1, Math.max(0, y / height));
  return Math.round(min + t * (max - min));
}
