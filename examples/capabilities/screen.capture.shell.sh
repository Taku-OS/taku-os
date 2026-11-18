# ADB / root screenshots · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: Authorized ADB/shell; the virtual display's ImageReader belongs to the root host
# Expected result: Saves an actual PNG; confirm its timestamp and DisplayID before giving it to a model
# This review did not automatically execute this example; define scenario-specific success criteria first.
phone-display capture
# /data/local/agentos/virtual-display/screen.png contains the background display
