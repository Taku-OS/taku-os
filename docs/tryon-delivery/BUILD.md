# Build and assemble the preview

Use a clean checkout of the delivery commit. Requirements: Node.js 22+, Python 3.11+,
JDK 17+, Android SDK platform/build-tools 36, zip and unzip. No device or model
account is needed to build. Model keys are supplied on the installed device only.

```sh
npm run verify
npm run check:live-tryon
python3 tests/tryon-delivery.test.py
python3 system/tryon/app/prepare-native-deps.py --download
bash system/tryon/test-rtc-cleanup.sh
bash system/tryon/app/build.sh
bash system/shopping-observer/build.sh
bash system/proactivity/build.sh
# Optional recorded-host wire adapter; separate from the current-platform target:
bash system/shopping-observer/pixel-compat/build.sh
```

Native output is `out/tryon/app/TakuTryOn.apk` (candidate 97); Observer output is
`system/shopping-observer/out/TakuShoppingObserver.apk` (candidate 30). These
commands compile current source, and do not install, capture or start a model.
Pinned native dependencies and their licenses are recorded by the existing builder.
The optional adapter output is `out/shopping-observer-pixel-compat/TakuShoppingObserver.apk`.

Native uses the public AOSP development test signer by default. The normal
Observer builder generates a local development signer; an integrator must arrange
the matching trusted signer for its central permission enforcement. Soong targets
use the platform certificate, with ordinary app UIDs. Public test signing material
is not a production identity. The recorded Pixel Native 96 and Observer 29 use
their existing development signers; fresh differently signed builds do not replace
them through `install -r`. Do not distribute signing keys or uninstall to bypass
an upgrade mismatch.

The recorded applications and fresh candidates are separate builds with separate
acceptance. The five source repairs are now included; a higher candidate version
does not establish new live-camera or cloud-service acceptance. Lucy's observed
INTERNAL_ERROR remains unresolved.

The restored capsule uses the existing platform-signed Assistant legacy view
host only when its exact component and HOST_ISLAND_VIEW grant are available.
This module includes the client, not a privileged host service. See INSTALL.zh-CN.md
for the dependency and the ordinary overlay fallback's status-bar input limit.

## System integration

The native and Observer Soong targets are `TakuTryOn` and `TakuShoppingObserver`.
Prepare the pinned dependencies with
`python3 system/tryon/app/prepare-native-deps.py --download --soong`, then opt in
with `TAKU_PREINSTALL_TRYON=true`. The matching platform tree must apply the
owned SystemUI integration patch before a normal SystemUI/ROM build. See
`system/island/README.md` in the source checkout.

The recorded SystemUI APK is explicitly classified as a compatibility DEX rewrite,
not a normal source build. This branch retains the Kotlin black-background fix;
complete platform compilation and cold-boot acceptance are separate release gates.

## Assemble an already verified artifact set

The artifact declaration pins package names, versions, certificate hashes, file
hashes and origins. Three exact-name runtime code files are separately declared
for integrators; no device data, credential backup or signing file is accepted.
Keep original device-verified files separate from fresh source
candidates. Fill the declared relative paths in an external artifact directory.
Do not put APKs, keys, device data or archives in Git.

```sh
# Requires clean committed source; copies only declared APK files and public docs.
python3 scripts/tryon-delivery.py assemble --artifacts /absolute/artifact-input \
  --output /absolute/new-delivery-directory
python3 scripts/tryon-delivery.py verify /absolute/new-delivery-directory
```

Assembly records the exact Git commit and a complete source-file hash lock. It
rejects symlinks, traversal, unexpected artifact types, mismatched bytes and dirty
source. It includes `source.bundle` and `source.patch` for the exact base-to-HEAD
source range. It never invokes ADB or copies arbitrary directories. `verify.py` in the
result verifies the exact file inventory and hashes without an Android toolchain.

Updating a candidate requires rebuilding it, recording its new version/hash and
validation scope, and committing the declaration before assembly. APK signature
and package metadata checks use Android `apksigner verify --print-certs` and
`aapt2 dump badging` before a declaration is accepted. Byte identity, compilation,
device installation and real model acceptance are recorded separately.

## Source handoff without a push

```sh
git format-patch --stdout 65917a4a658d06a39ac9e76b46a84e3d548f8aee..HEAD > /external/source.patch
git bundle create /external/source.bundle 65917a4a658d06a39ac9e76b46a84e3d548f8aee..HEAD
```

The patch/bundle contains source contributions only. The PR body is kept in
[`PR.md`](PR.md); pushing a branch or opening a remote PR is a later action.

The assembled bundle uses the `HEAD` ref and requires the declared public base.
In an existing clone containing that base, inspect and import it locally:

```sh
git bundle verify /absolute/delivery/source.bundle
git fetch /absolute/delivery/source.bundle HEAD:review/live-tryon
git switch review/live-tryon
```
