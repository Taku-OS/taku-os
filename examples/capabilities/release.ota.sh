# Research: ROM builds, OTA and recovery · Research proposal / not yet tested
# Environment: Read-only source-location command in an AOSP checkout; not evidence of implemented functionality
# Prerequisites: Choose an unlockable model and device/vendor trees; establish reproducible build, signing, upgrade and rollback experiments; never distribute userdata login state
# Expected result: Inspect packaging configuration in the pinned device source; this does not establish an available ROM release
# This review did not automatically execute this example; define scenario-specific success criteria first.
rg --threads 2 "PRODUCT_PACKAGES|PRODUCT_COPY_FILES" device -g "*.mk"
