// Inject semantic actions and gestures without flashing · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: User enables the Service; XML android:canPerformGestures=true; call within the Service lifecycle
// Expected result: Injects a single-pointer tap; verify application state even after the completion callback
// This review did not automatically execute this example; define scenario-specific success criteria first.
val p = Path().apply { moveTo(300f, 600f) }
val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 80)).build()
dispatchGesture(g, null, null)
