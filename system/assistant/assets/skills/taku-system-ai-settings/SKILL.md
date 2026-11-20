---
name: taku-system-ai-settings
description: Inspect and update Taku AI model routing and Taku Audio voice settings through their shared Android owners.
---

# Taku AI model settings

The Expo `/system-ai` page and Android content calls use the same backend settings owners.
Direct sections are `/system-ai?section=connection`, `account`, `usage`, `jev`, and `routing` (hand-edited model groups and scene bindings).
The background workspace entry opens `/workspaces`.

```sh
content call --uri content://org.agentos.assistant.open-access --method readSystemAi --arg '{}'
content call --uri content://org.agentos.assistant.open-access --method manageSystemAi --arg '{"operation":"setApp","app":"ime","enabled":false}'
content call --uri content://org.agentos.assistant.open-access --method manageSystemAi --arg '{"operation":"readConnection"}'
content call --uri content://org.agentos.assistant.open-access --method manageSystemAi --arg '{"operation":"selectConnection","mode":"openai","model":""}'
content call --uri content://org.agentos.assistant.open-access --method manageSystemAi --arg '{"operation":"readAccount"}'
content call --uri content://org.agentos.assistant.open-access --method manageSystemAi --arg '{"operation":"readLimits"}'
content call --uri content://org.agentos.assistant.open-access --method manageSystemAi --arg '{"operation":"readJev"}'
content call --uri content://org.agentos.assistant.open-access --method manageSystemAi --arg '{"operation":"setJev","enabled":false}'
content call --uri content://org.agentos.assistant.open-access --method manageSystemAi --arg '{"operation":"openFeature","feature":"audio"}'
```

`readSystemAi` returns `enabled`, `connected`, `assistantMode`, `assistantModel`,
`jevEnabled`, `models`, `groups`, `bindings`, `providers`, `apps`, `usage`, and
`audioAvailable`. `apps` contains `{id,name,enabled}` for each registered consumer.
`providers` contains `{name,configured}` without credentials. `usage` contains
`{consumer,model,at,elapsedMs,status,code}` for retained system AI calls.
`at` is epoch milliseconds. Requests, responses, images, and credentials are absent.
`audioAvailable` reports whether the Audio Engine service resolves, not whether
every voice model is ready.

`manageSystemAi` accepts exactly the fields for one operation.

| Operation | Required fields besides `operation` | Result |
| --- | --- | --- |
| `saveGroup` | `id` (`[A-Za-z0-9_-]{1,64}`), `name`, `members` (1–32 model ids in routing order), boolean `create` | `{group}`. `create:true` adds a group with `routing:"order"` and fails with `GROUP_EXISTS` if the id is taken; `create:false` replaces the name and members of an existing group or fails with `GROUP_NOT_FOUND`. Member ids are stored as typed, without a catalog check. Bad input returns `INVALID_GROUP` or `INVALID_MEMBERS`. |
| `bindScene` | `scene` (`[A-Za-z0-9_.-]{1,128}`), `group` id | Binds any scene id to an existing group. A built-in scene behaves exactly like `bindSystemAi` and returns its undo receipt; any other scene returns `{scene,binding}` without a compatibility check. |
| `unbindScene` | `scene` | `{scene}`. Removes an added scene's binding. A built-in scene returns `INVALID_SCENE` and an unbound one `SCENE_NOT_FOUND`. |
| `setApp` | `app` from `readSystemAi.apps[].id`, boolean `enabled` | `{app,enabled}`. Uses the system AI broker's existing app policy. |
| `readConnection` | None. | `{mode,model,models,running,message,version}` from the current broker. Reconciles composer options with the selected connection. |
| `selectConnection` | `mode`, `model` | The selected connection. `openai` requires an empty model. `magpie` requires an ID from `readConnection.models`. The broker rejects switching while tasks run or remain queued. |
| `readAccount` | None. | `{connected,type,planType,requiresOpenaiAuth}`. A service failure returns an error, not a disconnected account. |
| `readLimits` | None. | `limits` and `usage` arrays of `{key,label,value}`. Each array projects supported limit or usage fields. An unavailable request has `limitsError` or `usageError` instead. |
| `readJev` | None. | The existing Jev catalog with `models`, `selected`, and `enabled`. Runs the existing legacy credential and model migration before reading. |
| `setJev` | Boolean `enabled` | `{enabled}`. Enabling requires the selected scene model to remain in the decision catalog. |
| `testJev` | None. | `{connected:true}` after the existing synthetic text test completes. This consumes model quota and is not idempotent. It captures no screen. |
| `openFeature` | `feature` equal to `audio`, `ime`, or `memory` | Opens the owning app's existing settings entry. Returns an empty object after Android accepts the launch. |
| `setAssistScreenshots` | Boolean `enabled` | Updates Android `Settings.Secure` key `assist_screenshot_enabled` for the current user. `readSystemAi` returns `assistScreenshotsEnabled`. |

The `readAccount` projection excludes email addresses, tokens, authorization URLs, and
device codes. A pending sign-in's device code and official authorization page are
read through `accounts/read` in the installed open-access guide. Limits include returned percentage use, window duration, reset time,
credit state, token counts, and supported cost fields. `resetsAt` uses epoch seconds.
Absent upstream fields stay absent. An empty array means that upstream returned no
supported metrics. It does not mean zero usage or unlimited quota. The installed
open-access guide also documents the existing `account/usage/read` and
`account/rateLimits/read` methods for their full runtime responses.

Unknown fields, wrong JSON types, missing values, and control characters fail
before a mutation. Strings are at most 512 characters. Errors include
`INVALID_ARGUMENT`, `INVALID_CONNECTION`, `APP_NOT_FOUND`, `INVALID_FEATURE`,
`FEATURE_UNAVAILABLE`, and `UNSUPPORTED_OPERATION`, plus the owning service's
validation errors. Public calls also retain the runtime's user and unlock checks.
After a failed or interrupted write, read the current state before retrying.
Setting the same app or Jev value converges to the same preference. Opening a
feature or testing a connection performs the action on each call.

Model routing resides in the single `Settings.Global` value
`taku_system_ai_config`. The legacy `taku_magpie_groups`,
`taku_magpie_scene_bindings`, `taku_magpie_providers`, and `taku_magpie_models`
keys are migration sources and are removed after the unified value is published.

```sh
settings get global taku_system_ai_config
```

`groups` is an array of `{id,name,members,routing}`. `scenes` maps scene IDs to
`group/<id>`. Other fields include `providers`, `models`, `version`, `updated_at`,
and `updated_by`. Common scenes are `assistant`, `jev`, `memory.text`, `ime.vision`,
`screen`, and app-specific scenes. `group/taku-memory-jev` is the independent
Memory group. Select IDs from the actual catalog. Missing models remain visible
as unavailable and are not silently replaced.

Magpie management owns provider credentials and routing configuration. The Expo
page opens that existing browser flow. ChatGPT sign-in and OpenRouter keys are
handled on the same Expo page, under `/system-ai?section=account`, through the
`accounts/*` methods in the installed open-access guide. `manageSystemAi` does not
accept secrets.
Do not place credentials in Global Settings. A direct `settings put global
taku_system_ai_config 'JSON'` replaces the entire object and requires Android
Settings permission. Preserve unrelated fields and avoid concurrent writes.
The Settings provider does not offer compare-and-swap.

## Chat appearance

Taku AI settings → **Chat appearance** chooses the chat assistant's background: **Dark** (the
default, Figma 300:661: 92 % black over a moderate blur of what lies behind the chat) or **Light**
(a 24 % white tint over a stronger blur, dark text). It applies to the chat, its Taku Home page and
the window behind them, at once and for the current Android user. The GUI and Agents share one
value, `Settings.Secure` `taku_chat_appearance`:

```sh
settings get secure taku_chat_appearance          # dark | light; null (never set) reads as dark
settings put secure taku_chat_appearance light    # the open chat switches at once
settings put secure taku_chat_appearance dark
settings delete secure taku_chat_appearance       # back to the default, dark
```

Any other value reads as `dark`. The settings page writes the same key; if Android refuses the
write it says so and keeps the previous appearance. Where the device cannot blur (battery saver, no
GPU blur) the window shows an opaque fallback instead: `#121212` for Dark, `#E3E3E3` for Light.


## Taku Audio settings

Open `org.agentos.audio/.AudioHomeActivity` for the Taku Audio app. It owns the
voice switch, fal Key, default fal model, local reading preferences, and links
to video translation, call translation, and audio status. Opening it starts no
recording or inference. OpenRouter keys and global AI remain in Taku AI.

Use the same root settings owner as the Audio GUI through standard device-local
content calls. No assistant permission or private socket access is needed.

The provider is exported without a caller permission. Any installed device-local
app can use these settings commands, including replacing or removing the fal Key
and changing voice policy. This follows TakuOS's Agent access contract. Public
reads return only masked key status; the runtime credential channel stays private.

```sh
content call --uri content://org.agentos.audio.open-access --method settings --arg '{"action":"read"}'
content call --uri content://org.agentos.audio.open-access --method settings --arg '{"action":"setAudioEnabled","enabled":false}'
content call --uri content://org.agentos.audio.open-access --method settings --arg '{"action":"configureReading","language":"en-US","voiceId":"en-US-local-0","speed":1.0}'
content call --uri content://org.agentos.audio.open-access --method settings --arg '{"action":"resetReading","language":"en-US"}'
content call --uri content://org.agentos.audio.open-access --method settings --arg '{"action":"configureDictation","scope":"assistant-entry","primaryProfileId":"zipformer","fallbackProfileId":null,"language":"system"}'
content call --uri content://org.agentos.audio.open-access --method settings --arg '{"action":"resetDictation","scope":"assistant-entry"}'
content call --uri content://org.agentos.audio.open-access --method settings --arg '{"action":"removeFalKey"}'
```

Each `arg` is a JSON object with exactly the fields for its action.

| Action | Required fields besides `action` |
| --- | --- |
| `read` | None. |
| `saveFalKey` | `key`, one string containing a complete fal API Key. |
| `removeFalKey` | None. |
| `selectFalModel` | `model`, a string ID from the latest returned `fal.models`. |
| `setAudioEnabled` | `enabled`, a JSON boolean. |
| `configureReading` | `language`, `voiceId`, and `speed`. |
| `resetReading` | `language`. |
| `configureDictation` | `scope:"assistant-entry"`, `primaryProfileId`, `fallbackProfileId`, and `language`. |
| `resetDictation` | `scope:"assistant-entry"`. |

The shared root validator accepts `zh-CN` and `en-US`. It accepts `voiceId` as
the language followed by `-local-` and one to three digits. The GUI offers its
narrower shipped VoiceCatalog choices. Use a voice available on that page.
`speed` is a finite JSON number from 0.5 through 2.0. Reset removes only the
selected language's override. Missing fields, extra fields, unknown actions,
wrong JSON types, invalid voices, and invalid values fail before any write.
The API cannot select arbitrary operations, providers, files, or runtime sessions.

`Bundle.result` contains `{ok:true,result:Snapshot}` on success. The snapshot has
`enabled`, `audioEnabled`, `systemRevision`, `audioRevision`, `reading`, and `fal`.
`reading` maps language IDs to `{voiceId,speed}`. `fal` contains `configured`,
`masked`, `model`, `models` with `{id,name}` entries, `realtimeAvailable:false`, and
`error`, which is null, `KEY_UNREADABLE`, or `MODEL_UNREADABLE`. `masked` is null
when no readable key exists. A configured key is saved and readable, not validated
by fal. Reading or saving settings makes no provider validation call and does not
require OpenRouter or a model catalog refresh. Key replacement and removal retain
the selected model. Model selection requires a readable fal Key.

`dictation.assistant-entry` contains the saved desktop recognition preference and
its Engine-derived status. `primaryProfileId` is `zipformer` or `fal-whisper`.
`fallbackProfileId` must be present and is null or the other profile. `language`
is `system`, `zh-CN`, or `en-US`. A cloud primary or fallback requires an existing
readable fal Key. These commands make no online authentication or inference call.
Defaults are local Zipformer, system language, and no fallback. Reset removes
only this scope and preserves keys, reading and translation settings.

The view also returns `resolvedLanguage`, `isDefault`, `error`, and `backends`.
Backend rows report static availability, `unavailableReasons`, supported
languages, `processing`, `maxCaptureMs`, and `drainTimeoutMs`. A missing scope
uses defaults without saving or advancing the revision. Malformed settings
report `SETTINGS_UNREADABLE` instead of pretending to be defaults. An unsupported
system language reports `UNSUPPORTED_LANGUAGE`; the Taku Audio app locale does
not choose the recognition language.

The desktop hold gesture, desktop microphone, and Live Talk use this scope.
The full-chat microphone reads the saved language from this scope and uses local Zipformer.
An active recording keeps its selected settings. The next recording uses the
latest preference. Static fallback may select the saved alternate once before
capture for missing local models, unsupported backend language, or an absent
fal credential. It never bypasses voice policy, permissions or resource use,
and never replays a failed recording. A cloud fallback can upload audio even
when the primary is local. Read the actual session `profileId` and `processing`.

Whisper dictation sends audio to fal temporary storage and source transcription.
During capture it transcribes successive audio ranges and publishes replaceable
text previews. Stopping capture runs one full-recording transcription that
replaces the preview, including an empty final result. This uses fal Whisper's
file interface, so preview updates depend on completed provider requests.
Preview audio is processed once and the final request processes it again.
It does not translate, play speech, or require a configured text model. Its
capture limit is 60000 ms and finalization limit is 100000 ms. Local dictation
keeps its 5000 ms Engine finalization bound. These are resource limits, not
recognition latency or quality claims. The translation model picker remains
independent of the desktop recognition preference.

`audio.session.open` and `audio.capabilities` accept
`{"settingsScope":"assistant-entry"}` for DICTATION. Omitted purpose means
DICTATION. The scope cannot be combined with another purpose, explicit
`language`, or `profileId`. An unscoped request keeps its existing defaults.
Scoped open results, status and events expose `settingsRevision`,
`requestedProfileId`, actual `profileId`, `language`, `processing`, `fallbackUsed`,
`fallbackReason`, and the capture and drain limits. `drainDeadlineMonotonicMs`
uses the device's elapsed-realtime clock. Global capability defaults retain
their existing meaning; use the scoped backend rows or opened session facts.

`enabled` is the global system AI state and is read-only here. `audioEnabled` is
the voice policy switch backed by `taku_ai_audio_enabled` in Settings.Global.
Turning voice or global AI off revokes managed voice sessions and refuses new
ones. A globally paused system can retain `audioEnabled:true`.

Failures contain `{ok:false,code,message}`. Codes include `INVALID_ARGUMENT`,
`UNSUPPORTED_OPERATION`, `PROVIDER_NOT_CONFIGURED`, `SETTINGS_UNREADABLE`,
`SETTINGS_NOT_SAVED`, and `CONFIG_UNAVAILABLE`. A failed or interrupted save can have an unknown outcome.
Read the current state before trying a mutation again. Report the failure and
keep the last observed state separate from a confirmed save.

Keys stay in the existing private root credential store. Prefer Taku Audio's
password field for real keys. Never request a real key in conversation or put it
in task files, saved commands, logs, Intents, or Global Settings. `saveFalKey`
accepts one trimmed key of at most 4096 characters, with no whitespace, control
characters, or commas inside it. Only a masked summary can be returned. The
private engine credential getter, runtime read and subscription, translation
operations, and inference are not public settings actions.

The text translation scene remains `org.agentos.audio.text` in the `scenes`
object of `taku_system_ai_config`. An unbound scene reports that text translation
is not configured. A binding change during a session requires restarting that
session. This route is separate from the fal default model.

Check installation with `pm list packages org.agentos.audio`. The desktop
requires Audio Engine for recognition and reports an unavailable Engine instead
of accepting injected transcripts. Manual text entry remains available. Older
native assistant inputs retain their separate platform-recognizer behavior.
Audio features require the separate package and their corresponding resources.


ROM audio delivery can be inspected without starting recording:

```sh
pm path org.agentos.audio
cat /system_ext/etc/taku-audio/models/manifest.json
```

For protocol calls, prefer the host CLI: `node sdk/cli/taku.mjs call audio.capabilities --transport adb --serial SERIAL --params '{"purpose":"SPEAK","language":"en-US"}'`.
It uses the same engine state as Taku Audio's audio status page. The manifest lists bundled
file paths, sizes and hashes; resources are read-only, not a writable setting.
Local recognition/reading requires bundled models, while cloud translation
also requires user-configured credentials and a model scene. Availability
reasons are returned by `audio.capabilities`; installed APK alone does not
establish readiness. CLI recording/playback requires the visible
`org.agentos.audio/.AudioHostActivity` (`HOST_REQUIRED` otherwise); denied
microphone access reports `PERMISSION_DENIED`, and disabled/unavailable policy
reports `POLICY_DISABLED`. Do not replace model files through an Agent action;
model upgrades are sealed ROM/component releases. GUI reading preferences and
voice enablement use the shared Audio settings endpoint above.
