# Install, update and uninstall standalone APKs · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: Shell pm install; an ordinary installer needs unknown-source authorization and user action; updates must keep the signing identity
# Expected result: Returns Success and base.apk; verify signing, version and launch separately
# This review did not automatically execute this example; define scenario-specific success criteria first.
pm install -r /data/local/tmp/test-generated.apk
pm path org.agentos.apiboard
