# Default digital assistant and global invocation · Research proposal / not yet tested
# Environment: Root shell on the phone
# Prerequisites: Implement VoiceInteractionService/SessionService and select the default assistant; the current test-signed system app integrates these
# Expected result: Opens the current default assistant entry point; not speech recognition or low-power wake-up
# This review did not automatically execute this example; define scenario-specific success criteria first.
am start -a android.intent.action.ASSIST
