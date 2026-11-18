# Taku Input Method

An independent Android IME, package `org.agentos.inputmethod`, source version `0.4.0` (initially `0.1.0`). It builds a pinned Fcitx5 for Android 0.1.3 source revision with native keyboard UI, Fcitx5 engine, and libime Chinese Pinyin dictionaries. Features include Pinyin, English, candidates, symbols, numbers, deletion, and upstream input-method/dictionary/theme settings. Version 0.2.0 added screenshot reply suggestions. The IME still requests no network permission, and Chinese input remains offline.

This is an ordinary APK requiring neither root, platform signature, nor framework changes. APK installation is verified first; the same-signed APK can later be preinstalled. Current changes neither replace the ROM's default IME nor flash devices.

## Screenshot reply suggestions (0.3.0)

Open input-method settings → **AI reply suggestions**, enable the switch, and choose allowed apps. The feature defaults off with no default app allowlist. When the keyboard opens on an empty editor, the system Broker captures the current app's visible area excluding status bar and keyboard, compresses it to JPEG with longest edge 1024 and quality 80, and sends it to Taku AI's selected system image model (initially local Magpie's `codex/gpt-6-luna`). It separately selects `low` reasoning without changing the main Agent's model/connection. This page also allows ordinary generation models explicitly declaring image support in the catalog.

While waiting, the candidate bar shows a thinking message. The model returns `{"version":1,"candidates":[{"text":"…"}]}`. After validation, up to three suggestions share the original 40dp candidate/prediction row with the Taku logo and keyboard-collapse button. Typing yields to Fcitx Pinyin candidates. Keyboard height does not grow; there is no extra title, card, or panel. Tapping a suggestion only fills the current editor. Typing, candidate selection, selection changes, keyboard collapse, or switching editors cancels the round; late results cannot update UI. A new round starts only when the user reopens an empty editor.

Images/replies are handled briefly in memory without disk storage. Passwords, private mode, number/phone/URL/email fields, existing drafts, nonforeground apps, and `FLAG_SECURE` windows are excluded. Network failures, timeout, invalid JSON, or empty candidates never block ordinary input. This initial feature reads no group-chat history database, uses no tools, and never sends automatically.

The feature requires matching Taku Assistant v63 and Broker; on ordinary Android phones the IME still supports offline input. A dedicated ContentProvider protected by `signature|knownSigner` accepts fixed operations, and the system rechecks package, signature, default IME, and calling UID. The IME retains its own signature/UID, without platform permissions or model keys. Development-trusted certificates are listed in `system/assistant/res/values/ime_replies.xml`; formal releases must use the production IME certificate.

Magpie reuses on-phone subscription sign-in through `CODEX_HOME=/data/local/agentos/home/.codex`; neither the IME nor APK holds credentials. On 2026-10-22, under the owner's explicit request, a valid local ChatGPT Pro cache was copied by USB to root-private phone storage with mode 0600; old phone credentials were backed up privately on-device. Caches are excluded from source, APKs, and acceptance records. The procedure follows [official OpenAI browserless-device authentication documentation](https://learn.chatgpt.com/docs/auth#fallback-authenticate-locally-and-copy-your-auth-cache). Expired sign-in requires reauthorization in Taku. Catalog model/image capabilities alone do not prove generation; actual calls are required.

Version 0.2.2 classifies Magpie credential-refresh failure as `auth_required`, displaying a fixed sign-in-expired/reconnect message in the existing row. Tapping opens Taku's account connection page. Failure remains until typing or keyboard collapse; waiting/errors are never candidate replies. Only fixed error categories/copy reach the keyboard, never raw upstream bodies. The update was installed; 12 host checks covered error classification, while real generation/input passed the hardware acceptance below. The expired-login tap path was not tested by deliberately signing out an active account.

## Candidate previews and request reuse (0.4.0)

Ordinary taps fill an AI candidate directly, without confirmation. Long-press opens a separate non-focus-taking overlay with full text; “Insert this reply” is an optional entry there. “New set” remains on the candidate row's right; failures show Retry, expired sign-in shows Connect. New set captures again, bypasses completed cache, asks for different expressions/angles, and removes exact duplicates from the previous set. Controls remain in the original 40dp row. Preview does not resize the keyboard; typing or changing editors closes preview/candidates.

Every request first verifies foreground/protected windows and captures a fresh image. Reuse requires identical calling UID, app, actual window, system-policy revision, model, and complete JPEG SHA-256. Cache holds at most four sets for 20 seconds, storing strict JSON text rather than images, with timed background cleanup. Concurrent identical-image requests share one model call. Canceling one subscriber preserves work for remaining subscribers; canceling the last disconnects the network. Canceled requests, failures, and expired results never enter cache.

Longest image edge changed from 1280 to 1024 and JPEG quality from 85 to 80. New-set waiting/polling intervals became 120ms. Initial opening waits 260ms to avoid transition animation. Foreground checks use one complete window read before and after capture. Real receipts record capture preparation/model/total duration and cache/coalescing status. Root-only diagnostics contain only counts to establish reuse without another model request. Cold-call time depends on the actual service; cache hits or waiting indicators are not model-speed evidence.

`tests/replies-acceptance.py` covers preview, new set, retry, reuse, and dimensions using real replies. `tests/real-apps-acceptance.py` regresses synthetic content in actual Jelly/WebView, Android Settings, and Messaging, without sending messages. Real-model acceptance, upstream input regression, and isolated coordinator tests are recorded separately.

On 2026-10-23, final 0.4.0 was installed on OTA-upgraded Pixel 9 / Android 17 E. Thirty hardware checks passed: direct single-tap filling, long-press full preview, new set/retry, concurrent coalescing, and strict image caching. The 40dp row and keyboard height stayed unchanged. See [0.4.0 hardware acceptance](../../docs/reviews/inputmethod-replies-2026-10-23/README.md) for raw screenshots, real receipts, installation hashes, and runtime mount boundaries.

## System AI default models (0.3.0)

The input-method model defaults to “Follow system”. Android Settings → Taku AI centrally manages accounts, text/image defaults, global switch, and app permissions. The IME's Taku AI system settings button opens the same page and refreshes the selected model on return. Explicit image-model selection retains an app override; choosing Follow system restores shared configuration.

Pausing system AI or revoking IME AI access prevents new analysis and cancels late results from earlier configuration generations. Existing typing/password/protected-window boundaries remain. See the [shared module](../ai/README.md) for system-AI client, Memory defaults, gateway UID isolation, and development deployment limits.

## Proposed Memory integration (unimplemented)

The Taku Memory 0.5.0 implementation (`a01a08e`) was reviewed in an internal case-testing session. Existing `ObservationService.collect` skips editable nodes, so keyboard content needs a separate IME source; expanding screen-text collection does not establish integration.

The proposed IME integration retains final text for the current editor session and submits a combined record on input completion or confirmed input action. It does not store Pinyin composition or per-keystroke traces. Provenance includes original app, time, and `origin=ime`, explicitly marked as an input draft. Committed text does not prove a sent message. Unselected AI candidates are not saved; selected/inserted suggestions may be marked as AI text the user chose to use.

The entry should be a dedicated write channel trusting only Taku IME signature/UID and reusing Memory's user switch, source allowlist, retention, and deletion policy. It should not grant the IME access to all memories or database administration. Passwords, private mode, disabled personalized learning, and protected windows remain excluded.

Write records and `analysis_queue` in the same `MemoryStore` transaction. Reuse `AnalysisJob` / Jev's roughly 20-second coalescing and five-record batch limit, plus `MemoryCopy` titles. Jev/Agent evidence retains draft status; existing thresholds, cooldowns, and user acceptance govern proactive suggestions. No second memory database or per-keystroke model calls are needed. Only architecture review/design is complete; input-content collection is neither enabled nor implemented.

## Build

See [ROM integration](ROM.md) for preinstallation/shared-AI backend integration, independent-signature artifact locks, and reboot acceptance without bind mounts. The release code awaiting ROM acceptance is 10062; feature version remains 0.4.0. Historical hardware reports retain their actual release codes.

Requirements: JDK 21, Android SDK 36, Build Tools 36.1.0, NDK 28.0.13004108, CMake 3.31.6, host gettext (`msgfmt`), and extra-cmake-modules (ECM). On macOS, install JDK with `mise install java@temurin-21`, then SDK Manager for other components:

```sh
brew install gettext extra-cmake-modules
export JAVA_HOME="$(mise where java@temurin-21)"
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
  'platforms;android-36' 'build-tools;36.1.0' 'ndk;28.0.13004108' 'cmake;3.31.6'
bash system/inputmethod/build.sh
```

The build verifies main-repository and native-dependency commits, including nested submodules, applies reviewable patches in ignored `out/inputmethod/source`, compiles Android/JNI and the Chinese engine, runs default-configuration regression tests, and signs.

- APK: `out/inputmethod/TakuInputMethod-arm64-v8a.apk`
- Package metadata, size, SHA-256: adjacent `.json`
- Custom source directory: `python3 system/inputmethod/prepare.py --source /absolute/path`
- Offline verification of prepared source: `python3 system/inputmethod/prepare.py --no-fetch`
- Source archive: `python3 system/inputmethod/source-archive.py`, containing patched Android/native source and pinned support-library inputs.
- x86 emulator: `bash system/inputmethod/build.sh x86_64`

Default signing uses the local development key at `out/development-signing/taku-inputmethod.jks`. Preserve the same key for cross-workspace upgrades. Production releases must set `TAKU_IME_KEYSTORE`, `TAKU_IME_KEY_ALIAS`, and `TAKU_IME_STORE_PASSWORD`; never commit private keys.

Upstream support libraries/dictionaries use pinned `prebuilt` inputs; see `NOTICE`. This is a source build, not a renamed upstream APK.

## Install

```sh
bash system/inputmethod/install.sh DEVICE_SERIAL
```

Open Taku Input Method and follow setup to enable/select it. Defaults include Pinyin and English. Backspace after candidate selection clears predictions and deletes the previous character. First launch writes configuration; upgrades/imports preserve user configuration. The keyboard follows system light/dark mode with Taku orange and warm white/dark defaults. App icon, setup, and toolbar share the Taku vector brand. The toolbar logo retains original colors and toggles tools; private mode retains its dedicated indicator.

On a dedicated test emulator, or hardware whose owner explicitly requests installation and default selection, select the IME directly:

```sh
adb -s DEVICE_SERIAL shell ime enable org.agentos.inputmethod/org.fcitx.fcitx5.android.input.FcitxInputMethodService
adb -s DEVICE_SERIAL shell ime set org.agentos.inputmethod/org.fcitx.fcitx5.android.input.FcitxInputMethodService
```

Java/Kotlin namespaces and JNI symbols retain upstream `org.fcitx.fcitx5.android`; actual Android package and permission/Provider namespaces are `org.agentos.inputmethod`. This boundary is intentional.

## Licenses and source

`NOTICE` records provenance/licensing. Patches preserve upstream LGPL identifiers; native components/data retain their own licenses. About retains upstream attribution/open-source license access. The root repository's internal license does not override third-party components. External distribution must include source/build materials matching the build.

## Acceptance

```sh
bash system/assistant/test-ime-replies-host.sh
bash system/inputmethod/tests/build-ai.sh
adb -s DEVICE_SERIAL install -r out/inputmethod/probe/probe.apk
adb -s DEVICE_SERIAL install -r out/inputmethod/chatcase/chatcase.apk
adb -s DEVICE_SERIAL push out/inputmethod/native-probe/probe.jar /data/local/tmp/taku-ime-native-probe.jar
# Enable AI in IME settings and allow the Taku group-chat example first.
python3 system/inputmethod/tests/ai-acceptance.py DEVICE_SERIAL --allow-physical --require-generation
```

The group-chat APK is an independent synthetic source page with meal-planning messages and native EditText, without reply answers. Launch extras `--ez secure true` and `--ez password true` test protected windows/password fields. AI hardware acceptance requires a real image model; static replies or mock interfaces are not real generation.

```sh
bash system/inputmethod/tests/build.sh
adb -s emulator-SERIAL install -r out/inputmethod/fixture/fixture.apk
adb -s emulator-SERIAL install -r out/inputmethod/probe/probe.apk
python3 system/inputmethod/tests/acceptance.py emulator-SERIAL
```

Only dedicated emulators are accepted by default. After the owner explicitly requests hardware testing/default selection, add `--allow-physical`. Always specify a device serial:

```sh
python3 system/inputmethod/tests/acceptance.py DEVICE_SERIAL --allow-physical \
  --output out/inputmethod/device-acceptance
```

Tests enable and retain Taku as default IME, restoring theme settings on completion or exception. They tap actual keys/candidates and read synthetic EditText contents, covering logo controls, Chinese commitment, Pinyin editing/deletion, committed-text deletion, Chinese/English switching, English, symbols, numbers, passwords, IME restart, and themes. Results/native screenshots default to `out/inputmethod/acceptance` for emulators or `out/inputmethod/acceptance-DEVICE_SERIAL` for hardware; override with `--output`. Clipboard and `ACTION_SET_TEXT` are not substitutes for IME testing.

## 0.2.2 real image-generation verification (2026-10-22)

`physical_verified`: Pixel 9 / Android 17 / API 37. Installed hashes of IME 0.2.2, Assistant v61, and Broker matched builds. After importing the owner's subscription, native `account/read` confirmed ChatGPT Pro. Real `codex/gpt-6-luna` image generation returned three candidates and screenshot hash/dimensions/timing receipts. Two generations took 8340ms and 6609ms without response fixtures.

Seven AI hardware checks passed: real JSON in the original 40dp row, accurate candidate insertion without sending, waiting copy/native height, the first real Pinyin key dismissing AI, no canceled-result return, protected-window screenshot rejection, and no password-field trigger. Tests left the ordinary chat page and real suggestions, removed temporary probes, and retained Taku as default.

Subscription cache remained on the phone's data partition. System Broker/Magpie wrappers still loaded through temporary bind mounts under SELinux Enforcing. Reboot clears those mounts and requires reloading or formal-ROM integration. No image was flashed and reboot persistence was not accepted in that run.

[Real generation screenshots/records](../../docs/reviews/inputmethod-ai-2026-10-22/README.md) · [Structured acceptance](../../evidence/inputmethod-ai-20261022.json)

## 0.2.1 historical verification (2026-10-22)

`local_verified_model_pending`: the update was installed on Pixel 9 / Android 17 / API 37 and retained as default. Twenty-one JSON boundary checks, eight JVM tests, eleven ordinary-input hardware regressions, and five local AI-flow checks passed, including the 40dp row assertion. Magpie catalog returned `codex/gpt-6-luna` and image capability. Actual calls failed subscription credential refresh, awaiting Taku reauthorization before screenshot → JSON → insertion acceptance. Screenshots then showed only waiting and dismissal after typing.

System components used temporary bind-mount hot updates under SELinux Enforcing, without flashing. The model blocker was resolved by 0.2.2 verification above.

[Detailed record](../../docs/reviews/inputmethod-ai-2026-10-22/README.md) · [Structured acceptance](../../evidence/inputmethod-ai-20261022.json)

## Initial 0.1.0 verification (2026-10-22)

`physical_verified`: the latest ARM64 APK with keyboard logo was installed/defaulted on Pixel 9 running Android 16 / API 36. All eleven real-tap checks passed: logo tool expansion/collapse, Pinyin backspace, Chinese candidate commitment, committed-text deletion, language switching, English, symbols, numeric/password fields, Chinese input after IME restart, and light/dark mode. Installed APK SHA-256 matched the build; eight JVM tests passed again.

Tests retained Taku as default, restored the phone's original dark-mode setting, and removed temporary editor/probe apps. IME ran as an independent app UID. Existing adbd was UID 0 and remained unchanged; the app is neither root nor system-privileged. Process-exit records showed only intentional test force-stops, without crash/ANR.

[Hardware record](../../evidence/inputmethod-pixel9-20261022.json) · [Light keyboard/logo](../../docs/media/taku-inputmethod-pixel9-light-20261022.png) · [Dark keyboard/logo](../../docs/media/taku-inputmethod-pixel9-dark-20261022.png)

The earlier build without the toolbar logo retains this emulator baseline, with APK/patch hashes recorded separately:

`emulator_verified`: ARM64 APK on an independent API 36 emulator completed setup, Pinyin candidates/backspace, committed-text deletion, language switching, English, symbols, numbers, passwords, IME restart, and theme acceptance through real key/candidate taps. Eight JVM tests and repository `npm run verify` passed.

The old upstream theme-2.0 test's migration expectation was corrected to match existing 2.1 serialization. The migration algorithm was unchanged.

[Verification record](../../evidence/inputmethod-20261022.json) · [Pinyin keyboard](../../docs/media/taku-inputmethod-pinyin-20261022.png) · [Dark keyboard](../../docs/media/taku-inputmethod-dark-20261022.png)

Initial 0.1.0 acceptance covered an independently development-signed APK. Android 17 and ROM product-image inclusion were not verified then. Third-party plugins were outside adaptation/acceptance scope.

Current system-AI integration has real-model acceptance on Pixel 9 / Android 17. See [2026-10-23 acceptance](../../docs/reviews/system-ai-2026-10-23/README.md) for versions, installed hashes, screenshots, and full-reboot boundaries.
