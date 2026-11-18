# Targeted touch, swipe and key input · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: Authorized ADB/shell; the current root host sends events with input -d; ordinary JavaScript cannot call it directly
# Expected result: Input reaches that logical display; verify through its image/target state
# This review did not automatically execute this example; define scenario-specific success criteria first.
agent_display_id=$(phone-display id)
input -d "$agent_display_id" tap 360 1210
input -d "$agent_display_id" keyevent 4
