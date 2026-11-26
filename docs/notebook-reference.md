# Notebook reference interface

Notebook is an optional source-built system app (`org.agentos.notebook`). Its SQLite catalogue
and immutable original image files are authoritative for both UI and Agent calls. The provider
returns `Bundle.result` JSON: `{ "ok": true, "result": ... }` or
`{ "ok": false, "error": "CODE" }`; permission failure throws `SecurityException`.

## Provider

Run in an authorized Android shell. IDs below must come from the corresponding read:

```sh
N=content://org.agentos.notebook
content call --uri "$N" --method status
content call --uri "$N" --method recall --arg '{"query":"upload","queryAliases":["basketball"],"limit":3}'
content call --uri "$N" --method queryEntries --arg '{"sort":"newest","limit":3}'
content call --uri "$N" --method get --arg '{"id":"SAVED_ID"}'
content read --uri "$N/images/SAVED_ID" > original.png
```

Root/system/shell, the app itself and platform-signed callers have direct access. Other apps
are checked by App Permission Center's `taku.control` policy (default deny); they need the
provider in `<queries>`. An explicit read-only image URI grant permits only that image, never
the catalogue or mutations. `backgroundSourceUi` is broker-root-only and is not a general
foreground automation endpoint.

| Method | Arguments and behavior |
| --- | --- |
| `status`, `setEnabled` | Read screenshot observer/access/island state; set takes boolean `enabled`, changing the same preference as UI. It grants no Android permission. |
| `save`, `runAction` | Save the current pending screenshot; or take its `action` (`save`, `send`, `edit`, `go_to_notebook`, `change_list`, `add_note`) while valid. Returns `NO_PENDING_SCREENSHOT` when absent. Share/Edit can launch Android UI. |
| `list` | Optional `limit` (1–50), `offset`; saved entries, newest first. |
| `recall` | Optional `query`, up to three `queryAliases` (each ≤160 chars), `category`, `limit` (1–50). Keywords and aliases use actual stored topics; generic items of the exact same verified post can be recalled without replacing the selected original image. |
| `queryEntries` | Optional `query`, `source`, `listId`, inclusive `from`/exclusive `to` epoch ms, `sort` (`newest`/`oldest`), `limit` (1–50), `offset`. |
| `get`, `reference` | Exact `id`; `reference` additionally grants this image read-only to the platform-signed Agent package. |
| `open`, `openSource`, `copySource` | Exact `id`; open saved detail, open the actual saved source, or copy it. Missing sources are explicit failures. |
| `attachSource`, `retrySource` | `id,url` attaches one validated web source where manual attachment is allowed; `id` retries automatic native X resolution. An existing source is not overwritten. |
| `lists`, `createList`, `updateList` | Read lists; create takes `name`, optional `rule`; update takes `id` and `name` and/or `rule`. |
| `editEntry` | `id` and `title`, `appendDescription` and/or `listId`, using the same validation as UI. |
| `batchEntries` | Explicit `ids` and `operation` (`move` with `listId`, or `delete`); max 50 entries. Delete removes saved private copies. |
| `getAppearance`, `setAppearance` | Read; write takes `theme` (`system`, `light`, `dark`). |

Entry fields include `id`, `sha256`, `imageUri`, `mimeType`, `sizeBytes`, `capturedAt`, `savedAt`,
`sourcePackage`, `sourceLabel`, `sourceUrl`, `sourceResolution`, `title`, `summary`, `description`
and `listId`. The ID is a stable hash of the original media URI; `sha256` hashes the copied
original bytes. They have different meanings. Native X source states are `pending`, `linked`,
`not_found` and `unavailable`; none implies that a video was decoded.

Common errors: `INVALID_ARGUMENT`, `INVALID_PAGE`, `ENTRY_NOT_FOUND`, `IMAGE_UNAVAILABLE`,
`SOURCE_ALREADY_SET`, `SOURCE_AUTOMATIC_ONLY`, `NOTEBOOK_SOURCE_UNAVAILABLE`,
`NOTEBOOK_SOURCE_OPEN_UNAVAILABLE`, `NO_PENDING_SCREENSHOT`, `UNKNOWN_METHOD`.

## Native phone tools

- `taku_phone.notebook_recall`: subject/aliases/category or the three latest saves. Returns
  `taku.notebook.references/1` records and, when available, a hash-verified preview of the first
  exact screenshot. Select the newest relevant returned original when the user says “just saved”.
- `taku_phone.notebook_open`: exact recalled `id`, `destination: detail|source`; only open at the
  user's request and observe what actually loads.
- `taku_phone.notebook_read_video`: exact recalled `id`; re-reads that authoritative saved record,
  requires a linked public X source, resolves its exact post ID through the public embed endpoint,
  downloads at most 24 MiB of allow-listed `video.twimg.com` MP4 (≤800 px per dimension,
  ≤60 seconds) and decodes on Android in a bounded worker. It returns the source/video identity,
  hashes, timestamps and up to two contact sheets of actual image frames as model inputs.

Video output schema is `taku.notebook.video/1`; it includes `referenceId`, `sourceUrl`, `postId`,
`videoSha256`, `frames` and `sheets`. At most 32 frames are sampled over the duration with a
minimum 500 ms interval. Audio is not analyzed. It is sampled visual evidence, not a guarantee
that every gesture, transition, easing curve or collision rule was understood or recreated.
The feature does not implement motion-fidelity scoring or Figma creation/preview.

Expected failures include `VIDEO_REFERENCE_INVALID`, `VIDEO_SOURCE_NOT_LINKED`,
`VIDEO_SOURCE_UNSUPPORTED`, `VIDEO_NOT_PUBLIC`, `VIDEO_UNAVAILABLE`, `VIDEO_MP4_UNAVAILABLE`,
`VIDEO_DURATION_UNSUPPORTED`, `VIDEO_HTTP_*`, `VIDEO_WORKER_TIMEOUT`, `VIDEO_FRAME_UNAVAILABLE`.
Public X embed availability is an upstream dependency. No X account cookies or credentials are
used for public video retrieval. The separate native source lookup can use the already signed-in
X app only through its read-only isolated task; login/permissions are not bypassed.

Successful Notebook receipts remain in the bounded conversation projection; base64 image
payloads are stripped from that projection. Reopening actual history can refresh exact image
grants. The native conversation remains authoritative; a projected receipt cannot prove a new
network/decode run.

## Validation status

Host tests cover exact-post recall, URL/media bounds, author/time/text verification and ambiguity,
and receipt projection without inline image bytes. Local Notebook/Assistant builds and broker
compilation are compile checks only. A source-based full ROM build, current-main emulator/device
acceptance, Android decoder output, task isolation, persisted recovery and current SystemUI card
visuals must be validated before release. No existing user's runtime is modified by this change.
