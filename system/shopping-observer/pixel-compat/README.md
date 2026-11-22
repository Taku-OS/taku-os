# Pixel 9 development-host compatibility target

This optional target adapts the existing Observer to the recorded Proactivity 8 /
Surfaces 15 development host. It retains the source-issued presentation lease and
the existing permission checks. It does not replace those services, start a model,
or make an ordinary app a trusted system component.

`generate.py` derives the two instrumented wire clients from current source. Every
replacement must match exactly once; unknown source fails before compilation.
Only fixed diagnostic stage names are logged. No key, image, prompt or room text
is included. Native/current-platform builds continue using `../build.sh`.

```sh
bash system/shopping-observer/pixel-compat/build.sh
```

The output is `out/shopping-observer-pixel-compat/TakuShoppingObserver.apk`.
The default is the public AOSP development platform signer. Updating an existing
development installation requires its original signer; supply an existing
`TAKU_OBSERVER_KEYSTORE`, `TAKU_OBSERVER_KEY_ALIAS`, `TAKU_OBSERVER_STORE_PASS`
and optionally `TAKU_OBSERVER_KEY_PASS`. No signing key belongs in the delivery.

The recorded Observer 27 package is a historical device-verified artifact. The
current source candidate is version 28 and has separate build/runtime status.
Neither this adapter nor its presence proves full-ROM acceptance. See
[`docs/tryon-delivery/README.md`](../../../docs/tryon-delivery/README.md).
