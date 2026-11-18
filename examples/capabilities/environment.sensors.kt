// Sensors and activity recognition · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Enumerate device support first; register listeners as the algorithm requires; sensor values alone are not behavioral facts
// Expected result: Returns supported types; semantic activity recognition needs an algorithm or another API
// This review did not automatically execute this example; define scenario-specific success criteria first.
val sensors = getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL)
Log.i("TakuProbe", "types=${sensors.map { it.type }}")
