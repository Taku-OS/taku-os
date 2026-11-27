#!/usr/bin/env python3
"""Compile owned lock-screen source against android.jar and narrow SystemUI API doubles.

NOT a Soong/Dagger build: the doubles mirror the signatures used from the pinned SystemUI
(docs/lock-screen.md lists the files) and never enter the patch or an APK. It catches typos and
type errors early; the platform build remains the real check.

  python3 system/lockscreen/typecheck.py --kotlinc /path/to/kotlinc/bin/kotlinc \
      --android-jar /path/to/android.jar --coroutines-jar kotlinx-coroutines-core-jvm.jar
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / 'system/lockscreen/src'

DOUBLES = {
    'Dagger.kt': '''package com.android.systemui.dagger
annotation class SysUISingleton
''',
    'Qualifiers.kt': '''package com.android.systemui.dagger.qualifiers
annotation class Background
annotation class Main
''',
    'Inject.kt': '''package javax.inject
@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION) annotation class Inject
''',
    'Users.kt': '''package com.android.systemui.settings
interface UserTracker { val userId: Int; val userHandle: android.os.UserHandle; val userContext: android.content.Context }
''',
    'Keyguard.kt': '''package com.android.systemui.keyguard.shared.model
abstract class KeyguardSection {
 abstract fun addViews(constraintLayout: androidx.constraintlayout.widget.ConstraintLayout)
 abstract fun bindData(constraintLayout: androidx.constraintlayout.widget.ConstraintLayout)
 abstract fun applyConstraints(constraintSet: androidx.constraintlayout.widget.ConstraintSet)
 abstract fun removeViews(constraintLayout: androidx.constraintlayout.widget.ConstraintLayout)
}
interface KeyguardBlueprint { val id: String; val sections: List<KeyguardSection> }
''',
    'DefaultBlueprint.kt': '''package com.android.systemui.keyguard.ui.view.layout.blueprints
class DefaultKeyguardBlueprint : com.android.systemui.keyguard.shared.model.KeyguardBlueprint {
 override val id = "default"; override val sections = listOf<com.android.systemui.keyguard.shared.model.KeyguardSection>()
}
''',
    'KeyguardInteractor.kt': '''package com.android.systemui.keyguard.domain.interactor
abstract class KeyguardInteractor {
 abstract val dozeAmount: kotlinx.coroutines.flow.Flow<Float>
 abstract val isDozing: kotlinx.coroutines.flow.StateFlow<Boolean>
 abstract val isKeyguardShowing: kotlinx.coroutines.flow.StateFlow<Boolean>
 abstract val isKeyguardDismissible: kotlinx.coroutines.flow.StateFlow<Boolean>
 abstract val statusBarState: kotlinx.coroutines.flow.StateFlow<com.android.systemui.keyguard.shared.model.StatusBarState>
}
abstract class KeyguardClockInteractor { abstract fun setClockSize(size: com.android.systemui.keyguard.shared.model.ClockSize); abstract val clockSize: kotlinx.coroutines.flow.StateFlow<com.android.systemui.keyguard.shared.model.ClockSize>; abstract val currentClock: kotlinx.coroutines.flow.StateFlow<com.android.systemui.plugins.keyguard.ui.clocks.ClockController?> }
''',
    'KeyguardModelsShared.kt': '''package com.android.systemui.keyguard.shared.model
enum class ClockSize { LARGE, SMALL }
class BurnInModel { companion object { const val MAX_LARGE_CLOCK_SCALE = 0.9f } }
enum class StatusBarState { SHADE, KEYGUARD, SHADE_LOCKED }
''',
    'Dagger2.kt': '''package dagger
interface Lazy<T> { fun get(): T }
''',
    'SharedR.kt': '''package com.android.systemui.shared
object R { object id { const val date_smartspace_view = 201; const val date_smartspace_view_large = 202
 const val weather_smartspace_view = 203; const val weather_smartspace_view_large = 204 } }
''',
    'SysuiR.kt': '''package com.android.systemui.res
object R { object id { const val start_button = 101; const val device_entry_icon_fg = 104; const val keyguard_carrier_text = 105; const val keyguard_slice_view = 106; const val system_icons_container = 107 }; object string { const val controls_open_app = 102 }; object drawable { const val ic_apps_expressive = 103 } }
''',
    'DeviceEntry.kt': '''package com.android.systemui.keyguard.ui.view
class DeviceEntryIconView { enum class IconType { LOCK, UNLOCK, FINGERPRINT, NONE } }
''',
    'DeviceEntryViewModel.kt': '''package com.android.systemui.keyguard.ui.viewmodel
abstract class DeviceEntryIconViewModel { abstract val iconType: kotlinx.coroutines.flow.Flow<com.android.systemui.keyguard.ui.view.DeviceEntryIconView.IconType> }
''',
    # system/island, applied to SystemUI before this patch (image/build.sh): only what the lock screen calls.
    'Island.kt': '''package com.android.systemui.taku.island
abstract class IslandActivity { abstract val key: String }
abstract class IslandLockStatus {
 enum class State(val wire: String?) { NONE(null), LOCKED("locked"), UNLOCKED("unlocked") }
 abstract val state: kotlinx.coroutines.flow.StateFlow<State>
 abstract fun activity(): IslandActivity?
}
abstract class IslandPresenter {
 data class LockScreen(val activity: IslandActivity? = null, val redacted: Boolean = false, val card: IslandActivity? = null)
 abstract val lockScreen: kotlinx.coroutines.flow.StateFlow<LockScreen>
 abstract fun foldLockScreenCard()
}
object IslandRuntime { fun call(method: String, arg: String?, extra: String?, scope: Int = -1): org.json.JSONObject = TODO() }
class IslandStatusView(context: android.content.Context) : android.view.ViewGroup(context) {
 interface Gestures { fun tap(key: String); fun longPress(key: String); fun next(); fun hide(key: String) }
 var gestures: Gestures? = null
 var endLimitOnScreen: Int? = null
 fun show(status: IslandActivity?, activity: IslandActivity?, redacted: Boolean) {}
 fun capsuleLeftOnScreen(): Int? = TODO()
 fun state(): org.json.JSONObject = TODO()
 override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {}
}
class IslandLockScreenCard(context: android.content.Context, command: (String, String?, String?) -> Unit, fold: () -> Unit) : android.widget.FrameLayout(context) {
 fun show(next: IslandActivity?) {}
 fun state(): org.json.JSONObject = TODO()
}
''',
    'Falsing.kt': '''package com.android.systemui.plugins
interface FalsingManager { fun isFalseTap(penalty: Int): Boolean; companion object { const val LOW_PENALTY = 1 } }
interface ActivityStarter {
 fun startPendingIntentDismissingKeyguard(intent: android.app.PendingIntent)
 fun dismissKeyguardThenExecute(action: OnDismissAction, cancel: Runnable?, afterKeyguardGone: Boolean)
 interface OnDismissAction { fun onDismiss(): Boolean; fun willRunAnimationOnKeyguard(): Boolean = false }
}
''',
    'Commands.kt': '''package com.android.systemui.statusbar.commandline
interface Command { fun execute(pw: java.io.PrintWriter, args: List<String>); fun help(pw: java.io.PrintWriter) }
abstract class CommandRegistry { abstract fun registerCommand(name: String, commandFactory: () -> Command); abstract fun registerCommand(name: String, commandFactory: () -> Command, executor: java.util.concurrent.Executor) }
''',
    'ClickNotifier.kt': '''package com.android.systemui.statusbar
abstract class NotificationClickNotifier {
 abstract fun onNotificationActionClick(key: String, actionIndex: Int, action: android.app.Notification.Action,
  visibility: com.android.internal.statusbar.NotificationVisibility, generatedByAssistant: Boolean)
}
''',
    'NotificationVisibility.java': '''package com.android.internal.statusbar;
public class NotificationVisibility {
 public enum NotificationLocation { LOCATION_MAIN_AREA }
 public static NotificationVisibility obtain(String key, int rank, int count, boolean visible, NotificationLocation location) { return null; }
}
''',
    'NotificationEntries.java': '''package com.android.systemui.statusbar.notification.collection;
public abstract class NotificationEntry extends PipelineEntry {
 public abstract String getKey();
 public abstract android.service.notification.StatusBarNotification getSbn();
 public abstract com.android.systemui.statusbar.notification.row.ExpandableNotificationRow getRow();
 public abstract boolean isClearable();
 public abstract boolean isBubble();
 public abstract boolean isRowDismissed();
 public abstract kotlinx.coroutines.flow.StateFlow<Boolean> isSensitive();
}
''',
    'PipelineEntries.kt': '''package com.android.systemui.statusbar.notification.collection
abstract class PipelineEntry
abstract class GroupEntry : PipelineEntry() { abstract val summary: NotificationEntry?; abstract val children: List<NotificationEntry> }
abstract class BundleEntry : PipelineEntry() { abstract val children: List<PipelineEntry> }
abstract class NotifPipeline { abstract fun addOnAfterRenderListListener(listener: com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderListListener) }
''',
    'OnAfterRender.java': '''package com.android.systemui.statusbar.notification.collection.listbuilder;
public interface OnAfterRenderListListener { void onAfterRenderList(java.util.List<com.android.systemui.statusbar.notification.collection.PipelineEntry> entries); }
''',
    'Row.java': '''package com.android.systemui.statusbar.notification.row;
public abstract class ExpandableNotificationRow { public abstract boolean canViewBeDismissed(); public abstract void performDismiss(boolean fromAccessibility); }
''',
    'ActivityStarterN.kt': '''package com.android.systemui.statusbar.notification
interface NotificationActivityStarter {
 fun onNotificationClicked(entry: com.android.systemui.statusbar.notification.collection.NotificationEntry,
  row: com.android.systemui.statusbar.notification.row.ExpandableNotificationRow)
}
''',
    'SharedContainer.kt': '''package com.android.systemui.statusbar.notification.stack.ui.view
class SharedNotificationContainer(context: android.content.Context) : androidx.constraintlayout.widget.ConstraintLayout(context)
''',
    'QuickAffordances.kt': '''package com.android.systemui.keyguard.data.quickaffordance
interface KeyguardQuickAffordanceConfig {
 val key: String
 val pickerIconResourceId: Int
 val lockScreenState: kotlinx.coroutines.flow.Flow<LockScreenState>
 fun pickerName(): String
 suspend fun getPickerScreenState(): PickerScreenState = PickerScreenState.Default()
 fun onTriggered(expandable: com.android.systemui.animation.Expandable?): OnTriggeredResult
 sealed class PickerScreenState { data class Default(val configureIntent: android.content.Intent? = null) : PickerScreenState() }
 sealed class LockScreenState {
  object Hidden : LockScreenState()
  data class Visible(val icon: com.android.systemui.common.shared.model.Icon) : LockScreenState()
 }
 sealed class OnTriggeredResult {
  data class Handled(val actionLaunched: Boolean) : OnTriggeredResult()
  data class StartActivity(val intent: android.content.Intent, val canShowWhileLocked: Boolean) : OnTriggeredResult()
 }
}
''',
    'QuickAffordanceInteractor.kt': '''package com.android.systemui.keyguard.domain.interactor
abstract class KeyguardQuickAffordanceInteractor {
 abstract suspend fun select(slotId: String, affordanceId: String): Boolean
 abstract suspend fun unselect(slotId: String, affordanceId: String?): Boolean
 abstract suspend fun getSelections(): Map<String, List<com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePickerRepresentation>>
 abstract suspend fun getAffordancePickerRepresentations(): List<com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePickerRepresentation>
 abstract suspend fun getSlotPickerRepresentations(): List<com.android.systemui.keyguard.shared.model.KeyguardSlotPickerRepresentation>
}
''',
    'PickerModels.kt': '''package com.android.systemui.keyguard.shared.model
data class KeyguardQuickAffordancePickerRepresentation(val id: String, val name: String, val iconResourceId: Int,
 val isEnabled: Boolean = true, val explanation: String? = null)
data class KeyguardSlotPickerRepresentation(val id: String, val maxSelectedAffordances: Int = 1)
''',
    'CommonModels.kt': '''package com.android.systemui.common.shared.model
sealed class ContentDescription { data class Loaded(val description: String?) : ContentDescription() }
sealed class Icon {
 data class Loaded(val drawable: android.graphics.drawable.Drawable, val contentDescription: ContentDescription?) : Icon()
 data class Resource(val resId: Int, val contentDescription: ContentDescription?) : Icon()
}
''',
    'Expandable.kt': '''package com.android.systemui.animation
interface Expandable
''',
    'Settings.kt': '''package com.android.systemui.util.settings
interface UserSettingsProxy {
 fun getStringForUser(name: String, userHandle: Int): String?
 fun putStringForUser(name: String, value: String?, userHandle: Int): Boolean
}
interface SecureSettings : UserSettingsProxy
object SettingsProxyExt {
 fun UserSettingsProxy.observerFlow(userId: Int, vararg names: String): kotlinx.coroutines.flow.Flow<Unit> = TODO()
}
''',
    'SharedContainerVm.kt': '''package com.android.systemui.statusbar.notification.stack.ui.viewmodel
abstract class SharedNotificationContainerViewModel { abstract val isOnLockscreenWithoutShade: kotlinx.coroutines.flow.Flow<Boolean> }
''',
    'Lifecycle.kt': '''package com.android.systemui.lifecycle
fun android.view.View.repeatWhenAttached(coroutineContext: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
 assertOnMainThread: Boolean = true, block: suspend androidx.lifecycle.LifecycleOwner.(android.view.View) -> Unit): kotlinx.coroutines.DisposableHandle = TODO()
''',
    'AndroidxLifecycle.kt': '''package androidx.lifecycle
abstract class Lifecycle { enum class State { DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED } }
interface LifecycleOwner { val lifecycle: Lifecycle }
suspend fun LifecycleOwner.repeatOnLifecycle(state: Lifecycle.State, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Unit = TODO()
''',
    'Constraint.kt': '''package androidx.constraintlayout.widget
open class ConstraintLayout(context: android.content.Context) : android.view.ViewGroup(context) {
 override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}
}
open class ConstraintSet {
 companion object { const val PARENT_ID = 0; const val MATCH_CONSTRAINT = 0; const val WRAP_CONTENT = -2
  const val START = 6; const val END = 7; const val TOP = 3; const val BOTTOM = 4
  const val VISIBILITY_MODE_IGNORE = 1 }
 fun clear(viewId: Int, anchor: Int) {}
 fun constrainWidth(viewId: Int, width: Int) {}
 fun constrainHeight(viewId: Int, height: Int) {}
 fun constrainMaxHeight(viewId: Int, height: Int) {}
 fun setVisibilityMode(viewId: Int, visibilityMode: Int) {}
 fun setVisibility(viewId: Int, visibility: Int) {}
 fun setAlpha(viewId: Int, alpha: Float) {}
 fun connect(startID: Int, startSide: Int, endID: Int, endSide: Int) {}
 fun connect(startID: Int, startSide: Int, endID: Int, endSide: Int, margin: Int) {}
}
''',
    'PathParser.kt': '''package androidx.core.graphics
object PathParser { @JvmStatic fun createPathFromPathData(pathData: String): android.graphics.Path = TODO() }
''',
    'HiddenApi.kt': '''package com.android.systemui.taku.lockscreen
// Hidden in android.jar, public to platform SystemUI; a member in the real build shadows this extension.
fun android.app.WallpaperManager.getWallpaperFile(which: Int, userId: Int): android.os.ParcelFileDescriptor? = TODO()
fun android.app.WallpaperManager.getWallpaperInfo(which: Int, userId: Int): android.app.WallpaperInfo? = TODO()
fun android.content.Context.startActivityAsUser(intent: android.content.Intent, user: android.os.UserHandle): Unit = TODO()
''',
    'KeyguardModels.kt': '''package com.android.systemui.plugins.keyguard
class VRect
''',
    'KeyguardData.kt': '''package com.android.systemui.plugins.keyguard.data.model
class WeatherData
class AlarmData
class ZenData
''',
    'Clocks.kt': '''package com.android.systemui.plugins.keyguard.ui.clocks
typealias ClockId = String
data class ClockMetadata(val clockId: ClockId, val isDeprecated: Boolean = false)
data class ClockAxisStyle(val placeholder: Int = 0)
data class ClockSettings(val clockId: ClockId? = null, val seedColor: Int? = null, val axes: ClockAxisStyle = ClockAxisStyle())
class ClockMessageBuffers
data class ClockPickerConfig @JvmOverloads constructor(val id: String, val name: String, val description: String,
 val thumbnail: android.graphics.drawable.Drawable, val isReactiveToTone: Boolean = true)
data class ClockConfig(val id: ClockId, val name: String, val description: String,
 val useAlternateSmartspaceAODTransition: Boolean = false, val useCustomClockScene: Boolean = false)
data class ClockFaceConfig(val hasCustomWeatherDataDisplay: Boolean = false)
data class ThemeConfig(val isDarkTheme: Boolean, val seedColor: Int?)
data class ClockPositionAnimationArgs(val fromLeft: Int, val direction: Int, val fraction: Float)
enum class TimeFormatKind { HALF_DAY, FULL_DAY }
object ClockViewIds { val LOCKSCREEN_CLOCK_VIEW_LARGE = 1; val LOCKSCREEN_CLOCK_VIEW_SMALL = 2 }
class ClockEventListeners
interface ClockFaceEvents {
 fun onTimeTick(); fun onThemeChanged(theme: ThemeConfig); fun onFontSettingChanged(fontSizePx: Float)
 fun onTargetRegionChanged(targetRegion: com.android.systemui.plugins.keyguard.VRect); fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean)
}
interface ClockAnimations {
 fun enter(); fun doze(fraction: Float); fun fold(fraction: Float); fun charge()
 fun onPickerCarouselSwiping(swipingFraction: Float); fun onPositionAnimated(args: ClockPositionAnimationArgs)
 fun onFidgetTap(x: Float, y: Float); fun onFontAxesChanged(style: ClockAxisStyle)
}
interface ClockFaceLayout { fun applyConstraints(constraints: androidx.constraintlayout.widget.ConstraintSet): androidx.constraintlayout.widget.ConstraintSet }
interface ClockFaceController {
 val view: android.view.View; val layout: ClockFaceLayout; val config: ClockFaceConfig; var theme: ThemeConfig
 val events: ClockFaceEvents; val animations: ClockAnimations
}
interface ClockEvents {
 fun onTimeZoneChanged(timeZone: android.icu.util.TimeZone); fun onTimeFormatChanged(formatKind: TimeFormatKind)
 fun onLocaleChanged(locale: java.util.Locale); fun onWeatherDataChanged(data: com.android.systemui.plugins.keyguard.data.model.WeatherData)
 fun onAlarmDataChanged(data: com.android.systemui.plugins.keyguard.data.model.AlarmData); fun onZenDataChanged(data: com.android.systemui.plugins.keyguard.data.model.ZenData)
}
interface ClockController {
 val smallClock: ClockFaceController; val largeClock: ClockFaceController; val config: ClockConfig; val events: ClockEvents
 val eventListeners: ClockEventListeners
 fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float); fun dump(pw: java.io.PrintWriter)
}
interface ClockProvider {
 fun initialize(buffers: ClockMessageBuffers?); fun getClocks(): List<ClockMetadata>
 fun createClock(ctx: android.content.Context, settings: ClockSettings): ClockController?
 fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig
}
''',
    'DefaultClockFaceLayout.kt': '''package com.android.systemui.customization.clocks.view
open class DefaultClockFaceLayout(val view: android.view.View) : com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceLayout {
 override fun applyConstraints(constraints: androidx.constraintlayout.widget.ConstraintSet) = constraints
}
''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--kotlinc', required=True, type=Path)
    parser.add_argument('--android-jar', required=True, type=Path)
    parser.add_argument('--coroutines-jar', required=True, type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='taku-lockscreen-typecheck-') as temp:
        doubles = Path(temp) / 'doubles'
        doubles.mkdir()
        for name, text in DOUBLES.items():
            (doubles / name).write_text(text)
        sources = [str(path) for path in sorted(SOURCES.rglob('*.kt'))]
        command = [str(args.kotlinc), '-nowarn', '-jvm-target', '17', '-d', str(Path(temp) / 'out'),
                   '-classpath', f'{args.android_jar}:{args.coroutines_jar}', *sources,
                   *[str(path) for path in sorted(doubles.iterdir()) if path.suffix in ('.kt', '.java')]]
        subprocess.run(command, check=True)
    print(f'typecheck ok: {len(sources)} owned sources')


if __name__ == '__main__':
    main()
