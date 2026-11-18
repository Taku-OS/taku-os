/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject

/** SystemUI owns geometry and touch; bounded remote cards may supply their own chrome. */
internal class IslandCardWindow(context: Context, initial: IslandActivity, coverRequested: Boolean,
    private val command: (String, String?, String?) -> Unit, private val collapse: (String) -> Unit,
    private val covering: (Boolean) -> Unit,
    private val changed: () -> Unit,
    private val choices: List<IslandActivity>? = null) {
    private val windowContext = context.createWindowContext(context.display,
        WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL, null)
    private val wm = windowContext.getSystemService(WindowManager::class.java)
    private val density = windowContext.resources.displayMetrics.density
    private val metrics = wm.currentWindowMetrics
    private val topInset = metrics.windowInsets.getInsetsIgnoringVisibility(
        WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
    private var cover = coverRequested && !metrics.windowInsets.isVisible(WindowInsets.Type.ime())
    private var activity = initial
    private val nativeAssistant = choices == null && initial.category == "assistant"
    private val nativeView = if (nativeAssistant) IslandAssistantCard(windowContext, command, collapse) else null
    private val fullCard = nativeAssistant || choices == null && initial.payload.getJSONObject("expanded").optJSONObject("content")?.optString("layout") == "card"
    private val root = FrameLayout(windowContext).apply {
        background = GradientDrawable().apply { setColor(0xeb000000.toInt()); cornerRadius = dp(40).toFloat() }
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val fallback = LinearLayout(windowContext).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(windowContext).apply { gravity = Gravity.CENTER_VERTICAL }
    private val title = TextView(windowContext).apply { setTextColor(Color.WHITE); textSize = 18f; maxLines = 2 }
    private val body = FrameLayout(windowContext)
    private val template = LinearLayout(windowContext).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(windowContext).apply { addView(template) }
    private val actions = LinearLayout(windowContext)
    private var host: IslandContentConnection? = null
    private var hostView: SurfaceView? = null
    private var hostIdentity: String? = null
    private var attached = false
    private var richReady = false
    private var templateGeneration = 0
    private var animation: ValueAnimator? = null
    private val region = Region()
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val back = OnBackInvokedCallback { collapse("back") }
    private var finalHeight = desiredHeight(initial)
    private var availableHeight = Int.MAX_VALUE
    private var heightCap = finalHeight
    private val params = WindowManager.LayoutParams(
        minOf(dp(420), metrics.bounds.width() - dp(16)), if (nativeAssistant) finalHeight else dp(48),
        WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL, flags(), PixelFormat.TRANSLUCENT,
    ).apply {
        title = "TakuIsland"
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = if (cover) dp(8) else topInset + dp(8)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
    }
    private val insetsListener = ViewTreeObserver.OnComputeInternalInsetsListener { info ->
        val path = Path().apply { addRoundRect(RectF(0f, 0f, root.width.toFloat(), root.height.toFloat()),
            dp(40).toFloat(), dp(40).toFloat(), Path.Direction.CW) }
        region.setPath(path, Region(0, 0, root.width, root.height))
        info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
        info.touchableRegion.set(region)
    }

    init {
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(windowContext).apply {
            text = "⌃"
            contentDescription = "Collapse island"
            setOnClickListener { collapse("handle") }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        var startY = 0f
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = event.y; true }
                MotionEvent.ACTION_UP -> {
                    if (startY - event.y > dp(24)) collapse("swipe_up")
                    true
                }
                else -> true
            }
        }
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        fallback.addView(header, LinearLayout.LayoutParams(-1, -2))
        fallback.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        fallback.addView(actions, LinearLayout.LayoutParams(-1, -2))
        root.addView(nativeView ?: fallback, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (insets.isVisible(WindowInsets.Type.ime()) && cover) {
                cover = false
                covering(false)
                params.flags = flags()
                params.y = topInset + dp(8)
                backDispatcher?.unregisterOnBackInvokedCallback(back)
                backDispatcher = null
                updatePadding()
            }
            if (attached) {
                val available = metrics.bounds.height() - insets.getInsets(WindowInsets.Type.ime()).bottom - params.y - dp(8)
                availableHeight = available
                if (available < dp(120)) root.post { collapse("ime_no_space") }
                else {
                    heightCap = minOf(finalHeight, available)
                    params.height = minOf(params.height, heightCap)
                    wm.updateViewLayout(root, params)
                    changed()
                }
            }
            insets
        }
        updatePadding()
        update(initial)
    }

    private fun flags(): Int = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    private fun updatePadding() {
        fallback.setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    fun show() {
        root.viewTreeObserver.addOnComputeInternalInsetsListener(insetsListener)
        wm.addView(root, params)
        attached = true
        covering(cover)
        // Native content is complete before addView: no template/remote-surface transition.
        if (nativeAssistant) return
        animation = ValueAnimator.ofInt(dp(48), finalHeight).apply {
            duration = 220
            addUpdateListener {
                if (attached) {
                    params.height = minOf(it.animatedValue as Int, heightCap)
                    wm.updateViewLayout(root, params)
                    changed()
                }
            }
            start()
        }
    }

    /** Content snapshots update views; they do not restart the shell's geometry animation. */
    fun update(next: IslandActivity) {
        activity = next
        title.text = next.title
        val wantedHeight = desiredHeight(next)
        if (wantedHeight != finalHeight) {
            finalHeight = wantedHeight
            heightCap = minOf(finalHeight, availableHeight)
            if (attached) {
                animation?.cancel()
                params.height = heightCap
                wm.updateViewLayout(root, params)
                changed()
            }
        }
        if (nativeView != null) { nativeView.update(next); return }
        template.removeAllViews()
        if (choices != null) {
            title.text = "Activities"
            choices.forEach { choice -> template.addView(Button(windowContext).apply {
                text = choice.title
                setOnClickListener { command("expand", choice.key, null) }
            }) }
            actions.removeAllViews()
            return
        }
        val currentTemplate = ++templateGeneration
        val expanded = next.payload.getJSONObject("expanded")
        expanded.optString("pic").takeIf { it.isNotEmpty() }?.let { key ->
            next.pics[key]?.let { icon ->
                val picture = android.widget.ImageView(windowContext).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    contentDescription = next.title
                }
                template.addView(picture, LinearLayout.LayoutParams(-1, dp(64)))
                icon.loadDrawableAsync(windowContext, { drawable ->
                    if (currentTemplate == templateGeneration) picture.setImageDrawable(drawable)
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
        }
        if (next.body.isNotBlank()) template.addView(text(next.body))
        expanded.optJSONArray("steps")?.let { steps ->
            for (index in 0 until steps.length()) {
                val step = steps.getJSONObject(index)
                template.addView(text((if (step.optBoolean("running")) "• " else "✓ ") + step.getString("text")))
            }
        }
        expanded.optJSONObject("progress")?.let { progress ->
            template.addView(ProgressBar(windowContext, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = !progress.has("value")
                max = 1000
                this.progress = (progress.optDouble("value", 0.0) * 1000).toInt()
                contentDescription = if (isIndeterminate) "In progress" else "${this.progress / 10}%"
            }, LinearLayout.LayoutParams(-1, dp(12)))
        }
        actions.removeAllViews()
        next.actions.forEach { (id, action) ->
            actions.addView(Button(windowContext).apply {
                text = action.title
                if (expanded.optString("primaryAction") == id) setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 2
                setOnClickListener { command("action", next.key, JSONObject().put("id", id).toString()) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        next.nativeActions.forEach { (id, action) ->
            actions.addView(Button(windowContext).apply {
                text = action.title
                setOnClickListener { command("action", next.key, JSONObject().put("id", id).toString()) }
            })
        }
        if (actions.childCount == 0 && next.openIntent != null) actions.addView(Button(windowContext).apply {
            text = "Open"
            setOnClickListener { command("open", next.key, null) }
        })
        val identity = next.contentService?.flattenToString()?.plus(":" + expanded.optJSONObject("content")?.optString("token"))
        if (identity != hostIdentity) {
            host?.close()
            host = null
            hostView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
            hostView = null
            hostIdentity = identity
            richReady = false
            scroll.visibility = View.VISIBLE
            fallback.visibility = View.VISIBLE
            if (identity != null) {
                val surface = SurfaceView(windowContext).apply {
                    alpha = 0f
                    holder.setFormat(PixelFormat.TRANSLUCENT)
                }
                (if (fullCard) root else body).addView(surface, FrameLayout.LayoutParams(-1, -1))
                hostView = surface
                host = IslandContentConnection(windowContext, next, surface) { ready ->
                    richReady = ready
                    if (fullCard) (root.background as GradientDrawable).setColor(if (ready) Color.TRANSPARENT else 0xeb000000.toInt())
                    if (fullCard) fallback.visibility = if (ready) View.INVISIBLE else View.VISIBLE
                    else scroll.visibility = if (ready) View.INVISIBLE else View.VISIBLE
                    surface.alpha = if (ready) 1f else 0f
                }.also { it.start() }
            }
        }
    }

    fun state(): JSONObject = JSONObject().put("key", activity.key).put("windowType", params.type)
        .put("cover", cover).put("focusable", cover && !fullCard).put("layout", if (nativeAssistant) "native-assistant" else if (fullCard) "card" else "body").put("richReady", richReady)
        .put("renderer", if (nativeAssistant) "systemui-native" else "template").put("nativeReady", nativeAssistant && attached)
        .put("assistant", nativeView?.state() ?: JSONObject.NULL)
        .put("width", root.width).put("height", root.height).put("top", params.y)
        .put("touchRegion", region.toString()).put("outsideTapCollapses", false)

    fun close() {
        covering(false)
        templateGeneration++
        animation?.cancel()
        animation = null
        host?.close()
        host = null
        backDispatcher?.unregisterOnBackInvokedCallback(back)
        backDispatcher = null
        root.viewTreeObserver.removeOnComputeInternalInsetsListener(insetsListener)
        if (attached) { attached = false; wm.removeViewImmediate(root) }
    }

    private fun text(value: String) = TextView(windowContext).apply {
        text = value
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(0, dp(3), 0, dp(3))
    }
    private fun desiredHeight(item: IslandActivity): Int {
        if (nativeView != null) return minOf(nativeView.desiredHeight(item, minOf(dp(420), metrics.bounds.width() - dp(16))), metrics.bounds.height() / 3)
        val requested = if (fullCard) item.payload.getJSONObject("expanded").optJSONObject("content")?.optInt("heightDp", 300) ?: 300 else 340
        return minOf(dp(requested.coerceIn(96, 340)), metrics.bounds.height() / 3)
    }
    private fun dp(value: Int) = (value * density).toInt()
}
