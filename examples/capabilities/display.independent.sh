# A truly independent background display · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: The phone-display host owns an ImageReader Surface; current root + SELinux Enforcing operation, not merely ordinary MediaProjection mirroring
# Expected result: Obtains a dynamic nonzero displayId and an independent 720x1280 Surface, not a copy of the main screen
# This review did not automatically execute this example; define scenario-specific success criteria first.
phone-display start
phone-display id
phone-display status
