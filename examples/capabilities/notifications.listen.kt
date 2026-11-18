// Observe notification changes · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Declare the Service and intent-filter in the Manifest; the user enables notification access in Settings; wait for onListenerConnected
// Expected result: A newly posted test notification triggers a posted callback; this project has no verified listener adapter yet
// This review did not automatically execute this example; define scenario-specific success criteria first.
override fun onNotificationPosted(sbn: StatusBarNotification) {
  Log.i("TakuProbe", "posted pkg=${sbn.packageName} id=${sbn.id}")
}
