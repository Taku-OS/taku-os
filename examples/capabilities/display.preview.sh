# View and manually control the background display from the main screen · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: Taku Native MirrorActivity connects to the abstract Unix socket taku-mirror; no network socket is exposed; touch targets the root host
# Expected result: Shows the actual background image; taps/swipes reach only its logical display
# This review did not automatically execute this example; define scenario-specific success criteria first.
am start -n org.agentos.assistant/.MirrorActivity
