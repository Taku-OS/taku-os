// Ordinary camera preview / real-time understanding · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Authorize CAMERA, enumerate cameraId, and close sessions/devices correctly
// Expected result: Enumerates camera IDs; preview additionally requires callbacks, a Surface and working hardware
// This review did not automatically execute this example; define scenario-specific success criteria first.
val manager = getSystemService(CameraManager::class.java)
Log.i("TakuProbe", "cameras=${manager.cameraIdList.toList()}")
