# Single Android source tree and reproducible component builds

This page preserves the source-migration record from 2026-10-10. Subsequent image and device progress is updated through 2026-10-19 in [current engineering status](current-status.md); the migration-time build scope does not imply that later complete ROM or physical-device results do not exist.

On 2026-10-10, the frozen runtime sources were **moved once** into this project. The original source entry in the adjacent experimental project is a relative symlink pointing only here; no second editable implementation is retained. Origins, pre-/post-migration SHAs, and symlink mappings for the 112 original files are in `source-manifest.json`.

| Original experimental source | Sole location in this project |
| --- | --- |
| assistant-panel Java/TaskBroker/resources/manifest/tests/public scripts | `system/assistant/` |
| virtual-display host + phone-display + build | `system/display/` |
| Settings RRO resources and scripts | `system/overlay/` |
| Public phone Codex launcher/install | `adapters/codex-runtime/` |
| free-app-kit seed/API/on-device build scripts/standalone shell/preview Activity | `tooling/app-kit/` |

Experimental out/evidence/logs, generated apps, runtime state, personal authentication, third-party APKs/reference videos, ROM/userdata/snapshots, and production signing material were not moved. The adjacent lab is no longer a build dependency; it remains only a location for historical evidence.

## Local component build

Requires JDK17+, Android SDK android-36/build-tools36.0.0, Python3, zip, and network access to obtain the public AOSP development key initially. Pass ANDROID_SDK_ROOT / JAVA_HOME explicitly; standard SDK locations and installed JDKs are also detected. No personal cache is imported. Public development test signing is pinned to an Android 16 AOSP commit and SHA and cached in ignored `out/development-signing`; it must never be used for production releases.

```sh
npm run build:android
```

All outputs go only to ignored out; this does not install, log in, or restart. `source-build-verification.json` preserves the migration-time historical build snapshot; new checksums and execution output go to ignored `out/`. The Home package, broker/display JARs, RRO APK, and app-kit shell can be compiled separately. Complete ROMs use a separate [image build entry point](../image/README.md); success of this component command cannot substitute for complete ROM acceptance.

The 104 generated design-token XML files are release source resources with their source SHAs retained; default builds do not require a private design directory. Optionally use `--source /path/to/tokens.json` or TAKU_DESIGN_TOKENS for synchronization checks; no second editable token authority is copied.

## Subsequent progress and remaining work (2026-10-19)

Pixel 9 product/device/vendor/kernel integration and pinned manifests are implemented. The older complete candidate was built and exported on 10-11 and flashed on 10-14. Subsequent components on 10-15 have records of real model calls, some tools, and service recovery after cold boot. The latest complete ROM's last saved state is build failure with no new artifacts; see [image engineering](../image/README.md).

The cross-device app-kit native toolchain installer, independent acceptance of the new Codex download bootstrap, a complete image containing the latest components and corresponding device regression, production signing/OTA, and full hardware/app compatibility remain unfinished. The native app toolchain already has deployment and execution examples on a dedicated emulator; the missing cross-device installer must not be described as absence of on-device compilation, nor should that experiment be presented as integration into the generic ROM.

Subsequent directory cleanup moved three codec/texture diagnostic Java files into `tooling/diagnostics/`; the migration manifest records its original point in time and is not altered in place by refactoring.
