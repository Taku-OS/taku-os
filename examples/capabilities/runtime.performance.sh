# Research: CPU/GPU/thermal management · Research proposal / not yet tested
# Environment: Read-only source-location command in an AOSP checkout; not evidence of implemented functionality
# Prerequisites: Pin device HAL capabilities and kernel interfaces; optimize only within thermal-protection limits
# Expected result: Locates system scheduling interfaces; not an existing runtime-tuning capability
# This review did not automatically execute this example; define scenario-specific success criteria first.
rg --threads 2 "SetTaskProfiles|Thermal" system/core -g "*.cpp" -g "*.json"
