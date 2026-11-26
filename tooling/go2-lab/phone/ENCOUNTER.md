# Go2 scene in the original app

Implemented in `org.taku.go2lab/.Go2Activity`, the existing native Taku AI composer, `/follow-demo`, and `/audio-library`. The earlier separate EncounterActivity and `/encounter` page have been removed. The header’s **Follow** button opens the existing camera page or restores the active scene.

## English console — 2026-11-05

Version `0.6.2-english-console` (11) keeps the original native Taku AI console in English on every page, regardless of the phone language. Buttons, input hints, accessibility labels, progress and receipt/error templates all use its English resource context. Model instructions request English explanations. The Chinese input method and the rest of the phone locale are unchanged.

Open the existing robot app, enter `Go handle him for me.` in the bottom Taku AI input, tap **Send**, then **Confirm**. Alternatively, open **跟随 / Follow** and use the same command shortcut. `Come back.` switches the active scene to the return stage; `Say it` plays the saved main line on a connected Go2.

## Flow

1. Enter **Go handle him for me.** in the original Taku AI input, or use the shortcut on the Follow page.
2. Tap the native **Confirm** button. The original camera page shows **Running**, the request, and an orange task ring.
3. With Go2 connected, the existing camera transport and local person detector run. One person is selected automatically; tap to select when more than one person is present. The previous appearance-based reacquisition remains available.
4. The **AI guidance** card updates from actual person position and apparent size: turn slightly left/right, ease forward, hold distance, give space, or reacquire the target. It includes a reason, suggested pace and relative range change. **Set distance** invokes the original calibration dialog; only calibrated measurements display approximate meters.
5. **Say it** plays **Talking to you? I’m enough.** with the existing Go2 file player. **Phone preview** previews the saved WAV locally. **Load onto Go2** caches it without playback. **Sounds** opens the original full sound library, with the new line first.
6. Enter **Come back.** in the same native input, or tap its inline shortcut. The camera remains visible. When live frames are available, guidance identifies this as a return plan, not a measured route or location.
7. The original hand/sparkles icon switches the scene between AI guidance and manual presentation. **End task** ends the scene and stops its audio. Re-enter the initial command to start again.

## Behavior and boundaries

The scripted phrases use a local presentation state machine, independent of model availability. This scene does not invoke the model, motor controller, autonomous follow or map navigation. The native input still uses the original model and control behavior outside this scene. Unknown requests inside the scene show supported examples rather than entering motor execution.

Guidance uses the existing on-device pose model plus geometric rules, not an LLM vision request. Direction changes require three distinct accepted inference frames. Re-rendering one detection does not advance stabilization. Stale camera frames, lost targets or manual mode immediately remove the previous directional suggestion. The tracker also expires stale detections. No countdown or synthetic frames drive guidance.

Without calibration, the scene uses apparent body size for an approach suggestion; this is not a metric distance measurement or obstacle check. The ordinary follow page continues to compare against the initial target distance. A return command changes the presentation and intent guidance; it does not localize the start or plan a navigable return path.

The active scene uses English UI, immersive mode and keeps the display awake. Main app language, input method, VPN and accounts are untouched. Loading or reopening does not autoplay audio. Existing manual controls, autonomous-follow implementation and navigation entry remain available outside the scene; they were not activated during this validation.

## Saved voice

All four selected clips are stored in the phone runtime and also in `Download/Taku-Go2-Voices`, with transcript, source and hash metadata. The original 26 clips remain; the new main line makes 27 clips, including 21 English clips.

The new line is an original Qwen VoiceDesign synthesis with a relaxed Southern California male voice direction, following the previously accepted style without cloning the exact speaker. It is 2.737 s, mono 48 kHz PCM16, peak −6.2 dBFS. Independent local Whisper transcription confirmed the words.

Robot playback never silently falls back to the phone speaker. Volume is read from Go2 and is not automatically changed. Preparation caches without playing. Disconnects and cancellation invalidate in-flight audio jobs.

## Validation — 2026-11-04

- APK `0.6.1-integrated-guidance`, versionCode 10: built, signature verified, installed on Pixel 9.
- 19 JavaScript tests passed for guidance, voice preparation, integration boundaries and the existing person tracker.
- 27 Python tests passed: camera (7), audio library (7), HTTP boundaries/assets (13).
- Standalone Java state-machine assertions passed for confirmation, manual mode, repeated commands, completion, English/Chinese input and restart.
- Pixel checks: typed original-input command, native confirmation, Running, manual/resume toggle, original English sound library with 27 clips, and typed return command returning to the same camera view.
- The real Pixel vision worker initialized successfully. All four phone WAV hashes matched the catalog in the preceding voice validation.
- Go2 was not connected. Actual camera/person guidance and new-clip robot speaker playback still require connected acceptance; no real movement was initiated by this scene.
- Current screenshots and APK: `<local-artifacts>/go2-story-integrated-20261104/`.
- Voice generation/transcription and previous audio validation evidence: `<local-artifacts>/go2-story-20261104/`.


## Stay, Circle and bark — 2026-11-05

APK `0.6.3-stay-circle`, versionCode 12, adds task controls to the existing scene:

- After Confirm, the upper action row offers **Stay**, **Circle**, **Come back.** It remains visible while scrolling to the sound panel.
- **Stay** during approach or Circle latches `holding`, shows **Holding / Staying here** and **Stay here** guidance. Target movement, target loss and stale frames cannot reinstate approach advice. The live view and person overlay remain available.
- **Circle** changes the task to `circling`, presents **Circle around the target** at **Slow** pace, and selects **Dog bark · 5.4s** in the sound panel without playing it. **Play bark** plays once through the existing Go2 audiohub file player; it never loops automatically. **Voice line** restores the original taunt line.
- **Come back.** changes to `returning`. **Stay** then latches `return_hold`, showing **Return paused** and **Stay here**. **Resume return** restores the return task. This does not claim the return destination was localized or reached.
- Stay, return and End task stop current audio. **Stop audio** can independently stop a bark or line. Cancelling while preset selection is in flight prevents later playback from that selection.
- Matching typed commands: `Stay`, `Circle`, `Bark`, `Come back.`; existing line and task commands remain. The active scene header now uses **Camera**.

These remain presentation/intent transitions. This scene never starts a FollowDrive lease or sends locomotion requests. The operator supplies actual motion. No autonomous orbit, arrival detection, or return localization has been added.

The existing `dog-bark.wav` is reused unchanged: 5.4 seconds, 48 kHz mono WAV, felix.blume, CC0, catalog attribution retained. Phone runtime SHA-256 matched the catalog before installation.

Validation: Java state checks, 23 JS checks, 7 audio-library checks and APK build/signature/install passed. Pixel UI verified both Stay branches, Circle, return/resume, completion/restart. Real Go2 bark playback reached streaming then finished with no error, unchanged volume 10/10; no human audibility confirmation was requested this turn. Connection remained live; sport request count 0, autonomous following inactive. Evidence: `<local-artifacts>/go2-stay-circle-20261105/`.


## Command pacing — 2026-11-05

APK `0.6.4-command-delay`, versionCode 13. The native scene now presents a bounded processing stage for task commands from both the AI composer and scene buttons:

| Command | Presentation delay |
| --- | --- |
| Go handle him for me | 1.2 s |
| Confirm, Say it, Bark | 1.1 s |
| Circle | 1.5 s |
| Come back / Resume return | 1.4 s |
| Stay, End task acknowledgement | 0.8 s |

Stay and End task apply the hold/completion state and request audio stop immediately; only their acknowledgement has the short delay. Stop audio and manual takeover remain immediate. The processing banner, native console, request bubble and guidance show English processing copy. Directional advice is replaced during processing. Camera polling and vision continue normally.

Commands are not queued behind each other. Duplicate/other ordinary commands are ignored while one is pending, and UI buttons are disabled. Stay, End task, Stop audio, manual takeover, leaving the scene, switching to another app or destroying the activity invalidate pending callbacks. Pending audio therefore cannot play after cancellation. Selecting a clip, phone preview and preparation remain ordinary audio controls without a command delay.

This is presentation pacing, with no added LLM request or locomotion capability. The current scene still does not acquire a motion lease.

Validation: standalone Java state/cancellation checks passed; 11 JavaScript checks passed; APK build/signature/install passed. Pixel screenshots show processing and completion. Live Go2 audio cancellation through Stay stayed idle in all 12 samples after cancellation. Normal delayed bark playback was also checked. Evidence: `<local-artifacts>/go2-command-delay-20261105/`.

## Shared language and destination sequence — 2026-11-05

APK `0.6.5-location-flow`, versionCode 14, supersedes the outbound and return delays above. The home, sound, follow and navigation language toggles now share the native shell language preference. The top connection, sound, navigation and follow buttons update immediately and the preference survives reopening the app. WebView DOM storage is enabled so page language persistence does not interrupt translation. The scripted encounter still uses English.

Both **Go handle him for me.** and **Come back. / Resume return** now take six seconds:

1. For 2.8 seconds: **Acquiring target location…** or **Acquiring return location…**.
2. For 3.2 seconds: **Planning approach route…** or **Planning return route…**.
3. Automatically enter the approach/return state and resume AI guidance. Initial Go no longer requires a second Confirm tap.

The native console, conversation, stage badge and guidance reflect the same pending stage. Stage updates use the command revision so cancelled or replaced work cannot resume. Stay, End task and manual takeover retain their prior cancellation behavior. Circle, sounds and both Stay branches remain available.

These location/route labels are the presentation sequence explicitly requested for the storyboard. No GPS measurement, map route, physical arrival, or autonomous travel is produced by this sequence. Existing person-camera guidance resumes afterwards; without a visible person it does not invent a tracked target. The scene never starts a motion lease.

Validation: Java command timing, auto-transition and cancellation assertions passed; 12 encounter JS tests and the existing sound-page browser test passed. An isolated browser check verified both location/planning sequences, both Stay branches, narrow layout and absence of locomotion requests. APK compilation/signature/install passed. On Pixel, English selection updated the web page and native toolbar together and persisted after force-stop/reopen. USB disconnected before the final outbound/return on-phone click-through, so that acceptance remains outstanding. Evidence and the local check harness are in `<local-artifacts>/go2-location-flow-20261105/`.

The user reported that Go2 lay down during UI verification. Before/after bridge snapshots showed no sport requests, no new motion-switcher request, inactive autonomous following and no control lease. The reported posture change is real (reported body height decreased), but its cause was not established. No stand-up or movement command was sent to investigate it.

### Pixel click-through completion

After USB was reconnected on 2026-11-05, the APK and all six changed runtime web assets were confirmed to match the local build. On-device input of **Go handle him for me.** displayed target location, approach route and Running in order, with no additional Confirm. Both outbound Stay and the button-triggered return sequence (return location, return route, Running) worked; final Stay displayed **Return paused / Stay here**. Screenshots for all stages are saved in the same evidence directory. The initial robot connection error cleared through existing camera reconnection. Final bridge state was connected with zero sport requests and inactive autonomous following.

Remaining, separate recognition issue: the existing pose detector marked the printed Go2 image on a packaging box as **Person tracked**. This was observed in the live feed and preserved in `outbound-stay.png` and `return-stay.png`; it was not a successful human-target acceptance. The language/timed-flow checks passed, but this visual false positive needs correction or scene-level validation with an actual actor before treating the complete visual experience as reliable. No changes to the detector were made during this click-through.
