# Audio Engine ROM delivery

The userdebug/eng Taku product includes the platform-signed Audio Engine, its
six approved privileged audio and video-capture permissions, the assistant's existing
`MODIFY_AUDIO_ROUTING` request, the CLI bridge, English/Chinese Zipformer
and multilingual Kokoro resources, and the Silero VAD model (`vad/silero_vad.onnx`,
MIT) used by Live Talk's cold standby. Runtime microphone/notification permission
prompts are unchanged. No credentials or cloud subscription are bundled.

## Prepare the frozen inputs

```sh
bash system/audio-engine/build.sh --with-tts
python3 system/audio-engine/prepare-rom.py --write-lock
python3 system/audio-engine/prepare-rom.py
```

Use the same JDK/SDK environment as the component build (`JAVA_HOME`,
`ANDROID_SDK_ROOT`, optional `TAKU_BUILD_TOOLS`). The build records its source
hashes and output hashes; sealing refuses changed sources or stale outputs.
Review and commit `rom-input-lock.json`. `models/rom-files.mk` and
`prebuilt/Android.bp` are generated install manifests and remain ignored local
build inputs. APKs, JARs and models are also ignored; recreate all of them on a cloud
build host before taking a Pixel image snapshot. The snapshot explicitly
includes all sealed files and validates their source and content hashes. A
rebuild that changes bytes requires a reviewed lock refresh, not a disabled
check.

`prebuilt/` is about 559 MB, including approximately 523 MB of unpacked model
resources. The pinned Kokoro release asset is ID 677613419; its SHA-256 was
checked against the official sherpa-onnx GitHub release asset digest as well as
the downloaded archive. Models and upstream notices are copied with explicit
paths. The upstream voice filename containing a space is sealed from the
original input but installed as `Mr_serious`, because Android's build graph
does not safely represent spaces in generated install targets. Unknown extra
files are rejected.

## Installed paths

- APK: `/system_ext/priv-app/TakuAudioEngine/TakuAudioEngine.apk`
- Models and per-file manifest: `/system_ext/etc/taku-audio/models/`
- CLI bridge: `/system_ext/etc/taku-audio/taku-audio-bridge.jar`
- Engine permission policy: `/system_ext/etc/permissions/privapp-permissions-org.agentos.audio.xml`
- Assistant routing policy: `/system_ext/etc/permissions/privapp-permissions-org.agentos.assistant.audio.xml`

JNI libraries are stored uncompressed in the APK, with 16 KB ZIP and ELF
alignment and `extractNativeLibs=false`. Android loads them directly without
installation-time extraction. Models are read from the read-only system
partition; no first-boot copy into application data is needed. When the system
manifest is present, private development models never override it. Without the
ROM pack, development installs retain their existing private model path.
The CLI prefers the system bridge and falls back to its development path.

The product build verifies the installed APK contents, model hashes, bridge,
permission policy and packaging. Target-files/OTA verification applies the same
checks against the frozen input lock and permits product re-signing only:
non-signature APK entries must remain identical.

## Local acceptance and its boundary

`tests/rom-device.py --serial emulator-N --require-readonly --output PATH`
checks an explicitly disposable `Taku_Audio_ROM_*` AVD: system APK path, every
sealed asset, five engine privileges, init-started Broker, absence of private
models and a read-only system_ext without overlay mounts. Install only the test
APK and run `org.agentos.audio.tests/.RomModelsProbe` to exercise real JNI,
English/Chinese TTS and recognition against the bundled weights. The probe
uses synthetic speech without a microphone or cloud service and does not
approve recognition or speaker quality.

Use a new AVD/data directory for clean-start acceptance and repeat after reboot
with a different kernel boot ID. The local SDK-image fixture is not a Soong
Pixel ROM build. The full Pixel 9 build, central privileged-permission audit on
its actual framework, OTA validation and physical-device acceptance must run
on the forthcoming cloud build host. Do not mark those gates passed based on
component tests or an SDK emulator.

The API 36 AOSP SDK fixture does not define the feature-flagged
`BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION` permission at runtime. For that
fixture only, pass `--allow-unsupported-permissions` and instrumentation
`-e allowUnsupportedPermissions true`. Reports list unsupported permissions
and set `allPrivilegedPermissionsVerified=false`; a defined but denied permission
still fails. Do not use this exception for target-ROM acceptance. The normal
strict defaults require all six permissions.
