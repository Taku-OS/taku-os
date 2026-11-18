# On-phone application development scaffold

This directory provides two on-phone app development paths: React/JSX + business API + WebView APK shell, and native APKs compiled directly from Java source on the device. Both have dedicated-emulator testing, separate from the full OS SDK's scope. Status below is dated 2026-10-19; deployment scope is in [current engineering status](../../docs/current-status.md).

## React / WebView path

This is the sole source for JSX/API seeds, on-device generation/build/packaging scripts, app API service, native APK shell, and web-preview Activity. Models freely write `App.jsx` and `api.mjs`; interfaces do not constrain apps to business configuration.

```sh
# Repository root: build the precompiled Java shell without installing.
bash tooling/app-kit/build-shell.sh
# Optional online preparation of pinned React, ReactDOM, Android ARM64 esbuild, and Ajv.
bash tooling/app-kit/bootstrap-dependencies.sh
```

`apk-shell/out/dex/classes.dex` is reused for each on-device package. `apk-shell/src/org/agentos/runtime/AppApiClient.java` serves both preview and independent APKs. `preview/src/.../WebActivity.java` retains preview/hot refresh; historical multi-option Minilab/Doric APKs are not imported. Complete preview-host assembly still needs a new development installer. Existing experimental-host source files symlink to these two implementations.

The device path remains `/data/local/agentos/light-app-lab/free-app-kit`. `generate.sh` calls on-phone Codex to generate arbitrary React/API code; `build.sh` bundles JSX and previews it; `package-app.sh` produces a real independent APK for the same app; `mini-app` calls its public interfaces. See [API.md](API.md), `api/example.mjs`, and `seed/`.

Initial development installation still requires Android-native Node, a native toolchain (aapt/aapt2/zipalign, android.jar, apksigner DEX), and a locally generated development signature. Historical experiments obtained these from public Termux/SDK upstreams. **This migration imports neither those binaries nor personal device directories, and cross-device one-step preparation is unimplemented.** Host compilation of the shell does not establish automatic first-time on-phone environment setup. Real device-workflow evidence remains in the lab. Clean-checkout shell compilation is verified; device installation awaits a unified distribution installer.

Bootstrap pins React 18.3.1, ReactDOM 18.3.1, and Android ARM64 esbuild 0.25.10. The API lockfile pins Ajv 6.14.0. Downloads, node_modules, apps/state, generation logs, and APKs are ignored; corresponding MIT licenses are retained. Dependency updates need separate compatibility acceptance.

The API service listens only on localhost:8765 and exposes business functions/state to trusted local code under the experimental configuration. It provides neither cross-app permission isolation nor OS-wide open authorization. Start it explicitly; boot autostart is unverified. Device API notification verification requires root/shell; ordinary web JavaScript gains no root privileges from it.

## Java native APK path

[`build-native.sh`](build-native.sh) targets an already prepared Android development environment. Starting from a generated project's `src/` Java files and `AndroidManifest.xml`, it runs ECJ, D8, aapt2/aapt, zipalign, and apksigner on the device, installs the APK, and launches it on an explicitly selected display. It uses neither a precompiled business shell nor web assets. Because it installs and launches an app, it is not a read-only check or a host-only compilation command.

The device must already have `/data/local/agentos/light-app-lab/toolchain`, native tools, and a development signature. Supply the project path and select an existing development display with `TAKU_APP_DISPLAY`. This script is not a cross-device toolchain installer.

The dedicated Android 16 / ARM64 emulator on 2026-10-15 verified Agent-generated small native apps, on-device compile/sign/install, same-signature upgrade preserving business data, shared Activity/ContentProvider state, API-to-UI synchronization, and rejection of unauthorized calls. See [native app acceptance](../../docs/agent-system-app-acceptance-2026-10-15.md). The toolchain was deployed separately and is not part of the complete ROM. This experiment does not establish support for arbitrary native projects, physical phones, or full production permission isolation.
