# Observer verification

Run `bash system/shopping-observer/test-host.sh` and `bash system/shopping-observer/build.sh` from the repository root. Scripts produce only ignored build/test artifacts and perform no device installation or model call. The production `main` SDK sources are the compile input; no older generated SDK JAR is used.

Runtime checks must name an isolated emulator/device with `adb -s`. Installation, granting notification permission, enabling accessibility, and cloud generation are separate operations. A source build or a host fixture is not proof of a real TikTok/Lucy round trip.

## Local / emulator checks without a model call

1. Install the APK on the named isolated emulator. Read `status` while accessibility is disconnected: all three switches and command discovery remain available; no camera service exists.
2. Use `configure` to change each switch; open the UI and verify the same switch values. Change them back through UI and read the same flat values through `status`. Unknown keys or string booleans must reject the complete mutation.
3. Enable accessibility only on that isolated emulator. With observation disabled, `tryOn` returns `disabled`; with enabled observation and a non-TikTok foreground app, it returns a fixed `source_…` reason. Neither path produces a screenshot model request, admission, camera or RTC service.
4. Ordinary unapproved app calls must receive permission denied, while shell/self remain direct. On a platform-signed enforcement host with the App Permission Center, approve/revoke `taku.control` and verify both settings and commands follow that same central state. A development signer cannot substitute a local grant list.
5. Invalid `redeem` calls, including from shell, must return only `invalid_admission` without revealing reference/prompt/ticket.

## Named real LIVE integration (explicit cloud authorization required)

1. Keep a clear clothing LIVE room visible and unlocked. Enable only the automatic identification option being tested, authorize the configured system AI model and Proactivity observer, and grant notifications.
2. Entering the room may produce an opted-in recognition/reminder. It must not start a camera/RTC session. Main's **Viewed** only presents the separate Try on confirmation; it must not capture or generate another reference.
3. Choose **Try on** once. Matching appears through main's notification/island path. At most three screenshots / one automatic localization occur within 20 seconds. Native's visible entry redeems a fresh one-use admission and starts the front-camera service only after successful redemption.
4. Switch presenter/package/window, open the keyboard, rotate, lock, or disable observation during each asynchronous boundary. Any stale image/model callback must be rejected; returning to the page must not revive that attempt.
5. Use unclear/multiple garments to reach Select outfit. Tap the same retained frozen preview or use API normalized coordinates; both invoke the same selection guards. A late completed localization can be retained briefly but cannot auto-open a native session. A fresh explicit Try on / Retry is required after expiry.
6. Cancel/close at every preparation stage and verify notification/picker disappear. After handoff, close the native window or use its `stop` API and verify the camera/RTC service stops.
7. Test admission replay, target UID/signing change, and exact TTL boundaries with an isolated instrumentation fixture, never by publishing private tickets/images to logs. Host tests already verify the pure one-use/identity/time policy.

When an integration supplies an existing host presentation session, repeat steps 3–6 through that host: Matching must not lose its session between reminder acceptance and the first update; Select outfit / Retry / Try on confirmation must appear as expanded needs-action cards without opening a notification. Keep an action card visible for more than five seconds and exercise its button once. Explicit retry must retain the original acceptance deadline, and cancellation, lock, handoff, expiry or service teardown must release the accepted reminder binding. Do not reuse an acceptance session from before an APK replacement.

Report source/host tests, emulator runtime, real TikTok/Lucy runtime, and full ROM/Soong checks separately, including any part not executed.
