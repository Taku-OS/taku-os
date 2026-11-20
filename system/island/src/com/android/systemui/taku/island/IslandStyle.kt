/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.PathParser
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * Taku island design tokens (Figma 1:4031 expanded, 1379:776 compact). Every island surface, the
 * native assistant card, the template card for other publishers and the compact capsule, draws
 * with these; SystemUI never falls back to stock widgets for island chrome.
 */
internal object IslandStyle {
    /** content/900 at 92 %: the card and capsule fill. */
    const val CARD = 0xeb000000.toInt()
    const val CORNER_DP = 40
    const val PADDING_DP = 16
    /** surface/900, /800, /600, /200 (white at 92, 80, 48 and 8 %). */
    const val TEXT_PRIMARY = 0xebffffff.toInt()
    const val TEXT_BODY = 0xccffffff.toInt()
    const val TEXT_SECONDARY = 0xa3ffffff.toInt()
    const val TEXT_MUTED = 0x7affffff
    const val CHIP = 0x14ffffff
    const val TRACK = 0x29ffffff
    const val ACCENT = 0xffff6a28.toInt()

    @Volatile private var fonts: Pair<Typeface, Typeface>? = null

    /** Hanken Grotesk regular/medium from the Agent package that ships it; sans-serif otherwise. */
    private fun fonts(context: Context): Pair<Typeface, Typeface> = fonts ?: synchronized(this) {
        fonts ?: run {
            fun load(name: String, weight: Int) = runCatching {
                val app = context.createPackageContext("org.agentos.agent", 0)
                app.resources.getFont(app.resources.getIdentifier(name, "font", app.packageName))
            }.getOrDefault(Typeface.create("sans-serif", weight))
            Pair(load("hanken_grotesk_regular", Typeface.NORMAL), load("hanken_grotesk_medium", Typeface.NORMAL))
        }.also { fonts = it }
    }

    fun regular(context: Context): Typeface = fonts(context).first
    fun medium(context: Context): Typeface = fonts(context).second

    /** Body text: [size] sp with the design's −1 % tracking and no font padding. */
    fun label(context: Context, size: Float, color: Int, face: Typeface = regular(context)) = TextView(context).apply {
        textSize = size; setTextColor(color); typeface = face; includeFontPadding = false; letterSpacing = -.01f
    }

    /** Body/Medium/lg chip, 40 dp high: white 8 % (or white 92 % with dark text when [primary]). */
    fun chip(context: Context, title: CharSequence, primary: Boolean, click: () -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        val scaled = context.resources.displayMetrics.scaledDensity
        return label(context, 16f, if (primary) CARD else TEXT_PRIMARY, medium(context)).apply {
            text = title
            isSingleLine = true
            gravity = Gravity.CENTER
            val horizontal = (12 * density).toInt(); val vertical = (8 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            minHeight = (24 * scaled).toInt() + 2 * vertical
            background = GradientDrawable().apply { setColor(if (primary) TEXT_PRIMARY else CHIP); cornerRadius = 999 * density }
            isClickable = true; isFocusable = true
            setOnClickListener { click() }
        }
    }

    /** The 20 dp expand/collapse corner icon, exact paths from design/assistant-card/expand.svg. */
    class ExpandGlyph(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.66667f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val path = PathParser.createPathFromPathData("M1.77626 8.33333L1.71196 4.4221C1.69616 3.46072 1.68826 2.98003 1.87147 2.61455C2.03255 2.2932 2.2932 2.03255 2.61455 1.87146C2.98003 1.68826 3.46072 1.69616 4.4221 1.71196L8.33333 1.77626M18.2237 11.6667L18.288 15.5779C18.3038 16.5393 18.3117 17.02 18.1285 17.3854C17.9675 17.7068 17.7068 17.9675 17.3854 18.1285C17.02 18.3117 16.5393 18.3038 15.5779 18.288L11.6667 18.2237")
        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            canvas.save(); canvas.translate((width - 20 * d) / 2, (height - 20 * d) / 2); canvas.scale(d, d)
            canvas.drawPath(path, paint); canvas.restore()
        }
    }
}
