#!/usr/bin/env python3
"""Compile owned source against Android 17 and narrow SystemUI API doubles, NOT a Soong/Dagger build.

No downloads. Supply an Android 17 framework test jar and an existing Gradle dependency cache.
The doubles deliberately contain no implementation and never enter the shipped patch or an APK.
"""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
DOUBLES = {
    'Core.kt': '''package com.android.systemui
interface CoreStartable { fun start(); fun dump(pw: java.io.PrintWriter, args: Array<out String>) {} }
''',
    'Scope.kt': '''package com.android.systemui.dagger
annotation class SysUISingleton
''',
    'Qualifiers.kt': '''package com.android.systemui.dagger.qualifiers
annotation class Application
annotation class Main
annotation class Background
''',
    'Command.kt': '''package com.android.systemui.statusbar.commandline
interface Command { fun execute(pw: java.io.PrintWriter, args: List<String>); fun help(pw: java.io.PrintWriter) }
abstract class CommandRegistry {
 abstract fun registerCommand(name: String, factory: () -> Command)
 abstract fun registerCommand(name: String, factory: () -> Command, executor: java.util.concurrent.Executor)
}
''',
    'Shade.kt': '''package com.android.systemui.shade.domain.interactor
interface ShadeInteractor { val isAnyExpanded: kotlinx.coroutines.flow.StateFlow<Boolean> }
''',
    'Keyguard.kt': '''package com.android.systemui.statusbar.policy
interface KeyguardStateController {
 val isShowing: Boolean
 fun addCallback(callback: Callback)
 interface Callback { fun onKeyguardShowingChanged() {} }
}
''',
    'Phone.kt': '''package com.android.systemui.statusbar.phone
abstract class PhoneStatusBarView(context: android.content.Context) : android.widget.FrameLayout(context) {
 abstract fun setTakuIslandProbeWidth(width: Int)
}
''',
    'Res.kt': '''package com.android.systemui.res
object R { object id { const val clock_center = 1; const val network_traffic_center = 2; const val clock = 3; const val status_bar_end_side_content = 4; const val status_bar_start_side_except_heads_up = 5; const val network_traffic_holder_center = 6; const val status_bar_contents = 7; const val statusIcons = 8 } }
''',
    'Users.kt': '''package com.android.systemui.settings
interface UserTracker {
 val userId: Int
 fun addCallback(callback: Callback, executor: java.util.concurrent.Executor)
 interface Callback { fun onUserChanged(newUser: Int, userContext: android.content.Context) {}; fun onProfilesChanged(profiles: List<android.content.pm.UserInfo>) {} }
}
''',
    'Profiles.kt': '''package com.android.systemui.statusbar
interface NotificationLockscreenUserManager {
 fun isCurrentProfile(id: Int): Boolean
 fun isProfileAvailable(id: Int): Boolean
}
''',
    'Pipeline.kt': '''package com.android.systemui.statusbar.notification.collection
abstract class NotificationEntry { abstract val sbn: android.service.notification.StatusBarNotification; abstract val key: String }
abstract class NotifPipeline {
 abstract fun getAllNotifs(): Collection<NotificationEntry>
 abstract fun addCollectionListener(listener: com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener)
}
''',
    'Listener.kt': '''package com.android.systemui.statusbar.notification.collection.notifcollection
import com.android.systemui.statusbar.notification.collection.NotificationEntry
interface NotifCollectionListener {
 fun onEntryAdded(entry: NotificationEntry) {}
 fun onEntryUpdated(entry: NotificationEntry) {}
 fun onEntryRemoved(entry: NotificationEntry, reason: Int) {}
}
''',
    'Recording.kt': """package com.android.systemui.screenrecord
interface ScreenRecordUxController {
 val isRecording: Boolean
 fun stopRecording(reason: Int)
 fun addCallback(callback: StateChangeCallback)
 interface StateChangeCallback { fun onRecordingStart() {}; fun onRecordingEnd() {}; fun onCountdownEnd() {} }
}
""",
    'Casting.kt': """package com.android.systemui.statusbar.policy
interface CastController {
 val castDevices: List<CastDevice>
 fun stopCasting(device: CastDevice, reason: Int)
 fun addCallback(callback: Callback)
 interface Callback { fun onCastDevicesChanged() }
}
data class CastDevice(val id: String, val name: String?, val description: String?, val isCasting: Boolean, val origin: CastOrigin) {
 enum class CastOrigin { MediaProjection, MediaRouter }
}
""",
    'HeadsUp.kt': '''package com.android.systemui.statusbar.notification.headsup
interface HeadsUpManager { fun addListener(listener: OnHeadsUpChangedListener); fun hasPinnedHeadsUp(): Boolean; fun isHeadsUpEntry(key: String): Boolean }
interface OnHeadsUpChangedListener { fun onHeadsUpStateChanged(entry: com.android.systemui.statusbar.notification.collection.NotificationEntry, isHeadsUp: Boolean) {} fun onHeadsUpPinnedModeChanged(inPinnedMode: Boolean) {} }
''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-framework-jar', required=True, type=Path)
    parser.add_argument('--gradle-cache', type=Path, default=Path.home() / '.gradle/caches/modules-2/files-2.1')
    args = parser.parse_args()
    cache = args.gradle_cache
    def jar(group, artifact, version):
        matches = list((cache / group / artifact / version).glob('*/*.jar'))
        if len(matches) != 1:
            raise ValueError(f'Expected cached {group}:{artifact}:{version}')
        return str(matches[0])
    kotlin = [jar('org.jetbrains.kotlin', name, '2.2.21') for name in
              ('kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
    reflect = jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10')
    coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2')
    annotations = jar('org.jetbrains', 'annotations', '23.0.0')
    dependencies = [str(args.android_framework_jar.resolve()), kotlin[1], coroutines, annotations,
                    jar('javax.inject', 'javax.inject', '1'), jar('com.google.dagger', 'dagger', '2.59')]
    output = ROOT / 'out/island-typecheck'
    doubles = output / 'api-doubles'
    classes = output / 'classes'
    doubles.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    for name, content in DOUBLES.items():
        (doubles / name).write_text(content)
    source = ROOT / 'system/island/src'
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
    javac = str(Path(os.environ['JAVA_HOME']) / 'bin/javac')
    subprocess.run([javac, '--release', '17', '-cp', os.pathsep.join(dependencies), '-d', str(classes),
                    *map(str, source.rglob('*.java'))], check=True)
    subprocess.run([java, '-Xmx2g', '-cp', os.pathsep.join([*kotlin, reflect, coroutines, annotations]),
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-jvm-target', '17', '-classpath', os.pathsep.join([*dependencies, str(classes)]),
                    '-d', str(output / 'kotlin-classes'), *map(str, doubles.glob('*.kt')),
                    *map(str, source.rglob('*.kt'))], check=True)
    print('Owned source typecheck passed. SystemUI API doubles used: Soong/Dagger/ROM/device acceptance still pending.')


if __name__ == '__main__':
    main()
