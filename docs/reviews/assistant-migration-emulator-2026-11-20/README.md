# Assistant migration: ARM64 image and existing-data emulator verification

On 2026-11-20, the user requested installing this cloud image into their currently open emulator and preserving its data. This is an existing-data upgrade and smoke test, not a factory-reset or Pixel acceptance run.

## Fixed inputs and build

- PR branch base: `53a68931921ed5d8b8f867b822ff23ab78870998`.
- Image source: `a78aee851b3c8ae207f3ec678d5b6eb8302fd512`, adding the software AEC registration and original-signed IME 10072 lock.
- Target: `lineage_taku_emu_arm64-cp2a-userdebug`, build `20261120140000`.
- Native x86_64 cloud builder, ARM64 output, 56 jobs, 210 GiB memory limit. Prior source and compiler outputs were used as read-only OverlayFS lower layers; this run wrote separate upper layers.
- `TAKU_INCLUDE_GOOGLE_APPS=false`, explicitly selected by the user to match the existing emulator. Google services and apps are outside this image's scope.
- All 2,427 sealed input files were checked before compilation. Full `droid` build and export exited 0. The downloaded archive and all nine image hashes were independently verified.
- Archive SHA-256: `cf40153e18724122375d94b0d5da364a9ca9acb61eaaa35224fcbbf18c0e0235` (1,899,365,515 bytes).
- Cloud builder was stopped after downloading and verifying the archive. Source and cache remain on its independent disk.

## Upgrade and component identity

The existing `emulator-5590` AVD was stopped and its entire data directory, including encrypted userdata and encryption metadata, was cloned locally before changing image paths. The new image cold-booted with existing accounts, chat data, desktop layout and settings. A second reboot applied SystemUI and confirmed persistence.

Old manually installed SystemUI and Launcher packages initially overrode the ROM copies. They were replaced with the exact APKs pulled from the new image, preserving application data. Persistent SystemUI required Android's staged installation and reboot; ordinary replacement was rejected. The already-installed Audio Engine matched the image hash. Home, Assistant, IME, SystemUI, Launcher and Audio identities are recorded in [acceptance.json](acceptance.json).

SELinux remained Enforcing and ADB ran as UID 2000. No temporary AEC configuration mount was present after reboot. `/vendor/etc/audio_effects.xml` had the expected registered hash, and AudioLive reported `aec=true` while capturing.

## Device checks

| Check | Observed result |
| --- | --- |
| Existing-data cold boot and reboot | Build `20261120140000`, Broker running; desktop layout and default Home/IME preserved. |
| Keyboard and island | A real test reply appeared in the expanded native island while `mInputShown=true`. A physical UI tap on `q` changed the draft to `q`; it was then cleared. |
| Island navigation | Tapping the expanded card body opened its chat. Tapping the compact fixture's left side expanded it. An outside-card button incremented the fixture counter while the card remained open. |
| Explicit emoji request | A normal Chinese request, without naming a skill, produced a visible 🎉 badge and a `source: agent` reaction record. |
| Grok Voice via fal | A fixed 16 kHz mono PCM sample injected through the authenticated emulator microphone API was transcribed accurately; a generated reply and speaking playback state were observed. A fresh-session time query reached an Agent task with `state: completed` and a spoken result. |
| Live Talk controls | Actual microphone and close-button taps produced `muted=true, capturing=false`, then `lifecycle=closed`. |
| Recents | Native `show` returned `queued=true`; overview was displayed and Home restored. |
| Crash check | No `FATAL EXCEPTION` entries in the post-reboot crash buffer at the end of this run. |

The reaction and chat state were read through their standard content providers, and island state through `cmd statusbar island state`. GUI input and the provider draft matched. IME 10072 retained its independent signing certificate and was byte-identical to the verified handoff APK. Agent lint and typecheck passed, the IME staging verifier passed, and all six emulator host tests passed.

## Observations and limits

- The first Grok attempt failed with `NETWORK_FAILED` after AEC-enabled capture began. Later fresh-session tests passed. This is not a root-cause fix or a claim of connection reliability.
- Opening the chat briefly showed a BUSY/history-incomplete banner and recovered automatically.
- The time-task reply spoke “Thursday” for 2026-11-20, which is Friday. Task transport completed, but factual reply accuracy still needs attention.
- A second injected clip in one session did not produce a new transcript; restarting the session and injecting the same query succeeded. Continuous turns and emulator input routing require further verification.
- No physical-microphone/acoustic AEC-quality, Gemini, long-duration, reconnect, late-transcript, local-VAD overlap or goAway acceptance was completed.
- The compact fixture was rendered on this AVD with clock, Wi-Fi and battery visible. This does not cover the full crowded-icon/RTL/Notebook screenshot-to-receipt matrix.
- No factory-reset first boot, Google integration or Pixel verification was performed.

Raw device state, screenshots, local backup and credentials stay outside Git. This report includes only build identities and summarized test evidence. The test notification was cancelled and Live Talk capture was stopped before handoff.
