/**
 * How much demand a chat row holds for its generated card. A card on screen is visible demand; a
 * card just above or below it is preload, which the shared coordinator drops first when the two
 * native leases cannot hold everything. A row outside the band holds nothing.
 */
export type CardDemand = 'visible' | 'preload' | null;

/** A contiguous band of list rows, inclusive; null before the list has laid any row out. */
export interface RowBand {
  first: number;
  last: number;
}

/** Rows above and below the visible band that stay warm, so a small scroll never refetches. */
export const CARD_PRELOAD_ROWS = 4;

/** The contiguous band of row indexes the list calls viewable; null when it reports none. */
export function visibleBand(indexes: readonly (number | null | undefined)[]): RowBand | null {
  let first = Number.POSITIVE_INFINITY;
  let last = -1;
  for (const index of indexes) {
    if (typeof index !== 'number' || !Number.isFinite(index) || index < 0) continue;
    if (index < first) first = index;
    if (index > last) last = index;
  }
  return last < 0 ? null : {first, last};
}

export function sameBand(a: RowBand | null, b: RowBand | null): boolean {
  return a === b || (!!a && !!b && a.first === b.first && a.last === b.last);
}

/** The demand row [index] holds: on screen, inside the bounded preload band, or none. */
export function rowDemand(band: RowBand | null, index: number): CardDemand {
  if (!band) return null;
  if (index >= band.first && index <= band.last) return 'visible';
  if (index >= band.first - CARD_PRELOAD_ROWS && index <= band.last + CARD_PRELOAD_ROWS) return 'preload';
  return null;
}
