# 2026-11-13 open-source inputs and license-packaging checks

This report records component-level verification; it does not establish public-distribution permission for all history, all third-party assets, or a complete ROM.

- [Codex public-source build](codex-source-build.json): pinned OpenAI upstream commit, Taku transaction patch, Cargo.lock, Rust image, Debian snapshot, and V8/Zig checksums. The first 8 GiB / 4-job build was SIGKILLed while compiling core; original inputs/output were retained, and 20 GiB / 1 job completed. New default resource limits match that successful attempt. With a fresh temporary HOME, ARM64 Linux version output and API 37 AVD version/app-server initialize passed without copying existing authentication. Bit-for-bit reproducibility, complete plugin calls, and ROM boot startup were not verified.
- [Agent APK](agent-font-notices.json), [Assistant APK](assistant-font-notices.json): all 8 OFL notices entered each APK under `assets/licenses/fonts/`, byte-identical to original licenses.
- The new Codex packager includes verified upstream LICENSE, NOTICE, and provenance manifests, rejecting existing-archive overwrite and unlisted notice-directory files. Soong installs both upstream notices separately into system_ext; third-party dependency notices and corresponding-source review remain separate.
- The release manifest now includes WebP, fonts, SVG, audio, and more; it detects actual internal HTTP links without treating bare domain constants as private links. Missing Git-tracked files fail the manifest.

Published ROM input locks and downloads remain unchanged. These new components do not yet form a complete new ROM and have no hardware acceptance. Historical screenshots, internal links, and visual-asset distribution rights still need individual confirmation; this report does not approve publishing the whole repository.
