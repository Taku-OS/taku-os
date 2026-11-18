// Photo library and file location · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Prefer Photo Picker grants for selected URIs; full media reads need version-appropriate permissions; location needs separate authorization
// Expected result: The user selects an image and grants URI read access; check whether Exif contains GPS
// This review did not automatically execute this example; define scenario-specific success criteria first.
startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 101)
