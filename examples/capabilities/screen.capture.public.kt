// Screen capture without flashing · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Activity obtains the createScreenCaptureIntent result, then creates a capture session in a valid foreground service
// Expected result: Shows the system capture-consent UI; the result is not a screenshot; Surface/VirtualDisplay and a stop callback are still required
// This review did not automatically execute this example; define scenario-specific success criteria first.
startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 100)
