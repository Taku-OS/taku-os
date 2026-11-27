# Notebook reference interface

Taku Notebook (`org.agentos.notebook`) stores saved screenshots in an authoritative SQLite
catalogue with immutable original image files shared by both the Notebook UI and Agent tools.
`content://org.agentos.notebook` returns `Bundle.result` JSON: `{ "ok": true, "result": ... }`
or `{ "ok": false, "error": "CODE" }`; permission failure throws `SecurityException`.

## Provider additions for references and motion lookup

Run in an authorized Android shell against the current Android user (`U=$(am get-current-user)`):

```sh
N=content://org.agentos.notebook
U=$(am get-current-user)
content call --user "$U" --uri "$N" --method recall --arg '{"query":"upload","queryAliases":["basketball"],"limit":3}'
content call --user "$U" --uri "$N" --method reference --arg '{"id":"SAVED_ID"}'
content read --user "$U" --uri "$N/images/SAVED_ID" > original.png
```

- `recall` accepts optional `query`, up to three `queryAliases` (each ≤160 chars), `category`,
  `purpose` (`pose_reference`), and `limit` (1–50). Keywords and aliases match stored topics and
  can include generic unedited saves from the exact same verified post without substituting another
  image.
- `reference` takes an exact `id`, verifies platform signature match for `org.agentos.agent`, grants
  a read-only URI permission to that single image (`FLAG_GRANT_READ_URI_PERMISSION`), and returns
  the entry metadata.

## Native phone tools

- `taku_phone.notebook_recall`: searches by `category`, `query`, and optional `queryAliases` (or
  returns the newest saves when filters are omitted). General recall returns
  `taku.notebook.references/1` bound to `androidUserId`, grants up to 3 image references to
  `org.agentos.agent`, and attaches a SHA-256-verified preview of the first saved screenshot when
  available. A failed image grant leaves the saved entry in the result. Setting
  `purpose="pose_reference"` retains the pose-reference projection.
- `taku_phone.notebook_read_video`: takes an exact recalled `id`, re-reads that authoritative saved
  record for the initiating Android user, requires a linked public X source, resolves its exact
  post ID through the public embed endpoint, downloads at most 24 MiB of allow-listed
  `video.twimg.com` MP4 (preferring ≤800 px variants and falling back to the smallest HD MP4 if
  none is ≤800 px, ≤60 seconds), and decodes on Android in a bounded worker process
  (`NotebookVideoReader`). It returns a `taku.notebook.video/1` receipt and up to two timestamped
  contact-sheet images (≤32 frames, `OPTION_CLOSEST`, no audio).
- `AgentViewProjection` preserves `notebook_recall` and `notebook_read_video` JSON receipts in
  display history while omitting binary image payloads. When native history is reopened, image
  grants are refreshed in the background and may become available after the history response.

## Built-in Figma motion workflow

The built-in `taku-figma-motion` skill (`system/assistant/assets/skills/taku-figma-motion/SKILL.md`)
is installed by `AgentSkills.ensureBuiltin()`. It relies on an already-connected official Figma
connector (not bundled in this repository), a signed-in Figma account with edit and Code Layers
access, desktop Figma for Mirror selection, and the official Android Figma app (`com.figma.mirror`).
