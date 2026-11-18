# Research: low-power voice wake-up · Research proposal / not yet tested
# Environment: Read-only source-location command in an AOSP checkout; not evidence of implemented functionality
# Prerequisites: The target chip must support soundtrigger and a deployable model; implement HotwordDetectionService or a vendor path
# Expected result: Locates hardware wake-up services; an emulator cannot establish a low-power DSP path
# This review did not automatically execute this example; define scenario-specific success criteria first.
rg --threads 2 "SoundTrigger" hardware/interfaces/soundtrigger -g "*.aidl" -g "*.hal"
