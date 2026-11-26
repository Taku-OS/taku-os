# tokay mixed builds

The three lists under `tokay/` are inputs to LineageOS releasetools.
Keep the framework filesystem trees and their prebuilt images together.
Vendor owns both `pvmfw` and `system_dlkm`, including their images.
The regenerated `vbmeta_system` still describes `system_dlkm`.
Releasetools merges the shared META files and postinstall settings, and
regenerates derived images and related metadata.

Audit a complete tokay target-files ZIP on any host with Python 3.12.

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
