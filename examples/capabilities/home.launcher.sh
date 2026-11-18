# Custom home screen, task cards and traditional app entry points · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: Ordinary apps declare HOME; the current Taku assistant uses system UID1000 and a platform test signature, with minSdk36
# Expected result: Opens the actual Taku HOME, not a simulated web interface
# This review did not automatically execute this example; define scenario-specific success criteria first.
am start -a android.intent.action.MAIN -c android.intent.category.HOME
