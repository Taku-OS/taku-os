// Read and write contacts · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Request each runtime permission separately; use an isolated account/test contacts
// Expected result: Returns test contact IDs; this project has not called it
// This review did not automatically execute this example; define scenario-specific success criteria first.
contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { Log.i("TakuProbe", "count=${it.count}") }
