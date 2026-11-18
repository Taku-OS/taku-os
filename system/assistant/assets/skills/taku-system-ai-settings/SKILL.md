---
name: taku-system-ai-settings
description: Inspect and update Taku AI model routing and Taku Audio voice settings through their shared Android owners.
---

# Taku AI model settings

Android Settings.Global is the source of truth shared with the Taku AI settings page. Use the device's `settings` CLI to read the current values before changing them:

```sh
settings get global taku_magpie_groups
settings get global taku_magpie_scene_bindings
settings get global taku_magpie_providers
settings get global taku_magpie_models
```

Groups are a JSON array of Magpie group objects (`id`, `name`, `members`, `routing`); scene bindings are a JSON object mapping scene IDs to `group/<id>`. Common scenes: `assistant` (main assistant), `jev` (Jev decisions), `memory.text` (Memory analysis), `ime.vision` (keyboard vision), and `screen` (screen analysis). Other app scenes may also exist. `group/taku-memory-jev` is the independent Memory group. Check the actual available models and current settings; do not invent a provider or model.

To change a binding or group, make a minimal edit to the corresponding complete JSON value, preserving all unrelated entries, and write it with `settings put global KEY 'JSON'`. For example, after reading and preserving all existing bindings, a value containing only one binding can be written as:

```sh
settings put global taku_magpie_scene_bindings '{"memory.text":"group/taku-memory-jev"}'
settings get global taku_magpie_scene_bindings
```

The example replaces the entire value; do not use it unchanged if other bindings exist. Verify the readback and that Taku AI displays the change. Writes require an identity with Android Settings permission; report a permission failure instead of claiming success. Settings writes are not transactional across keys: avoid concurrent edits and re-read immediately before and after writing. Configure group members before binding a scene.

Do not store credentials, keys, headers or tokens in Global Settings, which other applications can read. Credentials stay in the private system credential store. Direct edits to Magpie's projected provider/group files are reverted; use Android Settings for these values.

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

Only the desktop hold gesture and desktop text-field microphone use this scope.
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

The text translation scene remains `org.agentos.audio.text` in
`taku_magpie_scene_bindings`. An unbound scene reports that text translation is
not configured. A binding change during a session requires restarting that
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
