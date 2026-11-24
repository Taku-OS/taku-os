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

The fixture is signed with the platform development key, so SystemUI accepts its island extras
without any allow-list entry (`canShowIsland` reports `platformSigned: true`). To test third-party
rejection (`publisher_not_allowed`) and the allow-list, use an app signed with any other key, add
it in the **actual island Settings allow-list**, repost and check both the GUI and
`cmd statusbar island list/get`. Preserve any existing allowed entries rather than replacing
another user's configuration.

Available provider `call` methods are `publish`, `publish-v2-memo`, `publish-v2-highlight`, `publish-live-update`, `publish-surface`, `publish-alert`, `publish-building`, `publish-listening`, `publish-error`, `publish-reply`, `publish-native-listening`, `publish-native-reply`, `crash-content`, `cancel`, `tap`
and `reset`.

### v2 acceptance cases (notification id 2)

These coexist with the id-1 cases, so two activities can be compared side by side.

| Case | Payload | What to verify |
|---|---|---|
| `publish-v2-memo` | Mic icon in A, timer in B, `tap: open`, 2 h `timeoutS`, `basic` card with Stop | Capsule tap opens the fixture; long press expands; `get` shows `source: custom`, `timeoutKind: explicit` |
| `publish-v2-highlight` | High-importance channel, `alert`, `cardPosition: below`, `highlight` card with "A123", `accentColor`, 40 actions, `tapAction` | No stock heads-up while unlocked; the card opens below the cutout for ≤ 5 s; one scrolling chip row; a body tap increments `outsideTaps` via `tapAction`; the A icon has a 60 % ring |
| `publish-live-update` | Plain promoted `ProgressStyle` notification with `shortCriticalText` "12 min" and `setTimeoutAfter` 2 h, no Taku extras | Island without allow-listing; B shows "12 min"; `progress` card at 40 %; `source: live-update` |

Then, with an id-1 case and an id-2 case active:
- **Swipe left** selects the other activity (`state.presentation.selected`).
- The 32 dp waiting circle left of the capsule shows the next activity's A glyph (`secondaryKey`);
  tapping it brings that activity forward.
- **Swipe right** hides the selected one (`dismissed`).
- An open card hides every capsule.
- Opening the fixture app hides its capsules (`suppressedByForeground`), and going home restores
  them.
- Compare each gesture with `cmd statusbar island next|tap KEY|hide KEY`.

The `publish-native-*` cases now demonstrate rejection. The `assistant` template belongs to Taku
Agent, so expect `reserved_template` in `diagnostics` and no island. Use the Agent's
`NativeIslandProbe` for the native assistant card. The activity buttons use the same operations. `query` returns `outsideTaps`,
`activeNotifications` (read from NotificationManager), and `lastOperation` in its `json` column.
Unknown operations throw `IllegalArgumentException`; none touch another package's notifications.

For manual expansion, record the returned notification key and use
`cmd statusbar island expand KEY`. Tap the activity's **Tap outside card** button: its counter must
increase while the card remains open. Check capsule tap, Back, IME input, another application
underneath, shade and rotation separately. `publish-alert` tests the five-second automatic alert,
so its natural timeout must not be mistaken for an outside-tap collapse.

### Alert replay regression (issue #30)

Keep the fixture app in the background. All of these operations update its same notification
ID 1. After `publish-alert` visibly expands, complete the next two calls **within five seconds**:

```sh
adb -s SERIAL shell cmd statusbar island collapse
adb -s SERIAL shell content call --uri content://org.agentos.island.fixture --method publish
adb -s SERIAL shell cmd statusbar island state
```

The final `presentation.expanded` must remain `false` while the summary content updates. Repeat
with a GUI upward swipe instead of `collapse`, and with `publish-alert` instead of `publish`
(a held `true` update). A `publish` → `publish-alert` flip during the 30-second cooldown must
also stay collapsed. After 30 seconds from the original alert, a fresh `publish` → `publish-alert`
must expand again. Do not cancel between the first alert and its quiet update: that would miss
the original same-window regression. Also verify that quiet updates to an already-open automatic
card refresh its content without extending its five-second timeout, and that an explicitly
expanded card stays open after that timeout.

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

`publish-building`, `publish-listening`, `publish-error` and `publish-reply` change only group B's
label, for checking pill text and ellipsizing. `publish-native-listening` and `publish-native-reply`
are rejected (`reserved_template`); the native assistant card is covered by the Agent's
`NativeIslandProbe`.

For focused-editor regression checks, start `.Fixture --ez editorOnly true` to hide the button
list and keep its real EditText on screen. This fixture is also rotation-capable, unlike Home.
