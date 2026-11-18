# Research: cross-app camera enhancement / replacement image pipeline · Research proposal / not yet tested
# Environment: Read-only source-location command in an AOSP checkout; not evidence of implemented functionality
# Prerequisites: True cross-app processing requires device HAL or system-service integration; CAMERA permission alone cannot change other apps' output
# Expected result: Locates the HAL stream contract; not a completed enhancement module
# This review did not automatically execute this example; define scenario-specific success criteria first.
rg --threads 2 "processCaptureRequest|configureStreams" hardware/interfaces/camera -g "*.aidl" -g "*.hal"
