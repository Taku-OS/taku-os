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
''',
    'Inject.kt': '''package javax.inject
@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION) annotation class Inject
''',
    'Users.kt': '''package com.android.systemui.settings
interface UserTracker { val userId: Int }
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
abstract class KeyguardInteractor { abstract val dozeAmount: kotlinx.coroutines.flow.Flow<Float> }
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
  const val START = 6; const val END = 7; const val TOP = 3; const val BOTTOM = 4 }
 fun clear(viewId: Int, anchor: Int) {}
 fun constrainWidth(viewId: Int, width: Int) {}
 fun constrainHeight(viewId: Int, height: Int) {}
 fun constrainMaxHeight(viewId: Int, height: Int) {}
 fun connect(startID: Int, startSide: Int, endID: Int, endSide: Int) {}
 fun connect(startID: Int, startSide: Int, endID: Int, endSide: Int, margin: Int) {}
}
''',
    'PathParser.kt': '''package androidx.core.graphics
object PathParser { @JvmStatic fun createPathFromPathData(pathData: String): android.graphics.Path = TODO() }
''',
    'HiddenApi.kt': '''package com.android.systemui.taku.lockscreen
// Hidden in android.jar, public to platform SystemUI; a member in the real build shadows this extension.
fun android.app.WallpaperManager.getBitmapAsUser(userId: Int, hardware: Boolean, which: Int): android.graphics.Bitmap? = TODO()
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
                   *[str(path) for path in sorted(doubles.glob('*.kt'))]]
        subprocess.run(command, check=True)
    print(f'typecheck ok: {len(sources)} owned sources')


if __name__ == '__main__':
    main()
