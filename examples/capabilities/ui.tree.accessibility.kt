// Read the accessible UI tree from an ordinary app · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Complete accessibility Service and XML capability; user enables it in system Settings; root is unnecessary
// Expected result: Returns currently accessible window nodes; custom-drawn content may be empty
// This review did not automatically execute this example; define scenario-specific success criteria first.
val root = rootInActiveWindow ?: return
Log.i("TakuProbe", "nodes=${root.childCount}")
