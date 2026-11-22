# TikTok LIVE outfit observer

Normal-UID Android accessibility app (`org.agentos.shopping`). It discovers supported TikTok LIVE rooms, optionally suggests a try-on, and prepares one immutable outfit reference after an explicit **Try on** action. `system/tryon` owns the visible camera window and the Lucy session.

This module is optional. All three observation switches default off. A LIVE visit never starts the camera or a Lucy session. Automatic title classification and no-card image classification require their separate switches, the configured system AI model/access, and Proactivity approval. Existing cooldown/history limits survive reconnection.

## Main integration

The observer registers an approved Proactivity choice with **Not now** / **Try on** and the cloud AI / maximum 60-second usage-billing disclosure. Only its explicit `choice` / `accept` receipt starts preparation. A host's legacy **Viewed** hint still opens a separate confirmation; viewing alone never starts recognition or a camera. The settings page's explicit Try on button and authenticated `tryOn` command use the same preparation path. Matching/failure updates use ordinary Android notifications carrying main's `taku.island.v1` data/actions through the shared `IslandPublisher`. Notification permission must be granted for those controls to appear.

No old Proactivity ActionSpec, platform action-ticket authority, Surfaces remote-view host, SDK snapshot, or replacement platform service is included. The frozen-image outfit picker remains an app-owned accessibility overlay.

A system integration may install a `LiveFrameProgressHost.Factory` to use its existing host-issued presentation session instead of notifications. The accepted reminder connection stays bound across matching, selection and explicit retry until cancellation, handoff or the original 120-second acceptance deadline; retry never extends that binding. Working updates use the host's compact state, while selection, retry and confirmation cards use its expanded needs-action state so their buttons appear directly. Missing optional sessions retain the ordinary notification path.

## Capture and handoff

- Validate the actual TikTok package, sole full-screen application window, LIVE markers, current presenter, and an unlocked interactive screen. Leaving the room, opening an incompatible window, rotation, or disabling observation invalidates unfinished work.
- Strong reads at screenshot boundaries use one fresh root for both page and layout. Passive content churn is coalesced for 700 ms; structural changes and expiry bypass that delay.
- Within one 20-second preparation attempt, capture at most three frames, score quality locally, and send one selected video ROI to system image AI. There is no automatic retry of the paid recognition call. Within the active attempt, a real user/API selection may authorize one additional localization on the retained frozen frame. After expiry, selection only stores the point and presents a new explicit Try on confirmation; it does not start another paid attempt.
- After validated localization/crop, create a private JPEG reference with a 120-second temporary read grant only for `org.taku.tryon`. Issue a separate 30-second, one-use admission bound to the immutable reference, request, source room/window, and the target's installed UID/signing identity. The native foreground entry must redeem it before opening the camera service.
- A failed recognition keeps a bounded Retry / Select outfit control. Retry and selected continuation require a new explicit action and a fresh verification of the same room. Stale callbacks never start a new native or model session.

Selectors currently cover `com.zhiliaoapp.musically` and `com.ss.android.ugc.trill`. TikTok UI changes can make a room unrecognizable; the app then fails closed with a fixed source reason. Short-video character replacement is outside this LIVE observer port.

## Agent / ADB API

`content://org.agentos.shopping.api` implements `ContentProvider.call`. Root, system, shell, self, and platform-signed callers are direct. Other apps use the central App Permission Center's `taku.control` approval; no module-local allowlist exists. UI and Agent commands use the same Preferences and main-thread runtime handlers.

```sh
adb -s <isolated-device> shell content call --uri content://org.agentos.shopping.api --method status
adb -s <isolated-device> shell content call --uri content://org.agentos.shopping.api --method configure --extra enabled:b:true --extra classification:b:false --extra vision:b:false
adb -s <isolated-device> shell content call --uri content://org.agentos.shopping.api --method tryOn
adb -s <isolated-device> shell content call --uri content://org.agentos.shopping.api --method cancel
adb -s <isolated-device> shell content call --uri content://org.agentos.shopping.api --method retry
adb -s <isolated-device> shell content call --uri content://org.agentos.shopping.api --method select
adb -s <isolated-device> shell content call --uri content://org.agentos.shopping.api --method select --extra x:i:500 --extra y:i:500
```

| Method | Input | Result / behavior |
| --- | --- | --- |
| `status` | none | `ok`, `code`, `connected`, three flat boolean settings, supported commands; when connected, fixed `acceptanceStage` / `acceptanceCode` for the latest explicit acceptance, phase/recognition code, busy/selection/retry/confirmation flags, screenshot/reference/text/image/selected-analysis/handoff counters and timing. No image, room text, prompt or admission. |
| `configure` | one or more actual boolean `enabled`, `classification`, `vision` | Validate all keys/types before a single settings commit. Readable even without a connected accessibility service. UI consent dialogs write through this same adapter. |
| `tryOn` | none | Explicit user-authorized capture/model/camera workflow. Requires enabled observation, connected accessibility, unlocked screen, verified current LIVE and no recognition in flight. It does not implicitly enable automatic classification switches. |
| `cancel` | none | Same cancel handler as notification/picker; cancels observer preparation/confirmation only. Stop a handed-off native camera using the native API. |
| `retry` | none | Same explicit retry as the failure notification. Reverify the same room and start a new bounded attempt; never automatically loops. |
| `select` | none, or integer `x` and `y` in 0..1000 | Open the same frozen-image picker, or select a normalized point in that retained frame using the same guarded handler. Requires a current retained frame, idle model and valid room. |

Common fixed codes include `service_not_connected`, `disabled`, `screen_locked`, `recognition_busy`, `use_retry_or_cancel`, `source_<fixed reason>`, `no_active_attempt`, `selection_unavailable`, `invalid_coordinates`, `invalid_setting`, and `settings_write_failed`. Unauthorized callers receive `SecurityException("permission_denied")`. Unknown commands/parameters are rejected. `status` remains readable and reports the three settings when accessibility is disconnected.

`redeem` is a separate native-only protocol, not an Agent control: exactly `requestId` and `admissionToken` in its Bundle, actual Binder UID and signing identity must match the target bound at issue, 30-second TTL, one successful consume. It returns the immutable reference/prompt only to that target. Root/shell and unrelated apps cannot redeem it. The reference provider is not exported; only an exact temporary read grant can open a single JPEG. Neither provider offers directory search or writes.

## Build and validation

```sh
bash system/shopping-observer/test-host.sh
bash system/shopping-observer/build.sh
```

Host tests require JDK 17+. The history adapter test uses a SHA-256-pinned Maven JSON test dependency cached under ignored `out/`; `TAKU_TEST_JSON_JAR` may supply the same pinned dependency offline. Build uses `scripts/android-env.sh` (Android SDK 36 by default), compiles the current Proactivity / system AI clients and the shared try-on common/contract directly from source, then produces ignored `out/TakuShoppingObserver.apk`.

The standalone build creates an ignored development keystore, or accepts `TAKU_OBSERVER_KEYSTORE`, `TAKU_OBSERVER_KEY_ALIAS`, `TAKU_OBSERVER_STORE_PASS`, `TAKU_OBSERVER_KEY_PASS`. No signing material belongs in Git. Soong module `TakuShoppingObserver` uses the platform certificate with its own ordinary UID, allowing it to enforce central permissions on behalf of ordinary callers. Development-signed builds fail closed if the central permission service does not trust that enforcement host.

Host coverage includes LIVE structural parsing, room/window continuity, deadlines, bounded capture, local frame quality and ROI transforms, manual selection, 700 ms read throttling and exact-root layout snapshots, durable cost/history limits, shared UI/Agent settings validation, and target-bound admission replay/TTL/concurrency. Host tests do not emulate a camera or call cloud AI. APK compilation is separate from a full Soong/ROM build.

See [TESTING.md](TESTING.md) for isolated runtime scenarios. Never install or enable this service on an unspecified personal device as part of a build/test script.
