# Build the Frida Node binding for Android ARM64

For Android 17/API 37, pair this binding with Frida 17.25.2 server/inject. REA
starts the server on loopback and talks to it through `REA_FRIDA_REMOTE`; the
17.25.2 server fixes Android 17 process discovery while the 17.23.2 binding keeps
REA's pinned Node API compatibility.

REA uses the upstream `frida` npm package as an optional dependency. The npm
package does not publish an Android ARM64 prebuild, so the binding must be built
for Bionic with the Android NDK.

## Build inputs

- Node.js 24.22.2
- Frida 17.23.2 (`npm` package)
- Android NDK r29 (`ANDROID_NDK_ROOT`)
- Host C and C++ compilers, Python 3, Git

## Rebuild

Run from the `frida` package directory inside the REA dependency tree:

```sh
npm install --package-lock-only --ignore-scripts --no-audit --no-fund
```

The published npm tarball omits `package-lock.json`, but its Meson build reads
that file. Generate it before configuring.

The npm package also omits `android_ndk_path` from the variable set used while
reading Node's Android `common.gypi`. Add it to the `default_variables` map in
`scripts/fetch-abi-bits.py`:

```python
"android_ndk_path": os.environ.get("ANDROID_NDK_ROOT", ""),
```

Then configure and compile only the native binding:

```sh
mkdir -p /tmp/frida-node-android
cd /tmp/frida-node-android
ANDROID_NDK_ROOT=/path/to/android-ndk-r29 \
  /path/to/rea/node_modules/frida/configure --host=android-arm64
ninja frida_binding.node
```

Building the default target also regenerates the TypeScript facade. On current
Node typings that target can fail with Buffer/ArrayBuffer type errors; the
already-published `build/src/frida.js` and declaration files remain usable, so
only the native target is required for the pack.

Install the result at:

```text
rea/node_modules/frida/build/frida_binding.node
```

## Verification

The resulting file must be an Android ARM64 ELF shared object with N-API v8:

```sh
file frida_binding.node
llvm-nm -D --defined-only frida_binding.node | grep napi_register_module_v1
llvm-readelf -d frida_binding.node
```

The 17.23.2 build only needs `libc.so`, `libdl.so` and `libm.so` from Android;
it does not depend on the standalone `frida-server` binary.
