/**
 * Voice → light integration, independent of any script.
 *
 * The voice light has two inputs, kept apart on purpose:
 *
 *  - `level` — speech energy smoothed for light: a fast but smooth attack
 *    (τ 70ms) and a slower release (τ 380ms). It changes how far the light
 *    reaches and how strongly it deforms, never whole-layer opacity.
 *  - `flow` — the light's own clock (in "flow seconds"). It always advances,
 *    at `FLOW_QUIET` per second in silence and up to `FLOW_SPEAK` while
 *    someone speaks, so speech speeds the existing motion up instead of
 *    starting or restarting it.
 */
export interface LightState {
  level: number;
  flow: number;
}

const LIGHT_ATTACK_MS = 70;
const LIGHT_RELEASE_MS = 380;
export const FLOW_QUIET = 0.55;
export const FLOW_SPEAK = 1.45;

/** Advances a light state by `dt` ms towards the raw speech level `target`. */
export function stepLight(state: LightState, target: number, dt: number): LightState {
  const tau = target > state.level ? LIGHT_ATTACK_MS : LIGHT_RELEASE_MS;
  const level = state.level + (target - state.level) * (1 - Math.exp(-dt / tau));
  const speed = FLOW_QUIET + (FLOW_SPEAK - FLOW_QUIET) * level;
  return { level, flow: state.flow + (speed * dt) / 1000 };
}
