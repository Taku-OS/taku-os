# System version and execution identity · Verified on an emulator (within the cited evidence)
# Environment: Host terminal, explicitly targeting emulator-5582
# Prerequisites: Ordinary apps can read Build; id/getprop/getenforce examples require an authorized shell; the Taku SDK adb transport requires ADB
# Expected result: Returns platform version; direct probes read API36, Android16, arm64-v8a, uid0 and Enforcing
# This review did not automatically execute this example; define scenario-specific success criteria first.
node sdk/cli/taku.mjs call system.info --transport adb --serial emulator-5582
