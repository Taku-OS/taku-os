# On-device native Java development

Prepared development toolchain: `/data/local/agentos/light-app-lab/toolchain/`; helper: `/data/local/agentos/light-app-lab/build-native.sh`. Check these exist. This helper is currently experimental and may have been prepared after flashing; its presence is not guaranteed by the image channel.

Write the application's own `AndroidManifest.xml` and `src/<package>/MainActivity.java`. The minimal helper uses Android framework classes, Java 16 or earlier, ECJ, D8, aapt2/aapt, zipalign and a dedicated development signature. It does not establish support for arbitrary Gradle/AndroidX dependencies.

Run with the actual background display ID from current Harness context:

```sh
TAKU_APP_DISPLAY=<backgroundDisplayId> sh /data/local/agentos/light-app-lab/build-native.sh <project>
```

Inspect compiler, signing and installation results. Confirm package/version and actual Activity display. Test meaningful interactions and persistence, then restore test settings. Use the same signature and `pm install -r` for a requested update; do not uninstall to hide update failures.

For an Agent-callable business interface, keep UI and API on the same business implementation. Android ContentProvider is one tested option; expose it under an explicit permission appropriate to the caller. Validate parameters and real state, because the `content` CLI can print an application exception while returning exit code zero.
