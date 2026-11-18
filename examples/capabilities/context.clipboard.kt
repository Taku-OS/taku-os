// Foreground clipboard / share entry point · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Read primaryClip in a focused Activity; register ACTION_SEND for a share target
// Expected result: A foreground app can obtain clipboard items; handle missing access or empty content as empty
// This review did not automatically execute this example; define scenario-specific success criteria first.
val clip = getSystemService(ClipboardManager::class.java).primaryClip
Log.i("TakuProbe", "items=${clip?.itemCount ?: 0}")
