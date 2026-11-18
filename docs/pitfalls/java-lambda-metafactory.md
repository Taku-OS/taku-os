# Compile fails because LambdaMetafactory cannot be resolved

[Back to pitfalls](README.md) · [Shared environment reference](emulator-reference.md)

**Environment:** SDK 36 / build-tools 36.0.0 / native app compile on the device

**Symptom:** `The type java.lang.invoke.LambdaMetafactory cannot be resolved`

**Cause:** The compile class library is missing the Android SDK lambda compile stubs. The counter button uses an ordinary lambda.

**Verified fix:** Add the classes missing from `android.classes.jar` from the official SDK 36 `core-lambda-stubs.jar`, then rebuild the same project unchanged.

1. Find `platforms/android-36/android.jar` and `build-tools/36.0.0/core-lambda-stubs.jar` from the same SDK.
2. Regenerate `android.classes.jar`. Collect only `.class` entries from these two JARs: read `android.jar` first, then add classes from the lambda stubs that are not already present, deduplicating by file name.
3. Replace `/data/local/agentos/light-app-lab/toolchain/android.classes.jar` on the device. Keep the original full `android.jar`, which is still used for resource linking and as D8's `--lib` input.
4. Leave the manifest and Java source unchanged and build with the original `build-native.sh`.

**Result:** The same counter project builds with exit code 0 and completes signing, install and opening in the background. Tapping goes from 0 to 1, and the value is still 1 after force-closing and reopening.
