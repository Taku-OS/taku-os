# Clean environment preparation

This directory supplies the previously missing builder definition. `Dockerfile` pins the Ubuntu base-image digest and repo-launcher checksum. APT packages come from Ubuntu repositories and are not yet pinned to historical snapshots, so byte-for-byte reproducibility is not claimed.

Linux amd64 Docker is required (Apple Silicon uses amd64 emulation). Complete Android source, kernel, and outputs require substantial disk space and memory; see `image/build-config.json` for resource settings. Use a dedicated build machine or a new Docker volume.

```sh
docker build --platform linux/amd64 -t taku-rom-builder:local image/builder
export TAKU_BUILDER_IMAGE=$(docker image inspect --format '{{.Id}}' taku-rom-builder:local)
docker volume create agentos-lineage23-source
docker volume create taku-pixel9-lineage23-source
python3 scripts/doctor.py --rom --network
```

`docker volume create` does not clear existing volumes. If these names belong to an existing build, continue using its original configuration without deleting or resetting them. The build controller performs its own integrity checks on reference caches; empty caches allow a cold sync.

`doctor` checks only the environment and anonymous URL reachability. It does not download complete runtimes, read login state, or operate devices. Anonymous 404s must be resolved with distributable inputs or source-build paths; current customized Codex and Agent UI release assets still require private-repository read access. While the repository remains private, this cannot count as passed public cold-build acceptance.

Next prepare each module's assets following `image/README.md`. Source license changes alter source-file hashes; existing released-ROM input locks and packages must not be overwritten. A new candidate must rebuild affected components from new source and freeze its new inputs with each module's receipt/lock tools.

This directory establishes a reviewable builder definition; complete-ROM cold builds and new-device acceptance are still executed and recorded separately.
