# Agent open access

This design follows the requirement in [AGENTS.md](../AGENTS.md). On the device,
`content://org.agentos.assistant.open-access` forwards to the native RPC or the
Skills manager the GUI uses. `content://org.agentos.memory.open-access` reaches
the same Memory store and management operations. Arguments are JSON passed in
`content call --arg`, and the returned Bundle's `result` is JSON with an `ok`
field. The full commands ship with the assistant in the
[skill documentation](../system/assistant/assets/skills/taku-open-access/SKILL.md).
These interfaces let device-local callers read and change personal data. That
is a deliberate product choice.

There is no persistent Case data model or cost ledger, so Case lists and
monetary totals cannot be returned from an authoritative GUI source. Token usage
is whatever the native App Server and Memory actually return, and a missing cost
is not zero. Unimplemented features and upstream errors are returned as they are.

New features must expose the same commands and state as the GUI. The installer
refreshes an existing `taku-open-access` skill when its contents match the
last verified official digest. It preserves local edits and files without a
trusted baseline. The installed skill details report why it kept a file.
See [current status](current-status.md) for what has been verified.

## Caller trust boundary and App Permission Center

Agent and developer CLI callers (`UID 0` root, `UID 1000` system, `UID 2000` ADB
shell, and platform-signed first-party Taku components) pass through directly to
these interfaces. Ordinary third-party Android applications (`UID >= 10000`
without the platform signing certificate) are governed by the central **App
Permission Center** (`content://org.agentos.assistant.permissions`, persisted at
`/data/local/agentos/ai/app-permissions.json`, UI in `Taku AI -> App
Permissions`):

- **`deny` (default)**: rejects all ungranted third-party app access across
  `memory.read`, `memory.write`, `taku.control`, and `taku.root` (including the
  `\0taku-godmode` and `\0taku-agent-native` local sockets).
- **`manual`**: requires explicit user toggles in the App Permissions UI per
  `(packageName, certSha256)`; CLI callers cannot escalate out of `manual` mode
  or modify per-app grants (`MANUAL_MODE_LOCKED`).
- **`auto`**: evaluates non-root requests (`memory.read`, `taku.control`) via
  deterministic package checks and bounded model review cached per APK
  fingerprint; `memory.write` and `taku.root` remain manual-only.
- **`yolo`**: grants `memory.read`, `memory.write`, and `taku.control` to
  installed apps, while `taku.root` stays gated behind an independent opt-in
  `allowRootInYolo` toggle. Global settings mutations (`Memory` `configure`,
  `SystemAi` `configure`, `Audio` `fal` credential replacement) remain restricted
  to the trusted system/Agent/GUI plane in every mode.

When authorized under an active mode or grant, the following surfaces are exposed:

| Interface | Read/write surface available to any device-local caller |
| --- | --- |
| `org.agentos.audio.open-access` / `settings` | Reads masked credential and configuration state. Can select the Live Talk provider and replace or remove its Gemini key or the fal key shared with translation and cloud dictation. Can choose translation models and change audio and dictation policy with the same validation as the GUI. Only the Audio Engine can retrieve the full keys. A caller can disrupt these features or change which provider or account handles future audio. |
| `org.agentos.agent.home-feed` | Reads cards and event history, including form `values`, and invokes the same card management/actions as the GUI. Event values are not anonymized. |
| `org.agentos.agent.home-layout` | Reads rendered widget text and invokes enabled widget controls; the receiving widget's PendingIntent may have real effects. |
| `org.agentos.agent.home-assistant` and `AssistantOverlayActivity` | Opens UI, starts/stops voice, reads transcripts and manages the same session as the GUI. Gesture IDs order pointer events; they are not credentials. Recording continues to depend on Audio Engine availability and Android microphone policy. |
| `org.agentos.agent.home-onboarding` | Reads first-run progress (`Settings.Secure` `taku_onboarding_step`), AI availability and whether a name is set; performs the card's own steps (set up, save or skip the name, saving the name to Memory) and can reset onboarding to the intro. The per-user `taku_onboarding_test_mode=1` switch replays intro/name even with existing AI/name, preserving accounts, keys and layout; Continue still requires real AI. It cannot mark onboarding completed: only confirmed available AI (ChatGPT, or Magpie with a model) does. |
| `org.agentos.assistant.open-access` / `account/login/start`, `account/login/cancel` | Starts or cancels ChatGPT sign-in and returns the official sign-in URL or device code the account page shows; `open:true` also opens the browser, as Home's ChatGPT chip does. May switch the assistant from Magpie to ChatGPT mode first. |
| `org.agentos.assistant.open-access` / `jev/status`, `jev/enable`, `jev/connect` | Reads Jev's switch and decision model; `jev/enable` switches Jev on with a Jev model Magpie already serves; `jev/connect` adds a TypeSafe or OpenRouter key as a Magpie provider, binds its Jev model and switches Jev on — the same operations as the onboarding Jev chip. |
| `org.agentos.assistant.open-access` / `dining/poll/send-*`, `dining/poll/send` | Requires `taku.root`. Uses the same candidate reply and WhatsApp poll journal as the native share UI. The journal contains the group, question, options and task identity. Preparing or sending requires the active execution task bound to that intent. |
| `org.agentos.assistant.open-access` / `dining/poll/state`, `dining/poll/watch`, booking decision and receipt methods | Requires `taku.root`. Reads the exact sent poll's watch and the booking offer, form, task and verified receipt. The native watch supplies actual vote counts. Booking actions require the current offer key and the same native validation as the GUI. |
| `org.agentos.assistant.open-access` / `dining/poll/calendar-*`, `dining/poll/ride-*` | Requires `taku.root`. Reads the Calendar and ride continuations for a verified reservation. The same offer keys and native forms govern GUI and Agent actions. The ride task hands its existing Uber screen to the user for the final decision. |
| `org.agentos.assistant.open-access` / `account/status` | Reads whether ChatGPT is signed in, whether AI is available (ChatGPT or Magpie), the plan type and the account phase. The sign-in URL and one-time code are never included. |
| `org.taku.widgets.data` and `WidgetActionActivity` | Reads media/alarm metadata, invokes media transport and launches the corresponding calendar/alarm/music UI. |
| `org.taku.weather.data` | Reads cached city/weather, changes location and requests forecast/geocoding. The shared request limits bound ordinary repeated traffic, not malicious process restarts. |

`Binder.clearCallingIdentity()` makes provider work run in its component's identity;
it does not establish trust in the original caller. The audio broker's engine UID
check authenticates the engine-to-broker transport, not the app using the open
settings facade. Likewise, the Assistant launcher's signature check protects that
launcher path only. Neither should be claimed as authorization for these public APIs.
Platform signing grants requested signature permissions; Clock still uses its own
app UID rather than sharing the platform UID.

These are concrete privacy, configuration-integrity and side-effect risks if an
untrusted app is installed. Removing those risks by allowing only signed callers
would change the product access contract, and is not performed in PR #57's security
follow-up. Documenting the choice does not resolve a security check that requires
caller isolation; acceptance must be reconciled with the repository's security policy.
The release package list currently includes Clock/Weather under
`PRODUCT_PACKAGES_DEBUG`; that packaging fact is not an access-control guarantee
for installed APKs or other build variants.

## SystemUI, recent tasks, and assistant capture

`content://com.android.systemui.island` and `content://com.android.launcher3.taku.recents`
are exported Agent interfaces. Both authorize the original Binder caller before passing its
request to the owning system service, which applies the same validation as the GUI.

- Recent tasks are restricted to root, system, ADB shell and platform-signed callers.
  Other callers receive a `SecurityException`.
- Trusted callers have direct Island access. Ordinary apps can query publication eligibility for
  their own package and need an App Permission Center `taku.control` grant to read or operate
  activities, and then only those their own UID published (another app's activity returns
  `not_found`; no cross-app actions or `RemoteInput` replies; `collapse` only folds their own
  expanded card). Only trusted callers can use `next` or change settings or the publisher
  allow-list. Denied
  Island operations return `permission_denied`. The publisher allow-list governs notification
  rendering separately.

Use `content call --uri content://com.android.systemui.island --method state` (Bundle `json`),
`--method setEnabled --arg 0`, `--method setAllowed --arg PACKAGE_A,PACKAGE_B`,
and the full [preinstalled island command reference](../system/assistant/assets/skills/taku-island/SKILL.md).
The recent-task provider returns Bundle `result` and offers `list`, `show`, `home`,
`open` and `remove`. Pass a task ID from `list` in `--arg` for `open` or `remove`.
`setAllowed` replaces the whole list; preserve existing entries when adding a package. Unsupported task IDs and malformed
arguments fail instead of being treated as a successful GUI operation.

`content://org.agentos.agent.home-assistant` exposes voice and `system/capture` on the same
basis. Capture invokes Android's selected assistant, and the native gateway accepts the
returned image only with a live `JevTestTrigger` token (`CAPTURE_EXPIRED` otherwise).
This invocation is not caller isolation and must not be described as such. Voice requires
an awake, unlocked device (`DEVICE_LOCKED`), the Audio Engine's enabled preference and microphone
permission. Screen-off cancels voice; a listening surface hidden behind another app releases capture,
while rotation and the Keep talking handoff retain it. No caller-UID restriction is added for Agents.

The legacy `CodexHomeActivity` remains an explicit advanced-panel entry, not a HOME or
ASSIST candidate. Frontend launchers validate the destination certificate. `taku_home_prop`
is a public HOME-package selector, so its SELinux read access is deliberately shared;
this does not grant property writes. `WITH_ADB_INSECURE` appears only in the emulator product
makefile, never the physical-device product.

Assistant `jev/*` and `account/login/*` methods (including reads and future methods)
require `taku.root` from ordinary apps before argument parsing. `taku.control` alone
returns `PERMISSION_DENIED`; local agent/root/system/shell and the signed gateway
retain access. `account/login/start` and `jev/connect` also retain their trusted-caller
credential restriction. `account/status` remains a redacted `taku.control` read.
