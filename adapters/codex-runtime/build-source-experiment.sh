#!/bin/bash
# Experimental source build. Does not install anything on a device.
# See source-build-experiment.md for validation status and limitations.
set -euo pipefail
# Match the Docker daemon, rather than silently emulating an ARM64 compiler on
# an x86 builder. Both native compiler hosts produce the same ARM64 target.
case "$(docker info --format '{{.Architecture}}')" in
  x86_64|amd64)
    host_arch=x86_64; platform=linux/amd64
    zig_sha=473ec26806133cf4d1918caf1a410f8403a13d979726a9045b421b685031a982 ;;
  aarch64|arm64)
    host_arch=aarch64; platform=linux/arm64
    zig_sha=ab64e3ea277f6fc5f3d723dcd95d9ce1ab282c8ed0f431b4de880d30df891e4f ;;
  *) echo 'A native x86_64 or ARM64 Docker daemon is required' >&2; exit 1 ;;
esac
work=${1:?Pass a new, empty absolute working directory}
[[ "$work" != *,* ]] || { echo 'Working directory cannot contain a comma' >&2; exit 1; }
[[ "$work" = /* ]] || { echo 'Working directory must be absolute' >&2; exit 1; }
mkdir -p "$work"
[[ -z "$(ls -A "$work")" ]] || { echo 'Working directory must be empty' >&2; exit 1; }
work=$(cd "$work" && pwd -P)
revision=979011409de0a60b52f179721948e65531d26144
git clone --depth 1 --branch rust-v0.161.0 https://github.com/openai/codex.git "$work/source"
[[ "$(git -C "$work/source" rev-parse HEAD)" = "$revision" ]] || { echo 'Unexpected source revision' >&2; exit 1; }
runtime_dir=$(cd "$(dirname "$0")" && pwd -P)
git -C "$work/source" apply --check "$runtime_dir/plugin-transaction/codex-0.161.0.patch"
git -C "$work/source" apply "$runtime_dir/plugin-transaction/codex-0.161.0.patch"
cp "$runtime_dir/plugin-transaction/transaction.rs" "$work/source/codex-rs/config/src/plugin_transaction.rs"
# The locked runtime archive was built with exactly these patch bytes; a changed
# patch is a different artifact, not a rebuild of the locked one.
python3 - "$runtime_dir/retire-panel-tools.patch" <<'PYPANEL'
import hashlib, pathlib, sys
digest = hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()
if digest != '4a6b9379df31c6feff2f95aa7d72b23061b44e0e485ffd4be2c2624baaa14699':
    raise SystemExit('Unexpected panel retirement patch')
PYPANEL
git -C "$work/source" apply --check "$runtime_dir/retire-panel-tools.patch"
git -C "$work/source" apply "$runtime_dir/retire-panel-tools.patch"
# The release tag bumps workspace.version but leaves workspace entries at 0.0.0 in Cargo.lock.
# Normalize only that known mismatch and pin both the input and resulting bytes.
python3 - "$work/source/codex-rs/Cargo.lock" <<'PYLOCK'
import hashlib, pathlib, sys
path = pathlib.Path(sys.argv[1]); data = path.read_bytes()
if hashlib.sha256(data).hexdigest() != '3206e2fdb53a3498758ce2f2972f19fae26beb261befd65cc0eb9e02efe23d52':
    raise SystemExit('Unexpected upstream Cargo.lock')
old = b'version = "0.0.0"\n'; new = b'version = "0.161.0"\n'
if data.count(old) != 160:
    raise SystemExit('Unexpected workspace version count')
normalized = data.replace(old, new)
if hashlib.sha256(normalized).hexdigest() != '4e43cd81ed341c32bde170917f38c3e79702cbc69d1f96744ab1e09f9e80a601':
    raise SystemExit('Unexpected normalized Cargo.lock')
path.write_bytes(normalized)
PYLOCK
mkdir "$work/artifacts"
base=https://github.com/openai/codex/releases/download/rusty-v8-v150.4.0
prefix=ptrcomp_sandbox_release_aarch64-unknown-linux-musl
for name in "rusty_v8_${prefix}.sha256" "librusty_v8_${prefix}.a.gz" "src_binding_${prefix}.rs"; do
  curl -fL --retry 2 "$base/$name" -o "$work/artifacts/$name"
done
python3 - "$work" "$prefix" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1]); prefix = sys.argv[2]
manifest = root / 'artifacts' / ('rusty_v8_' + prefix + '.sha256')
trusted = root / 'source/third_party/v8/rusty_v8_150_4_0_release_manifests.sha256'
expected = next(line.split()[0] for line in trusted.read_text().splitlines() if line.endswith('  ' + manifest.name))
if hashlib.sha256(manifest.read_bytes()).hexdigest() != expected:
    raise SystemExit('Untrusted V8 checksum manifest')
lines = manifest.read_text().splitlines()
names = {'librusty_v8_' + prefix + '.a.gz', 'src_binding_' + prefix + '.rs'}
if len(lines) != 2 or {line.split()[1] for line in lines} != names:
    raise SystemExit('Unexpected V8 checksum manifest entries')
for line in lines:
    checksum, name = line.split()
    if pathlib.PurePath(name).name != name or hashlib.sha256((manifest.parent / name).read_bytes()).hexdigest() != checksum:
        raise SystemExit('V8 artifact checksum mismatch: ' + name)
PY
curl -fL --retry 2 "https://ziglang.org/download/0.14.0/zig-linux-${host_arch}-0.14.0.tar.xz" -o "$work/artifacts/zig.tar.xz"
python3 - "$work/artifacts/zig.tar.xz" "$zig_sha" <<'PY'
import hashlib, pathlib, sys
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest() != sys.argv[2]:
    raise SystemExit('Zig checksum mismatch')
PY
script=$(cd "$(dirname "$0")" && pwd -P)/source-build-container-experiment.sh
docker run --rm --platform "$platform" \
  --cpus "${TAKU_CODEX_BUILD_CPUS:-4}" --memory "${TAKU_CODEX_BUILD_MEMORY:-20g}" \
  --env CARGO_BUILD_JOBS="${TAKU_CODEX_BUILD_JOBS:-1}" \
  --env TAKU_CODEX_HOST_ARCH="$host_arch" \
  --mount "type=bind,src=$work/source,dst=/src" \
  --mount "type=bind,src=$work/artifacts,dst=/artifacts,readonly" \
  --mount "type=bind,src=$script,dst=/build.sh,readonly" \
  rust@sha256:6258907abe69656e41cd992e0b705cdcfabcbbe3db374f92ed2d47121282d4a1 bash /build.sh
