// Power, WakeLocks and task scheduling · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Set a timeout and release the lock; long-term battery use needs physical testing; do not rely on an unlimited WakeLock for residency
// Expected result: A short WakeLock with a timeout does not remove network/foreground-service restrictions
// This review did not automatically execute this example; define scenario-specific success criteria first.
val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Taku:probe")
lock.acquire(5000)
lock.release()
