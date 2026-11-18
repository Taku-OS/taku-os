# Launch existing apps or Intent actions · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: Foreground apps can send public Intents; background starts have version-specific restrictions; display targeting needs authorized shell/system policy
# Expected result: The actual DeskClock task appears on that display; verify using a screenshot or dumpsys
# This review did not automatically execute this example; define scenario-specific success criteria first.
agent_display_id=$(phone-display id)
am start --display "$agent_display_id" -n com.android.deskclock/.DeskClock
