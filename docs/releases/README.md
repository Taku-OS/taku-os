# Taku OS Release Manifests & Unified Automation

- [`latest.json`](latest.json): machine-readable manifest for the latest published Pixel 9 (`tokay`) preview release.
- `tokay-<build>.json`: immutable per-build manifests recording Full OTA CDN URLs, matching GitHub Release `recovery.zip` boot stacks, and partition SHA-256 digests.

## Single Unified Pipeline

1. **Cloud Builder Control & Release Sync**: [`.github/workflows/taku-cloud-control.yml`](../../.github/workflows/taku-cloud-control.yml) handles both GCP WIF lifecycle (`status` / `start` / `stop` via [`scripts/cloud-builder-control.py`](../../scripts/cloud-builder-control.py)) and automated/manual GitHub Release synchronization (`sync-release` via [`scripts/publish-rom-release.py`](../../scripts/publish-rom-release.py)).
2. **Builder Disk Retention GC**: [`scripts/cloud-builder-prune.py`](../../scripts/cloud-builder-prune.py) enforces bounded `/mnt/taku-build` disk usage on `taku-os-builder` while preserving pinned OTA baselines and active OverlayFS mounts.
