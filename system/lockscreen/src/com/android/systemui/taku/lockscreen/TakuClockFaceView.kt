package com.android.systemui.taku.lockscreen

import android.content.Context
import android.graphics.Color
import android.icu.text.DateFormat
import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import java.util.Locale

/**
 * One Taku clock face: the date (Figma 979:468) above the time, centred, 24 dp apart.
 *
 * - Large (locked, Figma 979:470): [TakuTallClockView], 320 dp tall.
 * - Small: the same outlines at their compact Space Grotesk endpoint, white at 64 %.
 */
class TakuClockFaceView(context: Context, val isLarge: Boolean) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val date = TextView(context).apply {
        typeface = TakuLockscreenFonts.spaceGrotesk
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        letterSpacing = -0.01f
        setLineHeight((24f * 1.05f * resources.displayMetrics.scaledDensity).toInt())
        includeFontPadding = false
        gravity = Gravity.CENTER_HORIZONTAL
        setSingleLine(true)
    }
    private val clock = TakuTallClockView(context, compact = !isLarge)
    internal var morphOverlay: TakuClockFaceView? = null
    var morphProgress: Float
        get() = clock.progress
        set(value) { clock.progress = value }

    internal fun copyDisplayFrom(other: TakuClockFaceView) {
        date.text = other.date.text
        clock.time = other.clock.time
        contentDescription = other.contentDescription
    }

    private var timeZone: TimeZone = TimeZone.getDefault()
    private var locale: Locale = Locale.getDefault()
    private var is24Hour = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        val time = clock
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (24 * density).toInt()
        })
        setTheme(isDark = true)
    }

    fun setTimeZone(zone: TimeZone) { timeZone = zone; refresh() }
    fun setLocale(value: Locale) { locale = value; refresh() }
    fun setIs24Hour(value: Boolean) { is24Hour = value; refresh() }

    /** The lock screen sits on a darkened wallpaper: white text, at the design's opacities. */
    fun setTheme(isDark: Boolean) {
        val base = if (isDark) Color.WHITE else Color.BLACK
        date.setTextColor(withAlpha(base, 0.8f))
        clock.setColor(base)
    }

    fun setTextSizePx(fontSizePx: Float) {
        // Outline geometry is calibrated to 120 px Space Grotesk in a 412 dp frame.
        // The view uniformly fits constrained widths; system font size does not distort the paths.
    }

    fun refresh(now: Date = Date()) {
        val datePattern = DateFormat.getInstanceForSkeleton("EEEEMMMd", locale).let { (it as? SimpleDateFormat)?.toPattern() } ?: "EEEE, MMM d"
        date.text = SimpleDateFormat(datePattern, locale).apply { timeZone = this@TakuClockFaceView.timeZone }.format(now)
        val timeText = SimpleDateFormat(if (is24Hour) "HH:mm" else "hh:mm", Locale.ROOT)
            .apply { timeZone = this@TakuClockFaceView.timeZone }.format(now)
        clock.time = timeText
        contentDescription = "${date.text}, $timeText"
    }

    private fun withAlpha(color: Int, alpha: Float) = Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
