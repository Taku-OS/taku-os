# voice-waveform

Ten 3 px pill bars on a 28 px row whose heights follow a speech envelope (Figma 462:13279).
Each bar reads the envelope a few ms later than the one nearer the centre, so a burst spreads
outward; a little texture and idle motion keep it alive. Used by **10-AIPhoneHero**.

```ts
import { VoiceWaveform } from 'taku-animations/effects/voice-waveform';
```

Upstream preview: playground `#/effects/voice-waveform`. The web components shown below belong to the designer’s full package. For the bundled renderer and Android integration, see [Designer voice effects](../../README.md).

## Minimal integration

```tsx
<VoiceWaveform level={() => analyserLevel()} color="#1b1b1b" />
```

## Props

| Prop      | Type                           | Default   | Notes                                                   |
| --------- | ------------------------------ | --------- | ------------------------------------------------------- |
| `level`   | `number \| (timeMs) => number` | `0`       | Speech energy 0–1. A function gives the outward spread. |
| `timeMs`  | `number`                       | —         | Controlled time. When set, no clock runs.               |
| `running` | `boolean`                      | `true`    | Own clock (ignored when controlled).                    |
| `still`   | `boolean`                      | `false`   | Reduced motion: hold the pose at t = 0.                 |
| `color`   | `string`                       | `#1b1b1b` | Bar colour (`--wave-color`).                            |

Building blocks: `waveAt(timeMs, level)` → ten heights in px (3–28); `WaveBars` paints bars
from CSS variables `--{prefix}{i}` (the demo's timeline writes `--bar0…9` on its stage and
colours the bars by `--waveLive`).

## Playback and lifecycle

Pure function of time, so controlled mode is exact (seek, reverse). The own clock runs only
while running, on screen and in a visible tab; it is released on unmount.

## Layout, sampling, limitations

28 px tall row, 66 px wide (10 × 3 px + 9 × 4 px gaps). Samples nothing. The level-to-height
mapping is the demo's tuned one (rest heights `WAVE_REST`).
