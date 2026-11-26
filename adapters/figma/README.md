# Figma desktop adapter (draft)

This optional adapter lets the native Agent send declarative commands to a Figma
desktop plugin. It is source integration, not a Taku God Mode plugin, an APK, or
a cloud-only Figma service. A Mac or other supported desktop must keep Figma, the
plugin and the short-lived relay running. Figma's own cloud connection carries
desktop changes to its official mobile Mirror.

The earlier case prototype used one private file and machine-specific session
files. This adapter takes the file key as configuration and creates new private
credentials at setup. No existing account, session file, device or port is
accessed by importing or testing this code.

## Setup in an isolated test environment

1. Open a Figma Design file that the operator may edit. Obtain its real file key.
   The plugin checks the actual `figma.fileKey` before editing. If the plugin host
   does not expose that property, it returns `FILE_IDENTITY_UNAVAILABLE` and does
   not edit. A matching file name is not an identity substitute. Availability in
   the intended Figma plugin distribution must be checked before deployment.
2. In a new, private local directory, run the relay with a bounded lifetime:

   ```sh
   python3 adapters/figma/relay.py --file-key FILE_KEY --session-dir /absolute/new/session-directory --ttl 3600
   ```

   It binds **only** `127.0.0.1:8790` (phone requests) and `127.0.0.1:8792`
   (plugin receipts). An occupied port fails setup; it does not kill or replace
   the existing service. The directory must not already exist. `phone.json` and
   `plugin.json` contain different generated credentials, with mode `0600`.
   TTL is 60–7200 seconds. Ctrl-C or expiry stops both listeners. Startup prints
   no credential. Keep the directory and receipt log out of Git and screenshots.
3. Import `plugin/manifest.json` through Figma's normal development-plugin UI.
   Run **Taku Figma Desktop Bridge**, load that new `plugin.json`, then Connect.
   Use the actual configured file; the plugin does not change accounts or shares.
4. Through the authorized development provisioning workflow, install the matching
   `phone.json` as `/data/local/agentos/figma-desktop/session.json` with mode `0600`
   and owner readable by the native broker. On a USB-connected development device,
   the same workflow must establish `tcp:8790` reverse forwarding to desktop
   `tcp:8790`. This PR does not install files, restart the broker, modify init or
   persist reverse forwarding. Follow the device's existing authorized setup;
   absence of setup is a reported dependency, not a reason to weaken permissions.
5. In a **new Agent conversation**, call `taku_phone.desktop_design` with
   `{"action":"state"}`. A device without a session file returns
   `{"configured":false}`. A connected session returns `configured:true`, its
   single target file key, current request, acknowledgment and error. No token
   is returned. Harness background is frozen for a conversation; changing source
   instructions does not rewrite an existing conversation.

Do not start this setup on a user's active recording environment. The new source
has not been installed or tested on a phone as part of this PR.

## Protocol and supported output

The canonical wire schema is [figma-desktop-bridge.schema.json](../../spec/figma-desktop-bridge.schema.json).
The phone endpoint accepts only `GET /state` and `POST /request`; a browser
`Origin` cannot author a request. The distinct plugin endpoint accepts only
`GET /state`, `POST /ack` and `POST /error`. Tokens, file identity and expiry are
checked for both roles. Requests and receipts have separate size limits.

The Agent submits:

```json
{"action":"submit","payload":"{\"requestId\":\"unique-request-id\",\"job\":\"new-study\",\"body\":{\"action\":\"inspect\"}}"}
```

Supported declarative actions:

| Action | Effect |
| --- | --- |
| `begin` | Create a new plugin-owned page and its Start frame for a fresh job |
| `apply` | Add or update bounded symbolic native nodes in that job |
| `clone` | Clone a frame and apply property overrides |
| `link` | Connect click targets or timed transitions with Smart Animate |
| `select` | Select a symbolic frame for Mirror |
| `inspect` | Read this job and select Start; does not change layer content |

The plugin edits only pages tagged with its owner and the current job. It does
not evaluate arbitrary code, publish Code Layers or implement arbitrary drag
physics. Prototype links come from the current exact acknowledgment, never from
a previously generated file. `inspect` can change the selected Mirror frame.
Commands are not transactions: a Figma API failure can leave a partial edit.
The plugin retains both successful and error receipts, so retries do not repeat
that partial operation. Inspect and reconcile it before submitting a new request.
Large diagnostic snapshots are omitted with `snapshotTruncated:true`; execution
identity and URLs remain in the receipt. Observe the real app for visual checks.

`accepted:true` is queue acceptance. Before the next write, compare **this**
request's `requestId`, `job`, `fileKey`, `action`, `executedBy:"desktop-plugin"`
and nonempty `executedAt` in `/state`, with `error:null`. It proves plugin
execution only. It does not prove visual fidelity, cloud sync, mobile gesture
handling or a complete scenario.

## Live progress and mobile preview

Use the official Figma mobile app (`com.figma.mirror`) under the same existing
account as desktop Figma. Hold the app through `request_apps`, open its Mirror
tab on the assigned Android screen, and select a top-level Start frame on
desktop. Observe the actual mobile app during each build stage. To view that
execution screen on the physical phone, use the existing public
`workspaces/preview` interface with its exact thread/screen/generation lease;
see [background workspaces](../../docs/background-workspaces.en.md).

This PR does not add the earlier experimental `show_background` retention tool
or modify shared workspace turn-end cleanup. Its complete autonomous lifecycle
needs separate integration and device validation. A desktop plugin receipt or a
static image is not proof of Mirror updates. An isolated test should change a
nonce and color on the **same** frame and observe both updates without reloading
the phone between them.

For a browser prototype, `open_app` can open an authorized HTTPS Figma link in
Chrome on the assigned background screen. That is a browser preview, not proof
that the official Figma app accepted the interaction.

## Failure and recovery

| Observation | Meaning and recovery |
| --- | --- |
| `configured:false` | Provisioning is absent; do not submit or claim a design exists |
| `DESIGN_RELAY_HTTP_401` | Session expired or credential mismatch; provision a new bounded session through normal setup |
| Accepted request, no exact acknowledgment | Keep the same request pending; inspect the plugin and its connection before retrying |
| Lost plugin-to-UI response | UI retries the **same** request ID; the plugin replays its cached receipt without repeating writes |
| Lost HTTP acknowledgment | UI retains and resends the receipt until accepted; Stop/Connect preserves pending recovery |
| Wrong file or missing `figma.fileKey` | No edits; open the configured file or use a plugin host that exposes authoritative identity |
| Figma cloud offline | Native plugin operations may still run locally; reconnect Figma and verify actual mobile updates |

After an unknown interruption, read `/state` before issuing another command.
Never replace a pending request with a fresh ID to bypass recovery. In-memory
idempotency lasts only for this relay/plugin session. Restarting either process
requires reconciling the visible page and receipts before continuing writes.
Sessions allow at most 500 distinct requests and do not survive reboot.

## Validation

These commands use synthetic fixtures and bind no socket:

```sh
python3 adapters/figma/tests/test_relay.py
node --test adapters/figma/tests/*.test.mjs
node --check adapters/figma/plugin/code.js
bash system/assistant/test-workspace-execution-host.sh
```

The last script compiles the broker with the installed host toolchain and runs
host contracts. No Android device, Figma account, actual relay or model is used.
GUI/Agent configuration parity, current-main emulator/phone, full ROM, signed
delivery, reboot recovery and true drag interaction are **not verified** here.
