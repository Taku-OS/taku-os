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

The implementation does not yet cover every GUI setting and state. The
requirement applies to new features. Built-in skills do not overwrite an existing directory when
installed, so updating preinstalled documentation still needs a migration path.
See [current status](current-status.md) for what has been verified.

## Device-local trust boundary and remaining risks

The open interfaces intentionally do not distinguish platform-signed Agents,
shell callers, and other installed device-local applications. This follows the
engineering contract, not Android's usual separation of unrelated app data.
They are **not safe to describe as restricted to trusted apps**. In particular:

| Interface | Read/write surface available to any device-local caller |
| --- | --- |
| `org.agentos.audio.open-access` / `settings` | Reads masked credential/configuration state; can replace/remove the fal key, choose available models and change audio/dictation policy using the same validation as the GUI. It cannot read the full saved key. A caller can disrupt voice or select cloud processing using a credential it supplies; future audio may then go to that configured cloud account. |
| `org.agentos.agent.home-feed` | Reads cards and event history, including form `values`, and invokes the same card management/actions as the GUI. Event values are not anonymized. |
| `org.agentos.agent.home-layout` | Reads rendered widget text and invokes enabled widget controls; the receiving widget's PendingIntent may have real effects. |
| `org.agentos.agent.home-assistant` and `AssistantOverlayActivity` | Opens UI, starts/stops voice, reads transcripts and manages the same session as the GUI. Gesture IDs order pointer events; they are not credentials. Recording continues to depend on Audio Engine availability and Android microphone policy. |
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

`content://com.android.systemui.island` and
`content://com.android.launcher3.taku.recents` are intentionally exported Agent interfaces.
They clear Binder identity to use their owning system service, then apply the same validation
and authoritative state as the GUI. This includes `setEnabled`, `setAllowed`, notification
summaries/actions and recent-task switching/removal. The package allow-list governs custom
notification rendering, not authorization to change phone settings. An installed local app
can change it; `POST_ISLAND` is not a security boundary against that operation.

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
