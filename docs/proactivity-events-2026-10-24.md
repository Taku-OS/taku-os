# Taku proactivity event catalog and first-release implementation order

The first Taku proactivity release uses events and registrable observers, with policies maintained jointly by users and AI. It first detects opportunities to help through window changes, content changes and pauses in interaction, reusing on-device Magpie, existing screenshot suggestions, Surfaces and TaskBroker. Policy registration, context selection and the background flow using original images come first; new sources such as notifications follow. Double-pressing the power button proceeds as a separate ROM change.

The code baseline is `codex/power-screen-assist-20261023`, HEAD `81524acaba06356964cbad5017e18c99c59194da`, including `f3180b27b88840fcf0c605a11fd2b7242ec855b4`. The review below also checked the uncommitted Assistant dev68 background-task and Ask User implementation in the working tree. Code and sources were checked on **2026-10-24**. This is a snapshot of branch capabilities, not a statement about main, the version currently installed on the phone or the latest ROM.

The [trigger and policy maintenance design](proactivity-trigger-design.md) adds original-message links for S01–S06 from the Lark “Taku All Staff” group, community maintenance principles, the independent proactivity app and public extension contract, AI edits and rollback, selection of the latest N records needed by a policy, and current code gaps. The 89 events and their sources below are retained; implementation order has been updated to follow that design.

## 0.1 development progress

An [independent proactivity app](../system/proactivity/README.md), [public API and SDK](../system/proactivity/API.md), [request schema](../spec/proactivity-v1.schema.json) and [independent page observer](../examples/proactivity-observer/README.md) have been added. A plugin can register changes in its own pages, wait for user authorization, coalesce changes after they settle, select the latest N metadata records and publish real system hints. User tuning, disabling, version history, independent APK upgrades and policy rollback are supported. See [acceptance evidence and UI](reviews/proactivity-2026-10-24/README.md).

The original 89 rows below remain a **catalog snapshot of Android sources and baseline capabilities**, preserving each source and its conditions. Version 0.1 has not connected every system source to the new host. The example proves only the public integration path from W05-like changes in its own pages to D03-like settling rules; it does not prove global Memory events, Jev processing of original images or Agent execution. The next first-release priorities are independently authorized real event sources and image/model/task adapters, followed by AI policy maintenance across plugins and merging user overrides. U02 still requires a separate ROM change.

## Product boundaries and status labels

- The automatic path is original screenshot → Jev judges the image directly → when help is useful, the same captured image goes to the image model → lightweight hint → background Agent after user acceptance. There is no OCR, screenshot-to-text conversion or Assist-text fallback.
- An explicit user request to inspect the screen skips Jev. A single power-button press retains system behavior; a long press retains the existing input entry. Double-pressing power is not implemented yet.
- No Agent execution task is created before the user taps. After acceptance, the user's foreground app, main conversation and draft remain intact; necessary follow-up questions use the bottom component. dev68 currently supports only 1–3 options; free text and general approvals still need completion.
- An event indicates only that something changed. Capturing a screenshot, calling a model and displaying a suggestion are separate decisions. A deadline, network recovery or user unlock does not itself authorize execution.
- Memory recording and analysis are independent of Jev scheduling for original images. Accessibility event type, source package, window and time may be scheduling metadata. Notification bodies, clipboard contents and Memory bodies cannot replace the original image in this flow.

| Label | Meaning |
| --- | --- |
| Existing | Code or local handling exists in the specified module; this does not mean integration into the original-image proactive flow or physical-device acceptance |
| Existing dev68 | Uncommitted background implementation in this working tree; emulator integration evidence exists, but there is no acceptance result on a physical device running the same version |
| Direct integration | Public capabilities can be connected at the existing app, service or Broker layer without changing Android framework; implementation and validation are still required |
| Direct integration, authorization required | No framework change is needed, but the relevant user authorization, role or source permission is required |
| Adapter required | A particular SDK, app, connector, hardware component or owned IME must cooperate; this does not automatically mean a ROM change |
| ROM required | The current target involves system key policy or a system bridge and requires integration in the relevant framework or SystemUI component |
| Native / derived / integrated / internal | A raw system signal, a composite rule, an external source or a Taku-owned lifecycle event, respectively |

Priorities: **P0** covers the first-release automatic original-image flow and necessary invalidation/feedback handling; **P1** covers incremental entry points and sources; **P2** covers additional scenarios. **Not adopted** means the source cannot serve as a reliable trigger on current Android. Priority does not change the authorized scope.

“ROM required” here means a key-policy or system-bridge change. Including accepted APKs, Broker and permission configuration in an official system image is separate release-integration work. “Direct integration” does not mean the current physical device already has the relevant permissions or that an upgrade has made them persistently effective.

## Current implementation and code entry points

| Module | Existing entry points | Remaining proactivity gaps |
| --- | --- | --- |
| Independent proactivity 0.1 | [Host and SDK](../system/proactivity/README.md), [independent plugin](../examples/proactivity-observer/README.md); persistent authorization and policies, user tuning, latest N metadata records, settling rules and real Surfaces receipts | Community integration loop implemented for events from a plugin's own pages; global sources, original-image Jev, authorized AI edits across plugins and a public Agent handoff are still missing |
| Memory observation | [Service configuration][code-memory-config] subscribes to `typeWindowStateChanged`, `typeWindowContentChanged` and `typeViewScrolled`; [ObservationService][code-memory-observer] waits for 900ms of stability and checks source, main display, unlock and interactive state | Still depends on `MemoryStore.observing()` and page text; after `takeScreenshotOfWindow` succeeds, it immediately closes the image buffer without passing the original image to Jev |
| Memory analysis | [MemoryStore][code-memory-store], [AnalysisJob][code-memory-job] and [module documentation][memory-doc] provide an independent switch, approximately 20-second coalescing, at most 5 records per batch, at most one batch per 15 seconds, 60 batches per hour, backoff and leases | These queues and budgets are for Memory text analysis. Original-image Jev scheduling needs independent configuration; bodies must not substitute for images |
| Original-image suggestions | `onHandleScreenshot`, `showManualSuggestion` and `JevHintView` in [AssistantSession][code-session]; `analyzeScreen/analyzeJev` in [AgentRepository][code-repository]; [ImageScreenSuggestion][code-image] | Manual and image-bearing Jev branches exist; suggestions before acceptance remain inside the assistant overlay. Production automatic observation, independent scheduling, automatic hint publication and acceptance callbacks are not yet connected |
| Image capability and authorization | [MagpieDecisions][code-decisions] checks catalog `imageInput`; [SystemAi][code-ai] and [system AI documentation][ai-doc] manage models, app authorizations and revisions | Real Jev image capability must be verified on the phone. The old error still recommends page text; replace that with an explicit rejection, without adding a fallback |
| Surfaces | [SurfaceService][code-surface-service] handles `ACTION_SCREEN_OFF/USER_PRESENT`, lock checks, configuration changes, publication ACKs, interaction and expiry; [SurfaceWindow][code-surface-window] handles layout and keyboard avoidance | The presentation layer has no proactivity model or scheduler; a layout callback is not a phone-wide business event |
| Background tasks dev68 | [BackgroundScreenTasks][code-background] waits for a publication ACK before creating a new thread and submitting a task, and listens for bottom choices; [NativeBridge][code-native] handles real pending request/respond | Only `phone/askUser` and `phone/action/requestApproval` are connected; other questions, command/file/plugin approvals and free text are not fully covered |
| TaskBroker | [TaskBroker][code-broker] and [Agent documentation][agent-doc] provide the single persistent queue, separate execution display, cancellation and terminal states; a broker restart does not automatically replay operations that were running | Proactive scheduling only submits and tracks tasks; it must not create another execution queue. Recovery and state validation need strengthening |
| Input method and voice | [Taku IME][ime-doc] retracts suggestions on input, selection changes and keyboard dismissal; [ComposerToolbar][code-voice] provides callbacks for user-initiated voice input | IME signals are not yet delivered to proactive scheduling. Voice depends on an available local recognition service. IME-specific screenshot authorization does not automatically authorize general Jev observation |
| Policy registration and context | Memory, Jev and system AI each have local settings and versions; [MemoryStore][code-memory-store] has internal browse, while public Agent queries remain search/get | No general TriggerRegistry, EventJournal, ContextSelector or AI policy tools exist yet. A Memory analysis batch is not the latest N events for a policy; see the [trigger design](proactivity-trigger-design.md) |

## Event catalog

The catalog covers 89 events in 12 categories, including opportunity sources, observation gates, invalidation and execution feedback. It is not an exhaustive list of every Android API event. Each row links to the raw event or derivation method. The current-status column describes actual code and integration conditions on this branch. P0/P1/P2 are first-release engineering choices, not priorities assigned by the source documentation.

### Explicit user entry points

| ID and event | Source documentation and entry point | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| U01 System assistant invocation and screen-inspection request (native) | [VoiceInteractionSession][android-assist]: `onShow/onHandleScreenshot` | **Existing**. AssistantSession directly uses the screenshot supplied by the system and fails if it is missing. These requests skip Jev | P0 |
| U02 Double power-button press (native system policy) | [AOSP PhoneWindowManager][aosp-power]: `powerPress/powerMultiPressAction` | **ROM required**. The repository has no key policy routing a double press to screen inspection; the development receiver does not implement a physical double press | P1 |
| U03 Quick Settings tile tap (native) | [TileService][android-tile]: `onClick` | **Direct integration**. Add a tile service and a user flow for adding it; a tap does not itself grant background screenshot capability | P1 |
| U04 Sharing an image, link or file with the assistant (native) | [Receiving shares from other apps][android-share]: `ACTION_SEND/ACTION_SEND_MULTIPLE` | **Direct integration**. The current assistant manifest has no share receiver; URI grants, input types and ownership need handling | P1 |
| U05 User-initiated voice input (native) | [RecognitionListener][android-speech]: `onResults` | **Partially existing**. ComposerToolbar has recognition callbacks and draft insertion; it rejects when the local service is unavailable. This is not continuous ambient listening | P1 |
| U06 NFC tag scan (native) | [NfcAdapter][android-nfc]: `ACTION_NDEF_DISCOVERED/ACTION_TECH_DISCOVERED` | **Adapter required**. No NFC scenario entry exists; hardware, tag dispatch and NFC permission are required. Do not use `ACTION_TAG_DISCOVERED`, deprecated in API 37, for a new implementation | P2 |
| U07 User screenshot (locally native) | [Android screenshot detection][android-screenshot-detection]: Activity screenshot callback | **Direct integration in owned Activities**; global observation of screenshots in other apps requires a **SystemUI or ROM bridge**. The ordinary callback does not return the image | P2 |

### App and window changes

| ID and event | Source documentation and entry point | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| W01 App entering or leaving the foreground (native record) | [UsageEvents.Event][android-usage]: `ACTIVITY_RESUMED/PAUSED/STOPPED`; [AccessibilityEvent][android-a11y]: window state | **Partially existing**. Memory checks source packages from window events. UsageStats is not subscribed; querying its records also requires usage access and cannot be treated as a realtime callback | P0; use existing window sources first |
| W02 Window added or removed (native) | [AccessibilityEvent][android-a11y]: `TYPE_WINDOWS_CHANGED`, `WINDOWS_CHANGE_ADDED/REMOVED` | **Direct integration**. Interactive-window reading exists, but Memory does not currently subscribe to `typeWindowsChanged`; add the subscription and establish window identity | P0 |
| W03 Active or focused window changes (native) | [AccessibilityEvent][android-a11y]: `WINDOWS_CHANGE_ACTIVE/FOCUSED` | **Direct integration**. Memory checks the active window during reads; dedicated change dispatch is still missing | P0 |
| W04 Pane appears, disappears or changes title (native) | [AccessibilityEvent][android-a11y]: `CONTENT_CHANGE_TYPE_PANE_*` | **Existing at coarse granularity**. `typeWindowStateChanged` is subscribed, without classification by pane subtype; it can be refined directly | P0 |
| W05 Page content or subtree changes (native) | [AccessibilityEvent][android-a11y]: `TYPE_WINDOW_CONTENT_CHANGED`, `CONTENT_CHANGE_TYPE_SUBTREE` | **Existing in Memory**. Text is currently saved after debouncing; an independent original-image output is required, without reusing `page().text` | P0 |
| W06 Visible text or state description changes (native) | [AccessibilityEvent][android-a11y]: `CONTENT_CHANGE_TYPE_TEXT/STATE_DESCRIPTION` | **Existing at coarse granularity**. Type and time can serve as change signals; event text must not be fed upstream of the screenshot model | P0 |
| W07 Window bounds, layer or picture-in-picture changes (native) | [AccessibilityEvent][android-a11y]: `WINDOWS_CHANGE_BOUNDS/LAYER/PIP` | **Direct integration**. Requires the W02 subscription and window versions to invalidate old images and wait for new layout | P0 |
| W08 Rotation, size, language or display configuration changes (native) | [Configuration][android-configuration]; [Activity][android-activity]: `onConfigurationChanged` | **Existing in the presentation layer**. Surfaces relayouts, but this is not yet an invalidation event for original-image suggestions; it can be connected directly | P0 |
| W09 Display added, removed or properties changed (native) | [DisplayListener][android-display]: `onDisplayAdded/Removed/Changed` | **Direct integration**. TaskBroker manages separate displays but has no general display listener; first ensure isolation between the main and execution displays | P0; state isolation |
| W10 Keyboard shown or hidden (locally native) | [InputMethodService][android-ime]: `onWindowShown/Hidden`; [AccessibilityWindowInfo][android-window]: `TYPE_INPUT_METHOD` | **Partially existing**. Surfaces avoids its own keyboard; the IME retracts on input completion. A busy/idle signal across components needs an adapter for the owned IME or differences in authorized interactive-window reads | P0; defer first |
| W11 Fold, unfold or device posture changes (integrated) | [WindowManager foldable documentation][android-fold]: `WindowLayoutInfo/FoldingFeature` | **Adapter required**. Depends on supported hardware and the app window; not an entry point for the first Pixel 9 release | P2 |

### Raw user interaction signals

| ID and event | Source documentation and entry point | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| I01 Control tap (native) | [AccessibilityEvent][android-a11y]: `TYPE_VIEW_CLICKED` | **Direct integration**. Memory does not subscribe yet. Use only to help wait for the interaction result to settle; a tap does not prove business success | P1 |
| I02 Long press or context click (native) | [AccessibilityEvent][android-a11y]: `TYPE_VIEW_LONG_CLICKED/CONTEXT_CLICKED` | **Direct integration**. Add the subscription; do not infer that the user wants the assistant to take over | P1 |
| I03 Page scroll (native) | [AccessibilityEvent][android-a11y]: `TYPE_VIEW_SCROLLED` | **Existing in Memory**. Currently shares 900ms debouncing with other events; connect it to independent original-image scheduling | P0 |
| I04 Input-field text changes (native) | [AccessibilityEvent][android-a11y]: `TYPE_VIEW_TEXT_CHANGED` | **Direct integration**. Memory does not subscribe to this type; send event metadata only. The owned IME already retracts on input, so adapting that signal can come first | P0; defer or retract |
| I05 Cursor or text selection changes (native) | [AccessibilityEvent][android-a11y]: `TYPE_VIEW_TEXT_SELECTION_CHANGED` | **Locally existing in the IME**. Not connected across modules; do not store selected text or keystroke traces | P0; defer or retract |
| I06 Control focus or item selection changes (native) | [AccessibilityEvent][android-a11y]: `TYPE_VIEW_FOCUSED/SELECTED` | **Direct integration**. Add the subscription to identify editing phases; it cannot replace a business-completion receipt | P1 |
| I07 Drag start, drop or cancellation (native) | [AccessibilityEvent][android-a11y]: `CONTENT_CHANGE_TYPE_DRAG_*` | **Direct integration, dependent on correct events from the app**. No dedicated handling exists; cover it through content changes first | P2 |

### Events derived from pauses and changes

| ID and event | Source documentation and derivation | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| D01 Scrolling stops (derived) | [Screenpipe capture design][screenpipe-events]: Scroll stop | **Approximate rule exists**. Memory's shared 900ms debounce coalesces scrolls but has no separate scroll state; combine it with I03 | P0 |
| D02 Typing pauses (derived) | [Screenpipe capture design][screenpipe-events]: Typing pause; [Taku IME documentation][ime-doc] | **IME adapter or available I04 metadata required**. Input currently retracts the suggestion; an arbitrary typing pause does not retrigger original-image Jev | P0 |
| D03 Screen settles after a switch (derived) | [Screenpipe capture design][screenpipe-events]: App switch settle | **Event debouncing exists as a foundation**. New settling rules must also check window, source and capture versions, without reusing text-equality checks | P0 |
| D04 New or duplicate frame (derived) | [Screenpipe capture design][screenpipe-events]: Frame dedup; [existing IME boundaries][ime-doc] | **Local deduplication or caching foundations exist; general scheduling is not connected**. Memory deduplicates text. Original images need pixel deduplication; a small changed region cannot directly be classified as no business change | P0 |
| D05 Dwell time on one page or app reaches a threshold (derived) | [ActivityWatch data model][activitywatch-model]: `currentwindow`, duration, heartbeat | **Rule can be implemented directly**. No continuous-dwell model exists. Dwell does not prove reading is complete; it is not a strong independent trigger for the first release | P1 |
| D06 Long inactivity or resumed interaction (derived) | [ActivityWatch data model][activitywatch-model]: `afkstatus` | **Phone signal adapter required**. No accessibility events does not mean no user interaction. Do not enable touch exploration, which changes interaction, for this purpose. If system listening is used for precise global raw input, assess ROM permissions and implementation separately | P2 |

### Notification changes

| ID and event | Source documentation and entry point | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| N01 Notification posted (native) | [NotificationListenerService][android-notifications]: `onNotificationPosted` | **Direct integration, authorization required**. No NotificationListenerService currently exists; add the service and obtain notification access | P1 |
| N02 Existing notification updated (native plus comparison) | [NotificationListenerService][android-notifications]: `onNotificationPosted` | **Direct integration, authorization required**. Coalesce by notification key, version or time; not every callback is a new message | P1 |
| N03 Notification removed (native) | [NotificationListenerService][android-notifications]: `onNotificationRemoved`, reason | **Direct integration, authorization required**. This is not a universal read receipt; distinguish user dismissal, app cancellation and expiry | P1 |
| N04 Notification ranking, importance or channel changes (native) | [NotificationListenerService][android-notifications]: `onNotificationRankingUpdate/onNotificationChannelModified` | **Direct integration, authorization required**. Use to adjust priorities, usually without a screenshot; do not treat the system's own hints as new external opportunities | P1 |

### Time and calendar windows

| ID and event | Source documentation and entry point | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| T01 Reminder or deadline arrives for an established matter (derived and scheduled) | [Schedule alarms][android-alarms]: AlarmManager | **Direct integration**. No general reminder layer exists. Exact alarms have separate use-case and authorization restrictions and must not drive frequent screenshot polling | P1 |
| T02 Calendar event starts, ends or enters its preparation window (derived) | [Calendar Provider][android-calendar]: Instances `BEGIN/END` | **Direct integration, authorization required**. Read authorized calendar events, then schedule owned timers; there is no universal meeting-start broadcast | P2 |
| T03 Time, time zone or date changes (native) | [Intent][android-intent]: `ACTION_TIME_CHANGED/TIMEZONE_CHANGED/DATE_CHANGED` | **Direct integration**. Proactive scheduling does not currently recalculate. Use a monotonic clock for cooldowns and anchor matter dates to source time and time zone | P0; temporal correctness |
| T04 Fallback check time arrives (derived and scheduled) | [Screenpipe capture design][screenpipe-events]: Idle fallback; [PeriodicWorkRequest][android-periodic] | **Long-interval maintenance exists; short-interval observation is not connected**. MemoryMaintenance's 15-minute job does not capture screenshots. Candidate checks every 30–60 seconds can only be evaluated within a legitimately running observation service; periodic WorkManager cannot guarantee them | P1 |
| T05 Cooldown, budget, backoff or lease expires (internal) | [Memory queue documentation][memory-doc]: coalescing, rate limits, retries and leases | **Existing in Memory**. Original-image Jev needs its own budget and expiry handling, reusing mechanisms only; revalidate context when the interval expires | P0 |

### Local file and data changes

| ID and event | Source documentation and entry point | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| F01 File created, write completed, moved or deleted (native) | [FileObserver][android-files]: `CREATE/CLOSE_WRITE/MOVED_*/DELETE` | **Direct integration on accessible paths**. Does not grant access to other apps' private files; no user-directory observer currently exists | P2 |
| F02 Download completed (native) | [DownloadManager][android-download]: `ACTION_DOWNLOAD_COMPLETE` | **Direct integration for downloads the caller may query**. Check ownership and the real result; this is not observation of all downloads across the phone | P2 |
| F03 Media library changes (native plus query) | [ContentObserver][android-content]: `onChange`; [media access documentation][android-media-files] | **Direct integration, authorization required**. A new image does not directly mean a user screenshot; distinguish this from U07 | P2 |
| F04 Local calendar records added, removed or changed (native plus query) | [ContentObserver][android-content]; [Calendar Provider][android-calendar] | **Direct integration, authorization required**. Requires read permission, provider notifications and queries for differences. Memory viewing calendar pages does not mean integration with the calendar database | P2 |
| F05 Clipboard changes (locally native) | [OnPrimaryClipChangedListener][android-clipboard]; [Android 10 clipboard restrictions][android-clipboard-limits] | **Adapter required within the permitted foreground or default-IME scope**. No scheduling integration exists. Do not assume global access from system-assistant identity or substitute clipboard contents for a screenshot | P2 |

### Location and peripheral scenarios

| ID and event | Source documentation and entry point | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| L01 Entering a geographic area (integrated) | Android GPS provider | [Location arrival events](../system/environment-signals/arrival/README.md) persist a one-shot arrival event for a registered caller. Callers handle any presentation. | P2 |
| L02 Leaving a geographic area (integrated) | [Geofencing][android-geofence]: EXIT | **Adapter required**. Exit may invalidate location-related opportunities. | P2 |
| L03 Remaining within an area (integrated) | [Geofencing][android-geofence]: DWELL, loitering delay | **Adapter required**. A dwell event models remaining inside an area for a configured period. | P2 |
| L04 Location updated or movement exceeds a threshold (native plus derived) | [LocationListener][android-location]: `onLocationChanged` | **Direct integration, authorization required**. Android location interfaces need not depend on GMS, but available providers, background restrictions and power consumption require real testing; the scheduler defines thresholds | P2 |
| L05 Transition between stationary, walking, running, cycling or riding in a vehicle (integrated) | [ActivityRecognitionClient][google-activity]: Transition API | **Adapter required**. Depends on Play services, ACTIVITY_RECOGNITION permission and classification results; no such source currently exists | P2 |
| L06 Significant motion, stationary detection or step count changes (native) | [Sensor][android-sensors]: `TYPE_SIGNIFICANT_MOTION/STATIONARY_DETECT/STEP_*` | **Direct integration, hardware and relevant permissions required**. No sensor-observation module exists; start with capability discovery | P2 |
| L07 Proximity, light or orientation changes (native plus derived) | [Sensor][android-sensors]: `TYPE_PROXIMITY/LIGHT/ROTATION_VECTOR` | **Direct integration, hardware required**. Meanings such as being in a pocket or picked up require inference; they are not certain user intent | P2 |
| L08 Bluetooth device connects or disconnects (native) | [BluetoothDevice][android-bluetooth]: `ACTION_ACL_CONNECTED/DISCONNECTED` | **Direct integration, authorization required**. No observer exists; appropriate permissions such as BLUETOOTH_CONNECT are required. Connecting to a vehicle device does not directly prove driving | P2 |
| L09 Audio device added or removed (native) | [AudioDeviceCallback][android-audio-devices]: `onAudioDevicesAdded/Removed` | **Direct integration**. Use for output mode and interruption cost; do not infer that the user has put on headphones | P2 |
| L10 USB device or accessory attached or removed (native) | [UsbManager][android-usb]: `ACTION_USB_DEVICE_* / ACTION_USB_ACCESSORY_*` | **Direct integration; reading the device requires separate authorization**. No corresponding scenario consumer exists | P2 |

### User state and observation gates

| ID and event | Source documentation and entry point | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| G01 Entering or leaving interactive state (native) | [UsageEvents.Event][android-usage]: `SCREEN_INTERACTIVE/NON_INTERACTIVE`; [Intent][android-intent]: screen broadcasts | **Partially existing**. Memory checks `isInteractive`; Surfaces handles `ACTION_SCREEN_OFF`. The proactive capture queue still needs immediate invalidation. Always-on display is not interactive state | P0 |
| G02 Keyguard shown or hidden (native) | [UsageEvents.Event][android-usage]: `KEYGUARD_SHOWN/HIDDEN`; [Intent][android-intent]: `ACTION_USER_PRESENT` | **Partially existing**. Memory checks Keyguard and Surfaces hides while locked. Scheduling entry points must recheck current state, not rely only on an old broadcast | P0 |
| G03 User's encrypted storage unlocks (native) | [Intent][android-intent]: `ACTION_USER_UNLOCKED` | **State checks exist**. Memory jobs check `isUserUnlocked`; a recovery subscription can be added directly. This is not the same as every screen unlock | P0; recovery |
| G04 Work profile becomes available or paused (native) | [Intent][android-intent]: `ACTION_MANAGED_PROFILE_AVAILABLE/UNAVAILABLE` | **User/profile boundary adaptation required**. Several existing runtime paths hardcode user 0; existing isolation across profiles cannot be claimed | P2 |
| G05 Do Not Disturb interruption filter changes (native) | [NotificationManager][android-notification-manager]: `getCurrentInterruptionFilter`, `ACTION_INTERRUPTION_FILTER_CHANGED`; [notification listener][android-notifications] | **Gate state can be read directly; continuous listening requires authorization**. Surfaces does not currently check DND. Since Android 10, this broadcast is sent only to registered receivers with Do Not Disturb access | P0 state read; P1 listening |
| G06 Cellular phone ringing, in-call or disconnected (native) | [TelephonyCallback.CallStateListener][android-calls] | **Direct integration, authorization required**. No phone-state source exists; requires READ_PHONE_STATE and per-subscription handling, and does not cover all VoIP calls | P1 |
| G07 Media playback, pause, stop or session end (integrated) | [MediaController.Callback][android-media-callback]; [MediaSessionManager][android-media-sessions] | **Adapter or authorized session access required**. Cross-app access requires applicable capabilities such as notification listening or MEDIA_CONTENT_CONTROL; this is not a universal meeting state | P2 |
| G08 Screenshot failure due to a secure window, missing permission, invalid window or similar condition (native) | [AccessibilityService][android-accessibility-service]: `ERROR_TAKE_SCREENSHOT_*`; [VoiceInteractionSession][android-assist] | **Rejection paths exist**. Memory does not persist protected-window contents; manual suggestions fail without an image. New scheduling must record stable error codes and stop, without falling back to text | P0 |
| G09 AppOp access mode changes (native) | [OnOpChangedListener][android-appops]: `onOpChanged` | **Direct integration within the scope permitted for listening**. Not a general revocation broadcast for every permission; authorization must still be rechecked for every capture, model call and accepted result | P1; P0 recheck every time |

### Network and resource state

| ID and event | Source documentation and entry point | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| R01 Network becomes available or is lost (native) | [NetworkCallback][android-network]: `onAvailable/onLost` | **Direct integration**. Relevant modules have no network callbacks; Memory already has request-failure backoff. An available network is not necessarily validated internet access | P1 |
| R02 Network capabilities, metering or blocked access changes (native) | [NetworkCallback][android-network]: `onCapabilitiesChanged/onBlockedStatusChanged` | **Direct integration**. Adjust budgets using actual capabilities; do not infer safety or location from a Wi-Fi name | P1 |
| R03 Power connected/disconnected or battery level changes (native) | [Battery monitoring][android-battery]: power broadcasts and battery state | **Direct integration**. No proactive-scheduling consumer exists. Limit frequency; do not capture on every battery change | P1 |
| R04 Battery saver or Doze state changes (native) | [PowerManager][android-power]: `ACTION_POWER_SAVE_MODE_CHANGED/ACTION_DEVICE_IDLE_MODE_CHANGED` | **Direct integration**. Use to reduce frequency or pause. Doze is a system power-saving state, not evidence that the user is available | P1 |
| R05 Thermal status changes (native) | [PowerManager][android-power]: `addThermalStatusListener` | **Direct integration**. Pause or reduce screenshot/inference load; not currently connected | P1 |
| R06 Memory trimming requested (native, version-limited) | [ComponentCallbacks2][android-memory-pressure]: `onTrimMemory` | **Not adopted as a reliable global trigger**. Newer Android versions no longer deliver some memory-pressure levels; available callbacks can still release the component's own caches | P2 |
| R07 Storage low, recovered or write failed (native or internal) | [Intent][android-intent]: `ACTION_DEVICE_STORAGE_LOW/OK`; [task persistence documentation][agent-doc] | **Write-failure handling exists; legacy broadcasts are restricted**. Since API 26, a manifest receiver cannot be relied on for legacy low-storage broadcasts. First handle the component's own write failures correctly | P0 failure handling; P2 global monitoring |

### Cloud and business integration events

These sources are independent of screen observation. Their structured business data must not be presented as evidence that “this original image has been inspected,” and screen suggestions do not automatically obtain the relevant account authorization.

| ID and event | Source documentation and entry point | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| C01 Mailbox or label changes (integrated) | [Gmail Push][gmail-push]: `watch/historyId/history.list` | **Connector adapter required**. No proactive mailbox source exists; read authorized differences after notification | P2 |
| C02 Cloud calendar event collection changes (integrated) | [Calendar Push][calendar-push]: `watch` | **Connector adapter required**. Push does not include the complete changed content; separate queries, deduplication and subscription renewal are required | P2 |
| C03 Issue, PR, comment, review or CI changes (integrated) | [GitHub webhook catalog][github-webhooks] | **Connector adapter required**. Repository subscriptions, accounts and task associations require independent authorization | P2 |
| C04 Weather alert issued, updated, cancelled or expired (integrated plus difference detection) | [QWeather realtime alerts][qweather-alerts]: `messageType`, validity times, replacement relationships | **Service adapter required**. A query API is not push; location, credentials and change detection are needed. Do not notify on every result | P2 |
| C05 Navigation starts or reaches a waypoint/destination (integrated) | [AMapNaviListener][amap-events]: `onStartNavi/onArrivedWayPoint/onArriveDestination` | **Navigation SDK adapter required**. Operating Amap through the GUI does not mean the system can listen to SDK callbacks from the third-party Amap app | P2 |
| C06 Traffic updates or route recalculation (integrated) | [AMapNaviListener][amap-events]: `onTrafficStatusUpdate`, route-recalculation callbacks | **Navigation SDK adapter required**. Same as C05; separate from first-release GUI route acceptance | P2 |
| C07 Order, payment, refund or subscription state changes (integrated) | [Stripe Event types][stripe-events]: Checkout, Payment, Refund and related events | **Business-service adapter required**. This is only an example of verifiable merchant events, not a phone-wide order interface or a Meituan personal reservation API | P2 |
| C08 Browser navigation completed, failed or history state changed (integrated) | [Chrome webNavigation][chrome-navigation]: `onCompleted/onErrorOccurred/onHistoryStateUpdated` | **Supported-browser adapter required**. Desktop extension APIs cannot be directly applied to Android Chrome; an owned WebView needs a separate adapter | P2 |

### Taku feedback, execution and recovery

| ID and event | Source documentation and entry point | Current status and integration conditions | Priority |
| --- | --- | --- | --- |
| B01 Background progress publication ACK (internal) | [Surfaces API][surfaces-doc]: `published`; [BackgroundScreenTasks][code-background] | **Existing dev68**. After suggestion acceptance, publish background progress first; create the thread and submit the task only after the ACK. The ACK means the component accepted publication, not that the user saw it. Pre-acceptance publication for automatic candidates is not yet connected | P0 |
| B02 User accepts a hint (internal) | [AssistantSession][code-session]: acceptance callback from `JevHintView.result`; [Surfaces API][surfaces-doc]: reusable `ActionEvent/accept` | **Existing dev68 in the assistant overlay**. Execution continues in the background after the tap. The Surfaces acceptance callback for automatic hints is missing; repeated taps, versions and original-image ownership need validation | P0 |
| B03 User dismisses a hint (internal) | [Surfaces API][surfaces-doc]: `dismissed` | **Existing**. Retracting presentation does not cancel a task. Proactive scheduling needs matter deduplication or cooldown, not a permanent negative preference | P0 |
| B04 Hint expires, is withdrawn or removed (internal) | [Surfaces API][surfaces-doc]: `expired/withdrawn/removed` | **Presentation protocol exists**. Scheduler candidate cleanup is still needed; not all removal reasons are user rejection | P0 |
| B05 User answers a bottom choice (internal) | [Surfaces API][surfaces-doc]: choice, ActionEvent; [PhoneAskUser][code-ask-user] | **Existing dev68**. Real native pending/respond with 1–3 options; no free text, and not all other general request types are connected | P0 |
| B06 Task completed, failed, cancelled or interrupted (internal) | [Agent documentation][agent-doc]: `turn/completed/interrupt` and TaskBroker terminal states | **Existing**. Tasks owned by dev68 use the bottom component. Check the corresponding task, turn and status; the mere presence of completedTurn is insufficient | P0 |
| B07 Memory added, corrected, deleted or analysis completed (internal) | [Memory documentation][memory-doc]: storage, versions and queues | **Existing**. Continues as an independent Memory pipeline; no unified matter deduplication spans Memory and original-image hints | P0; prevent duplicate handoffs |
| B08 AI master switch, app permission or model configuration changes (internal) | [Taku system AI][ai-doc]; [Memory documentation][memory-doc] | **Revisions and invalidation exist**. New automatic scheduling must still recheck Jev settings, image authorization and candidate generations; do not add credential copies | P0 |
| B09 Presentation or execution service disconnects, reconnects or binding dies (native and internal) | [ServiceConnection][android-service-connection]; [Surfaces lifecycle][surfaces-doc] | **Foundation exists; recovery is incomplete**. dev68 restores only saved running tasks. Unsubmitted captures are not replayed; startup phases, completed results and pending-request boundaries need validation | P0 |
| B10 System boots or a component upgrades (native) | [Intent][android-intent]: `ACTION_BOOT_COMPLETED/MY_PACKAGE_REPLACED`; [task recovery documentation][agent-doc] | **Some persistent jobs and Assistant onReady recovery exist**. No general event bus exists. Revalidate permissions, versions and pending work after an upgrade; do not repeat unconfirmed external operations | P0; bounded recovery |

## First-release implementation order

### P0: policy maintenance and the original-image proactive flow first

The table below defines delivery order across modules. See the [trigger design](proactivity-trigger-design.md) for interfaces, editing flows, context selection and individual completion criteria.

| Order | Work | Main events | Completion criteria |
| --- | --- | --- | --- |
| 1 | Independent proactivity app and public contracts for events, triggers, observers and context bundles | Initial source-to-catalog mapping, B08 | Host and external extensions build and install independently, integrating through the public protocol or SDK without modifying Memory/Assistant or rebuilding the ROM; the 89 items are not a closed enum |
| 2 | Add policy registration, user overrides, AI editing interfaces and version maintenance | Source filtering and feedback B03/B08 | Query, replay, enable/disable, edit and rollback are available; stale concurrent patches are rejected; policy changes invalidate old candidates and model results |
| 3 | Decouple observation switches; establish a short-lived metadata journal and latest-N selector | W01–W07, I03–I05, D01–D03, G01–G03 | Memory and original-image observation have independent authorization; records are selected by anchor, scope, type, time window and budget; no text-body substitution for images or query expansion through internal batches |
| 4 | Implement an independent ProactiveScheduler, rules and external APK observers | D01–D04, T03, T05, G05, B07–B08 | Official examples and community extensions share the contract; debouncing, coalescing, single concurrency, budgets and fair scheduling are shared; window changes, locking or setting changes invalidate old results |
| 5 | Verify automatic original-image sources and connect Jev/image suggestions with ContextBundle | W05, I03, G08, U01 | Existing `takeScreenshotOfWindow` is a candidate pending validation; follow the [target device's recorded limits on background self-triggering][screen-doc]; missing images or protection rejection prevent model calls, and the same capture/context spans judgment and suggestion |
| 6 | Connect automatic Surfaces hints and acceptance callbacks; stabilize dev68 background questions | W08–W10, B01–B06, B09 | No execution task before the tap; foreground, main conversation and draft remain intact afterward; repeated taps do not submit twice; complete free text and general approvals; unknown requests remain waiting |
| 7 | Validate the community development loop, policy maintenance and recovery, then staged physical-device acceptance on the same version | B03–B10, G08 | External authors can independently develop, upgrade and roll back; users can understand, change and revoke policies; verify each task's corresponding turn and terminal state; restarts do not replay external actions |

P0 original-image sources and event dispatch have integration paths at the app layer; they must not all be classified as ROM engineering in advance. If physical-device platform restrictions block the chosen path, record the missing capability explicitly before deciding on necessary system changes. An available event source does not imply screenshot permission or a Jev declaration of image support.

### P1: incremental entry points and recovery conditions

1. Connect N01–N04 notification metadata to initiate candidate checks. Do not open WeChat in the background or substitute notification bodies for original images. Cover duplicate updates, historical notifications on first connection and listener disconnection first.
2. Connect R01–R05, G06 and G09; adjust queues for network, resources and busy/idle state. Retries must not create duplicate Agent tasks.
3. Add U03 tiles, U04 sharing and T01 established matters. They reuse the same scheduling/execution flow, without separate task queues.
4. Implement U02 framework routing for double power-button presses separately and coordinate ROM integration. Verify its relationship with existing single-press, long-press, camera and other behaviors. A double press uses the explicit-user-request path and skips Jev.
5. Add an in-host dynamic script runtime, template migration and an authorized-record selector. External APK code extensions and the independent development loop are already P0; script hot loading is not a prerequisite for community integration.

### P2: add sources for actual scenarios

For location, motion, peripherals, files, databases, cloud and browser events, establish capability discovery and source authorization before adding consumers. Android system-app identity does not mean GMS, cross-profile, notification or business-account access is already granted. SDK integration in C05/C06 differs from “the Agent operates the Amap app” and is not a prerequisite for the first-release route case.

## First-release frequency and scheduling parameters

These are initial values for replay and limited real use, stored as editable policy defaults and global budgets. See the [trigger design](proactivity-trigger-design.md) for user overrides, template upgrades and rollback rules. They still need tuning against missed opportunities, unwanted interruptions, latency, power use and request volume. Existing Memory parameters remain in place and are not overwritten by the new values.

| Item | Initial value or rule | Relationship to the existing implementation |
| --- | --- | --- |
| Window and scroll settling | 1–2 seconds; start testing at 1.5 seconds | Memory currently uses 900ms; the new original-image policy has independent settings |
| Jev judgment interval and budget | At least 15 seconds apart, at most 60 per hour, single concurrency | Counted separately from Memory's 60 batches per hour; Magpie currently has only a single-concurrency guard, without this persistent original-image budget |
| Queue coalescing | Coalesce the same observation target, retaining its latest valid image; do not mix evidence across targets | After expiry, discard or wait for a new valid event; an old screenshot cannot serve as realtime context |
| Image-based generation | Generate only after Jev accepts; explicit screen-inspection requests generate directly | Reuse the existing System AI image role, app authorization and revisions |
| Hint cadence | At least 5 minutes globally; no repeat for the same matter without new evidence; an additional cooldown can start at 30 minutes | Distinct from the existing Memory hint interval and the original-image suggestion's 2-minute TTL |
| Fallback observation | In P1, try candidate checks every 30–60 seconds, requiring the screen on, authorized sources and a legitimately running service | Not periodic WorkManager work; do not wake a screen-off device for continuous screenshots |
| Explicit requests and follow-up questions within a task | Independent of proactive-hint cooldown; still recheck permissions, freshness and request ownership | Cooldown must not block Ask User for a task the user has already accepted |

Methodology: event debouncing/deduplication draws on [Screenpipe][screenpipe-events], and coalescing identical states on [ActivityWatch][activitywatch-model]. Hint timing draws on [Attelia][attelia] ([author's paper][attelia-paper], §IV–V) and [principles of mixed-initiative interaction][mixed-initiative]. Evaluate whether help is needed separately from whether a task succeeds, following [ProactiveAgent][proactive-agent] and [PARE][pare]. These projects support the choice of methods; they do not prove that the intervals above are universally optimal for phones. The Screenpipe reference is a historical design document whose page says current code is authoritative. Only its event classification and methods are used here; this does not claim its design parameters or every capability have shipped. Its OCR and text-capture flow are not part of Taku's original-image flow.

## First-release acceptance and evidence boundaries

| Layer | Existing evidence scope | Additional acceptance required |
| --- | --- | --- |
| Host | Handoff records show an Assistant APK build and 26 passing host checks | Scheduling replays compare fixed intervals, event settling and events plus cooldown; cover deduplication, budgets, source changes and expired results |
| Emulator dev68 | Synthetic dinner-planning foreground → real image model → foreground retained after acceptance → real bottom options → native response returned | The [existing UI test][code-background-test] treats completedTurn presence or a new pending request as a continuation signal without checking status. Add the matching turn and subsequent behavior, then test automatic triggers, free text and recovery |
| Pixel 9 Assistant 66 | A real chat screenshot produced a relevant suggestion; acceptance created a real Agent task and follow-up question, but still entered the main chat | dev68 background execution and bottom questions need physical-device validation on the same version; then validate automatic original-image Jev sources, recording the two separately |
| Cross-app | The new background flow has not yet reached Amap and the Meituan confirmation page; older manual continuation is separate evidence | Use manual screen inspection as a control, then validate automatic Jev → lightweight hint → background Agent → real Amap route → Meituan reservation confirmation page, retaining continuous evidence for the same task |
| ROM and cold start | No passing U02 double-press routing record exists in the current working tree | Device and main coordinators arrange ROM integration and cold-start validation with matching versions; component success must not be expanded into whole-image acceptance |

WeChat dinner-planning acceptance reads existing context only and does not have the phone account send more messages. Missing city, starting point or exact time is resolved through authorized Memory or real bottom follow-up questions; relative dates are interpreted using the message or captured source time. The endpoint is the reservation confirmation page, without submitting an order, reservation or payment. Private screenshots, video, credentials and device state remain in ignored `out/` and are not embedded in documentation.

Report scheduling quality with unwanted interruptions per active hour, missed useful opportunities, hint latency, Jev/image request counts, power use, hint acceptance rate and task success after acceptance. First label authorized UI sequences with “appropriate hint windows” and “do not interrupt,” then replay different policies. A missing tap does not directly mean help was unnecessary; dwell does not mean reading is complete; notification removal does not mean read; a tap does not mean business success.

Historical note: the validation table in the [screen-suggestion record][screen-doc] stops at an earlier checkpoint, including Assistant 65, 13 host checks and the older statement that emulator testing had not run. The [Magpie README][magpie-doc] still describes an Assist-text fallback. Future updates to those files should align with this branch snapshot while retaining historical version and device scope; dev68 must not be described as already on a physical device or in the latest ROM.

[android-assist]: https://developer.android.com/reference/android/service/voice/VoiceInteractionSession
[aosp-power]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/PhoneWindowManager.java
[android-tile]: https://developer.android.com/reference/android/service/quicksettings/TileService
[android-share]: https://developer.android.com/training/sharing/receive
[android-speech]: https://developer.android.com/reference/android/speech/RecognitionListener
[android-nfc]: https://developer.android.com/reference/android/nfc/NfcAdapter
[android-screenshot-detection]: https://developer.android.com/about/versions/14/features/screenshot-detection
[android-usage]: https://developer.android.com/reference/android/app/usage/UsageEvents.Event
[android-a11y]: https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent
[android-configuration]: https://developer.android.com/reference/android/content/res/Configuration
[android-activity]: https://developer.android.com/reference/android/app/Activity
[android-display]: https://developer.android.com/reference/android/hardware/display/DisplayManager.DisplayListener
[android-ime]: https://developer.android.com/reference/android/inputmethodservice/InputMethodService
[android-window]: https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo
[android-fold]: https://developer.android.com/codelabs/android-window-manager-dual-screen-foldables
[screenpipe-events]: https://github.com/screenpipe/screenpipe/blob/main/docs/EVENT_DRIVEN_CAPTURE_SPEC.md
[activitywatch-model]: https://docs.activitywatch.net/en/latest/buckets-and-events.html
[android-notifications]: https://developer.android.com/reference/android/service/notification/NotificationListenerService
[android-notification-manager]: https://developer.android.com/reference/android/app/NotificationManager
[android-alarms]: https://developer.android.com/develop/background-work/services/alarms
[android-calendar]: https://developer.android.com/identity/providers/calendar-provider
[android-intent]: https://developer.android.com/reference/android/content/Intent
[android-periodic]: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
[android-files]: https://developer.android.com/reference/android/os/FileObserver
[android-download]: https://developer.android.com/reference/android/app/DownloadManager
[android-content]: https://developer.android.com/reference/android/database/ContentObserver
[android-media-files]: https://developer.android.com/training/data-storage/shared/media
[android-clipboard]: https://developer.android.com/reference/android/content/ClipboardManager.OnPrimaryClipChangedListener
[android-clipboard-limits]: https://developer.android.com/about/versions/10/privacy/changes#clipboard-data
[android-geofence]: https://developer.android.com/develop/sensors-and-location/location/geofencing
[android-location]: https://developer.android.com/reference/android/location/LocationListener
[google-activity]: https://developers.google.com/android/reference/com/google/android/gms/location/ActivityRecognitionClient
[android-sensors]: https://developer.android.com/reference/android/hardware/Sensor
[android-bluetooth]: https://developer.android.com/reference/android/bluetooth/BluetoothDevice
[android-audio-devices]: https://developer.android.com/reference/android/media/AudioDeviceCallback
[android-usb]: https://developer.android.com/reference/android/hardware/usb/UsbManager
[android-calls]: https://developer.android.com/reference/android/telephony/TelephonyCallback.CallStateListener
[android-media-callback]: https://developer.android.com/reference/android/media/session/MediaController.Callback
[android-media-sessions]: https://developer.android.com/reference/android/media/session/MediaSessionManager
[android-accessibility-service]: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
[android-appops]: https://developer.android.com/reference/android/app/AppOpsManager.OnOpChangedListener
[android-network]: https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
[android-battery]: https://developer.android.com/training/monitoring-device-state/battery-monitoring
[android-power]: https://developer.android.com/reference/android/os/PowerManager
[android-memory-pressure]: https://developer.android.com/reference/android/content/ComponentCallbacks2
[android-service-connection]: https://developer.android.com/reference/android/content/ServiceConnection
[gmail-push]: https://developers.google.com/workspace/gmail/api/guides/push
[calendar-push]: https://developers.google.com/workspace/calendar/api/guides/push
[github-webhooks]: https://docs.github.com/en/webhooks/webhook-events-and-payloads
[qweather-alerts]: https://dev.qweather.com/docs/api/warning/weather-alert/
[amap-events]: https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/AMapNaviListener.html
[stripe-events]: https://docs.stripe.com/api/events/types
[chrome-navigation]: https://developer.chrome.com/docs/extensions/reference/api/webNavigation
[attelia]: https://ieeexplore.ieee.org/document/7146515/
[attelia-paper]: https://www.julian-ramos.com/papers/Okoshi2015.pdf
[mixed-initiative]: https://erichorvitz.com/chi99horvitz.pdf
[proactive-agent]: https://github.com/thunlp/ProactiveAgent
[pare]: https://github.com/deepakn97/pare
[memory-doc]: ../system/memory/README.md
[ai-doc]: ../system/ai/README.md
[ime-doc]: ../system/inputmethod/README.md
[surfaces-doc]: ../system/surfaces/API.md
[agent-doc]: agent-module.md
[screen-doc]: screen-suggestions-2026-10-23.md
[magpie-doc]: ../adapters/magpie-runtime/README.md
[code-memory-config]: ../system/memory/res/xml/observation.xml
[code-memory-observer]: ../system/memory/src/org/agentos/memory/ObservationService.java
[code-memory-store]: ../system/memory/src/org/agentos/memory/MemoryStore.java
[code-memory-job]: ../system/memory/src/org/agentos/memory/AnalysisJob.java
[code-session]: ../system/assistant/src/org/agentos/assistant/AssistantSession.java
[code-repository]: ../system/assistant/src/org/agentos/assistant/AgentRepository.java
[code-image]: ../system/assistant/src/org/agentos/assistant/ImageScreenSuggestion.java
[code-decisions]: ../system/assistant/broker/org/agentos/lab/MagpieDecisions.java
[code-ai]: ../system/assistant/broker/org/agentos/lab/SystemAi.kt
[code-surface-service]: ../system/surfaces/src/org/agentos/surfaces/SurfaceService.java
[code-surface-window]: ../system/surfaces/src/org/agentos/surfaces/SurfaceWindow.java
[code-background]: ../system/assistant/src/org/agentos/assistant/BackgroundScreenTasks.java
[code-native]: ../system/assistant/broker/org/agentos/lab/NativeBridge.java
[code-broker]: ../system/assistant/broker/org/agentos/lab/TaskBroker.java
[code-voice]: ../system/assistant/src/org/agentos/assistant/ComposerToolbar.java
[code-ask-user]: ../system/assistant/broker/org/agentos/lab/PhoneAskUser.java
[code-background-test]: ../system/assistant/tests/org/agentos/tests/BackgroundScreenUiTest.java
