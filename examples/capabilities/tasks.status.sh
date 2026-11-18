# Query existing task state through the SDK · Verified on an emulator (within the cited evidence)
# Environment: Host terminal, explicitly targeting emulator-5582
# Prerequisites: Explicitly target and query serially; return id/state only, excluding prompts/results, authentication and personal information
# Expected result: Returns existing TaskBroker task id/state and source; errors must be structured
# This review did not automatically execute this example; define scenario-specific success criteria first.
node sdk/cli/taku.mjs call tasks.status --transport adb --serial emulator-5582
