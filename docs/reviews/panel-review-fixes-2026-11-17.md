# Panel review fixes — 2026-11-17

Branch: `vela/panel-card`. Production source commit:
`7732c3e9b2a897f6a86a80b001a84552bfdb24ed`.

## Replaced-source writes

Card-view writes and panel publication use the same native runtime transaction.
After publication, `card-view/apply` rejects a new write to the superseded matter
with `STALE_SOURCE`, before changing state or creating a success receipt. The
supersession tombstone also covers a first edit when no saved state existed to
migrate. A previously committed request still replays its original receipt.
The client preserves its in-memory pending input on rejection and does not retry an
unchanged rejected change. It does not blindly apply an old edit to a new schema.
The preinstalled open-access documentation describes the same error for agents.

Tests cover late writes after migration and restart, late first writes,
unchanged replacement state, absent success receipts, committed receipt replay,
and retaining a rejected client draft.

## Out-of-order pin state

Matter snapshots only replace pin state at an equal or newer `listRevision`.
Pin/unpin mutation receipts use the same comparison. Tests cover a newer pin
followed by an old unpin snapshot, a newer unpin followed by an old pin snapshot,
and delayed mutation receipts arriving after newer directory notifications.

## Runtime inputs and build architecture

The unavailable Codex archive was rebuilt from the pinned upstream revision and
the existing transaction/panel-retirement patches. The final build uses native
x86_64 Rust/C compilers targeting ARM64 musl, with 24 CPUs, 96 GiB and 16 Cargo
jobs. Small static ARM64 C/Rust execution probes passed before the full build;
the Codex release Cargo invocation completed in 12m 02s. An earlier ARM64
compiler-emulation attempt was stopped, with its files retained and none of its
unfinished outputs used. Source, download and build caches were preserved.

The lock binds the newly built Codex archive (`db210eab…`, 102398579 bytes), its
executable (`41fa7b41…`), and the Agent APK (`f6a81e12…`, 50941646 bytes).
Both artifacts are published in the versioned [runtime release](https://github.com/Taku-OS/taku-os/releases/tag/panel-runtime-20261117-7732c3e).
A repository-lock regression check rejects missing download sources and
`unpublished` entries. The real preparation script downloaded both release
assets into a newly created empty directory. Size/hash checks, extracted ARM64
executable hash and ELF identity, notices, APK package/version and signing
certificate all matched the lock. The [download receipt](panel-runtime-download-2026-11-17.json)
records the verified bytes; this was not a populated-cache check.

## Verification

- The complete root `npm run verify` suite passed with the final artifact lock.
- 417 MatterRuntime host assertions passed using the production state/store logic
  with host Android/storage adapters.
- 388 Agent JavaScript tests passed, along with typecheck and lint.
- 23 root JavaScript tests, protocol checks, 4 raw Android interface tests,
  8 Agent ROM binding tests and 8 image snapshot tests passed.
- Both Assistant and Agent APKs built on the existing cloud builder in a new
  scoped working directory. Its 441 Agent source inputs matched the local source
  manifest after the build. npm dependencies were installed with scripts disabled;
  only the reviewed Markdown native-resource installer and project build scripts
  ran explicitly.
- Both APKs installed on `emulator-5684`, isolated hardware-GPU AVD
  `Taku_Panel_Migration_20261117`. HomeActivity rendered, and the ordinary-UID,
  zero-permission probe/provider preflight, directory reads and malformed
  card-view write validation passed. The user's `emulator-5590` was untouched.
- The new stripped Codex executable passed `--version` and app-server
  initialization on the same isolated ARM64 AVD, with a fresh temporary HOME
  and no copied account data. Runtime notices were verified during packaging.
- Source-check CI passed all eight jobs for implementation/artifact commit
  `61f1fdd3bd095754d8d09ad6b3ba7a5df69195f5`, on both
  [push](https://github.com/Taku-OS/taku-os/actions/runs/44076610093) and
  [pull request](https://github.com/Taku-OS/taku-os/actions/runs/44076619146).

Evidence is retained under `out/panel-fixes-20261117/` locally and
`/mnt/taku-build/runs/panel-fixes-20261117/` on the builder. The isolated device has
no authenticated live Agent source. This does not claim live AI continuation,
filled-card GUI race acceptance, Pixel hardware acceptance or a complete ROM
build. The concurrency cases above were verified by the focused regression tests.
