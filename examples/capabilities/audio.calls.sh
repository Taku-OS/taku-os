# Research: both sides of cellular / VoIP calls · Research proposal / not yet tested
# Environment: Read-only source-location command in an AOSP checkout; not evidence of implemented functionality
# Prerequisites: Select the device and routing first, then prove non-silent RX/TX in test calls without reading real calls
# Expected result: Locates call/routing definitions; source research only, producing no audio tracks
# This review did not automatically execute this example; define scenario-specific success criteria first.
rg --threads 2 "VOICE_CALL|VOICE_UPLINK|VOICE_DOWNLINK" frameworks/av -g "*.cpp" -g "*.h"
