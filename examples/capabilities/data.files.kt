// Select, search and organize files · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Select a directory with ACTION_OPEN_DOCUMENT_TREE and persist URI read/write grants; this does not grant access to private directories
// Expected result: Select a directory and obtain its authorized URI before traversing DocumentFile
// This review did not automatically execute this example; define scenario-specific success criteria first.
startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 102)
