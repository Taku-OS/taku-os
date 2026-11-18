# Faster Taku OS builds and cache maintenance on Mac mini

The host is the home M4 Pro Mac mini with 14 cores and 64 GiB of memory; the target is LineageOS 24 and Android 17 for Pixel 9. In the near term, keep Apple Virtualization and Rosetta, preserve source and build output in Linux named volumes, and prioritize resource contention, stable inputs, and incremental module workflows. Changing container software or adopting a native ARM64 toolchain requires separate validation. Startup speed or results from older Android versions cannot predict this project's full-build duration.

This investigation performed only online research, public-post reads through the X API, and read-only remote checks. It did not modify container resources, running snapshots, source volumes, or build settings. Automation remains paused. The [baseline record](android17-baseline-2026-10-20.md) and build logs remain authoritative for this round's current status.

**Configuration already in use**

Read-only observations from 12:18 to 12:23 on October 21, 2026 confirmed Docker Desktop 4.80.0 and Engine 29.6.1. The Linux VM provides 14 vCPUs, with 40 GiB configured memory, about 39.1 GiB actually visible, and 4 GiB configured swap. The current build container has a 10-core CPU quota, a 36 GiB hard memory limit, and a 40 GiB combined memory-plus-swap limit. Ninja concurrency remains 8; Soong uses Go runtime concurrency 4 and a 24 GiB soft memory limit.

The large source tree and its output are in the Docker named volume at /src. /taku is a read-only input snapshot and /results holds status and exports; two old cache volumes are mounted read-only at /previous and /reference. This layout already avoids putting the entire Android source tree on a macOS shared-file path. [Docker's volume documentation](https://docs.docker.com/engine/storage/volumes/) identifies volumes as suitable for high-performance I/O and persistent data.

The old Taku emulator was paused during the previous performance diagnosis, preserving its running state. Earlier samples showed it competing for several CPU cores. Power observations show powermode 2 and automatic sleep 0; pmset reported no historical thermal or performance warnings, which does not mean frequency and temperature under prolonged full load have been measured. M4 Pro Mac mini supports the system's High Power Mode, but Apple's description emphasizes thermal headroom and promises no percentage improvement for Android builds. [Apple power-mode documentation](https://support.apple.com/en-us/101613)

**Ranked by benefit and maintenance cost**

| Priority | Adjustment | Specific benefit on this machine |
| --- | --- | --- |
| First | Keep stable source volumes, output directories, and input snapshots | Preserve objects, kernels, dependency graphs, and logs; reduce invalidation caused by directory changes, tool versions, and unnecessary timestamp changes |
| First | Pause confirmed idle heavy workloads during dedicated builds | Prevent emulators, software graphics rendering, and concurrent browser work from competing for CPU, memory, and thermal headroom |
| First | Tune concurrency by stage and measure complete elapsed time | Soong, Kati, and Ninja have different limits; simply setting -j to the total core count is insufficient |
| First | Build specific modules or APKs for everyday assistant development | UI and input testing do not require target-files, a full OTA, and system images every time |
| Second | Try higher concurrency after increasing available memory | Reduce host pressure first, then change VM, container, and task counts one at a time; choose settings using actual throughput |
| Second | Evaluate ccache for repeated C/C++ compilation | It saves work only on cache hits; it does not accelerate the first cold-cache build or cover Java, Kotlin, Soong graph construction, and every packaging action |
| Third | Run isolated OrbStack or Colima comparisons | Compare compatibility and real action durations; startup and file-sharing benchmarks are not this project's full ROM results |
| Experimental | Native ARM64 host toolchain | Android 17 experiments exist, but porting and acceptance work remain; it cannot directly replace the current release build |

AOSP officially supports building specific modules. m nothing can validate build-structure parsing alone, but it also writes build metadata, so run it only when no other build is writing or in an isolated copy. [AOSP build instructions](https://source.android.com/docs/setup/build/building)

**Virtualization and filesystems**

Keep Apple Virtualization and Rosetta for amd64 builds. Docker's current documentation explicitly lists Docker VMM as lacking Rosetta support and warns that amd64 emulation is slow. A newer name or general performance claims do not justify switching directly. [Docker VMM known issues](https://docs.docker.com/desktop/features/vmm/#known-issues)

Keep source and output in Linux named volumes, with host sharing for lightweight inputs and results. The existing /src already meets this requirement; switching to VirtioFS will not suddenly accelerate every build action in that named volume. Docker's VirtioFS setting governs file sharing between the Mac and containers. [Docker settings](https://docs.docker.com/desktop/settings-and-maintenance/settings/)

OrbStack and Colima can also execute x86 Linux tools through Rosetta. OrbStack's public comparison identifies Docker 4.37.2 from January 2025, so it cannot evaluate the currently installed 4.80.0. Container-startup or bind-mount multipliers also cannot be applied to the current CPU-intensive workload whose source is in named volumes. [OrbStack architecture](https://docs.orbstack.dev/architecture), [comparison and version date](https://docs.orbstack.dev/compare/docker-desktop), [Lima Rosetta documentation](https://lima-vm.io/docs/config/vmtype/vz/#rosetta)

Apple also provides a Rosetta optimization for the TSO memory model that requires cooperation between the Linux guest kernel and translator. Whether Docker's current kernel supports it has not been verified; do not record it as a missing optimization or manually replace the Docker kernel currently supporting the build. [Apple TSO documentation](https://developer.apple.com/documentation/virtualization/accelerating-the-performance-of-rosetta?changes=la)

**Resource ranges for the next experiment**

The table below gives an experiment order after the current candidate is complete and host background load is reduced, not measured optimal values. Change one factor at a time and retain the original resource settings and rollback records. Do not apply these changes during the current build.

| Parameter | Current | Later candidate range |
| --- | --- | --- |
| VM memory | 40 GiB; about 39.1 GiB visible | Try 44 GiB first; evaluate 48 GiB when host pressure is low |
| Container memory | 36 GiB | Evaluate 40–44 GiB alongside the VM, leaving headroom for the VM system |
| Container CPU | 10 cores | Compare 10 and 12 cores, preserving host scheduling headroom |
| Ninja concurrency | 8 | Keep 8 while validating resource changes, then compare 10 separately |
| Soong Go runtime | Concurrency 4; 24 GiB soft limit | Retain the validated settings for the first round, then evaluate separately using graph-construction peaks and durations |
| High-memory task pool | Selected automatically upstream | Preserve the safeguard; do not force it to equal the total task count |

The 64 GiB of physical memory must serve macOS, the Linux VM, and other applications together. Expanding the VM to 48 GiB leaves only about 16 GiB of nominal host budget. If the host continuously compresses memory or swaps, a larger VM may reduce overall throughput. Do not start a second large VM or full build and conclude that resources are sufficient from individual container memory figures alone.

The pinned Soong high-memory task pool calculates its default budget at roughly 8 GiB per task, so increasing total concurrency does not guarantee a proportional increase in high-memory actions. [Pinned configuration source](https://github.com/LineageOS/android_build_soong/blob/183e59578606687d66096cbce86cb50c27f4e9d7/ui/build/config.go#L1588)

Kati's slow path primarily generates Make rules; Ninja concurrency has no direct effect on it. First check whether no-change runs rebuild the graph because of shell output, dynamic time values, or input drift. Soong already provides build.trace.gz, critical dependency chains, and Go CPU/memory profiling entry points. Use these records to locate long stages before deciding whether to raise concurrency. [Pinned Soong performance documentation](https://github.com/LineageOS/android_build_soong/blob/183e59578606687d66096cbce86cb50c27f4e9d7/docs/perf.md)

**Cache strategy**

Preserve the same product and variant, toolchain, output directory, object files, Ninja .ninja_log and .ninja_deps, and both Git reference volumes. Ninja reruns actions based on input timestamps and command changes. Putting a new tool at an old path or manually adjusting timestamps cannot replace validity checks. [Ninja dependency and command logs](https://ninja-build.org/manual.html#ref_log)

Resuming the same candidate should preserve its build identity; only formally producing B creates a new version identity. Continue to obey existing resume and rebuild input checks. Do not bypass new-input, kernel, or signature verification to reduce graph-construction work. Do not run clean, clobber, or a second m command on the active primary source volume.

The pinned LineageOS 24 ccache integration requires USE_CCACHE and CCACHE_EXEC and no longer bundles the ccache binary. Source comments also note that ordinary incremental builds may already be more suitable. Add a pinned Linux ccache version, persistent volume, and actual wrapper only in a later independent experiment, checking real compiler commands and ccache statistics. Adding a wrapper changes some compile commands and may initially trigger rebuilds; an empty cache does not speed up the first build. Protect the existing output cache before adding another cache footprint. [Pinned ccache integration](https://github.com/LineageOS/android_build/blob/90d006387398ae6899145e6531a304500cfa0f70/core/ccache.mk), [how ccache works](https://ccache.dev/manual/latest.html)

The host currently has about 338 GiB free. Do not add another 100–250 GB cache or full source copy without a capacity calculation. Downloaded-object caches, build outputs, compiler-result caches, and incremental phone OTAs are different layers. Remote caching or distributed builds also require matching toolchains, input packaging, and worker nodes; enabling one variable on a single machine does not provide another machine's computing power. [FOSDEM 2026 Android build performance talk](https://fosdem.org/2026/schedule/event/KX88W8-aosp-build/)

**Actual progress on native ARM64**

In June 2026, Actinis published an ARM64 Linux host build of Android 17 / SDK 37: a cold build of the same emulator target took 2 hours 13 minutes on DGX Spark, compared with 1 hour 45 minutes on an i9-14900K with 128 GB RAM. The approach adds host tools, fixes the Rust sysroot, allows missing dependencies, skips ABI checks, and makes one LFI validation failure nonfatal. It demonstrates that the port is feasible, not that a Pixel 9 LineageOS 24 release image satisfies the same conditions. This route needs a separate toolchain and complete acceptance experiment and must not enter the current A/B baseline. [Author's article](https://actinis.io/blog/building-aosp-on-arm64)

Ika provides readable ARM64 host patches and toolchain organization covering Soong, Make, Go, Clang, Rust, JDK, Trusty, and more. Its documentation uses a LineageOS 23.2 desktop target and includes compiler-version changes, so it cannot be applied directly to this project's pinned Android 17 source. [Ika ARM64 host-build documentation](https://github.com/DesktopECHO/ika/blob/main/BuildingOnARM64.md)

Keeping macOS and using native tools in an ARM64 Linux VM is another experimental host option; installing bare-metal Linux on the Mac is not a prerequisite. But changing a container base image to ARM64 only makes some system tools native. If x86 Clang, Go, or JDK still runs, hotspots may remain in the translation layer. A complete port must align the toolchain, host ABI, and build graph. Asahi's M4 Pro support table still lists GPU and cpufreq as TBA and NVMe as WIP, making it unsuitable for an immediate OS replacement on the current build host. [Asahi M4 support status](https://asahilinux.org/docs/platform/feature-support/m4/)

One author's Apple Container measurement used an M3 Max, 128 GB, and Android 14: a single-module build took about 27 minutes 31 seconds cold and 17 minutes 18 seconds warm. It supports the module-iteration and persistent-cache workflow. The author's full-build duration is only an estimate, and the setup relaxes artifact-path checks. It therefore cannot establish that it is faster than the current environment or justify copying those acceptance bypasses. [Module-build measurements and configuration](https://chauyan.dev/en/blog/running-aosp-builds-on-mac-with-apple-container-en)

**Original X posts and applicability**

Successful X API retrieval in this investigation covered 2020–2026 and yielded 76 deduplicated leads. The posts below directly inform engineering judgments. The remaining material included emulator operation, application builds, and marketing, none of which was used as evidence for faster ROM builds.

| Author and date | Original post | Supported conclusion |
| --- | --- | --- |
| kdrag0n, February 2023 | [AOSP host tools and short-process overhead](https://x.com/kdrag0n/status/1625177717749731330) | Host-tool and translation overhead existed historically; the AOT state at that time is not a conclusion about the 2026 implementation |
| mikegapinski, May 2022 | [One initial AOSP build on M1](https://x.com/mikegapinski/status/1530219788160618496) | A Mac mini can perform real builds; an older version, different device target, and unpinned comparison cannot estimate Android 17 results |
| Docker, December 2023 | [Rosetta optimization release](https://x.com/Docker/status/1737186792472825969) | Rosetta is an established acceleration path; these performance claims do not compare Rosetta with native x86 for this project |
| jedisct1, November 2024 | [Link to Apple TSO optimization documentation](https://x.com/jedisct1/status/1854457503204909562) | Check VMM and guest-kernel capabilities; TSO is not an ordinary build environment variable |
| kdrag0n, June 2023 | [OrbStack Rosetta compatibility fix](https://x.com/kdrag0n/status/1672593130422665217) | Worth considering for a compatibility comparison; the test covers its own project, not Taku ROM |
| Actinis, June 2026 | [Building Android 17 natively on ARM64](https://x.com/ActinisHQ/status/2070473473038614598) | Host porting merits further investigation, read together with the original article's target and acceptance limitations |
| chardidathing, June 2026 | [Slow incremental builds despite 32 threads and ccache](https://x.com/chardidathing/status/2069341290853171647) | Thread count and ccache alone do not explain throughput; stages still need diagnosis. This is not a controlled performance benchmark |

**How to validate the next round**

After completing and archiving the current candidate, pin the same source, toolchain, device target, and acceptance scope. Compare no-change checks, specific-module incremental builds, and complete packaging in an isolated test directory. Change only one of VM memory, CPU quota, task count, or container implementation at a time. Record Soong/Kati durations, Ninja critical path, actual CPU use, CPU throttling, container and host memory pressure, swapping, I/O wait, peak memory, and time spent on failures and retries separately.

Judge results by wall-clock duration for complete stages and successful artifacts, not startup speed, action percentages, or brief CPU-utilization samples. Repeat warm-cache tests at least once. Do not count a configuration as an optimization success if more memory causes sustained host swapping, higher concurrency triggers OOM, or new tools skip checks.

The near-term order is to stabilize the existing build and caches, adopt incremental module workflows, then test resource budgets one at a time in the next round. OrbStack, Colima, and native ARM64 remain independent experiments. They do not require interrupting the current build or automatically resuming follow-up tasks.
