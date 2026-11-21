# Designer dot-matrix loader

Source: user-provided `taku-animation-handoff-10-15/effects/dot-matrix-loader`.
`pose.ts` and `effect.json` are verbatim handoff source; no npm package is installed or executed.
The handoff labels its code private/UNLICENSED; these files retain the designer provenance.

`DotMatrixLoader.java` ports only the default `thinking` loop used by Building and working progress capsules (including onboarding sign-in). SystemUI
and the Compose fallback call this same allocation-free implementation: 1000 ms period,
125 ms clockwise steps, 40/50/110 ms outer attack/hold/decay, centre floor .86 and peak .92,
scale growth .03. Reduced motion uses the exact static Figma pose.

Reference SHA-256: `0614323f859c2815a73f241d74ec389b7d7f337ae87a09c08062d2833454a198`.
`thinking-golden.csv` was generated directly by `poseAt('thinking', time, createPose())` from
this file, including attack/hold/decay boundaries and negative/wrapped times. `test.sh` compares
all nine opacity and scale values against the Java port at 1e-12 tolerance.
