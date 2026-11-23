# Default digital assistant and global invocation · Research proposal / not yet tested
# Environment: Root shell on the phone
# Prerequisites: Implement VoiceInteractionService/VoiceInteractionSessionService and select the default assistant; the platform-signed Taku Agent app (org.agentos.agent, ordinary UID) provides SystemAssistantService/SystemAssistantSessionService
# Expected result: Prints org.agentos.agent/org.agentos.agent.home.assistant.SystemAssistantService in the Taku image, then opens the entry point of that assistant; not speech recognition or low-power wake-up
# This review did not automatically execute this example; define scenario-specific success criteria first.
settings get secure voice_interaction_service
am start -a android.intent.action.ASSIST
