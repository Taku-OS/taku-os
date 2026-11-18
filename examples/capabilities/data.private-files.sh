# Research: export other apps' private data · Research proposal / not yet tested
# Environment: Read-only source-location command in an AOSP checkout; not evidence of implemented functionality
# Prerequisites: Verify unlock state, sepolicy, the app's export support and consistent snapshots; there is no universal read-everything API
# Expected result: Locate private-directory policy in an AOSP checkout; research only, without collecting real personal databases
# This review did not automatically execute this example; define scenario-specific success criteria first.
rg --threads 2 "neverallow|app_data_file" system/sepolicy -g "*.te"
