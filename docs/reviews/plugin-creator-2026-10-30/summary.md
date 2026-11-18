# Plugin Creator v1: verification receipt

## 2026-10-30 community-publication update

Earlier local-fallback records below remain historical. Community repository [Taku-OS/taku-os-plugins](https://github.com/Taku-OS/taku-os-plugins) now exists at `main` commit `de3a753`. A newly built broker ran `publish` on the dedicated AVD existing `status-break-reminder` source, then authenticated host `gh` submitted [example PR #1](https://github.com/Taku-OS/taku-os-plugins/pull/1) through a fork; it is unmerged. [CI validate](https://github.com/Taku-OS/taku-os-plugins/actions/runs/41084090478/job/124160539666) passed. Package SHA-256: `3e1d3ccb86840d7ff572d9ad2ef9641a4c26890dd36734d77a4a734dc154b9bb`. This used temporary broker/device CLI wrappers without copying host credentials to the device.

Device lacks `gh`/`git`; on-device `publish` still returns local artifacts. Connect a host for `publish-plugin-host.py --packed ...`, or orchestrate both steps from host with `--source ... --output ...`; Android does not independently create PRs without a host. A temporary configuration without `gh` sign-in explicitly failed while retaining the device local index. New broker passed `test-plugin-creator.py` with temporary CLI path, build, and structural checks on the dedicated emulator. Its system image lacked `/system_ext/bin/taku-plugin`, so a temporary wrapper was used; no ROM/hardware verification is claimed.

Draft PR: [Taku-OS/taku-os#15](https://github.com/Taku-OS/taku-os/pull/15), branch `codex/plugin-creator-20261030`, depending on #13 and awaiting rebase after its completion. This branch implements no upgrade, rollback, or transactions.

## Implemented

- Preinstalled `taku-plugin-creator` uses short SKILL.md/references for gathering current-conversation artifacts, declaring capabilities/permissions, local test install/uninstall, and publication. Codex `skills/list` discovered the installed skill on the emulator.
- `taku-plugin validate`, `pack`, and `publish` produce a native-manifest-compatible copy, executable-bit-preserving ZIP, SHA-256, local marketplace, and community index. With an existing GitHub repository and device tools/access, publication attempts branch push/PR; otherwise it clearly reports local artifacts/reason. It never creates repositories. [CLI documentation](../skills-lifecycle-2026-10-30/plugin-cli.md).
- `catalog-add` registers the packaged copy in the workspace native marketplace; GUI/CLI share marketplace, Codex config, and native RPC. Ordinary Android apps may also call plugin operations; an independently signed app actually read/disabled/enabled on-device. Capabilities/permissions are declarations, not runtime authorization or isolation.

## Emulator evidence

Temporarily deployed artifacts on independent `Taku_PluginCreator_20261030` / `emulator-5682`: manually packaged, registered, installed, uninstalled, and reinstalled repository-owned [status-tip example](../../../examples/plugin-creator/status-tip/). CLI and [plugin UI](plugin-enabled.png) both showed enabled; native MCP discovered `status_tip`, whose direct call returned `Time for a short break.` This is not evidence of Agent-generated plugins.

Emulator `publish` generated ZIP and [index](artifacts/index/taku-status-tip/1.0.0.json) with matching SHA-256. Repository rules prohibit ZIP commits; full package/index remain in untracked `out/plugin-creator-2026-10-30/`. Missing device `gh`/`git` returned `published: false`; no community PR was created at that stage. No community repository was then configured/confirmed, nor found clearly in the organization list; none was created. Real repository push/community PR was not yet verified end-to-end at this stage.

`system/assistant/build.sh`, `python3 scripts/check_structure.py`, `python3 system/assistant/test-plugin-creator.py emulator-5682`, and `system/assistant/test-plugin-open-access.sh emulator-5682` passed. Coverage: manifests, ZIP contents/executable bits/hashes, duplicate-output/symlink rejection, local publication fallback, ordinary-app CLI reads/writes. Shared GUI/CLI state was observed on an actual emulator; no complete-ROM build/flash.

## Initial incomplete attempt (continued below)

Submitted a one-sentence request to turn the status script into a plugin and publish it. After adding virtual-display dependencies, the preinstalled skill was stored/discovered, but conversation resume returned “Assistant network connection failed” (translated); model generation/test installation/publication did not complete. The complete sentence→Agent plugin creation→activation→artifact-publication demo remained unpassed and needed working network plus rerun/screenshots. Existing screenshots/ZIP came from manually driven CLI and could not replace it.

Device `publish` requires external `git` and `gh`, absent from the current image; configuring a repository also requires tools, Git identity, and authentication for remote PRs. ZIP/manifest checks do not fully audit consistency of declared capabilities and actual behavior.

Implementation commits pushed: `cb15d08`, `023576b`, `b37b67d`, `4b91444`; receipt/evidence committed separately.

## Continuation: real Agent creates a plugin from one sentence (2026-10-30)

Dedicated `Taku_PluginCreator_20261030` / `emulator-5682` passed **real model → creator skill → validation, packaging, test install, and local-publication fallback**. First turn created/ran `creator-live-case/status.sh`, output `Take a short break.` The next actual composer request meant “Turn that status reminder into a plugin and publish it to the community.” Agent independently read `taku-plugin-creator/SKILL.md` and `references/creation.md`, generated `status-break-reminder` 1.0.0, ran `validate`, `pack`, `catalog-add`, `install`, executed the installed script with identical output, uninstalled the test copy, then called `publish`. Turn completed with real model output. [Sanitized command receipt](agent-receipt.json) preserves commands/output; initial registration/install failed with relative paths, then Agent corrected to absolute paths and succeeded. Retry remains visible.

[Request](agent-request.png), [working](agent-working.png), [result](agent-result.png), [execution list](agent-publication.png), and [publish output](agent-output.png) are actual screenshots from that run. Self-test means executing the installed script, not later model use of the new skill or MCP-tool acceptance. Agent uninstalled after self-test per skill instructions; local catalog entry remained.

`publish` returned `published: false` and `No community repository configured; artifacts are local`, without a community PR; this was the accepted local-index fallback. Device package: `/data/local/agentos/work/plugin-publish/status-break-reminder/status-break-reminder-1.0.0.zip`; index: adjacent `index/status-break-reminder/1.0.0.json`. [Index](artifacts/index/status-break-reminder/1.0.0.json) copied into repository; package/generated source retained under `out/plugin-creator-2026-10-30/agent-publish/` and `agent-source/`, ZIP excluded from Git. Device/host SHA-256 both `158c2d90355bd88603d8fcb2c71d511bcb67a18592ef2a5ae4e958fb78f97245`; ZIP contains manifest, skill, executable script and matches index.

### Network investigation and reproduction

- Original AVD connection was `openai`; local Magpie was absent, so this was not the previously asserted confirmed Magpie upstream failure. Device `network-proxy` was `http://127.0.0.1:7897` without ADB reverse after startup; Wi-Fi/mobile DNS was `10.0.2.3`, network validated. No `nslookup` existed, so successful DNS resolution was not inferred. Mac HTTP/HTTPS proxy `127.0.0.1:7890` reached the internet through Clash. Restoring `adb -s emulator-5682 reverse tcp:7897 tcp:7890` reached ChatGPT but returned 401: cached access token expired and refresh token already used. Missing reverse after restart/current 401 were verified; this does not establish the sole cause of the earlier failure.
- To avoid copying/refreshing device ChatGPT credentials, a temporary route was used: Android native Codex → `127.0.0.1:3425` → ADB reverse → temporary Mac loopback bridge → existing authenticated Mac Magpie route → `aws-claude/anthropic.claude-opus-5-5`. Bridge listens only on Mac loopback and reads existing local `magpie_bedrock` auth to add upstream headers; device holds only a powerless placeholder token. **Magpie did not run inside Android this round**; forwarding is not independent-phone network/cold-boot acceptance.
- Reproduction requires an existing working Mac `127.0.0.1:13427` Magpie route and matching local config; bridge/reverse are temporary dedicated-AVD development infrastructure, stopped afterward. Prepare matching APK/Broker/Codex/virtual display/preinstalled skill above, start AVD, run `adb root`; after reboot, restart Broker and reestablish reverse. Run below from repository root:

```sh
# Verify AVD name to avoid other devices
adb -s emulator-5682 emu avd name
adb -s emulator-5682 reverse tcp:7897 tcp:7890
# Run in another terminal and stop after acceptance; requires existing local 13427 route/auth configuration
python3 docs/reviews/plugin-creator-2026-10-30/magpie-test-bridge.py
adb -s emulator-5682 reverse tcp:3425 tcp:13428
bash system/assistant/build-workspace-tests.sh
adb -s emulator-5682 push out/workspaces-device-tests/tests.jar /data/local/tmp/creator-native-probe.jar
adb -s emulator-5682 shell 'nohup env CLASSPATH=/system_ext/framework/agentos-task-broker.jar app_process /system/bin org.agentos.lab.TaskBroker >/data/local/tmp/taku-creator-broker.log 2>&1 </dev/null &'
adb -s emulator-5682 shell 'env CLASSPATH=/data/local/tmp/creator-native-probe.jar app_process /system/bin org.agentos.tests.NativeSocketProbe exchange "{\"op\":\"modelConnection\",\"action\":\"select\",\"mode\":\"magpie\",\"model\":\"aws-claude/anthropic.claude-opus-5-5\"}"'
```

Model/route catalogs depend on existing host services and are checked through native selection. At completion restore original connection, remove both reverse mappings, and stop bridge. Host-dependent routing does not replace fixing expired device login or configuring on-phone Magpie; remote community push remained unverified at that stage. Repository implementation was unchanged by this temporary route.
