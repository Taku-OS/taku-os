# Designer voice effects

Source: `taku-animation-handoff-10-15`, supplied by the designer. The pure renderer, integration functions and SVG masks below are copied verbatim. The effect READMEs retain the upstream web API reference, with links adapted to this Android integration; only the required renderers are bundled.

`host.js` adapts measured Engine PCM RMS to `waveAt` and `stepLight`, and uses the supplied `LightField` shader and masks. `scripts/build-voice-effects.cjs` builds `android/src/main/assets/voice-effects.html` using the existing TypeScript compiler; it installs no packages and makes no network requests. Run it after changing the wrapper or supplied source. The Android release build runs it automatically.

Both native 1-1 and React Native Live Talk render this asset in a transparent, noninteractive WebView. File/content access and network loading are disabled; the page has a restrictive CSP and no JavaScript/native interface. Data URLs contain only the bundled SVG masks. Animation stops offscreen/detached and respects the Android animator setting. If WebGL is unavailable, the same supplied layers render statically. Recognition and recording remain owned by the Audio Engine, never the WebView.

The shader runs at the designer’s half-resolution 206×178 field, then scales to the 412×357 bottom band. The waveform has ten 3px bars and nine 4px gaps inside 66×28 design pixels. Quiet light flow is intentional; amplitude and speed use real audio rather than a fabricated speech envelope.

## Bundled source SHA-256

- `assets/effects/voice-light-field/listen-glow-2.svg`: `b83f0006514e1195e044f4e36252310c25c00da7454651db23dc3613f7890c25`

- `assets/effects/voice-light-field/listen-glow-3.svg`: `f259bddef23c14e37708df34ef8ef8eeb2f09ca86a12d929a2109e3166026119`

- `assets/effects/voice-light-field/listen-glow-4.svg`: `631eb46f3043440a71c33eaddd8070da7beecf4a62b025c76271767fff1693d6`

- `assets/effects/voice-light-field/listen-glow-5.svg`: `03f5671161402f1a978fc6b75162be21c468afa8037db38090d5641e5dc7fcb9`

- `assets/effects/voice-light-field/live-glow-2.svg`: `2a7b31cbd56ded29fa4882762fffa4f541170235528cb03ce9b9885729a7c7cc`

- `assets/effects/voice-light-field/live-glow-3.svg`: `834ec10f29ea0e0ae669fbc76ccab160b74a917a55f1479c36de4f197af3be11`

- `assets/effects/voice-light-field/live-glow-4.svg`: `cef194779048efbd50b09fe791abbf93ad815ea06456857d5f8c49e65ce3a42d`

- `assets/effects/voice-light-field/live-glow-5.svg`: `21151b9ea1d1b9e72e311b3a6ec59299afccc55fe3a819517a8e1d86e1ed97b3`

- `assets/effects/voice-light-field/live-glow-6.svg`: `a7408410d14392470fb7a46e33bd6fa5a5ea8295d3b9bb33f358aa8b58844bc0`

- `assets/effects/voice-light-field/live-glow-7.svg`: `e4097b0459ff3e1d9e08184ef7223351194aba4eb6e24b9093924f8907c3e999`

- `effects/voice-light-field/README.md`: `d4f2a23989225a6b62a3bfdcf3a4f556b4ccc00cc6e5077bc5fd254f3ea7d295`

- `effects/voice-light-field/effect.json`: `3f1d3806b6976566a6e25c0e116fdf85a258374e3f0194cfa512b9943738be3b`

- `effects/voice-light-field/light-field.ts`: `2d9a7358e3d857f34b091c1257bcc0f64568f3d2e7be5d2a1f5db4ed48a695d5`

- `effects/voice-light-field/masks.ts`: `e440c40f935106bfb7500d03d53d1f758bb350b13c1196f18d9f7bfea46945b7`

- `effects/voice-light-field/voice.ts`: `af351f432361b7c081a071710d29ba7b1a3569489147e5e7071bc7ec86ab2e01`

- `effects/voice-waveform/README.md`: `a1f2c39066509f3d781170dcf55b0731f8028db5a762e04e0bc8bc1301bd0574`

- `effects/voice-waveform/effect.json`: `e3e8e58b057ac526fbc12b679be3c0be5a692c8a0a9a442b8dff83a64c527960`

- `effects/voice-waveform/waveform.ts`: `a43e9f0216d2b742dc163d338dbeb37c7c68f885ffa0b06cb1d9350e8f87a1e2`
