# Task and multi-window policy changes · Research proposal / not yet tested
# Environment: Read-only source-location command in an AOSP checkout; not evidence of implemented functionality
# Prerequisites: Pin an AOSP branch and design task preemption/pause events and window policy; existing CLI flags alone are insufficient
# Expected result: Read-only location of display-launch restrictions; requires separate implementation and app regression testing
# This review did not automatically execute this example; define scenario-specific success criteria first.
rg --threads 2 "setLaunchDisplayId|isActivityStartAllowedOnDisplay" frameworks/base -g "*.java"
