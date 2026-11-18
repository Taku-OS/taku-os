// Query and create system calendar events · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Runtime authorization; ACTION_INSERT lets the calendar app request confirmation without the caller holding database-write permission
// Expected result: Opens the calendar creation page; read back the event to confirm it was saved
// This review did not automatically execute this example; define scenario-specific success criteria first.
startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, "Taku test event"))
