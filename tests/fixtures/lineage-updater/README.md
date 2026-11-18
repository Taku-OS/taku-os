# Pinned Updater source fixtures

The four download preimages and the additional UI/Android.bp preimages were read from the generated Android
checkout at `LineageOS/android_packages_apps_Updater` commit
`6d50e0bf1b16fe7bca818375a3e734f4959e2e57`. Their full original SHA-256 values
are checked against `image/updater/manifest.json` before the actual patch is
applied in a temporary test directory. Existing upstream license headers are
retained. No Android source volume is written by these tests.

The separate `image/branding/manifest.json` checks the UI sources used by the
product branding layer. Its manifest preimage is the already accepted v2
download layer; tests apply v1 → v2 → v4, and independently verify migration
from the hash-pinned v3 branding layer. Display-only Taku constants
do not change `DeviceInfoUtils` or the update eligibility DTO values. The
Settings preimages under `../taku-settings-branding` come from fixed commit
`b9b9d68135714d9a84741620e9386e1d43931139`; their upstream license headers remain.

The Java byte-integrity helper and the actual patched controller start/resume
methods are compiled and executed with local Java; Android/network collaborators
in the controller harness are stand-ins. This verifies source/transport behavior only; it is not an
APK signature, ROM, update-engine or physical-device acceptance test.
