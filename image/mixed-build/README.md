# tokay mixed builds

The three lists under `tokay/` are inputs to LineageOS releasetools.
Keep the framework filesystem trees and their prebuilt images together.
Vendor owns both `pvmfw` and `system_dlkm`, including their images.
The regenerated `vbmeta_system` still describes `system_dlkm`.
Releasetools merges the shared META files and postinstall settings, and
regenerates derived images and related metadata.

Audit a complete tokay target-files ZIP on any host with Python 3.10 or later.

```sh
python3 image/mixed_build.py audit lineage_tokay-target_files.zip
```

The audit reports non-directory members by owner and counts directories
separately. It reads metadata and sparse-image headers directly from the ZIP.
AVB checks cover descriptor membership, chained vbmeta public-key agreement
and rollback index locations. Use the release signing checks for cryptographic
signature verification and device acceptance.

On Linux, unpack the matching otatools bundle and pass its root directory.

```sh
python3 image/mixed_build.py merge --framework A.zip --vendor A.zip \
  --otatools /path/to/otatools --output AA.zip --dry-run
```

Remove `--dry-run` to execute the merge. The wrapper audits both complete
inputs and checks the output, including SHA-256 for Vendor images in IMAGES,
PREBUILT_IMAGES and RADIO. Use the updated framework build as `--framework`
for the follow-up A′/A run. The output directory must exist and have space
for a staging ZIP. The output path must be unused.

### LineageOS tools and the tokay build variant

Some tokay preview builds use the `userdebug` policy variant and set the
runtime property `ro.debuggable=0`. A releasetools build that reads only
`ro.debuggable` runs the Treble labeling test in `user` mode and rejects the
debug-only domains compiled into that policy.

On an isolated LineageOS build checkout with this
[releasetools behavior](https://github.com/LineageOS/android_build/blob/lineage-23.2/tools/releasetools/merge/merge_compatibility_checks.py)
(Apache-2.0),
apply [the build-type patch](lineage-treble-build-type.patch) to `build/make`
and rebuild `merge_target_files` using the matching source and host tools.

```sh
git -C build/make apply /path/to/taku-os/image/mixed-build/lineage-treble-build-type.patch
# After initializing the Android build environment and selecting the product:
m merge_target_files
```

Use the Linux host output (`out/host/linux-x86`) or an otatools bundle containing
the rebuilt executable. Pass `--source-root /path/to/android` when the
archive uses signing keys with paths relative to the Android checkout. The change selects the labeling test mode from the
system build type, retaining the runtime property fallback for inputs without
a build type. It leaves the image properties and SELinux policy intact.

Generate test packages from the verified merged target-files ZIP with the
matching Linux tools. The signing key paths for these development builds are
relative to the Android source checkout.

```sh
ota_from_target_files AA.zip AA-full-ota.zip
img_from_target_files AA.zip AA-fastboot.zip
```
