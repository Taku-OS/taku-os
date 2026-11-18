# On-device task execution and state queue · Verified on an emulator (within the cited evidence)
# Environment: Host terminal, explicitly targeting emulator-5582
# Prerequisites: Use the existing single TaskRequests queue with init agentos_codex running; do not create a second task queue
# Expected result: The SDK reads the latest task state; reading state does not submit a new AI task
# This review did not automatically execute this example; define scenario-specific success criteria first.
node sdk/cli/taku.mjs call tasks.status --transport adb --serial emulator-5582
