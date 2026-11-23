# Onboarding rebase verification — 2026-11-20

Base: `9775ee97ec91820f8aafe6eaa8210308021dbfd2` (main after PR #26).
Rebased all onboarding commits through `9b2c8929`, including cold account refresh and upgrade versions.
Final application source: `1bf1a36ffe7a676d041955a2005ea09b11b18544`.

## Permission boundary

`AgentOpenAccessPolicy` requires `taku.root` for every `jev/*` and `account/login/*`
method, including reads and unknown future methods. Enforcement remains before JSON parsing
and Binder identity clearing. Existing trusted-only rules for credential-returning/replacing
operations remain. The provider has one `only()` helper.

The added `AppPermissionPolicyTest` cases failed on the preceding policy at `jev/status`;
with the fix they pass through the real Store's manual, auto and yolo cases.

On isolated Android 17 / API 37 ARM64 emulator `emulator-5592`, using the updated Assistant APK:

- Ordinary separately signed app, UID 10203, yolo with root opt-in disabled: 41 checks pass.
  Control reads work; the signature gateway and sensitive methods reject access. Malformed
  arguments prove that denial precedes parsing, without executing sensitive operations.
- Instrumentation in the real signed Agent app, UID 10170: 7 checks pass. Gateway draft writes
  and open-access reads share state; the draft is restored. `readAccount` and `readJev` work.
- ADB shell can read `jev/status`; malformed `account/login/start` reaches validation without
  being denied or starting a sign-in.

## Migration and onboarding

Onboarding reuses `ComposerOptions.switchConnection`, preserving the authoritative composer
settings and cache invalidation. Account-page fallback opens the current Expo account route;
it does not depend on the removed native connection view. Main's workspace previews, original-page
return, whole-card taps and crowded status-bar layout remain.

With the newly built Agent APK installed as an upgrade:

- GUI: Set up Taku → Skip for now → Set up later. The provider reads `stored:dismissed`,
  `visible:false`; the real Home widgets and launcher icons are visible.
- Agent: reset → set-up → skip-name → dismiss follows the same states. Dismissal survives
  force-stopping and reopening Home.
- This emulator contains no signed-in account. No real first-time authorization or key saving
  was completed. A pending test sign-in was cancelled during cleanup.

The user's existing emulator was not modified. The isolated probe's permission mode is returned
to deny and the isolated emulator is stopped after verification.

## Build and source checks

- `npm run build:android`: ARM64 release build and development platform signature verified.
- `bash system/assistant/build.sh`: Assistant APK and broker built.
- Agent app lint/typecheck and all 453 JS tests pass; onboarding host suite: 20 tests pass.
- System AI host suite (including AppPermissionPolicyTest and Magpie endpoint repair) passes.
- Gateway host suite: 184 protocol assertions, composer and durable submission tests pass.
- `npm run verify` passes. Cloud builder control: 9 tests pass.
- Island patch/manifest regenerated with `update-patch.py` from upstream files at the pinned
  framework revision, individually verified against `beforeSha256`. Patch/store/source-policy/
  geometry/animation checks pass. Owned sources compile against Android 17 with narrow SystemUI
  API doubles. Separate on-device Canvas instrumentation: 51 checks pass.
- Pinned gitleaks 8.30.1: 7 regression tests pass (including all exact digest/path cases).
  Two new lock-file hits were verified as the actual SHA-256 of TakuCallerAccess.kt and Tokens.kt;
  exceptions bind only those values to the exact lock path. The rebased public Figma provenance
  fingerprint remains an exact historical exception.

## Published APK

Release: [agent-ui-0.5.1-code12-1bf1a36f](https://github.com/Taku-OS/taku-os/releases/tag/agent-ui-0.5.1-code12-1bf1a36f).

- Version: 0.5.1 / code 12; 52,708,278 bytes.
- SHA-256: `187c1ee787b2eb2a01b4294d7ca39b651efde715d1c681d737eb69e3b9c07b91`.
- `image/runtime-assets.lock.json` binds 526 source inputs. A fresh release download matches
  both the locked size and hash; `image/agent_ui.py check` passes.
- Other runtime assets, including the signed input method 10072, are unchanged from main.

## Limits

No full ROM or SystemUI Soong build and no physical-device acceptance in this pass. Canvas
instrumentation verifies components, not installed SystemUI window placement. Real ChatGPT
first authorization, Jev/Fal credential saving and larger-camera-cutout layout remain separate
acceptance work. No credentials, sign-in codes or raw device dumps are included in this record.
