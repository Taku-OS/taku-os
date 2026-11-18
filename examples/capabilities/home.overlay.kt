// Global assistive layer / overlay · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: User enables overlay permission; WindowManager adds a non-blocking view and handles focus/input
// Expected result: Displays a separate overlay; touch passthrough/layout require separate tests
// This review did not automatically execute this example; define scenario-specific success criteria first.
val p = WindowManager.LayoutParams(200, 100, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
getSystemService(WindowManager::class.java).addView(TextView(this).apply { text = "Taku status" }, p)
