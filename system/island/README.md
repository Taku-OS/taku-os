# SystemUI island

Native source for [issue #28](https://github.com/Taku-OS/taku-os/issues/28). This is the **source implementation**, not a standalone launcher overlay. The real pinned-platform SystemUI module
build passed on 2026-11-15 ([receipt](../../docs/reviews/island-cloud-2026-11-15/README.md)). The complete
Pixel ROM build and physical-device acceptance remain open; compilation is not runtime acceptance.
The same-source ARM64 emulator now has a complete build and device evidence:
[2026-11-15 integration record](../../docs/reviews/island-emulator-2026-11-15/README.md).
Notification/settings, touch pass-through and rich-surface death/rebind passed; covering-card input
focus failed in that historical run. The PR #60 follow-up makes every card non-focusable;
see the current review receipt for the new verification scope.

## Native implementation

- `src/` is installed into `frameworks/base/packages/SystemUI/src/` by an exact-version patch.
  Five upstream hooks register the Dagger startable/provider, attach to the real status bar, and
  reserve compact width. SystemUI already has `STATUS_BAR_SERVICE`; the launcher UID is unchanged.
- `IslandEngine` owns the notification repository and policy. `IslandPresenter` owns selection,
  expansion and transitions. Every compact island uses the Figma `1379:889` geometry anchored
  to the display cutout: glyph disc, camera disc and a text pill sized to its content, joined by
  the authored metaball necks. Every expanded card is SystemUI-native in the `1:4031` design
  (`IslandAssistantCard` for Taku, `IslandTemplateCard` for other publishers, shared
  `IslandStyle` tokens); no stock widgets are used for island chrome. Centered clock/network Views are temporarily moved to
  the start container, then restored. Landscape supports compact presentation only.
- `IslandCardWindow` uses `TYPE_STATUS_BAR_ADDITIONAL`, a bounded window with rounded touchable
  insets updated during animation. Outside input has no collapse handler. Shade/keyguard/heads-up
  transitions collapse it; explicit handle/upward swipe and Agent commands share the presenter.
- Every card, including legacy body-layout covering cards, is non-focusable. The underlying
  editor/recording Activity retains focus. Swipe, header controls and Agent commands collapse
  the card; Back remains owned by the underlying app. IME height limits survive content updates.
- Content updates do not restart geometry animation. Native templates remain during asynchronous
  rich-host binding; a shared Messenger contract carries a `SurfacePackage` from a same-package,
  signature-protected service. Stale replies, timeout, service death and teardown keep the template.
- The phase-0 demo/probe commands from commit `1789122` have been replaced by production data and
  presentation operations. No demo island is automatically created.

## Lock status

`IslandLockStatus` shows the device's lock state as a status: the capsule's disc and camera with a
white padlock drawn by `IslandCapsuleView`, no pill, no card, no actions. It is not an activity: it
is never in `IslandStore`, so `list` does not return it and the island switch, lifetimes and
dismissal do not apply. It is `locked` while the keyguard shows and needs authentication
(`KeyguardStateController.canDismissLockScreen()` is false), `unlocked` for 1.5 s after that ends,
and absent otherwise, including on a keyguard that needs no authentication.

The status bar hides its island on the keyguard, so the lock screen hosts `IslandStatusView`, the
same capsule placed by the same camera geometry (`IslandCameraGeometry.locate`), from
`system/lockscreen` (`TakuLockStatusSection`). It takes no touches there. Once the keyguard has gone,
`IslandPresenter` draws the open padlock as the primary capsule for its 1.5 s (restarted when the
keyguard leaves); the selected live activity waits as the circle beside it and keeps its chip
ownership. Any gesture on the open padlock ends it early. `state.presentation.lockStatus`
(`locked`, `unlocked` or null) and `lockStatusInCapsule` report it; `compact.disc` is `lock`.
Not yet run on an emulator or device.

## AI producer and remote body

SystemUI binds `org.agentos.agent.home.island.IslandPublisherService` in the current user.
It observes the authoritative native `AssistantSession`, publishes bounded notifications and
restores only an existing backend thread after process recreation. Each user turn has a separate
presentation identity so a dismissed/expired prior activity cannot suppress a new task.
Assistant listening, replies and real model options are drawn directly by SystemUI's native
`IslandAssistantCard`; the Agent publishes data and actions, not a Compose island surface.
Other third-party payloads may request the documented rich-content service.
A monotonically increasing expansion request distinguishes an explicit new interaction from
routine state updates, including when both states are Expanded. Recovery and streamed reply
updates do not undo the user's swipe dismissal. The launcher owns voice/text input, never the island.

## System sources and arbitration

Call priority derives from Android CallStyle plus the default Telecom dialer, managed-calls
permission or a system publisher; custom JSON cannot claim it. Alarm/stopwatch notifications derive
priority from the Android category, ongoing/full-screen and countdown fields. CallStyle answer,
decline and hang-up PendingIntents are used directly; clock actions retain the producer's labels
and IDs (no guessed snooze/stop broadcasts). Original notifications and full-screen intents remain.
Recording and casting use `ScreenRecordUxController` / `CastController` callbacks and stop methods.
They neither wait for nor require the AI process. Native controller actions appear in `availableActions`
and use the same `action` command as card buttons.

Calls and alarms outrank ordinary alerts. A rising alert is bounded to five seconds; repeated
updates cannot extend it forever. Remaining categories follow recording/cast, navigation, timer,
assistant, media, progress, other, then relevance/time. Explicit selection survives routine ticks.
Every actually displayed island (the primary capsule and waiting disc) suppresses its identical
ongoing chip; unrepresented chips remain. Heads-up takes precedence: it collapses the card and hides its matching compact island,
without cancelling the heads-up or full-screen intent. When hidden, disabled, locked, or out of
space, the island releases chip ownership. Multi-display and visibility transitions need final ROM
acceptance; primary-display ownership is the supported presentation scope.

## Source/build commands

```sh
python3 image/apply-island-patches.py --check-inputs
python3 tests/island-patches.test.py
bash system/island/build-tests.sh

# Complete pinned platform checkout, on the cloud builder:
python3 image/apply-island-patches.py --source-root /src --results-dir /results
python3 image/apply-island-patches.py --source-root /src --results-dir /results --verify-only
# Build SystemUI using the same lunch target/toolchain as the image; then build/install that image.
```

The production image build applies the patch during integration and verifies it on resume. Unknown source bytes,
partial application, changed pins and symlink targets fail without overwriting them. The integration
report records source verification separately from compilation and device acceptance. To refresh
owned code after editing, use `python3 system/island/update-patch.py --upstream-root PATH`, where PATH
contains the unmodified framework files at the pinned revision. It retains existing upstream hooks.

The parser test APK can be installed on an API 36+ emulator and run with:

```sh
adb -s SERIAL install -r out/island-parser-tests/TakuIslandParserTests.apk
adb -s SERIAL shell am instrument -w org.agentos.island.tests/org.agentos.island.tests.IslandParserTests
```

## Agent and production data interfaces

The SystemUI startable subscribes to `NotifPipeline`. Worker extraction validates custom payloads
and Icon/Action references; rejected custom data falls back to a promoted ongoing notification or
leaves the ordinary notification untouched. Only resource icons from the posting package/Android
and bitmaps up to 512×512 / 1 MiB are accepted; URI/data icons are not fetched. The store tracks
at most 128 notifications. A new one beyond that is turned away (`capacity_limit`) and keeps its
native heads-up: the heads-up filter suppresses a brand-new post only after `IslandStore.admit`
reserved its slot. Custom publishing
is automatic for apps signed with the platform certificate; other apps need an allow-list entry.
Agent access to already-visible state and operations has no publication-permission gate.

```sh
adb shell content query --uri content://com.android.systemui.island/capabilities
adb shell content query --uri 'content://com.android.systemui.island/canShowIsland?package=org.example.app'
adb shell content query --uri content://com.android.systemui.island/list
adb shell content call --uri content://com.android.systemui.island --method get --arg 'NOTIFICATION_KEY'
adb shell cmd statusbar island state
adb shell cmd statusbar island list
adb shell cmd statusbar island get 'NOTIFICATION_KEY'
adb shell cmd statusbar island select 'NOTIFICATION_KEY'
adb shell cmd statusbar island next
adb shell cmd statusbar island tap 'NOTIFICATION_KEY'
adb shell cmd statusbar island expand 'NOTIFICATION_KEY'
adb shell cmd statusbar island collapse
adb shell cmd statusbar island dismiss 'NOTIFICATION_KEY'
adb shell cmd statusbar island action 'NOTIFICATION_KEY' '{"id":"stop"}'
adb shell cmd statusbar island action 'NOTIFICATION_KEY' '{"id":"reply","reply":"Continue"}'
adb shell cmd statusbar island open 'NOTIFICATION_KEY'
adb shell cmd statusbar island settings
adb shell cmd statusbar island setEnabled 1
adb shell cmd statusbar island setAllowed org.example.app,org.example.other
```

Protocol: `taku.island.v2`, the only custom protocol ([schema](../../spec/taku-island-v2.schema.json),
[developer guide](../../docs/dynamic-island-developer.md)). Host tests (`test.sh`) cover the store,
access policy, payload validator (via a test-only `android.util.JsonReader` shim), capsule
geometry and system-source classification.

Provider queries return one `json` column; provider calls return a Bundle containing `json`.
Callers are authorized first (`IslandAccessPolicy`). UID 0/1000/2000, SystemUI and platform-signed
packages have full access. Third-party apps get only `capabilities` and `canShowIsland` for their own
package, or, with an App Permission Center `taku.control` grant, read/operate access to the
activities their own UID published. The provider passes that UID to the engine as the call's scope:
reads are filtered to it, a targeted operation (`get`, `select`, `expand`, `tap`, `dismiss`, `hide`,
`open`, `action` including a `RemoteInput` reply) on another app's key returns `not_found`, and the
returned state is cut the same way. `collapse` folds the expanded card only when that UID published
it (`IslandPresentation.expandedKey`), and is refused otherwise, including when no card is open.
`next` and settings writes are never available to them. Refusals return `permission_denied`.
Every cmd operation is also a provider `call` of the same name: `arg` is the first argument and
Bundle string `value` is the optional second argument (e.g. action JSON). `get` includes the payload,
revision, monotonic first-seen/expiry, staleness, dismissal and fallback reason. `dismiss` hides the
island only; it never cancels the task or its notification. Only `action` invokes the producer's
PendingIntent. Reply text requires a corresponding free-form `RemoteInput`, capped at 4,000 chars.

Settings keys are `global taku_island_enabled` (`0`/`1`), `global taku_island_allowed_packages`
(comma-separated package names, max 128), and `global taku_island_protocol` (read-only capability
value `1`). Raw invalid settings writes are restored to the last valid policy. Android Global
Settings cannot enforce read-only per key: the protocol marker is repaired by the observer, while
provider/cmd reject protocol writes synchronously. Use provider/cmd for synchronous validation.

Errors include `not_found`, `island_disabled`, `presentation_unavailable`, `invalid_enabled`,
`invalid_allowed_packages`, `read_only_protocol`, `action_unavailable`, `reply_unavailable`,
`reply_too_long`, `systemui_starting` and `systemui_timeout`. Retry a read after a timeout before
retrying a mutation. Expansion reports `presentation_unavailable` when the real bar is absent, landscape/lock/shade/heads-up
blocks it, or window attachment fails. Device acceptance remains pending.

Local source checks: `npm run check:island`. `system/island/typecheck.py` additionally accepts an
Android 17 framework test JAR and an existing Gradle cache. It uses narrow API doubles for SystemUI
and does **not** run Dagger annotation processing, Soong, or ROM/device acceptance.

## Onboarding sign-in capsule

Progress notifications with picture + text slots use the camera-anchored compact
status layout, shared with Building. The working glyph uses the designer's native
nine-dot loader; the login text is `Signing...`. Geometry, source assets, and the
non-invasive Android render checks are documented in
[onboarding-connecting](design/onboarding-connecting/README.md). This source change
requires a new SystemUI build; updating the Home APK alone cannot change the shape.

Simple Taku progress notifications also use a content-sized native expanded card
styled from Figma 1155:1965. It shares the assistant eyes, preserves actual login
text/actions, and replaces the fixed-height generic template for that scope.
See [expanded onboarding](design/onboarding-expanded/README.md) for metrics,
background fallback, Agent commands and test/deployment boundaries.

## Acceptance to run on the final build

Record exact source revision, image digest, target, build type, screen geometry, navigation mode,
font scale, clock/network options and device/host load. Keep screenshots, `cmd statusbar island state` readback,
`dumpsys window`, crash logs and frame traces for:

1. Compact layout on centered/corner/no cutout, rotation and large fonts; clock/battery remain visible.
2. Capsule tap versus vertical shade pull-down; rounded card corners and outside taps pass through
   during every animation frame, and outside taps leave the card open.
3. Shade/keyguard are above the card; lock, rotation, display removal, IME and SystemUI restart.
4. Rich-surface attachment, timeout, binder death and template fallback without a SystemUI crash.
5. GUI actions versus `cmd`/`content` readback from the same authoritative state.
6. Real frame timing and regressions from issue #25. No numerical performance claim until measured.

See [publisher guide](../../docs/dynamic-island-developer.md), [preinstalled Agent commands](../assistant/assets/skills/taku-island/SKILL.md), and [cloud/device handoff](../../docs/dynamic-island-handoff.md).
