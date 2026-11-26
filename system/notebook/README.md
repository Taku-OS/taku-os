# Taku Notebook references

Notebook saves a user-selected system screenshot into app-private storage, keeps its original
bytes and SHA-256 alongside its source, and exposes the same catalogue to the library UI and
Android `content` callers. This source introduction includes screenshot actions, lists, notes,
search and exact saved-reference recall. It does not include location reminders or pose tools.

For a screenshot captured from native X, an authorized background job reads visible evidence
with the configured image model, searches public posts, and verifies author, caption and visible
publication minute against public embed metadata. When public discovery cannot identify it,
a bounded read-only lookup can use an independent X task and copy its share link after a visual
match. It defers while X is foreground and refuses task reuse that could move the user's task.
No guessed post IDs or unrelated matching images are accepted. An unavailable/ambiguous source
remains unavailable; it is not a successful link. Other apps can save a visible web URL or use the
library's manual source attachment; automatic native source lookup in this change supports X.

## Build without deployment

Requirements are the repository's Android SDK 36 and JDK 17+ setup. Run from the repo root:

```sh
bash system/notebook/test-host.sh
bash system/notebook/build.sh
```

`test-host.sh` runs synthetic Java policy tests and JavaScript syntax checking. It verifies a
pinned test-only `org.json` dependency (downloads from Maven Central only if absent). Set
`TAKU_TEST_JSON_JAR` to an existing copy for offline use. Neither script invokes ADB or installs
anything. `build.sh` produces ignored `system/notebook/out/TakuNotebook.apk` signed with public
AOSP **development** keys. Do not distribute that as a production update.

`TakuNotebook` is also a source-based Soong module. In the supported full-image build checkout,
`m TakuNotebook TakuAssistant agentos-task-broker` is the integration target. This PR does not add
Notebook to the default product package list, invent a release lock, or replace a pinned APK.
A ROM owner must include/release the module with the platform certificate and updated Assistant
provider/broker, validate permissions, and run the emulator/device acceptance below. Source-only
Soong wiring has not been validated with a full ROM build here.

## Runtime setup and acceptance still required

In Notebook settings, enable screenshot actions, grant image and notification access, enable
its Android accessibility service, and authorize Notebook in Taku AI. Configure an image-capable
routing group for the `org.agentos.notebook` consumer. Android can require the system settings UI
for accessibility and photo consent. No setting is changed by building this source.

The saved record is committed before enrichment/source lookup. User edits to a title/list are
preserved when an older AI result arrives. Filing context that exceeds the shared AI API's
8,000-character budget is rejected; the original save is retained without that enrichment. Original images use immutable read-only content URIs;
recall grants only each returned image to the platform-signed Agent UI. Deleting a save removes
its private copy; it does not delete the system screenshot.

The screenshot card uses the current public `taku.island.v2` native `basic` template, opaque fill
and `cover` position. The old v1/Agent-only `assistant` template is deliberately not submitted to
current SystemUI. Stock or unavailable SystemUI falls back to a notification. The library's
existing local assets and font licenses are retained.

Before release, on an isolated emulator or designated test phone, check screenshot → Save →
exact original bytes/hash → source binding → recall of that exact ID → actual decoded video
frames. Also verify list/note/appearance/action mutations via UI and the documented provider,
third-party denial/grants, clipboard/task restoration, notification fallback, language and card
layout on current SystemUI, cancellation/consent changes, cold-start and persisted-job recovery.
None of those runtime checks has been run against this branch. Earlier Pixel builds are not
acceptance evidence for this latest-main source integration.

See [the interface and capability guide](../../docs/notebook-reference.md). No Figma plugin,
relay, file key, account credential, APK/JAR or user session is part of this module.
