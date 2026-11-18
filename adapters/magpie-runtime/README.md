# On-device Magpie

Taku OS runs the Magpie gateway on Android ARM64, routing Codex Responses requests to other model services. Codex remains responsible for conversations, tools, approvals, stopping, and message events; the assistant continues using the same native conversation rendering without adding a separate task flow.

```text
Taku Assistant → local Codex app-server → local Magpie → model service
                  native thread / turn / item             DeepSeek, etc.
```

“Local” means Codex and the routing gateway run on the phone; DeepSeek inference still calls its online API. The runtime does not rely on MacBook forwarding or reuse a desktop Magpie process.

## Usage

Open Assistant settings → Model connection. On first use, the user adds a model service; acceptance used the official DeepSeek API and `deepseek/deepseek-flash`. That personal service configuration is not distributed with source code or installation packages.

- **Manage models and routes**: open the local Magpie administration page to add API services, models, or routing groups. Leaving the browser automatically returns to “Model connection” in the current conversation and rereads the model catalog. You can also select “Refresh model services” to update it manually, then choose a default model or routing group.
- **ChatGPT login**: switch back to the native ChatGPT connection after tasks finish. Existing login data stays in its original location and is not overwritten by Magpie.
- Running or queued tasks prevent connection switching. Switching restarts local app-server while retaining conversation history, composer drafts, and attachments; model and reasoning-effort preferences are saved separately for each connection.
- The current DeepSeek catalog declares text-only support. Sending an image or screenshot is explicitly rejected before turn creation; the user can keep the draft and remove the image or choose an image-capable model.

## Jev and decision models

v57 provides a separate decision-model selector under Assistant settings → **Jev decision model**, reusing **Manage models and routes**, service credentials, and the catalog here. The administration page returns to Jev settings; the main conversation model keeps its existing selection. Upstream must provide the Decisions protocol; ordinary chat models and Jev Router do not appear in the decision list.

```text
Current-page Assist text / explicitly supported screenshot → Broker → Magpie /v1/systemone → lightweight suggestion
User selects suggestion → existing Codex Agent (original screenshot, preserving unsent drafts and attachments)
```

- Decision catalog: `GET /v1/models` with `X-Magpie-Deciders: 1`, filtering `kind: decision` and preserving actual IDs and `modalities.input`.
- Current-screen suggestions send screenshots directly and allow only decision models whose catalog explicitly declares image support; there is no Assist-text fallback. Image-protocol tests use a synthetic Remote Magpie fixture declaring full capabilities.
- Legacy encrypted Jev credentials migrate through a UID-restricted in-memory socket: reuse a service with the same key or invoke the add operation of Magpie `POST /api/provider/save`, and clear the old copy only after rereading private storage confirms success. Existing providers and routing groups are not overwritten.
- Decision HTTP uses a separate Broker branch, independent of main Codex RPC. Choose other models from their actual catalog; do not copy model IDs from the old alpha interface.

See [Agent module](../../docs/agent-module.md), [v57 synthetic regression](../../docs/reviews/jev-magpie-2026-10-21/README.md), and [real Jev-to-Agent acceptance](../../docs/reviews/jev-magpie-live-2026-10-21/README.md) for operations, trigger boundaries, and acceptance scope.

## Pinned version and file layout

| Content | Path |
| --- | --- |
| Launch wrapper | `/system_ext/bin/magpie` |
| Upstream Android ARM64 binary | `/system_ext/bin/magpie-runtime` |
| init service | `/system_ext/etc/init/taku-magpie.rc`, service name `taku_magpie` |
| Gateway and administration page | `127.0.0.1:3425`, `127.0.0.1:3430` |
| Private configuration and API credentials | `/data/local/agentos/magpie/config/magpie/` |
| Administration-entry credential | `/data/local/agentos/magpie/web.key` |
| Current connection selection | `/data/local/agentos/magpie/connection.json`, containing only mode / model |
| HOME, cache, temporary files | `/data/local/agentos/magpie/{home,cache,data,state,tmp}` |
| Upstream MIT license | `/system_ext/etc/magpie-LICENSE` |

The pinned version is **0.1.2204**, Android ARM64 download size **62,259,496 bytes**, SHA-256 `784eeac4560062f38a74a9e0d644a0aecfee6103fd453f3576914fb87ff60074`. URL, size, and digest are stored together in the [asset lock](../../image/runtime-assets.lock.json); the download directory is excluded from Git. Complete device regression for this version is still in progress; historical acceptance records do not establish acceptance of the new version.

Device configuration is created at first startup, using Magpie gateway mode without automatically rewriting Codex configuration. Configuration, upstream API keys, and Web keys do not enter ROMs, APKs, the repository, or conversation projections; the native settings page obtains the administration entry only through a UID-restricted in-memory socket. The launch wrapper reuses existing Codex CA certificates. Optional device proxy configuration continues to use `/data/local/agentos/network-proxy`; loopback requests always connect directly.

## Building and maintenance

```sh
# Prepare only the pinned public Magpie binary
bash adapters/magpie-runtime/bootstrap.sh

# Install components on a configured development emulator; do not clear device data
TAKU_SERIAL=emulator-5634 bash adapters/magpie-runtime/install.sh
# Restart after adding/changing init services; ordinary binary updates may restart the service while idle
adb -s emulator-5634 reboot

# Read-only status checks
adb -s emulator-5634 shell getprop init.svc.taku_magpie
adb -s emulator-5634 shell magpie --version
```

The ROM [Android.bp](../../Android.bp) and [product definition](../../image/taku.mk) include the launcher, pinned binary, and license; the [image verifier](../../image/verify-artifacts.py) checks these artifacts. Upgrades must update the asset lock and upstream license together and repeat download verification, protocol tests, real model validation, and image-artifact checks. At present this uses the Taku development image's `su` service domain; production SELinux policy and physical-phone acceptance are unfinished.

## Interface boundaries and validation

Codex 0.161.0 uses `model_provider=magpie`, `wire_api=responses`, native catalog `/v1/codex/models`, and SSE; WebSocket is disabled for this provider. Taku's `ModelConnection` separately handles device connection control without masquerading as a new Codex method. The existing 168 native methods and conversation/event structures remain unchanged.

```sh
bash system/assistant/build.sh
TAKU_SERIAL=emulator-5634 bash system/assistant/test-magpie-runtime.sh
bash system/assistant/test-agent-interface.sh --test testModelConnection
npm run verify

# Jev protocol, credential migration, and draft regression (all use synthetic data)
bash system/assistant/test-jev-magpie-host.sh
# Build the dedicated fixture APK and UI test JAR
bash system/assistant/build-jev-magpie-tests.sh
# Only a dedicated writable, rooted API 36 AVD is allowed; see the v57 acceptance record for preparation
python3 scripts/test-jev-magpie.py --serial emulator-5584
```

Protocol tests use a temporary local Chat Completions service as an explicitly labeled simulated upstream, running the actual Codex and Magpie on the phone and checking routing groups, chunked Chinese text, dynamic phone tools, stopping, switching while busy, and history across connections. Afterward they restore the connection, archive test conversations, and remove temporary providers/groups and ADB reverse.

See the [v55 acceptance record](../../docs/reviews/magpie-runtime-2026-10-19/README.md) for real DeepSeek acceptance, the administration UI, and known boundaries. The first version validates API providers and routing groups; plugins requiring Bun have not completed Android adaptation. v60 adds Codex subscription discovery for the input method: Magpie retains its own HOME/config and reuses the phone's native login through CODEX_HOME, without automatically importing accounts or rewriting the main Agent configuration. During v61 integration, at the phone owner's explicit request, a valid local ChatGPT Pro cache was written via USB to the phone's private Codex directory. Pixel 9 / Android 17 completed the real `codex/gpt-6-luna` screenshot → JSON → native suggestion bar → tap-to-fill flow; the two generations took 8340 ms and 6609 ms. Credentials are excluded from the repository, APK, and reports. See [input-method acceptance](../../docs/reviews/inputmethod-ai-2026-10-22/README.md) for detailed results and restart-mount limitations.

References: [upstream release](https://github.com/yetone/magpie-releases/releases/tag/v0.1.2204), [pinned-version reference documentation](https://github.com/yetone/magpie/blob/v0.1.2204/docs/reference.md), [Codex configuration schema](https://github.com/openai/codex/blob/rust-v0.161.0/codex-rs/core/config.schema.json).

Assistant v62 adds unified system AI configuration and a shared entry point for ordinary apps. On serve, the Android wrapper establishes a `taku_ai` loopback 3425 UID-isolation chain allowing direct access only to root/system; ordinary apps use independent authorization through the system Provider. Positive service-availability checks, rejection of ordinary-app direct access, and real public-client calls are complete; see [system AI physical-device acceptance](../../docs/reviews/system-ai-2026-10-23/README.md).
