#!/bin/bash
# Called inside a native Linux build container; the output is always ARM64 musl.
set -euo pipefail
host_arch=${TAKU_CODEX_HOST_ARCH:?Native compiler host architecture is required}
[[ "$(uname -m)" = "$host_arch" ]] || { echo 'Unexpected compiler host architecture' >&2; exit 1; }
rustc -vV | grep -Fx "host: ${host_arch}-unknown-linux-gnu"
export DEBIAN_FRONTEND=noninteractive
# Snapshot fixes both direct and transitive Debian package versions for this image.
rm -f /etc/apt/sources.list.d/debian.sources
cat > /etc/apt/sources.list <<'SOURCES'
deb [check-valid-until=no] https://snapshot.debian.org/archive/debian/20261029T000000Z bookworm main
deb [check-valid-until=no] https://snapshot.debian.org/archive/debian/20261029T000000Z bookworm-updates main
deb [check-valid-until=no] https://snapshot.debian.org/archive/debian-security/20261029T000000Z bookworm-security main
SOURCES
apt-get update -qq
apt-get install -y --no-install-recommends sudo ca-certificates curl musl-tools pkg-config libcap-dev g++ clang libc++-dev libc++abi-dev lld xz-utils perl make python3 cmake git
target_strip=strip
if [[ "$host_arch" = x86_64 ]]; then
  # Debian's ARM64 musl-dev contains the cross-GCC specs and wrapper. Keep the
  # compiler itself native; do not run ARM64 gcc/rustc under QEMU.
  dpkg --add-architecture arm64
  apt-get update -qq
  apt-get install -y --no-install-recommends musl-dev:arm64 gcc-aarch64-linux-gnu binutils-aarch64-linux-gnu
  target_strip=aarch64-linux-gnu-strip
fi
rustup target add aarch64-unknown-linux-musl
tar -xf /artifacts/zig.tar.xz -C /tmp
export PATH="/tmp/zig-linux-${host_arch}-0.14.0:$PATH"
export TARGET=aarch64-unknown-linux-musl GITHUB_ENV=/tmp/taku-env GITHUB_WORKSPACE=/src
# Keep downloaded crates and native-library outputs on the mounted work volume.
# Host-specific tool directories prevent mixing native and cross-built tools.
export CARGO_HOME=/src/.taku-build-cache/cargo
export RUNNER_TEMP="/src/.taku-build-cache/${host_arch}/tools"
mkdir -p "$CARGO_HOME" "$RUNNER_TEMP"
python3 - "$host_arch" "$(command -v aarch64-linux-gnu-gcc)" <<'PYHOST'
import pathlib, sys
with pathlib.Path(sys.argv[2]).open('rb') as compiler:
    header = compiler.read(20)
assert header[:6] == b'\x7fELF\x02\x01'
assert int.from_bytes(header[18:20], 'little') == {'x86_64': 62, 'aarch64': 183}[sys.argv[1]], 'C compiler must run natively'
PYHOST
# Fail before the large build if either native compiler produces the wrong ELF.
printf '#include <stdio.h>\nint main(void) { puts("taku-cross-c-ok"); }\n' > /tmp/taku-cross-smoke.c
aarch64-linux-musl-gcc -static /tmp/taku-cross-smoke.c -o /tmp/taku-cross-c
printf 'fn main() { println!("taku-cross-rust-ok"); }\n' > /tmp/taku-cross-smoke.rs
rustc --target "$TARGET" -C linker=aarch64-linux-musl-gcc /tmp/taku-cross-smoke.rs -o /tmp/taku-cross-rust
for binary in /tmp/taku-cross-c /tmp/taku-cross-rust; do
  readelf -h "$binary" | grep -E 'Machine:.*AArch64'
  ! readelf -l "$binary" | grep -q INTERP
done
# These tiny target execution probes may use binfmt on x86. The build tools do
# not: rustc's host triple and the container architecture were checked above.
[[ "$(/tmp/taku-cross-c)" = taku-cross-c-ok ]]
[[ "$(/tmp/taku-cross-rust)" = taku-cross-rust-ok ]]
echo "PASS: native $host_arch C/Rust compilers produce working static ARM64 programs"
: > "$GITHUB_ENV"
tool_path="$PATH"
if [[ "$host_arch" = x86_64 ]]; then
  # libcap's upstream helper invokes objcopy by its unprefixed name. Scope the
  # cross-binutils alias to native-library setup, not subsequent host builds.
  mkdir -p /tmp/taku-cross-tools
  ln -s /usr/bin/aarch64-linux-gnu-objcopy /tmp/taku-cross-tools/objcopy
  tool_path="/tmp/taku-cross-tools:$PATH"
fi
BUILD_CC=gcc PATH="$tool_path" bash /src/.github/scripts/install-musl-build-tools.sh
while IFS= read -r line; do export "$line"; done < "$GITHUB_ENV"
export HOST_CC=gcc HOST_CXX=g++ HOST_AR=ar
export RUSTY_V8_ARCHIVE=/artifacts/librusty_v8_ptrcomp_sandbox_release_aarch64-unknown-linux-musl.a.gz
export RUSTY_V8_SRC_BINDING_PATH=/artifacts/src_binding_ptrcomp_sandbox_release_aarch64-unknown-linux-musl.rs
export CARGO_NET_GIT_FETCH_WITH_CLI=true AWS_LC_SYS_NO_JITTER_ENTROPY=1 STABLE_GIT_COMMIT=979011409de0a60b52f179721948e65531d26144
cd /src/codex-rs
cargo build --locked --target "$TARGET" --release --bin bwrap
"$target_strip" --strip-debug --strip-unneeded "target/$TARGET/release/bwrap"
export CODEX_BWRAP_SHA256="$(sha256sum "target/$TARGET/release/bwrap" | cut -d ' ' -f 1)"
cargo build --locked --target "$TARGET" --release --bin codex
"target/$TARGET/release/codex" --version
cp "target/$TARGET/release/codex" "target/$TARGET/release/codex-stripped"
"$target_strip" --strip-debug --strip-unneeded "target/$TARGET/release/codex-stripped"
