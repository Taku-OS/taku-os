# On-phone Magpie and DeepSeek acceptance, v55

Status: `emulator_verified`. Installed assistant v55 `20261019-magpie`, Codex 0.161.0, and Magpie 0.1.1290 on `Taku_ImageIntegration_20261010 / emulator-5634`, Android 16 / API 36 / ARM64. Both Codex and Magpie run inside Android; DeepSeek requests go directly to the online API without MacBook model forwarding.

## Delivery scope

Assistant settings add “Model connection” (translated), switching between existing ChatGPT sign-in and local Magpie and selecting Magpie models/route groups. “Manage models and routes” opens upstream local management UI and dismisses the assistant overlay. Magpie owns credentials/routes; Taku saves only connection/default-model selection. Existing ChatGPT accounts, conversations, drafts, and attachments retain their original storage.

Default selection is `deepseek/deepseek-flash`, reusing the user-authorized existing official DeepSeek configuration, without new accounts or desktop proxy. Current catalog display name: `DeepSeek-V4.1-Flash · DeepSeek`.

Source responsibilities and pinned-version upgrades: [maintenance guide](../../../adapters/magpie-runtime/README.md). Device credentials are excluded; [acceptance.json](acceptance.json) records only versions, checksums, results, and model replies.

## Actual verification

- **Real model**: one conversation through on-phone Codex → Magpie → DeepSeek. The model actually called `taku_phone.device_info`; the native tool item completed and returned Android 16 and ARM64 emulator model. Chinese, emoji, and Markdown worked. Reply matched actual device data: [real reply](live-response.png).
- **Protocol regression**: 12 checks passed. Upstream used an explicitly marked temporary Chat Completions fixture; on-phone Codex, Magpie, dynamic tools, and conversations were real processes. Covered native catalog, valid TOML URLs, route groups, arbitrarily split UTF-8, Chinese/emoji, tool roundtrip, busy-switch rejection, native Stop, and reading the same history after returning to OpenAI.
- **Actual UI**: settings entry, state reads, refresh, model selection, management-page opening, and overlay dismissal passed; original conversation restored afterward. [Connection settings](model-connection.png).
- **Image capability**: current DeepSeek catalog declares text only. Image input is rejected before turn creation with a Chinese-language message; unsupported images are not sent upstream.
- **Persistence**: reboot after initial installation verified automatic `taku_magpie` and `taku_broker` startup. Pinned binaries/assistant APK matched installed artifacts; ordinary builds contain no device configuration.

Two integration bugs were fixed: JSON-string `\/` cannot be used directly in a TOML provider URL; opening external management UI must dismiss the assistant overlay. Capability tracking updates after native successful responses, and restored conversations use current-connection image capabilities.

## Reproduction

```sh
bash system/assistant/build.sh
TAKU_SERIAL=emulator-5634 bash system/assistant/test-magpie-runtime.sh
bash system/assistant/test-agent-interface.sh --test testModelConnection
npm run verify
```

UI tests use the configured DeepSeek provider. Protocol tests restore the original connection, archive temporary conversations, and remove temporary providers/groups, ADB reverse, and probes. Real-model acceptance uses existing API configuration and one read-only device-info request; fixture success does not prove upstream availability.

Scope is component build and development emulator; full-ROM rebuild, physical phones, long conversations, and multi-model failover stress tests were not run. Initial support covers verified API providers/route groups, without complete Bun-dependent Magpie plugins or subscription-sign-in adaptation.

## Management-page return navigation, v56

v56 `20261019-magpie-return` fixes inability to return to the assistant after leaving management web UI. `ModelManagementActivity` is the browser return target, opens the page with `startActivityForResult`, then reinvokes the system assistant directly into Model connection and refreshes the catalog when the browser exits.

Android may reuse a running browser task without sending `onNewIntent` to the covered root Activity. Return uses the assistant current conversation selection instead of a stale entry ID; existing controllers/SharedPreferences still own conversations/drafts, without another copy.

Automated emulator acceptance passed: normal roundtrip restored the same conversation/Chinese draft; switching conversation while management remained open, then reentering and pressing system Back returned to the later conversation with both drafts intact. Tests submitted no model messages. Screenshots: [return to Model connection](return-to-model-connection.png), [Chinese draft retained](return-draft.png). Version/APK checks: [return record](acceptance-return.json). Assistant build and `npm run verify` passed; Linux FIFO cases skipped on macOS. v55 model/route evidence retains its original version and is not combined with this navigation acceptance.
