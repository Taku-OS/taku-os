# Jev shares on-phone Magpie (v57)

Date: 2026-10-21. Component: `20261021-jev-magpie` / versionCode 57. Gateway remains Magpie 0.1.1290; Agent runtime remains Codex 0.161.0.

Jev decision requests moved from direct app-to-OpenRouter calls to on-phone Magpie, sharing provider credentials/model management with a separate decision-model selection, while retaining hint → user tap → original Agent receiving screenshot.

## Verification scope

| Environment | Scope this round |
| --- | --- |
| Host regression | Synthetic gateway over real loopback HTTP: catalog filtering, capability checks, authorization, typed answers, sanitized errors, credential-persistence acknowledgment, idempotent migration; existing draft/native-conversation regression |
| Android emulator | API 36 / Android 16 / ARM64 independent AVD; real APK, Broker, Magpie, Codex; synthetic model upstream and chat-page fixture |
| Real Jev inference | Same-version real Jev → phone Magpie → hint → original Agent acceptance was completed later; see [real-chain record](../jev-magpie-live-2026-10-21/README.md). This original synthetic record is retained |
| Physical phone | v57 not accepted this round |
| Full ROM | No rebuild/acceptance of a complete v57 image this round; earlier ROM build processes unaffected |

Host/device results: [acceptance.json](acceptance.json). Evidence contains only synthetic pages/test replies, without user chats, provider keys, or management-login links.

## Passed results

Final APK, Broker, and test-JAR digests recorded; 41 gateway checks, 95 draft assertions, 46 native-conversation assertions, typed-answer tests, and legacy encrypted-storage tests passed. Full `npm run verify` passed; existing Ninja FIFO guard skipped on macOS.

Both Android input modes completed hint-to-original-Agent tasks, with 2 decision calls and 2 Agent calls. Synthetic-credential import and idempotent retry against real Magpie management API passed. See [UI log](ui-test.txt) and [host output](host-tests.txt).

![Text-model hint with no task before tap](text-hint.png)

![Agent complete, original draft and unsent attachments retained](image-completed.png)

## Pinned-version limitations

Magpie 0.1.1290 ordinary-provider decision catalogs may omit input capabilities; the client does not guess image support, marks it unknown, and uses current Assist text. Device tests use a synthetic upstream over Remote Magpie protocol explicitly declaring capabilities to cover text/image paths; this does not establish OpenRouter Jev image understanding.

## Reproducible checks

```sh
bash system/assistant/build.sh
bash system/display/build.sh
bash system/assistant/test-jev-magpie-host.sh
bash system/assistant/build-jev-magpie-tests.sh
npm run verify
```

Device tests are restricted to their own temporary AVD `Taku_JevMagpie_20261021`, never shared development emulators or physical phones. Create API 36 / ARM64 AVD, start with `-writable-system`, run `adb root` and `adb remount`, then prepare build outputs as follows:

```text
out/device-qa/AgentOSAssistant.apk   ← system/assistant/out/AgentOSAssistant.apk
out/device-qa/task-broker.jar       ← system/assistant/out/task-broker.jar
out/device-qa/virtual-display.jar   ← system/display/out/virtual-display.jar
out/device-qa/fixture.apk           ← out/jev-magpie-device-tests/fixture.apk
out/device-qa/test.jar              ← out/jev-magpie-device-tests/test.jar
```

Public Codex, Magpie, and CA assets are downloaded/verified from `image/runtime-assets.lock.json`. The driver rechecks digests, installs components, and runs real on-phone processes against synthetic upstream; it writes synthetic configuration only in the dedicated AVD, without user providers or real credentials.

```sh
python3 scripts/test-jev-magpie.py --serial emulator-5584
```

Results: `out/jev-magpie-device-qa/<UTC>/`. Tests verify text models receive current Assist text, image models receive screenshots, password fields are excluded from Assist, no task before tap, no duplicate-trigger task, exactly one completed task after acceptance, original screenshot delivered to Agent, unsent text/attachments preserved, and main-model selection unchanged. A synthetic legacy key also exercises real Magpie management API, checking persistence acknowledgment, retry reuse, and existing-provider preservation. Model quality, Assist coverage in real third-party chat apps, long runs, and physical power-key behavior need separate acceptance.
