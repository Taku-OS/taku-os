/**
 * The dot-matrix loader's geometry and its five loops.
 *
 * Geometry is Figma `744:110593` "Loading": a 16 × 16 frame holding nine 4 × 4
 * full-radius rectangles at 0, 6 and 12px on each axis, so a 2px gap between
 * dots. Here they are circles of r 2 centred at 2, 8 and 14. The centres are
 * fixed: no state, frame or transition ever moves a dot. Figma's opacity
 * pattern is the STOPPED pose; a running loop never returns to it.
 *
 * Every loop is a table of HITS on a fixed period. A hit lights one dot: it
 * snaps up over a short `attack` (35–55ms), holds briefly, then fades over a
 * softer `decay`. Hits are spaced so the next one lands before the previous
 * dot has faded, which is what makes a sequence read as motion. Each hit is
 * evaluated modulo the period, so a decay that runs past the end of the cycle
 * carries on into the next one: pose(t) equals pose(t + period) exactly, and
 * there is never a reset, a rest beat or a blank frame.
 *
 * The rhythm is taken from the reference video, which steps on a ~105ms tick:
 * a dot comes on in one tick, holds one or two, and fades over two or three.
 *
 * The whole pose is a pure function of (state, time): deterministic, and
 * sampleable at any moment.
 */

export const LOADER_STATES = ['thinking', 'scanning', 'reading', 'sending', 'searching'] as const;
export type LoaderState = (typeof LOADER_STATES)[number];

/** The default accessible name of each state. */
export const STATE_LABELS: Record<LoaderState, string> = {
  thinking: 'Thinking',
  scanning: 'Scanning',
  reading: 'Reading',
  sending: 'Sending',
  searching: 'Searching',
};

/* ------------------------------------------------------------- geometry */

/** The view box edge, Figma's frame. */
export const GRID = 16;
/** Figma's dots are 4px across. */
export const DOT_R = 2;
const CENTRES = [2, 8, 14] as const;

/** Row-major: index = row * 3 + column, top-left first. Never moved. */
export const DOTS = CENTRES.flatMap((cy) => CENTRES.map((cx) => ({ cx, cy })));

/**
 * The resting pattern, Figma's Content/900, 600, 400, 800 and 700 tokens. In
 * both of Figma's modes they are the same five alphas over black (light) or
 * white (dark), which is exactly `currentColor` at these opacities.
 */
export const FIGMA_OPACITY = [0.92, 0.48, 0.24, 0.92, 0.8, 0.64, 0.92, 0.48, 0.24] as const;

/* --------------------------------------------------------------- timing */

/** One loop of each state, in ms. */
export const CYCLE_MS: Record<LoaderState, number> = {
  thinking: 1000,
  scanning: 1100,
  reading: 1200,
  sending: 900,
  searching: 1050,
};

/** Blend from whatever is on screen into a newly requested state. Applied on
    a real state change only, never between cycles. */
export const TRANSITION_MS = 200;
/** Blend from whatever is on screen into the resting pattern when stopped. */
export const SETTLE_MS = 320;

/** An unlit dot while running: faint, but the grid is still there. */
export const INACTIVE_OPACITY = 0.08;
/** A fully lit dot: Figma's strongest token, Content/900. */
export const PEAK_OPACITY = 0.92;
export const MIN_SCALE = 0.85;
export const MAX_SCALE = 1.1;
/** The shape of every decay: (1 − x)^DECAY_POWER. Above 1 it leaves fast
    and lands softly, so the trail lingers a little without holding. */
const DECAY_POWER = 1.6;

/* ----------------------------------------------------------------- hits */

interface Hit {
  dot: number;
  /** When it lands, in cycle ms. */
  at: number;
  /** Opacity at the peak. */
  peak: number;
  attack: number;
  hold: number;
  decay: number;
  /** Extra scale at the peak: 0.04 is 104%. Follows the same envelope. */
  grow: number;
}

type HitStyle = Omit<Hit, 'dot' | 'at'>;

const hits = (dots: readonly number[], at: number, style: HitStyle): Hit[] =>
  dots.map((dot) => ({ dot, at, ...style }));

const CENTRE = 4;
const CROSS = [1, 3, 5, 7];
const CORNERS = [0, 2, 6, 8];
/** The eight edge positions clockwise from the top-centre: top, top-right,
    right, bottom-right, bottom, bottom-left, left, top-left. */
const CLOCKWISE_FROM_TOP = [1, 2, 5, 8, 7, 6, 3, 0];
/** Diagonals from the top-left: row + column = 0 … 4. */
const DIAGONALS = [[0], [1, 3], [2, 4, 6], [5, 7], [8]];

/**
 * Thinking — as in the reference: the centre stays lit, and one secondary dot
 * steps clockwise round the eight edge positions, one every 125ms. Nothing
 * moves; the turning is which dot is lit. Each handoff overlaps the previous
 * dot's fade by about one frame-group, so it reads as a step, not a blink.
 * The centre answers each step with a small lift (0.86 → 0.92).
 */
const THINKING_STEP = CYCLE_MS.thinking / 8;
const THINKING: Hit[] = CLOCKWISE_FROM_TOP.flatMap((dot, step) => [
  { dot, at: step * THINKING_STEP, peak: 0.66, attack: 40, hold: 50, decay: 110, grow: 0.03 },
  {
    dot: CENTRE,
    at: step * THINKING_STEP,
    peak: 0.92,
    attack: 40,
    hold: 0,
    decay: 80,
    grow: 0,
  },
]);
/** The centre's level between its lifts while Thinking. */
const THINKING_CENTRE_FLOOR = 0.86;

/**
 * Scanning — a diagonal band from the top-left, one diagonal every 220ms. Each
 * diagonal snaps on and holds for a step and a half, so as in the reference
 * the newest two are lit together and the one before is half-faded: a band
 * two and a half diagonals wide moving as one cluster. The bottom-right
 * corner is still lit when the next band's first dot lands at the top-left,
 * so the wave never empties.
 */
const SCAN_STEP = CYCLE_MS.scanning / DIAGONALS.length;
const SCANNING: Hit[] = DIAGONALS.flatMap((dots, d) =>
  hits(dots, d * SCAN_STEP, { peak: 0.92, attack: 45, hold: 330, decay: 200, grow: 0.04 }),
);

/**
 * Reading — left-to-right bursts, 111ms per dot, with a longer 178ms step at
 * each row change (bottom row → top row included), so the rows read as lines
 * without a stopped beat: the last dot of a row is still lit when the next
 * row starts. Each dot holds about a step, so as in the reference the lead and
 * the dot before it are both at full, with a softer one behind them.
 */
const READ_STEP = 111;
const READ_ROW = (CYCLE_MS.reading - 6 * READ_STEP) / 3;
const READING: Hit[] = Array.from({ length: 9 }, (_, dot) => ({
  dot,
  at: Math.floor(dot / 3) * (2 * READ_STEP + READ_ROW) + (dot % 3) * READ_STEP,
  peak: 0.92,
  attack: 40,
  hold: 150,
  decay: 160,
  grow: 0.04,
}));

/**
 * Sending — the reference's expansion: the centre snaps on; 250ms later the
 * cross joins it (centre + cross); 250ms after that the corners land as the
 * centre fades, so the ring of eight is lit round a dimming centre; then the
 * cross fades and the corners go last. Each ring holds 300ms, which is what
 * makes the rings build on each other instead of taking turns. The next
 * centre lands while the corners are still at about 0.6.
 */
const SENDING: Hit[] = [
  ...hits([CENTRE], 0, { peak: 0.92, attack: 45, hold: 300, decay: 160, grow: 0.07 }),
  ...hits(CROSS, 250, { peak: 0.92, attack: 45, hold: 300, decay: 160, grow: 0.05 }),
  ...hits(CORNERS, 500, { peak: 0.92, attack: 45, hold: 300, decay: 200, grow: 0.05 }),
];

/**
 * Searching — a brisk chase round the edge, clockwise from the top-centre, one
 * position every 131ms. Each dot holds about a step, so the lead and the dot
 * behind it are both lit, with a short soft tail. The centre stays faint,
 * which is what tells it apart from Thinking.
 */
const SEARCH_STEP = CYCLE_MS.searching / 8;
const SEARCHING: Hit[] = CLOCKWISE_FROM_TOP.map((dot, step) => ({
  dot,
  at: step * SEARCH_STEP,
  peak: 0.92,
  attack: 40,
  hold: 100,
  decay: 220,
  grow: 0.03,
}));

const HITS: Record<LoaderState, Hit[]> = {
  thinking: THINKING,
  scanning: SCANNING,
  reading: READING,
  sending: SENDING,
  searching: SEARCHING,
};

/** Each state's level for a dot with no hit lit. */
function floorOf(state: LoaderState, dot: number): number {
  return state === 'thinking' && dot === CENTRE ? THINKING_CENTRE_FLOOR : INACTIVE_OPACITY;
}

/* ----------------------------------------------------------------- pose */

/** Per-dot opacity and scale, nine of each, row-major. Centres never change,
    so they are not part of a pose. */
export interface Pose {
  opacity: Float64Array;
  scale: Float64Array;
}

export function createPose(): Pose {
  return { opacity: new Float64Array(9), scale: new Float64Array(9) };
}

/** Figma's resting pattern: Figma opacities, every dot at full size. */
export function restPose(out: Pose): Pose {
  for (let i = 0; i < 9; i++) {
    out.opacity[i] = FIGMA_OPACITY[i]!;
    out.scale[i] = 1;
  }
  return out;
}

const wrap = (value: number, cycle: number): number => ((value % cycle) + cycle) % cycle;
const easeOutQuad = (x: number): number => 1 - (1 - x) * (1 - x);
const smoothstep = (x: number): number => x * x * (3 - 2 * x);
const clamp = (value: number, min: number, max: number): number =>
  Math.min(max, Math.max(min, value));

/** One hit's level `since` ms after it lands: a crisp rise, a short hold,
    then a decay that leaves quickly and trails off softly. */
function envelope(since: number, hit: Hit): number {
  if (since < hit.attack) return easeOutQuad(since / hit.attack);
  const fading = since - hit.attack - hit.hold;
  if (fading <= 0) return 1;
  const x = fading / hit.decay;
  return x < 1 ? Math.pow(1 - x, DECAY_POWER) : 0;
}

/** The pose of `state` at `t` ms (any value; it wraps), written into `out`. */
export function poseAt(state: LoaderState, t: number, out: Pose): Pose {
  const cycle = CYCLE_MS[state];
  const now = wrap(t, cycle);
  for (let i = 0; i < 9; i++) {
    out.opacity[i] = floorOf(state, i);
    out.scale[i] = 1;
  }
  for (const hit of HITS[state]) {
    const level = envelope(wrap(now - hit.at, cycle), hit);
    if (level <= 0) continue;
    const base = floorOf(state, hit.dot);
    out.opacity[hit.dot] = Math.max(out.opacity[hit.dot]!, base + (hit.peak - base) * level);
    out.scale[hit.dot] = Math.max(out.scale[hit.dot]!, 1 + hit.grow * level);
  }
  for (let i = 0; i < 9; i++) out.scale[i] = clamp(out.scale[i]!, MIN_SCALE, MAX_SCALE);
  return out;
}

/** `out = from + (to - from) * k`, per dot. */
export function mixPose(from: Pose, to: Pose, k: number, out: Pose): Pose {
  for (const channel of ['opacity', 'scale'] as const) {
    const a = from[channel];
    const b = to[channel];
    const o = out[channel];
    for (let i = 0; i < 9; i++) o[i] = a[i]! + (b[i]! - a[i]!) * k;
  }
  return out;
}

export function copyPose(from: Pose, out: Pose): Pose {
  out.opacity.set(from.opacity);
  out.scale.set(from.scale);
  return out;
}

/** The blend curve for state changes and settling. */
export const blendEase = smoothstep;

/** A hit that outlasts its period would light its dot twice. Checked once at
    load so a retimed table fails loudly. */
for (const state of LOADER_STATES) {
  for (const hit of HITS[state]) {
    if (hit.attack + hit.hold + hit.decay >= CYCLE_MS[state] || hit.attack <= 0) {
      throw new Error(
        `DotMatrixLoader: a ${state} hit does not fit its ${CYCLE_MS[state]}ms cycle`,
      );
    }
  }
}
