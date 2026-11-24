# Conversation Activity regression

Run `:taku-agent-bridge:testDebugUnitTest` for lifecycle selection unit tests.
The instrumentation probe also invokes the **actual registered Expo module** with
MainActivity behind HomeActivity. It checks route reads, ordinary selection after a Panel launch,
stale selection refusal, and IME observer ownership without sending model requests. Use only an
isolated signed-out AVD. The probe selects synthetic conversation IDs and ends with no selection.

Build a same-source release target with
`./gradlew :app:assembleRelease -Pandroid.enableMinifyInReleaseBuilds=false -PreactNativeArchitectures=arm64-v8a`
from `system/agent-app/android`, sign it with the development platform certificate,
and install it on the explicitly selected test emulator. Reflection requires this
unminified test target; keep the normal production artifact separately.

```sh
bash system/agent-app/modules/taku-agent/tests/build.sh
adb -s emulator-5684 install -r system/agent-app/out/conversation-host-probe/ConversationHostTests.apk
adb -s emulator-5684 shell am instrument -w org.agentos.agent.bridge.tests/.ConversationHostProbe
```

Require `report.passed:true` and `INSTRUMENTATION_CODE: -1`; process exit alone is
not acceptance. The probe is a separate APK and is never packaged in the product.
