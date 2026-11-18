# Query main/background displays through the SDK · Verified on an emulator (within the cited evidence)
# Environment: Host terminal, explicitly targeting emulator-5582
# Prerequisites: SDK adb transport reads display service/existing host state only, with an AVD/serial allowlist
# Expected result: Returns actual displayIds and background.active; IDs are assigned by the system
# This review did not automatically execute this example; define scenario-specific success criteria first.
node sdk/cli/taku.mjs call displays.status --transport adb --serial emulator-5582
