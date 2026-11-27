# On-device native Java development

Prepared development toolchain: `/data/local/agentos/light-app-lab/toolchain/`; helper: `/data/local/agentos/light-app-lab/build-native.sh`. Check these exist. This helper is currently experimental and may have been prepared after flashing; its presence is not guaranteed by the image channel.

Write the application's own `AndroidManifest.xml` and `src/<package>/MainActivity.java`. Set `<uses-sdk android:targetSdkVersion="…"/>` to the device's API level (`getprop ro.build.version.sdk`) and follow that level's permission and foreground-service rules: Play Protect blocks apps built for an older Android (`INSTALL_FAILED_VERIFICATION_FAILURE`), and the helper stops before linking with `BUILD_STATUS=target_sdk_missing` or `BUILD_STATUS=target_sdk_too_old` when the manifest targets less. The minimal helper uses Android framework classes, Java 16 or earlier, ECJ, D8, aapt2/aapt, zipalign and a dedicated development signature. It does not establish support for arbitrary Gradle/AndroidX dependencies.

Run with the actual background display ID from current Harness context:

```sh
TAKU_APP_DISPLAY=<backgroundDisplayId> sh /data/local/agentos/light-app-lab/build-native.sh <project>
```

The helper reports the install on one `INSTALL_STATUS=` line, and the app is launched only after a confirmed install:

| Status | Exit | Meaning and next step |
| --- | --- | --- |
| `waiting_for_user` | (still running) | A Play Protect or installer page has focus. You cannot read it: ask the user to respond there. Their answer is not the result; wait for the final status. |
| `installed` | 0 | The package is installed with this APK's `versionCode`. |
| `cancelled` | 4 | The user or a restriction stopped it (`INSTALL_FAILED_ABORTED`, `INSTALL_FAILED_USER_RESTRICTED`). Do not retry without asking. |
| `blocked` | 3 | Package verification refused it (`INSTALL_FAILED_VERIFICATION_FAILURE` / `_TIMEOUT`). Ask the user for the reason shown, fix the cause (usually the target SDK) and rebuild. An "install anyway" answer applies to a new install, not this failed one. |
| `target_sdk_too_old` | 3 | `INSTALL_FAILED_DEPRECATED_SDK_VERSION`: raise `targetSdkVersion`. |
| `failed` | 1 | Any other `CODE=` (or `VERSION_MISMATCH`); read `build/install.log`. |

Diagnose from the status and code before retrying; repeating the same install does not change a verification result.

Inspect compiler, signing and installation results. Confirm package/version and actual Activity display. Test meaningful interactions and persistence, then restore test settings. Use the same signature and `pm install -r` for a requested update; do not uninstall to hide update failures.

For an Agent-callable business interface, keep UI and API on the same business implementation. Android ContentProvider is one tested option; expose it under an explicit permission appropriate to the caller. Validate parameters and real state, because the `content` CLI can print an application exception while returning exit code zero.
