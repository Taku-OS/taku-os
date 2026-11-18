# PR #60: Jacky's review follow-up

Review: [jw-12138 on 489e01a6](https://github.com/Taku-OS/taku-os/pull/60#pullrequestreview-5918573269).
This receipt separates corrections from product decisions and module acceptance from a full image build.

## Disposition

| Finding | Resolution |
|---|---|
| P0 audio source closure / stale prebuilt | Rebuild Audio with `--with-tts`, reseal the APK/assets/source lock, and validate the real source closure in CI. |
| P0 dismissed/recovered Expanded card cannot reopen | Explicit monotonic expansion requests for gestures, resume, text submissions and explicit expand. Stream updates preserve dismissal. Remove the probe's forced Hidden reset; test a real new voice gesture after a retained Expanded reply. |
| P0 landscape does not close non-compact / covered card | Collapse before visibility/alpha early returns. |
| P0 voice page stays after release/send | **Intentional product behavior**, explicitly requested by the owner. Silence keeps listening; finalization/reply stays on the page until Cancel. Correct the outdated probes. The separate bug where Open conversation started Keep talking is fixed. |
| P1 card steals focus / resets IME height cap | All card layouts are non-focusable; retain available IME height across content updates. Back belongs to the underlying app; swipe/header/Agent commands close the card. |
| P1 bridge requests race | Serialize blocking provider requests on one IO lane, retaining latest-request cancellation across retry delays. |
| P1 hidden assistant participates in compact cycling | Cycle only compact-visible entries. |
| P1 unconditional 1 Hz serialization | Schedule actual expiry/staleness/alert deadlines; tick at 1 Hz only for supported timer/countdown slots, notify observers only when snapshots change. |
| P1 Home focus loss cancels latched/finalizing capture | Abort only a held pointer gesture; latched listening and finalization retain ownership. |
| P1 Live Talk pause/resume/lifecycle/navigation | Pause follows user intent; finalizing capture is not replaced, activity stop filters MainActivity, React unsubscribe does not stop native recording, explicit conversation changes and collapse stop Live Talk, resume clears pause/pending launch, synchronous start failures propagate. |
| P1 Quickstep fullscreen geometry / empty action row / backdrop | Restore fullscreen params, reset hidden action flags even with zero tasks, decode sampled wallpaper on UI helper executor and hide it outside portrait-phone mode. |
| P1 image resume / verify-only omissions | Check both recents and launcher patches; add read-only verification paths and tests rejecting unapplied patches. |
| P1 NativeControls rotation / surface ownership / pipe capture | Handle configuration changes without consuming capture twice; stale process-restored captures close. Track all live surfaces, not a last-created singleton. Read a bounded pipe once before bounds and bitmap decoding. |
| P1 Clock resolver / Agent error | Preserve Android chooser for multiple alarm handlers; provider preflights the same clock intent and returns the actual missing-app error. |
| P1 CI coverage | Add home-policy and launcher-entry checks to check:images; real audio closure and cross-lock Pixel Weather consistency tests. |
| P2 UI/resource details | Theme WorkingDots, restore text blank-area dismissal, apply changed cutout layout params, add audio microphone view ID and defer asked flag until a real permission result, guard destroyed Weather activity and apply safe insets. |
| P2 openConversation thread race | Use shared SessionThreadCreation and retain the current thread/stream while opening chat. |

## Access-policy findings

- Accept the missing PendingIntent creator-UID check and destination signature check; fix both.
- Enforce the same DEVICE_LOCKED guard in the voice backend and external Home entry.
- Keep Island/Recents and capture Agent interfaces available to ordinary local callers as required by
  the repository engineering contract. Document the concrete operations and their real privilege impact,
  including allow-list replacement and capture UUID versus pending Jev token distinctions.
- Keep the explicitly exposed legacy panel and public Home property readable. `WITH_ADB_INSECURE`
  remains confined to the emulator product; do not apply it to a physical-device target.
- Keep exact historical `.gitleaksignore` fingerprints. They refer to old commits, so moving current
  lock-file lines does not justify broadening or rewriting those exceptions.

## Verification scope

Local `npm run verify`, Agent lint/typecheck/377 tests, Android Home unit tests and native module
builds are recorded separately from device probes below. Audio was rebuilt with TTS and its input
closure was resealed. SystemUI was compiled with the pinned Lineage target/toolchain, without
uploading credentials or device data. No full current-HEAD Pixel/Emulator ROM build is claimed.

Quickstep patch/source tests pass. The owner separately authorized its source upload and cloud
compilation. The 05:36 pinned-target module build and all six RecentsReviewProbe checks passed:
real card launch, idempotent show, same-task Agent open, landscape overview, invalid-ID rejection
and removal of only the newly created fixture task. Existing tasks were preserved.

Device regression results are in [acceptance.json](acceptance.json): 8 voice-state cases, 5
review regressions, native reply/options, real gesture reopening, upward dismissal and Agent
observer synchronization, landscape dismissal and portrait re-expansion. Real host microphone
audio was transcribed by the configured FAL cloud profile and automatically sent to the real
Agent; its “Voice test successful” reply (translated from Chinese) matched the SystemUI card. No reply was fabricated.

Both legacy and native cards preserved the underlying window and editor focus. Synthetic
`adb input text` did not enter text even in the no-card baseline, so typing/IME-height acceptance
is not claimed from that run. The IME cap is source-verified. Pixel hardware, full-image
reproducibility, frame timing, pipe-capture runtime and empty-task overview acceptance remain
separate from the reported module/device checks.

The source-lock CI regression reconstructs the gitignored voice Blueprint deterministically from
sealed model paths, then verifies all source and generated hashes. `npm run verify` also passed
in an independent clean checkout without local prebuilt APKs or the model pack. This fixes the
first CI run’s missing `prebuilt/Android.bp` failure without weakening the image-build checks.
