# TakuOS 1.1.1 test-channel candidate

This is a scoped release/acceptance plan, not a published or device-accepted release.

The source is accepted **TakuOS 1.1.0 / 20261027143926**, snapshot `0264202b91a907422aead356e699abf0da8e37bf7e389be0bd9ad9bb68a6ed52`. Its actual L-to-N incremental installation, two boots and selected-data acceptance remain recorded in [the v73 ROM acceptance](runtime-v73-rom-2026-10-27.md); do not relabel that installation as a full baseline sideload.

The new candidate is **1.1.1 / Updater 6**. Keep accepted Assistant 73, IME 10062, Memory 8, Surfaces 4, Codex/Magpie locks, signing roots, platform/kernel locks and firmware. Do not import the independent Chat UI candidate. Derive branding before reapplying the same accepted source packet, so the source-repair capsule still authenticates every inherited input; reuse the existing Mac mini source/compiler/kernel cache.

The accepted v5 branding manifest and patch remain immutable predecessors. The v6 transition must verify all old source hashes before changing the two version constants/Updater manifest, and preserve unrelated files. Archived v1/two-part and v2/three-part product identities retain their exact bytes.

Before generating N-to-candidate OTA, bind N's real prior incremental delivery, installation and postboot evidence to a fresh exact-source device observation. Validate the complete source chain instead of replacing a gate with a self-declared success flag. Keep private device evidence and target-files outside public release objects.

`pixel9-ota.py bind-incremental-source` freezes the previous portable delivery, original installation directory, exact post-upgrade report and formal acceptance, each with its explicit SHA-256. It reuses the existing cryptographic delivery/source/installation validators recursively, verifies the previous target equals the new source, and requires the same observed phone/build/slot and probe APK/UID. Its private `taku.incremental-source-installation/1` record has method `verified-incremental-upgrade`; it never rewrites a prior `adb-sideload`, native Updater or full-baseline receipt. Each chain is bounded to 32 links, 4,096 files and 8 GiB per copied evidence tree; checkpoint rollover needs its own reviewed installation proof.

The fresh `device-before --probe-apk` mode may reinstall/initialize only the independent sample probe, as documented by that existing command. A new sample's hashes belong to the new upgrade; the old post-upgrade samples remain unchanged in the previous bundle. Probe APK/UID, real source installation, exact signed source/target and current before binding are still required. This is not permission to reset product/user applications or to borrow the historical I-only probe-anchor mode for another baseline.

Bind only after the new complete candidate and fresh before exist:

```sh
python3 scripts/pixel9-ota.py bind-incremental-source \
  --plan "$PLAN" --plan-sha256 "$PLAN_SHA256" \
  --before "$BEFORE" --before-sha256 "$BEFORE_SHA256" \
  --previous-plan-sha256 "$PREVIOUS_PLAN_SHA256" \
  --previous-delivery "$PREVIOUS_DELIVERY" --previous-delivery-sha256 "$PREVIOUS_DELIVERY_SHA256" \
  --previous-installation "$PREVIOUS_INSTALLATION" --previous-installation-sha256 "$PREVIOUS_INSTALLATION_SHA256" \
  --previous-after "$PREVIOUS_AFTER" --previous-after-sha256 "$PREVIOUS_AFTER_SHA256" \
  --previous-acceptance "$PREVIOUS_ACCEPTANCE" --previous-acceptance-sha256 "$PREVIOUS_ACCEPTANCE_SHA256" \
  --output "$NEW_PRIVATE_SOURCE_BINDING"
```

Pass the resulting `installation.json` and hash to the existing `generate --source-installation` gate. Existing full-baseline and direct-image installation paths retain their own checks. A binding is provenance for generation, not new device/retention or publication acceptance.

Reuse `releases`, the existing OS update Worker and CloudFront. The existing public Worker branding parser needs strict v2 three-part support while retaining the v1 two-part contract. Publish immutable full/delta objects and minimum public metadata, verify readback, then append only the exact source entry with an If-Match condition. Preserve D/I and other entries; do not open stable.

Real acceptance must include the Pixel's native system-update check/download, downloaded ZIP hash, a fresh IDLE update-engine observer, actual ApplyPayload/callback evidence, reboot, exact target AVB/slot, snapshot merge, selected-data/configuration retention and Taku smoke checks. Keep screenshots of the update-available page. Code tests or USB sideload do not substitute for this online path. Coordinate the phone window with the Case owner; automation remains paused.

## Signed full candidate and deployment checkpoint

The cached O build completed in 24 minutes 24 seconds. Its immutable export is `out/pixel9-android17/artifacts/20261027161951-bdf65d54f3444be0/` on Mac mini. The exported full OTA is `TakuOS-1.1.1-tokay-20261027161951-full.zip`, 1,961,631,213 bytes, SHA-256 `127663c3c4786f7ce5c15f0494f86ee8364bcd10a79e8d82eedbc778853644be`; the target-files hash is `61549f8b76808e2943e68057141ff54c3d644c40af846853ccabc54be790f22d`. The real OTA/payload/AVB signature report is `690b716086ec31083fd4acc8cd99e4ab2e577847989ef2ed6ccae77a6366ece1`; the baseline receipt is `8a89a5d539334c520ff5c68472d3ea9b6fffb7b20e9dae25d3cc8ad68981037d`. These are development-signed artifact checks, not new physical-device acceptance.

The sealed N-to-O plan is `66b7d7f607ad09cec1878e8c94c53cd5d8fe3d0d9e7f12fd3ad10cf299aa8318`. The 12 baked runtime/application components and accepted versions remain verified in the exported `system-ai-rom.json`; only product/Updater version inputs changed. No independent Chat UI candidate was included.

Worker PRs [315](https://github.com/Taku-OS/taku-workers/pull/315) and [316](https://github.com/Taku-OS/taku-workers/pull/316) add the strict v2 branding reader and resolve the existing production text-binding limit without changing live secrets. Main `c7f11d2c02b337c5cba3e7bfb76bcb404b9199d3` reached the existing Git Builds pipeline and active Worker version `c09f1763-5b9d-4e57-a810-a8a8c6bb9cac`. The two disabled legal gates continue to use their existing false defaults; staging remains explicit. No manual deployment was used.

The Pixel was freshly observed on accepted N, with no active native or durable task, and a private selected-data live safety copy completed: 15,518 entries, 1,373,710,536 compressed bytes. This is not an atomic database or full-phone backup. USB disconnected after the copy; no fresh OTA sample was initialized and no install/reboot started. Await a reconnected, unlocked Pixel before `device-before`, source-chain binding, real delta generation, public archive, conditional canary append and native acceptance. No new R2 object or channel pointer has been written at this checkpoint. Android 17 automation remains paused.
