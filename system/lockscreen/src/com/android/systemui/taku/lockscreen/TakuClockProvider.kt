package com.android.systemui.taku.lockscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.icu.util.TimeZone
import android.text.format.DateFormat
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.data.model.ZenData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEventListeners
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMessageBuffers
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMetadata
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPickerConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockProvider
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind
import java.io.PrintWriter
import java.util.Locale

/**
 * SystemUI's built-in clock provider plus the Taku clock. `ClockRegistry` takes a single default
 * provider that must keep supplying the stock `DEFAULT` clock, so this wraps the stock provider and adds
 * [TAKU_CLOCK_ID], which `ClockRegistryModule` makes the fallback. The choice is the standard
 * `Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE` JSON, so agents read and change it with `settings`.
 */
class TakuClockProvider(private val context: Context, private val stock: ClockProvider) : ClockProvider {
    override fun initialize(buffers: ClockMessageBuffers?) = stock.initialize(buffers)

    override fun getClocks(): List<ClockMetadata> = stock.getClocks() + ClockMetadata(TAKU_CLOCK_ID)

    override fun createClock(ctx: Context, settings: ClockSettings): ClockController? =
        if (settings.clockId == TAKU_CLOCK_ID) TakuClockController(ctx, settings) else stock.createClock(ctx, settings)

    override fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig =
        if (settings.clockId == TAKU_CLOCK_ID) {
            ClockPickerConfig(TAKU_CLOCK_ID, NAME, DESCRIPTION, TakuClockThumbnail(context), isReactiveToTone = false)
        } else {
            stock.getClockPickerConfig(settings)
        }

    companion object {
        const val TAKU_CLOCK_ID = "TAKU"
        const val NAME = "Taku"
        const val DESCRIPTION = "Taku lock screen clock"
    }
}

/** The Taku clock's two faces and the time, format and locale events they share. */
class TakuClockController(context: Context, settings: ClockSettings) : ClockController {
    override val smallClock = TakuClockFaceController(context, settings, isLarge = false)
    override val largeClock = TakuClockFaceController(context, settings, isLarge = true)
    override val config = ClockConfig(TakuClockProvider.TAKU_CLOCK_ID, TakuClockProvider.NAME, TakuClockProvider.DESCRIPTION)
    override val eventListeners = ClockEventListeners()

    private val faces get() = listOf(smallClock.view, largeClock.view)

    init {
        val is24 = DateFormat.is24HourFormat(context)
        faces.forEach { it.setIs24Hour(is24) }
    }

    override val events = object : ClockEvents {
        override fun onTimeZoneChanged(timeZone: TimeZone) = faces.forEach { it.setTimeZone(timeZone) }
        override fun onTimeFormatChanged(formatKind: TimeFormatKind) =
            faces.forEach { it.setIs24Hour(formatKind == TimeFormatKind.FULL_DAY) }
        override fun onLocaleChanged(locale: Locale) = faces.forEach { it.setLocale(locale) }
        override fun onWeatherDataChanged(data: WeatherData) {}
        override fun onAlarmDataChanged(data: AlarmData) {}
        override fun onZenDataChanged(data: ZenData) {}
    }

    override fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float) {
        for (face in listOf(smallClock, largeClock)) {
            face.events.onThemeChanged(face.theme.copy(isDarkTheme = isDarkTheme))
            face.animations.doze(dozeFraction)
            face.animations.fold(foldFraction)
            face.events.onTimeTick()
        }
    }

    override fun dump(pw: PrintWriter) {
        pw.println("TakuClockController small=${smallClock.view.contentDescription} large=${largeClock.view.contentDescription}")
    }
}

/** Picker thumbnail: the tall clock reading 09:30, as in the design. */
private class TakuClockThumbnail(context: Context) : Drawable() {
    private val clock = TakuTallClockView(context).apply { time = "09:30" }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        clock.measure(0, 0)
        val scale = minOf(bounds.width() / clock.measuredWidth.toFloat(), bounds.height() / clock.measuredHeight.toFloat())
        canvas.save()
        canvas.translate(bounds.exactCenterX() - clock.measuredWidth * scale / 2f, bounds.exactCenterY() - clock.measuredHeight * scale / 2f)
        canvas.scale(scale, scale)
        clock.layout(0, 0, clock.measuredWidth, clock.measuredHeight)
        clock.draw(canvas)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
