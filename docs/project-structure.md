# Project structure

`taku-os` holds the Taku OS system implementation, interfaces, product integration and engineering documentation. The full upstream Android checkout is fetched in a separate build environment, and images and APKs go to artifact storage or git-ignored output directories. `image/` pins the Android 17 (API 37) platform, device, vendor, kernel and runtime inputs for the current Pixel 9 build target. See [current status](current-status.md) for what has been tested.

## Source and examples

| Path | Purpose |
| --- | --- |
| `core/` | Protocol facade, device probes and method list. Reuses the existing task system |
| `sdk/` | General SDK and CLI. This layer has no third-party npm runtime dependencies |
| `adapters/` | Android transport and Agent runtime adapters |
| `adapters/codex-runtime/` | Codex Android launcher, runtime packaging and Taku transaction patch notes. See [runtime dependency](../adapters/codex-runtime/README.md) |
| `adapters/magpie-runtime/` | On-phone model gateway, version pinning and background service. Connects to external model services |
| `system/assistant/` | Native Home and Assistant, task service, gateway, settings, background display entry, Skills and Codex plugin management, preinstalled agent documentation |
| `system/agent-app/` | React Native and Expo session UI, component gallery and plugin marketplace pages. Sessions come from the native runtime |
| `system/ai/` | Taku AI public client, scenario interfaces and documentation. The service is implemented in the native Assistant |
| `system/memory/` | Standalone Memory APK with authoritative storage, capture, organizing and retrieval, an open provider and experimental reminders |
| `system/notebook/` | Optional screenshot catalogue, exact-source recall and public X video reference integration; source preview, runtime acceptance pending |
| `system/plugins/` | Plugin manager, JVM and Android hosts, root service, management page, MCP and provider, REA pack tools. Separate from the Codex marketplace |
| `system/inputmethod/` | Taku input method source locks, product patches, build and keyboard tests |
| `system/proactivity/` | Proactivity app, public contract and SDK, policies and native management UI |
| `system/surfaces/` | Cross-app capsules, suggestion cards, result entries and public SDK, with rendering and action callbacks |
| `system/display/` | VirtualDisplayHost and the phone-display runtime |
| `system/overlay/` | Android resource overlay |
| `tooling/app-kit/` | On-phone generated apps, precompiled shell and experimental API tools |
| `tooling/diagnostics/` | Codec and GPU development diagnostics |
| `spec/` | Canonical data for protocol schemas, the capability list and extension declarations |
| `examples/` | SDK, phone app and plugin examples. Each README states whether an example is research, a fixture or tested |
| `examples/godmode/` | Plugin examples and the REA provider. JVM JAR and Android DEX outputs are built separately |
| `examples/codex-plugin-lifecycle/` | Native Codex marketplace and MCP fixture |
| `examples/proactivity-observer/` | Observer with its own signature and UID, built on the public SDK |

## Build and documentation

| Path | Purpose |
| --- | --- |
| `tests/` | Host protocol, build and boundary tests. Device UI tests also live in the system components they cover |
| `scripts/` | Build orchestration, dependency preparation, checks and generated data |
| `image/` | Pinned source, kernel and runtime locks, product configuration, patches, Android integration and image checks. Holds no images |
| `docs/` | Specifications and usage docs tied to the source. See the [index](README.md) |
| `docs/releases/agent-app/` | Release manifests and the stable pointer for the APK component. Not a ROM channel |
| `out/`, component `out/` | Git-ignored local builds, caches and check output |
| `.github/` | CI, issue and pull request templates, and maintainer information |

`scripts/pixel9-image.py` is the entry point for the full ROM build, and `scripts/pixel9-ota.py` is the entry point for OTA. Both read the configuration, frozen inputs and check results in `image/`.

Images, APKs and runtime packs record their checksums, sources, signatures and scope. See [versioning](versioning.md).

Hosted Taku services are not part of this repository. Site accounts, cloud secrets, user sessions and credentials stay outside the source tree.
