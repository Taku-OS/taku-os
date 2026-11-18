/**
 * Voice waveform motion: ten bars driven by a speech envelope. Pure
 * function of (t, level): seekable and deterministic.
 */

/** Figma 462:13279's ten bars at rest (px, row height 28). */
export const WAVE_REST = [4, 6, 10, 15, 28, 15, 20, 10, 6, 4] as const;

/** Bar heights from the speech envelope: each bar reads the envelope a few
    ms later than the one nearer the centre, so a burst spreads outward. */
export function waveAt(t: number, level: (t: number) => number): number[] {
  const s = t / 1000;
  return WAVE_REST.map((rest, i) => {
    const e = level(t - Math.abs(i - 4.5) * 14);
    const texture = 0.82 + 0.18 * Math.sin(s * 11.3 + i * 1.9);
    const idle = 0.06 * Math.sin(s * 2.1 + i * 0.8);
    const v = rest * (0.3 + idle + 1.05 * e * texture);
    return Math.max(3, Math.min(28, v));
  });
}
