package com.android.systemui.taku.lockscreen

import android.content.Context
import android.util.TypedValue
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.customization.clocks.view.DefaultClockFaceLayout
import com.android.systemui.plugins.keyguard.VRect
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAnimations
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPositionAnimationArgs
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig

/**
 * One face of the Taku clock. The keyguard shows the large face while no notification is visible and
 * the small one otherwise; Taku hides notifications until the user authenticates, so the large face is
 * the locked lock screen (Figma 979:429) and the small face the authenticated one (979:574).
 */
class TakuClockFaceController(context: Context, settings: ClockSettings, isLarge: Boolean) : ClockFaceController {
    override val view = TakuClockFaceView(context, isLarge).apply {
        // DefaultClockFaceLayout and the keyguard address the faces by these ids.
        id = if (isLarge) ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE else ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
    }

    override val layout = TakuClockFaceLayout(view, context)
    override val config = ClockFaceConfig()
    override var theme = ThemeConfig(isDarkTheme = true, settings.seedColor)

    override val events = object : ClockFaceEvents {
        override fun onTimeTick() = view.refresh()

        override fun onThemeChanged(theme: ThemeConfig) {
            this@TakuClockFaceController.theme = theme
            // The face always sits on the darkened lock-screen background.
            view.setTheme(isDark = true)
        }

        override fun onFontSettingChanged(fontSizePx: Float) = view.setTextSizePx(fontSizePx)
        override fun onTargetRegionChanged(targetRegion: VRect) {}
        override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}
    }

    override val animations = object : ClockAnimations {
        override fun enter() {}
        override fun doze(fraction: Float) {}
        override fun fold(fraction: Float) {}
        override fun charge() {}
        override fun onPickerCarouselSwiping(swipingFraction: Float) {}
        override fun onPositionAnimated(args: ClockPositionAnimationArgs) {}
        override fun onFidgetTap(x: Float, y: Float) {}
        override fun onFontAxesChanged(style: ClockAxisStyle) {}
    }
}

/**
 * Both faces are centred and start 24 dp below the status bar (76 dp from the top of a 412 × 917 frame).
 * The stock defaults put the small clock at the start edge with a fixed height; this layout replaces
 * that after the clock section's defaults are applied.
 */
class TakuClockFaceLayout(view: TakuClockFaceView, private val context: Context) : DefaultClockFaceLayout(view) {
    override fun applyConstraints(constraints: ConstraintSet): ConstraintSet {
        val id = view.id
        val top = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TOP_DP, context.resources.displayMetrics).toInt()
        constraints.apply {
            clear(id, ConstraintSet.START)
            clear(id, ConstraintSet.END)
            clear(id, ConstraintSet.TOP)
            clear(id, ConstraintSet.BOTTOM)
            constrainWidth(id, ConstraintSet.WRAP_CONTENT)
            constrainHeight(id, ConstraintSet.WRAP_CONTENT)
            constrainMaxHeight(id, 0)
            connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, top)
        }
        return constraints
    }

    companion object {
        /** Figma 979:466: the date's top edge, from the top of the screen. */
        const val TOP_DP = 76f
    }
}
