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
bash system/shopping-observer/pixel-compat/test-host.sh
bash system/shopping-observer/pixel-compat/build.sh
```

The offline bridge checks compile the actual progress host and legacy receipt adapter.
They cover one-use received leases, stale/disabled controls, fixed diagnostic fields,
and preservation of the modern explicit-choice authorization boundary. The test-only
JSON dependency is pinned by SHA-256 and reused from the local host-test cache when
available; a missing cache is fetched from Maven Central. These checks do not bind a
device service, read a credential, capture an image, or start a model.

The output is `out/shopping-observer-pixel-compat/TakuShoppingObserver.apk`.
The default is the public AOSP development platform signer. Updating an existing
development installation requires its original signer; supply an existing
`TAKU_OBSERVER_KEYSTORE`, `TAKU_OBSERVER_KEY_ALIAS`, `TAKU_OBSERVER_STORE_PASS`
and optionally `TAKU_OBSERVER_KEY_PASS`. No signing key belongs in the delivery.

Recorded Pixel packages retain their individually stated verification scope. The
current source candidate is version 30 and has separate build/runtime status.
It includes passive preview suppression and the exact verified DetailActivity LIVE
container while retaining the original trusted-host wire and source checks.
Neither this adapter nor its presence proves full-ROM acceptance. See
[`docs/tryon-delivery/README.md`](../../../docs/tryon-delivery/README.md).
