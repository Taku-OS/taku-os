/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * The island's expanded card as an ordinary view, for the lock screen's card column. It draws the
 * same native cards as [IslandCardWindow] ([IslandAssistantCard], [IslandProgressCard],
 * [IslandTemplateCard]) with the same fill and corners, but it is part of the page: no window, no
 * backdrop blur, never over the camera, and no remote surface from the publisher (an app's live
 * surface stays off the keyguard, so the native template always shows).
 *
 * [command] is the island operation a control asks for (`open`, `action`), as (method, key, extra);
 * the host decides how to run it on the lock screen. [fold] is the card's handle, swipe up or
 * Close button.
 */
class IslandLockScreenCard(context: Context, private val command: (String, String?, String?) -> Unit,
    private val fold: () -> Unit) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var activity: IslandActivity? = null
    private var kind = ""
    private var assistant: IslandAssistantCard? = null
    private var progress: IslandProgressCard? = null
    private var template: IslandTemplateCard? = null
    private val fill = GradientDrawable().apply { cornerRadius = IslandStyle.CORNER_DP * density }

    init {
        background = fill
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, IslandStyle.CORNER_DP * density)
            }
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = View.GONE
    }

    /** The activity whose card to draw, or null for none. A different kind of card rebuilds the content. */
    fun show(next: IslandActivity?) {
        if (next === activity) return
        activity = next
        if (next == null) {
            visibility = View.GONE
            return
        }
        val wanted = (if (next.nativeAssistant) "assistant" else if (next.nativeProgressCard) "progress" else "template") + "|" + next.key
        if (wanted != kind) {
            kind = wanted
            removeAllViews()
            assistant = null
            progress = null
            template = null
            val collapse: (String) -> Unit = { fold() }
            val content: View = when {
                next.nativeAssistant -> IslandAssistantCard(context, command, collapse).also { assistant = it }
                next.nativeProgressCard -> IslandProgressCard(context, command, collapse).also { progress = it }
                else -> IslandTemplateCard(context, command, collapse).also { template = it }
            }
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        fill.setColor(IslandStyle.cardFill(next))
        assistant?.update(next)
        progress?.update(next)
        template?.update(next)
        visibility = View.VISIBLE
        requestLayout()
    }

    /** As tall as the native card asks for at this width, at most a third of the screen, like the window. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val current = activity
        val wanted = when {
            current == null -> 0
            assistant != null -> assistant!!.desiredHeight(current, width)
            progress != null -> progress!!.desiredHeight(current, width)
            else -> template!!.heightFor(width)
        }
        val height = minOf(wanted, resources.displayMetrics.heightPixels / 3)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    fun state(): JSONObject = JSONObject().put("key", activity?.key ?: JSONObject.NULL)
        .put("visible", visibility == View.VISIBLE && isShown)
        .put("layout", kind.substringBefore('|')).put("renderer", "systemui-native")
        .put("width", width).put("height", height)
        .put("assistant", assistant?.state() ?: JSONObject.NULL)
        .put("template", template?.state() ?: JSONObject.NULL)
        .put("progress", progress?.state() ?: JSONObject.NULL)
}
