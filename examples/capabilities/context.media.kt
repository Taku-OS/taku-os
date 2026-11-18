// Media-session state and playback control · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: After notification-listener authorization, specify its listener component; only sessions exposed by apps are controllable
// Expected result: Pauses an available controllable session; verify the player's state
// This review did not automatically execute this example; define scenario-specific success criteria first.
val sessions = getSystemService(MediaSessionManager::class.java).getActiveSessions(listenerComponent)
sessions.firstOrNull()?.transportControls?.pause()
