/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
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
    /** Backdrop Blur/md behind every expanded card. */
    const val BLUR_DP = 24
    /** Card fill when compositor blur is off: opaque, so the screen behind never shows through. */
    const val CARD_OPAQUE = 0xff121212.toInt()
    /** surface/900, /800, /600, /200 (white at 92, 80, 48 and 8 %). */
    const val TEXT_PRIMARY = 0xebffffff.toInt()
    const val TEXT_BODY = 0xccffffff.toInt()
    const val TEXT_SECONDARY = 0xa3ffffff.toInt()
    const val TEXT_MUTED = 0x7affffff
    const val CHIP = 0x14ffffff
    const val TRACK = 0x29ffffff
    const val ACCENT = 0xffff6a28.toInt()

    /**
     * Text and control colours for one card background. [DARK] is the Taku card; a template's
     * light `backgroundColor` (such as white) gets the matching content/ tokens in black.
     */
    data class Palette(val primary: Int, val body: Int, val secondary: Int, val muted: Int, val chip: Int, val track: Int)
    val DARK = Palette(TEXT_PRIMARY, TEXT_BODY, TEXT_SECONDARY, TEXT_MUTED, CHIP, TRACK)
    /** content/900, /800, /700, /600, /200 and /300 (black at 92, 80, 64, 48, 8 and 16 %). */
    val LIGHT = Palette(0xeb000000.toInt(), 0xcc000000.toInt(), 0xa3000000.toInt(), 0x7a000000, 0x14000000, 0x29000000)

    /** The publisher's validated `#RRGGBB` card background, or null for the Taku card. */
    fun background(activity: IslandActivity): Int? = activity.backgroundColor

    /** The card fill drawn over the backdrop blur: the background at 92 % (content/900's alpha). */
    fun cardFill(activity: IslandActivity): Int = background(activity)?.let { (it and 0xffffff) or 0xeb000000.toInt() } ?: CARD

    fun opaqueFill(activity: IslandActivity): Int = background(activity) ?: CARD_OPAQUE

    fun palette(activity: IslandActivity): Palette = background(activity)?.takeIf(::prefersDarkText)?.let { LIGHT } ?: DARK

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

    private val iconLoader by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(2) { task -> Thread(task, "TakuIslandIcons").apply { isDaemon = true } }
    }

    /**
     * Loads a publisher's icon off the main thread and delivers it there. A resource icon is
     * inflated in the publisher's own package context: [Icon.loadDrawableAsync] inflates with
     * SystemUI's theme, so an AnimatedVectorDrawable's animators resolve against SystemUI's
     * resources and fail with Resources.NotFoundException (static vectors happen to work).
     */
    fun loadIcon(context: Context, icon: Icon, done: (Drawable?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        if (icon.type != Icon.TYPE_RESOURCE) { icon.loadDrawableAsync(context, { done(it) }, main); return }
        iconLoader.execute {
            val drawable = runCatching { context.createPackageContext(icon.resPackage, 0).getDrawable(icon.resId) }.getOrNull()
                ?: runCatching { icon.loadDrawable(context) }.getOrNull()
            main.post { done(drawable) }
        }
    }

    fun regular(context: Context): Typeface = fonts(context).first
    fun medium(context: Context): Typeface = fonts(context).second

    /** Body text: [size] sp with the design's −1 % tracking and no font padding. */
    fun label(context: Context, size: Float, color: Int, face: Typeface = regular(context)) = TextView(context).apply {
        textSize = size; setTextColor(color); typeface = face; includeFontPadding = false; letterSpacing = -.01f
    }

    /**
     * Body/Medium/lg chip, 40 dp high: the palette's 8 % chip fill (or its primary colour, with
     * contrasting text, when [primary]). A primary chip with an [accent] fills with it instead.
     */
    fun chip(context: Context, title: CharSequence, primary: Boolean, accent: Int? = null, palette: Palette = DARK, click: () -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        val scaled = context.resources.displayMetrics.scaledDensity
        val fill = if (primary) accent ?: palette.primary else palette.chip
        val ink = if (!primary) palette.primary else if (prefersDarkText(fill or 0xff000000.toInt())) CARD else TEXT_PRIMARY
        return label(context, 16f, ink, medium(context)).apply {
            text = title
            isSingleLine = true
            gravity = Gravity.CENTER
            val horizontal = (12 * density).toInt(); val vertical = (8 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            minHeight = (24 * scaled).toInt() + 2 * vertical
            background = GradientDrawable().apply { setColor(fill); cornerRadius = 999 * density }
            isClickable = true; isFocusable = true
            setOnClickListener { click() }
        }
    }

    /** Whether black text reads better than white on [color] (WCAG relative luminance). */
    fun prefersDarkText(color: Int): Boolean {
        val luminance = IslandPayload.luminance(color and 0xffffff)
        return (luminance + .05) / .05 >= 1.05 / (luminance + .05)
    }

    /** The 20 dp expand/collapse corner icon, exact paths from design/assistant-card/expand.svg. */
    class ExpandGlyph(context: Context) : View(context) {
        var color: Int = Color.WHITE
            set(value) { if (field != value) { field = value; paint.color = value; invalidate() } }
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
