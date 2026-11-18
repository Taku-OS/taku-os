// System SMS and call logs · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Runtime consent alone is insufficient; check installer allowlists, default SMS/phone roles and OEM policy; use dedicated test data only
// Expected result: Returns test SMS IDs/timestamps when properly authorized; denial raises SecurityException, so universal access must not be claimed
// This review did not automatically execute this example; define scenario-specific success criteria first.
contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "date"), null, null, "date DESC")?.use { Log.i("TakuProbe", "count=${it.count}") }
