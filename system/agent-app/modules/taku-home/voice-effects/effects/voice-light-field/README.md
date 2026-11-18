# voice-light-field

The voice-reactive fluid light at the bottom of the screen (AIPhoneHero listening sheet, Keep
Talking and Live Talk): Figma's blurred light layers (#FBFF00 / #FF0000 / #5ED100 / #002FFF
listening; six layers in Live Talk) re-rendered as **one flowing field** in a WebGL shader.
Used by **10-AIPhoneHero**.

```ts
import { VoiceLight } from 'taku-animations/effects/voice-light-field';
```

Upstream preview: playground `#/effects/voice-light-field`. The web components shown below belong to the designer’s full package. For the bundled renderer and Android integration, see [Designer voice effects](../../README.md).

## Minimal integration

```tsx
import { VoiceLight, BACKDROP } from 'taku-animations/effects/voice-light-field';
import { usePrefersReducedMotion } from 'taku-animations/shared/motion';

function ListeningSheet({ analyser }: { analyser: () => number /* 0–1 RMS */ }) {
  const still = usePrefersReducedMotion();
  return (
    <div
      style={{
        position: 'relative',
        width: 412,
        height: 357,
        background: '#e3e3e4',
        overflow: 'hidden',
      }}
    >
      <VoiceLight target="listen" voiceLevel={analyser} still={still} />
    </div>
  );
}
```

## Props (`VoiceLight`)

| Prop                 | Type                     | Default                   | Notes                                                                             |
| -------------------- | ------------------------ | ------------------------- | --------------------------------------------------------------------------------- |
| `target`             | `'listen' \| 'live'`     | `'listen'`                | Material: light-sheet palette/blends, or the dark Live Talk palette.              |
| `frame`              | `{ flow, level, mix }`   | —                         | Controlled. When set, no clock runs; output is a pure function of it.             |
| `voiceLevel`         | `number \| () => number` | `0`                       | Live mode: raw speech energy 0–1, read every frame.                               |
| `mix`                | `number`                 | 0 (`listen`) / 1 (`live`) | Live mode: geometry 0 = listening layout, 1 = Live Talk layout.                   |
| `running`            | `boolean`                | `true`                    | Live mode clock.                                                                  |
| `still`              | `boolean`                | `false`                   | Reduced motion: the light holds (no flow).                                        |
| `onStatus`           | `(s) => void`            | —                         | `'loading' \| 'gl' \| 'unavailable' \| 'lost'` — show a fallback on the last two. |
| `className`, `style` |                          |                           | The canvas is FIELD-sized (412 × 357 CSS px) by default.                          |

Lower level: `acquireLightField(onReady, onLost)` → `{ field, release }`, and
`field.draw(canvas2d, target, frame)`; `stepLight(state, rawLevel, dtMs)` is the voice
integration (attack τ 70 ms, release τ 380 ms; flow speed 0.55 → 1.45 flow-s/s with level).

## Playback and lifecycle

- **Controlled** (`frame`): exact and seekable. The demo samples `{flow, level}` from its
  scripted speech table (`lightAt(t)`) and `mix` from its timeline.
- **Live** (`voiceLevel`): `stepLight` integrates the level every frame on the component's own
  requestAnimationFrame loop, paused offscreen (IntersectionObserver) and in hidden tabs. The
  flow clock always advances and is never reset, so speech speeds the existing motion up
  instead of restarting it; a level change mid-attack simply retargets (interruptible,
  reversible).
- **Shared renderer**: every instance on the page draws through **one** WebGL context
  (`acquireLightField`). `draw` sets every uniform it uses and copies the result into the
  caller's own 2D canvas before returning, so instances never see each other's state. The
  context is disposed one task after the last `release()` (so a remount in the same commit does
  not rebuild it). On context loss every holder gets `onLost` and the next acquire builds a new
  field.
- **Cleanup**: unmount releases the shared field, the frame loop and observers.

## Layout and clipping

The field covers a fixed band of a 412-wide phone: `FIELD = { x: 0, y: 560, w: 412, h: 357 }`
(phone px). It renders at half resolution (206 × 179) — the light is a 58 px blur, so the
browser's bilinear upscale is visually lossless — and is independent of `devicePixelRatio`.
Scale the canvas with CSS (`transform` or width/height) for other sizes. The listening material
fades out above phone y 705 (Figma's clip), so clip the container there or below.

## Shader and sampling

GLSL ES 1.0 fragment shader. **It samples only its own mask atlas**: the ten Figma layer
exports in `assets/effects/voice-light-field/` are drawn into one canvas at startup and
uploaded as a texture. It does not sample the page. Each pixel samples the masks through a
divergence-free curl flow, a per-layer warp, per-layer 1D-noise travel and a vertical reach
raised by three travelling crests; layers composite in Figma's order and blend modes (normal,
linear dodge, lighten) over the measured sheet colour (`BACKDROP`). Masks are same-origin
bundled SVGs (no CORS concerns); if you host them on another origin they must be CORS-enabled
or the texture upload fails.

## Reduced motion

Pass `still` (e.g. `usePrefersReducedMotion()`): the flow stops; the level still shapes the
light. With `still`, the light is drawn once per prop change (not per voice sample).

## Limitations

- Needs WebGL. With `onStatus('unavailable' | 'lost')` show a static fallback (the demo keeps
  Figma's static blurred layers under the canvas and hides them while `data-light="gl"`).
- The layer geometry is the Taku phone's (412-wide). Other layouts need new layer boxes in
  `light-field.ts` (`LISTEN`, `LIVE`).
