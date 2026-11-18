# ADB UI-tree and control testing · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: Developer enables ADB and authorizes the host; UI Automator starts through shell instrumentation
# Expected result: Returns the accessible node tree; the command writes the target test XML without clicking
# This review did not automatically execute this example; define scenario-specific success criteria first.
uiautomator dump /sdcard/taku-ui.xml
cat /sdcard/taku-ui.xml
