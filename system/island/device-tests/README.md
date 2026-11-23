# Device-only island integration fixture

This APK posts real Android notifications and hosts a real `SurfaceControlViewHost` in its own
`:content` process. It is labelled as a test fixture, is never added to the ROM, and does not create
or alter an Assistant session. Build it locally with `bash system/island/build-device-fixture.sh`.
The build script does not operate on devices.

Use only the new custom emulator's explicit serial, after its full image has booted:

```sh
adb -s SERIAL install -r out/island-device-fixture/IslandFixture.apk
adb -s SERIAL shell pm grant org.agentos.island.fixture android.permission.POST_NOTIFICATIONS
adb -s SERIAL shell am start -n org.agentos.island.fixture/.Fixture
adb -s SERIAL shell content query --uri content://org.agentos.island.fixture
adb -s SERIAL shell content call --uri content://org.agentos.island.fixture --method publish
```

The fixture does not request the signature custom-publication permission. Test rejection before
adding its package to the **actual island Settings allow-list**; then repost and check both the GUI
and `cmd statusbar island list/get`. Preserve any existing allowed entries rather than replacing
another user's configuration. The fresh dedicated emulator has no user allow-list to preserve.

Available provider `call` methods are `publish`, `publish-surface`, `publish-alert`, `publish-building`, `publish-listening`, `publish-error`, `publish-reply`, `crash-content`, `cancel`, `tap`
and `reset`. The activity buttons use the same operations. `query` returns `outsideTaps`,
`activeNotifications` (read from NotificationManager), and `lastOperation` in its `json` column.
Unknown operations throw `IllegalArgumentException`; none touch another package's notifications.

For manual expansion, record the returned notification key and use
`cmd statusbar island expand KEY`. Tap the activity's **Tap outside card** button: its counter must
increase while the card remains open. Check capsule tap, Back, IME input, another application
underneath, shade and rotation separately. `publish-alert` tests the five-second automatic alert,
so its natural timeout must not be mistaken for an outside-tap collapse.

`publish-surface` shows **LIVE SURFACE** on a teal background only after a real frame is committed.
To test death fallback, call `crash-content` or tap its activity button. The bound content service
SIGKILLs its own `:content` process; this does not require enabling rooted debugging or weakening
SELinux, and never targets another application. Its main process/notification must remain active,
SystemUI must survive, and the native fallback must remain usable. Close/reopen to test rebind.
This verifies the host wire/window integration, not the Agent's Compose body or AI backend.

## Crowded status bar / Notebook reading state

On a matching build containing the capsule geometry fix, keep the normal status-bar
icon settings and test with enough right-side icons to require native overflow.
Trigger Notebook through the system screenshot entry while an app is foreground,
or publish a building notification with the fixture. Check `cmd statusbar island
list` and `cmd statusbar island state` alongside the visible camera-side loader
and reading label, then expand the same activity. Notification delivery alone is
not a successful presentation. Verify Notebook's presentation receipt through its
current integration before its timeout; do not treat a withdrawn notification as
a layout pass. Check the clock and non-collapsible end content remain visible.

Repeat with fewer icons, a right/center clock, unequal status-bar padding and RTL;
restore any changed settings afterward. The geometry host test covers allocation
and centered reservation only. It does not establish Pixel/ROM rendering, Notebook
delivery receipts, or Save / Send to / Edit actions.

`cancel` removes the fixture notification. `reset` also clears its tap counter. After acceptance,
uninstall only `org.agentos.island.fixture` from the selected emulator and remove its allow-list
entry if the test environment is being handed over without the fixture.

The four assistant modes verify compact policy. Only `publish-building` carries the explicit
`compact:building` flag. Listening/error/reply intentionally omit it to cover cached legacy
notifications. Their ordinary notification and expandable card remain inspectable.

`publish-native-listening` and `publish-native-reply` exercise the native assistant renderer.
The first contains zero options; the second has one `Show details` option whose PendingIntent
increments the fixture tap counter (no conversation/model call). Compare a GUI tap with
`cmd statusbar island action 'KEY' '{"id":"reply_0"}'`; both must update the same provider tap count.
Neither native fixture publishes a remote service or starts its content process.

For focused-editor regression checks, start `.Fixture --ez editorOnly true` to hide the button
list and keep its real EditText on screen. This fixture is also rotation-capable, unlike Home.
