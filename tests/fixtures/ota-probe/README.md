# Independent OTA data-retention probe

This debuggable fixture stores a random test nonce in SharedPreferences, a SQLite
database and an application-private file. It requests no permissions, reads no
other application's data, and disables Android backup. `run-as` reads only SHA-256
digests of its three data files. The test proves retention of this app's sample
data; it does not prove that every user's app/account/database was preserved.

Build offline using the repository's `scripts/android-env.sh` JDK/SDK discovery
(Android SDK 36 by default). The default signing inputs are the **existing public
AOSP development test key and certificate** in `out/development-signing`.
`scripts/aosp-test-keys.py` verifies their pinned hashes before use; the probe
builder refuses missing files and does not download or create keys. No new
keystore, password or account credential is needed:

```bash
bash tests/fixtures/ota-probe/build.sh "$PWD/out/pixel9-android17/ota/probe/ota-probe.apk"
```

Use `PROBE_TEST_SIGNING_DIR` to select another directory containing that same
pinned `platform.pk8` / `platform.x509.pem` pair. These are public development
keys, not a production signing identity. Alternatively, select an existing
development keystore (do not also set `PROBE_TEST_SIGNING_DIR`):

```bash
export PROBE_KEYSTORE=/path/to/existing/debug.keystore
# Supply PROBE_KEYSTORE_PASSWORD through the environment; do not save it here.
bash tests/fixtures/ota-probe/build.sh /absolute/output/ota-probe.apk
```

The builder verifies the signed APK with `apksigner`, prints its SHA-256, refuses
to overwrite an existing APK, and never installs or connects to a device. Keep
that exact APK for the complete A→B probe run.

`ota_device.py before` installs this package for primary user 0, initializes fresh
test values, waits for all stores to close, and records their hashes. `after`
never installs, starts, resets or repairs the app. It must see the same installed
APK, application UID and all three private-data hashes after booting baseline B.
Unlock the primary user before both captures. Keep the probe installed until the
post-upgrade evidence is complete; its removal/clear-data correctly fails the test.

Both captures require an explicit ADB serial and accepted A/B baseline archive
directories. They reject an emulator, another product/API, the wrong build or
vbmeta digest, and an unchanged boot slot. The post-upgrade capture additionally
requires the SHA-256 returned by the pre-upgrade capture, binding its full record.
Phone serials are recorded only as hashes. No account values, generated nonce,
private-file bytes or complete `dumpsys`/`getprop` output enter evidence.

The fixture has not been run on the Android 17 Pixel 9 candidate. Local Java/APK
build checks and fake-ADB tests are not physical-device acceptance.
