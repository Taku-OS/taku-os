package com.android.systemui.taku.lockscreen

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import kotlin.math.abs

/**
 * One notification card in the Figma style (979:574):
 *
 * - compact (979:680): 72 dp, 48 dp picture, title (Body/Medium/xl) and one line below it;
 * - expanded (979:642): app header, a message panel (picture, sender, time, conversation, text) and up
 *   to two buttons, the last one primary.
 *
 * Tapping opens the notification, swiping sideways dismisses it (when the notification allows it) and
 * buttons run their actions; all three go through [Callbacks] to [TakuLockscreenNotifications].
 */
class TakuNotificationCardView(context: Context) : FrameLayout(context) {
    interface Callbacks {
        fun onOpen(key: String)
        fun onDismiss(key: String)
        fun onAction(key: String, index: Int)
    }

    var callbacks: Callbacks? = null
    var card: TakuNotificationCard? = null
        private set

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4

    init {
        background = GradientDrawable().apply {
            setColor(CARD_FILL)
            cornerRadius = dp(24f)
        }
        foreground = RippleDrawable(ColorStateList.valueOf(PRESSED), null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(24f) })
        val pad = dp(12f).toInt()
        setPadding(pad, pad, pad, pad)
        isClickable = true
        setOnClickListener { card?.let { callbacks?.onOpen(it.key) } }
    }

    fun bind(card: TakuNotificationCard) {
        val previous = this.card
        this.card = card
        if (previous != null && previous.sameContent(card)) return
        removeAllViews()
        addView(if (card.expanded) expandedContent(card) else compactContent(card))
        contentDescription = listOfNotNull(card.appLabel, card.title, card.text).joinToString(", ")
        isClickable = card.clickable
    }

    // region Layouts

    private fun compactContent(card: TakuNotificationCard): View {
        minimumHeight = dp(72f - 24f).toInt()
        val hasPicture = card.image != null
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48f).toInt())
        }
        row.addView(picture(card, 48f, 12f), LinearLayout.LayoutParams(dp(48f).toInt(), dp(48f).toInt())
            .apply { marginEnd = dp(8f).toInt() })
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        column.addView(text(card.title, TakuLockscreenFonts.hankenGroteskMedium, 18f, 1.55f, INK_900))
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Figma 979:680 / 979:687: the second line is the notification text; without text it names the
        // app, marked with the app icon (shown here only when the picture is not already the app icon).
        val body = card.text
        if (body == null && hasPicture) line.addView(appIcon(card), LinearLayout.LayoutParams(dp(16f).toInt(), dp(16f).toInt())
            .apply { marginEnd = dp(4f).toInt() })
        line.addView(text(body ?: card.appLabel, TakuLockscreenFonts.hankenGrotesk, 14f, 1.42f, INK_700))
        column.addView(line)
        row.addView(column, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun expandedContent(card: TakuNotificationCard): View {
        minimumHeight = 0
        val gap = dp(12f).toInt()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(appIcon(card), LinearLayout.LayoutParams(dp(16f).toInt(), dp(16f).toInt())
            .apply { marginEnd = dp(4f).toInt() })
        header.addView(text(card.appLabel, TakuLockscreenFonts.hankenGrotesk, 14f, 1.42f, INK_800),
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        column.addView(header)

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(PANEL_FILL); cornerRadius = dp(12f) }
            setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(12f).toInt())
        }
        panel.addView(picture(card, 32f, 6f), LinearLayout.LayoutParams(dp(32f).toInt(), dp(32f).toInt())
            .apply { marginEnd = dp(11f).toInt() })
        val message = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text(card.title, TakuLockscreenFonts.hankenGroteskMedium, 16f, 1.5f, INK_900),
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 0f)
                .apply { marginEnd = dp(8f).toInt() })
        if (card.showWhen) {
            top.addView(text(DateFormat.getTimeFormat(context).format(Date(card.whenMillis)),
                TakuLockscreenFonts.hankenGrotesk, 14f, 1.42f, INK_700))
        }
        top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        card.context?.let {
            top.addView(text(it, TakuLockscreenFonts.hankenGrotesk, 14f, 1.42f, INK_700).apply {
                maxWidth = dp(120f).toInt()
            })
        }
        message.addView(top)
        card.text?.let {
            message.addView(text(it, TakuLockscreenFonts.hankenGrotesk, 14f, 1.42f, INK_800, maxLines = 4),
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8f).toInt() })
        }
        panel.addView(message, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        column.addView(panel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4f).toInt() })

        if (card.actions.isNotEmpty()) {
            val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            card.actions.forEachIndexed { position, action ->
                val primary = position == card.actions.lastIndex
                buttons.addView(button(card.key, action, primary),
                    LinearLayout.LayoutParams(0, dp(40f).toInt(), 1f).apply {
                        if (position > 0) marginStart = dp(8f).toInt()
                    })
            }
            column.addView(buttons, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = gap })
        }
        return column
    }

    private fun button(key: String, action: TakuCardAction, primary: Boolean): TextView =
        text(action.title, TakuLockscreenFonts.hankenGrotesk, 16f, 1.5f, if (primary) Color.WHITE else FOREGROUND).apply {
            gravity = Gravity.CENTER
            val shape = GradientDrawable().apply {
                setColor(if (primary) FOREGROUND else BUTTON_FILL)
                cornerRadius = dp(20f)
            }
            background = RippleDrawable(ColorStateList.valueOf(if (primary) PRESSED_ON_DARK else PRESSED), shape, null)
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
            isClickable = true
            setOnClickListener { callbacks?.onAction(key, action.index) }
        }

    private fun picture(card: TakuNotificationCard, sizeDp: Float, radiusDp: Float): ImageView =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val image: Drawable? = card.image?.let { icon ->
                try {
                    icon.loadDrawable(context)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Notification picture unavailable for ${card.key}", error)
                    null
                }
            }
            setImageDrawable(image ?: card.appIcon)
            if (image != null) {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp))
                }
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            minimumWidth = dp(sizeDp).toInt()
        }

    private fun appIcon(card: TakuNotificationCard): ImageView = ImageView(context).apply {
        setImageDrawable(card.appIcon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun text(
        value: CharSequence,
        face: Typeface,
        sizeSp: Float,
        lineHeight: Float,
        color: Int,
        maxLines: Int = 1,
    ): TextView = TextView(context).apply {
        text = value
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        letterSpacing = -0.01f
        setLineHeight(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp * lineHeight,
            resources.displayMetrics).toInt())
        includeFontPadding = false
        setTextColor(color)
        this.maxLines = maxLines
        if (maxLines == 1) setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }

    // endregion

    // region Swipe to dismiss

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    /** Tracks the gesture; returns true once it is a sideways drag owned by this card. */
    private fun track(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                animate().cancel()
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    downX = event.rawX
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        velocity?.addMovement(event)
        return dragging
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = track(event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val wasDragging = dragging
        val start = event.actionMasked == MotionEvent.ACTION_DOWN
        if (!start || !wasDragging) track(event)
        if (!dragging) return super.onTouchEvent(event) || start
        if (!wasDragging) {
            // The press turned into a drag: drop the pressed state without clicking.
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.onTouchEvent(cancel)
            cancel.recycle()
        }
        val dx = event.rawX - downX
        val dismissable = card?.dismissable == true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                translationX = if (dismissable) dx else dx * RESISTANCE
                alpha = 1f - (abs(translationX) / width.coerceAtLeast(1)).coerceAtMost(1f) * 0.6f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val flung = abs(vx) > minFling && (vx > 0) == (dx > 0)
                val far = abs(dx) > width * DISMISS_FRACTION
                if (event.actionMasked == MotionEvent.ACTION_UP && dismissable && (far || flung)) settleOut(dx)
                else settleBack()
                dragging = false
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun settleOut(dx: Float) {
        val key = card?.key ?: return settleBack()
        animate().translationX(if (dx >= 0) width.toFloat() else -width.toFloat()).alpha(0f)
            .setDuration(SETTLE_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animate().setListener(null)
                    callbacks?.onDismiss(key)
                }
            })
            .start()
    }

    private fun settleBack() {
        animate().translationX(0f).alpha(1f).setDuration(SETTLE_MS).setListener(null).start()
    }

    /** A recycled card (or one whose dismissal failed) returns to rest. */
    fun resetSwipe() {
        animate().cancel()
        translationX = 0f
        alpha = 1f
    }

    // endregion

    private fun dp(value: Float) = value * density

    companion object {
        private const val TAG = "TakuNotificationCard"
        /** surface/700: white at 64 %. */
        private val CARD_FILL = Color.argb(0.64f, 1f, 1f, 1f)
        /** content/100: black at 6 %. */
        private val PANEL_FILL = Color.argb(0.06f, 0f, 0f, 0f)
        /** content/200: black at 8 %. */
        private val BUTTON_FILL = Color.argb(0.08f, 0f, 0f, 0f)
        private val PRESSED = Color.argb(0.08f, 0f, 0f, 0f)
        private val PRESSED_ON_DARK = Color.argb(0.16f, 1f, 1f, 1f)
        /** main/foreground. */
        private val FOREGROUND = Color.rgb(0x1B, 0x1B, 0x1B)
        private val INK_900 = Color.argb(0.92f, 0f, 0f, 0f)
        private val INK_800 = Color.argb(0.8f, 0f, 0f, 0f)
        private val INK_700 = Color.argb(0.64f, 0f, 0f, 0f)
        private const val RESISTANCE = 0.15f
        private const val DISMISS_FRACTION = 0.4f
        private const val SETTLE_MS = 200L
    }
}
