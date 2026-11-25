# Notification fixture

These are synthetic notifications for this acceptance report, not Slack data. The APK was built on the cloud host with Android SDK 36 and a test signing key.

`Fixture --es command publish` posts the expanded MessagingStyle card and seven compact cards. `clear` cancels only this app’s notifications. Content taps and action buttons open a page displaying the exact destination. Finish the fixture activity after publishing so an existing task does not mask the incoming intent.

The optional `wallpaper` command needs the repository file `system/lockscreen/media/default_lock_wallpaper.jpg` copied into APK assets as `lock.jpg`; `clear-wallpaper` clears only the lock wallpaper. These commands were used solely for a temporary visual comparison. No signing key or APK is included here.
