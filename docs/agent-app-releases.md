# Taku Agent APK stable distribution

On 2026-10-29, Liu Hai approved promotion of the accepted **agent-app 0.2.0 / versionCode 2** original build to stable at 15:24 (Asia/Shanghai). This changes only the external release registration; the APK, ZIP, signature, embedded manifest, and build timestamp are preserved.

## Current stable version

- [Stable pointer](releases/agent-app/stable.json) → [0.2.0 release manifest](releases/agent-app/0.2.0.json). This is the **agent-app-specific manual APK distribution registry** created in this round; it is not yet connected to automatic app updates or the system Updater.
- [Stable release page](https://github.com/Taku-OS/taku-os/releases/tag/agent-app-v0.2.0), with tag `agent-app-v0.2.0` pinned to build source `c6210fce88c3eaeddd13ea59ceea33025702963f`. Later registration-document commits are not the build source.
- [Download the original ZIP](https://download.taku.ai/releases/0.2.0/TakuAgent-0.2.0-arm64-canary.zip), 44,154,745 bytes; SHA-256: `a8331edeca93585b7209e3f94bdd7770556e64884661c4e596e9c1d068f5f6fc`.
- `TakuAgent.apk` inside the ZIP is 44,152,192 bytes; SHA-256: `426cf8e7dd577e84a5058c60347ac15ec367621b47cff45c0a642f517cfaca7f`.

`canary` in the download filename and embedded manifest is the original archive identity and must remain unchanged. The external stable manifest records this approval; changing embedded fields or recompressing must not create another package with the “same version.” The GitHub repository and manifest retain their existing private access permissions; the ZIP above is an existing public download endpoint.

## Installation and runtime

This package still uses the repository development platform certificate, SHA-256 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`. This approval retains that signature; it must not be described as signed with a new production key.

The application ID is `org.agentos.agent`, and the ABI is `arm64-v8a`. The target device must have the Assistant gateway and TaskBroker from the same source commit, with matching signatures and conversation-streaming protocol. Their SHAs are recorded in the release manifest. This ZIP includes no system image or runtime update; installing the APK alone does not supply protocol support missing from an older runtime.

After verifying the ZIP checksum, extracted APK checksum, signature, and version information, update in place on an explicitly selected compatible device:

```sh
adb -s <assigned-serial> install -r TakuAgent.apk
```

Preserve existing app data; do not uninstall first. In-place frontend APK updates and incremental ROM OTAs built from exact source are separate workflows. This round publishes no new full ROM package, delta, or Updater feed.

## Acceptance and promotion process

0.2.0 was built on the Mac mini from the source above already merged into main; [CI for that exact commit](https://github.com/Taku-OS/taku-os/actions/runs/40815763212) passed. After downloading the same public ZIP, the independent GPU AVD `Taku_MDStreaming_Mini_GPU_20261029` / `emulator-5664` passed installation preserving data, version checks, real model-streaming conversations, and visual acceptance. See the [original acceptance report](rn-markdown-streaming-mini-2026-10-29.md) for performance and visual scope. Pixel and the complete ROM were not counted as passing.

Future promotion follows the [versioning rules](versioning.md) and [agent-app build and canary process](../system/agent-app/README.md):

1. Pin the accepted ZIP/APK bytes, checksums, signature, source, matching runtime, and acceptance receipts; record the responsible person's formal approval of the exact version. Build and upload on the Mac mini.
2. Redownload the existing public ZIP and verify its complete size, SHA-256, ZIP CRC, APK signature, and package identity. Promotion does not rebuild, recompress, resign, or replace the archive.
3. Add an immutable stable manifest at `docs/releases/agent-app/<version>.json`. The stable release tag points to the original build source; mark the GitHub Release non-prerelease, attach the manifest and checksum file, and link the existing ZIP.
4. After publication, update `stable.json` in this directory, binding the version manifest's SHA-256; update main through an ordinary Git fast-forward commit, refusing to overwrite concurrent changes. Verify the remote manifest, tag, Release status, and download checksum.
5. Before and after promotion, compare the bytes, ETag, and LastModified of Desktop `apps/taku/releases/latest/active.json` and `beta/active.json` read-only. This component's registration, tag, Release, and archive are independent of Desktop update pointers; do not change Desktop metadata or releases.

Version manifests and Release attachments remain immutable once formally published. Later APK byte changes require a new version and new acceptance; only promotion of the same candidate retains the version. `stable.json` is a mutable component pointer referencing a published immutable version manifest.

Original publication and download/installation receipts for this round remain in ignored `out/releases/0.2.0/` in the Mac mini streaming worktree; stable-promotion evidence and the independent Desktop comparison are in its `stable-promotion-20261029/` and `desktop-audit-20261029/`, respectively. Credentials, device conversation bodies, and build artifacts must not be committed to the source repository.
