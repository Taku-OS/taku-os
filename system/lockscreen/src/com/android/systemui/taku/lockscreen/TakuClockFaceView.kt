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
 * - Small (authenticated, Figma 979:599): Space Grotesk 120 sp, tracking −3 %, white at 64 %.
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
    private val tall: TakuTallClockView? = if (isLarge) TakuTallClockView(context) else null
    private val compact: TextView? = if (isLarge) null else TextView(context).apply {
        typeface = TakuLockscreenFonts.spaceGrotesk
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 120f)
        letterSpacing = -0.03f
        includeFontPadding = false
        gravity = Gravity.CENTER_HORIZONTAL
        setSingleLine(true)
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
        val time = tall ?: compact!!
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
        compact?.setTextColor(withAlpha(base, 0.64f))
        tall?.setColor(withAlpha(base, 0.92f))
    }

    fun setTextSizePx(fontSizePx: Float) {
        // The keyguard reports a preferred clock text size; the design fixes the compact clock at 120 sp.
        if (fontSizePx > 0f) compact?.setTextSize(TypedValue.COMPLEX_UNIT_PX, maxOf(fontSizePx, 120f * resources.displayMetrics.scaledDensity))
    }

    fun refresh(now: Date = Date()) {
        val datePattern = DateFormat.getInstanceForSkeleton("EEEEMMMd", locale).let { (it as? SimpleDateFormat)?.toPattern() } ?: "EEEE, MMM d"
        date.text = SimpleDateFormat(datePattern, locale).apply { timeZone = this@TakuClockFaceView.timeZone }.format(now)
        val timeText = SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm", Locale.ROOT)
            .apply { timeZone = this@TakuClockFaceView.timeZone }.format(now)
        tall?.time = timeText
        compact?.text = timeText
        contentDescription = "${date.text}, $timeText"
    }

    private fun withAlpha(color: Int, alpha: Float) = Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
