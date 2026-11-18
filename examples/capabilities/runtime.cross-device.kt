// Research: cross-device Agent collaboration · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Network transport is available; remote app interfaces need authentication/reconnection/conflict handling; a localhost development API is not a public Internet API
// Expected result: Reads whether an active network exists; cross-device tasks still require a protocol
// This review did not automatically execute this example; define scenario-specific success criteria first.
// Transport probe only, not a synchronization/authorization protocol
val connected = getSystemService(ConnectivityManager::class.java).activeNetwork != null
