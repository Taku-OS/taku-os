// Research: backup, restore and migration · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Implement BackupAgent / an export format for your own app; copying complete third-party login state is not promised
// Expected result: Marks the app's data as awaiting backup; does not establish immediate backup completion or restoration
// This review did not automatically execute this example; define scenario-specific success criteria first.
BackupManager(this).dataChanged()
