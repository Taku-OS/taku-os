# Codex 0.161.0 source-build experiment

`build-source-experiment.sh /absolute/empty/workdir` is an experimental, device-independent ARM64 Linux musl build attempt. It uses a compiler container matching the Docker daemon architecture: native ARM64 on ARM64 builders, or native x86_64 compilers with an ARM64 target toolchain on x86 builders. It clones tag `rust-v0.161.0`, verifies the expected commit, pins the Rust container image by digest and Debian repositories to a dated snapshot, checks the upstream V8 checksum manifest against the pinned source and verifies its two artifacts, and checks the matching host archive of Zig 0.14.0. The release tag's `Cargo.lock` has 160 workspace packages at `0.0.0` although `workspace.version` is `0.161.0`. The script checks the original lockfile hash, changes exactly those entries, checks the resulting hash, then builds with `--locked`; the observed diff contains no other changes. It uses upstream `install-musl-build-tools.sh` and Cargo's lockfile. The binary is produced under `<workdir>/source/codex-rs/target/aarch64-unknown-linux-musl/release/codex`; `codex-stripped` is the packaging input. It does not change the ROM, launcher or an Android device.

The x86 build uses Debian's `musl-dev:arm64`, native cross-GCC and cross-binutils. It checks the Rust host triple and the C compiler ELF architecture, then builds and runs small static ARM64 C/Rust probes before building the runtime. On x86, only target execution probes (including `codex --version`) need ARM64 binfmt/QEMU support; the compilers run natively. `libcap` uses a native `BUILD_CC`, with its unprefixed `objcopy` scoped to the ARM64 cross-tool during library setup. Crate downloads and native-library outputs persist under `<workdir>/source/.taku-build-cache/`, alongside the existing Cargo `target` directory. Preserve the entire work directory on failure. Switching compiler host architecture can invalidate Cargo fingerprints; old ARM64 compiler outputs must not be assumed reusable by an x86 compiler.

On the existing large x86 cloud builder, use `TAKU_CODEX_BUILD_CPUS=24 TAKU_CODEX_BUILD_MEMORY=96g TAKU_CODEX_BUILD_JOBS=16 bash adapters/codex-runtime/build-source-experiment.sh /absolute/empty/workdir`. The conservative defaults remain suitable for smaller builders; they are not the settings used for the 2026-11-17 native cloud rebuild.

A clean run of the complete script succeeded and printed `codex-cli 0.161.0` on ARM64 Linux. A stripped binary from that run also printed its version and completed app-server `initialize` on the dedicated ARM64 Android 16 AVD, using a temporary home. An earlier build using then-current Debian packages also succeeded, but its stripped SHA-256 differed from the snapshot build (`3147cf5b…` versus `6a8caf47…`); bit-for-bit reproducibility is not established. Those historical runs did not validate packaging, wider runtime tests or boot integration. The selected asset is separately locked by checksum; consult the latest lifecycle review for its acceptance evidence. The build depends on network access to Debian, GitHub, Zig and Cargo dependencies and may use substantial CPU, memory and disk. On failure the provided working directory retains the source and artifacts for inspection; it must be empty on the next run.

The source build applies the checked-in Taku plugin transaction and shared configuration lock patch, then the checked-in panel retirement patch. It does not implement MCP isolation. Android isolation and adversarial tests remain necessary before treating third-party MCP execution as isolated.

A basic device smoke test can be run with `python3 adapters/codex-runtime/test-source-runtime.py emulator-5668 /absolute/path/to/codex`. It requires the dedicated lifecycle AVD, creates a fresh temporary runtime home, checks `--version` and app-server initialization, and cleans up its temporary files. It does not test plugins, MCP isolation, tool execution or boot persistence. Run it only after the binary is fully written.

## 2026-11-13 public-input check

The latest [build receipt](../../docs/reviews/open-source-inputs-2026-11-13/codex-source-build.json) records a new run from public upstream/dependencies with the checked-in transaction patch. The first 8 GiB / four-job attempt was killed; resuming its preserved source/output with 20 GiB and one job completed successfully. Defaults now use 4 CPUs, 20 GiB and one Cargo job, overridable with `TAKU_CODEX_BUILD_CPUS`, `TAKU_CODEX_BUILD_MEMORY` and `TAKU_CODEX_BUILD_JOBS`. This is not a claim that the successful retry started with an empty compilation cache.

The stripped binary passed version and app-server initialization checks on the dedicated API 37 ARM64 emulator with a fresh temporary HOME. `--avd EXACT_AVD_NAME` permits an explicitly named isolated AVD while retaining the emulator-only and qemu checks. `package-runtime.py` includes verified upstream notices and refuses to replace an existing archive. Dependency notices, full plugin invocation, boot persistence and a complete ROM build remain separate checks. Existing release locks were not changed.

## 2026-11-15 panel retirement rebuild

The rebuilt archive adds the retirement patch input, and packaging includes the verified upstream notices. The upstream revision, container digest, Debian snapshot, V8 artifacts, Zig archive and `Cargo.lock` normalization are unchanged from the run recorded above.

| Input | Value |
| --- | --- |
| Upstream revision | `979011409de0a60b52f179721948e65531d26144` (`rust-v0.161.0`) |
| `plugin-transaction/codex-0.161.0.patch` | `4f1179767f02335cee595f7d9b6f128d7d3b6b29ecc26fe74dc4ce466d13e199` |
| `plugin-transaction/transaction.rs` | `2fed31d8a2834b58b5a5306c185689c43cb2f180e86cb6597763338b50eb1468` |
| `retire-panel-tools.patch` | `4a6b9379df31c6feff2f95aa7d72b23061b44e0e485ffd4be2c2624baaa14699` |

The retirement patch removes the `taku_panel` dynamic tool namespace from thread start and from resumed sessions, so a frozen session no longer keeps the retired native tools. `build-source-experiment.sh` checks the patch bytes before applying them, and the applied tree used for this binary reverse-applies that patch exactly.

| Output | Value |
| --- | --- |
| `codex-aarch64-unknown-linux-musl` | `81cf03239d9f7a1d6e2da872157a8b9582023b233d36ff33601337c83509a569`, 252899432 bytes |
| `codex.tar.gz` (`package-runtime.py`) | `a6aecd99aa0fd7aa0d40cfe24621d23463e5e8c2463238d12d96ec6c524bcecc`, 102706132 bytes |

The historical lock bound those bytes, the extracted executable hash and the source inputs. That archive was not published and a clean checkout could not fetch it. The native cross-build below replaces that unavailable input with newly verified bytes. `.sha256` reuse in the device launcher keys on the archive hash, so an image upgrade extracts a fresh runtime directory instead of reusing the previous extraction.

## 2026-11-17 native cross-build

The existing x86 cloud builder used native x86_64 Rust 1.95.0 and C compilers with the ARM64 musl target, 24 CPUs, 96 GiB and 16 Cargo jobs. The earlier ARM64 compiler-emulation attempt was stopped; none of its unfinished outputs are published. The new build passed the native-compiler architecture checks and static ARM64 C/Rust execution probes. The Codex release Cargo invocation completed in 12m 02s, followed by a successful `codex-cli 0.161.0` execution check. This duration excludes initial toolchain setup. The first cross-toolchain setup exposed libcap's unprefixed `objcopy`; the final recipe scopes the correct cross-binutils alias to that setup and builds libcap's host helper with native GCC.

| Output | SHA-256 | Bytes |
| --- | --- | --- |
| Stripped ARM64 executable | `41fa7b41b62e3f128177bcadd13f704e044db0807c41d77e66dcc580d8595f76` | 252964960 |
| Runtime archive | `db210eab09391d218e948f173c39df350173c285fd0d9284a3289ea7e7851a71` | 102398579 |

The upstream revision and both runtime patches remain unchanged. The lock binds the updated host-aware build scripts and actual output hashes. See the [panel review verification record](../../docs/reviews/panel-review-fixes-2026-11-17.md) for device and download acceptance scope.
