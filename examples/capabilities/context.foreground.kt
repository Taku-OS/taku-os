// Usage history and foreground-app clues · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Declare in the Manifest and let the user enable usage access in Settings; queryEvents uses a time window
// Expected result: Returns activity-usage events; first check AppOps and actual event availability
// This review did not automatically execute this example; define scenario-specific success criteria first.
val now = System.currentTimeMillis()
val events = getSystemService(UsageStatsManager::class.java).queryEvents(now - 60_000, now)
