// Quick replies through existing notifications · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Obtain the action through an authorized NotificationListenerService; the target app must expose a reply action
// Expected result: Triggers the target app's own reply action; verify business success through its UI/service
// This review did not automatically execute this example; define scenario-specific success criteria first.
val reply = Intent()
RemoteInput.addResultsToIntent(action.remoteInputs, reply, Bundle().apply { putCharSequence(action.remoteInputs[0].resultKey, "Received") })
action.actionIntent.send(this, 0, reply)
