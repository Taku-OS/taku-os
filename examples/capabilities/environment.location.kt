// Location and location context · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Authorize runtime permissions separately; background location is not covered by the foreground grant; Google Play Services can provide geofencing but is unverified in this image
// Expected result: Reads only whether a historical fix exists, without collecting a personal route; real-time location requires separate testing
// This review did not automatically execute this example; define scenario-specific success criteria first.
val location = getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.GPS_PROVIDER)
Log.i("TakuProbe", "hasFix=${location != null}")
