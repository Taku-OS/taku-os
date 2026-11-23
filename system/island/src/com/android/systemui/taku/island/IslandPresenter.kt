/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One presentation state machine; input and Agent operations converge here through IslandEngine. */
@SysUISingleton
class IslandPresenter @Inject constructor(
    private val context: Context,
    private val engine: IslandEngine,
    private val shade: ShadeInteractor,
    private val keyguard: KeyguardStateController,
    private val headsUp: HeadsUpManager,
    private val foreground: IslandForeground,
    @Main private val handler: Handler,
    @Application private val scope: CoroutineScope,
) : IslandPresentation {
    private var bar: PhoneStatusBarView? = null
    private var capsule: IslandCapsuleView? = null
    private var items: List<IslandStore.Entry<IslandActivity>> = emptyList()
    private var selected: String? = null
    private var arbiterPrimary: String? = null
    private var card: IslandCardWindow? = null
    private var cardKey: String? = null
    private var cardRevision = -1L
    private var reason = "startup"
    private var automatic = false
    /** Keys whose heads-up the island replaces right now; read by the interruption filter. */
    @Volatile private var presentable: Set<String> = emptySet()
    private val alerted = mutableMapOf<String, Long>()
    private data class Relocated(val view: View, val parent: ViewGroup, val index: Int, val params: ViewGroup.LayoutParams)
    private val relocated = mutableListOf<Relocated>()
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutCapsule() }

    fun start() {
        engine.attachPresentation(this)
        foreground.start { layoutCapsule() }
        keyguard.addCallback(object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() {
                if (keyguard.isShowing) collapse("keyguard")
                layoutCapsule()
            }
        })
        headsUp.addListener(object : OnHeadsUpChangedListener {
            override fun onHeadsUpStateChanged(entry: com.android.systemui.statusbar.notification.collection.NotificationEntry, isHeadsUp: Boolean) {
                layoutCapsule()
            }
            override fun onHeadsUpPinnedModeChanged(inPinnedMode: Boolean) {
                if (inPinnedMode) collapse("heads_up")
                layoutCapsule()
            }
        })
        scope.launch {
            shade.isAnyExpanded.collect { expanded ->
                if (expanded) collapse("shade")
                layoutCapsule()
            }
        }
    }

    fun attach(view: PhoneStatusBarView) {
        if (view.context.displayId != Display.DEFAULT_DISPLAY || bar === view) return
        detach()
        bar = view
        capsule = IslandCapsuleView(view.context,
            tap = { if (card != null) invoke("collapse", null) else selected?.let { invoke("tap", it) } },
            longPress = { selected?.let { invoke("expand", it) } },
            next = { invoke("next", null) },
            hide = { selected?.let { invoke("dismiss", it) } },
        )
        view.addView(capsule, FrameLayout.LayoutParams(dp(180), dp(28), Gravity.CENTER))
        view.addOnLayoutChangeListener(layout)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = layoutCapsule()
            override fun onViewDetachedFromWindow(v: View) {
                if (bar === v) detach()
                v.removeOnAttachStateChangeListener(this)
            }
        })
        layoutCapsule()
    }

    private fun detach() {
        IslandPresentationOwnership.show(null)
        collapse("status_bar_detached")
        restoreCentered()
        bar?.removeOnLayoutChangeListener(layout)
        capsule?.let { bar?.removeView(it) }
        bar?.setTakuIslandProbeWidth(0)
        capsule = null
        bar = null
    }

    override fun render(activities: List<IslandStore.Entry<IslandActivity>>) {
        items = activities
        val old = items.find { it.key == selected }
        val first = items.firstOrNull()
        // A user's choice holds until something newer is triggered (a new activity, a rising alert
        // or a system-tier source) and takes the head of the order, or the chosen one ends.
        if (old == null || (first != null && first.key != arbiterPrimary)) {
            if (selected != first?.key && card != null) collapse("priority_changed")
            selected = first?.key
        }
        arbiterPrimary = first?.key
        alerted.keys.retainAll(items.map { it.key }.toSet())
        val expanded = items.find { it.key == cardKey }
        if (card != null && expanded == null) collapse("source_ended")
        if (automatic && expanded != null && expanded.alertUntil <= SystemClock.elapsedRealtime()) collapse("alert_timeout")
        if (expanded != null && expanded.revision != cardRevision) {
            cardRevision = expanded.revision
            // An update the card cannot draw closes it rather than leaving stale or partial chrome.
            try { card?.update(expanded.data) } catch (failure: RuntimeException) {
                collapse("render_failed")
                reason = "render_failed:${failure.javaClass.simpleName}"
            }
        }
        layoutCapsule()
        if (first != null && first.alertUntil > SystemClock.elapsedRealtime() && alerted[first.key] != first.revision) {
            alerted[first.key] = first.revision
            if (card == null && canExpand()) {
                selected = first.key
                open(first, cover = first.data.cardPosition != "below", isAlert = true)
                handler.postDelayed({ if (automatic && cardKey == first.key &&
                    (items.find { it.key == first.key }?.alertUntil ?: 0) <= SystemClock.elapsedRealtime()) collapse("alert_timeout") },
                    (first.alertUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            }
        }
    }

    override fun select(key: String): Boolean {
        if (items.none { it.key == key }) return false
        selected = key
        if (card != null && cardKey != key) collapse("selection_changed")
        layoutCapsule()
        return true
    }

    /** Capsule data exists and the publishing app is not the one in front. */
    private fun showsCapsule(activity: IslandActivity) = activity.compactVisible && !foreground.suppresses(activity)

    /** Swipe left: the next activity that can show a capsule, wrapping around. */
    override fun next(): Boolean {
        val candidates = items.filter { showsCapsule(it.data) }
        if (candidates.size < 2) return false
        val index = candidates.indexOfFirst { it.key == selected }.coerceAtLeast(0)
        return select(candidates[(index + 1) % candidates.size].key)
    }

    /**
     * Whether the island takes over this notification's heads-up: it can present right now, the
     * activity is an ordinary one (calls and alarms keep Android's heads-up), and the user has not
     * hidden it. Brand-new posts are not extracted yet, so the engine pre-checks those.
     */
    fun replacesHeadsUp(key: String, newIslandCandidate: () -> Boolean): Boolean =
        engine.policy.enabled && (key in presentable || (presentableNow && engine.isUnknown(key) && newIslandCandidate()))

    @Volatile private var presentableNow = false

    override fun expand(key: String): Boolean {
        val item = items.find { it.key == key } ?: return false
        if (!canExpand()) return false
        selected = key
        if (cardKey == key && card != null) { automatic = false; return true }
        collapse("switch")
        val cover = item.data.cardPosition != "below"
        return open(item, cover, false)
    }

    private fun open(item: IslandStore.Entry<IslandActivity>, cover: Boolean, isAlert: Boolean): Boolean {
        val view = bar ?: return false
        var candidate: IslandCardWindow? = null
        return try {
            // Construction binds the first content; a payload the card cannot draw never shows.
            candidate = IslandCardWindow(view.context, item.data, cover, ::invoke, ::collapse,
                covering = { covered ->
                    view.alpha = if (covered) 0f else 1f
                    // show/close update the card reference after this callback. Reconcile afterward,
                    // avoiding a recursive close during rotation to landscape.
                    handler.post { layoutCapsule() }
                }, changed = engine::presentationChanged)
            candidate.show()
            card = candidate
            cardKey = item.key
            cardRevision = item.revision
            automatic = isAlert
            reason = if (isAlert) "alert" else "expanded"
            true
        } catch (failure: RuntimeException) {
            candidate?.close()
            reason = "window_failed:${failure.javaClass.simpleName}"
            false
        }
    }

    override fun collapse(reason: String) {
        card?.close()
        card = null
        cardKey = null
        automatic = false
        this.reason = reason
        handler.post { layoutCapsule() }
        engine.presentationChanged()
    }

    private fun canExpand(): Boolean = bar?.isAttachedToWindow == true && !keyguard.isShowing &&
        !shade.isAnyExpanded.value && !headsUp.hasPinnedHeadsUp() &&
        context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    private fun layoutCapsule() {
        try { layoutCapsuleNow() } finally {
            val now = canExpand()
            presentableNow = now
            presentable = if (now) items.filter { it.data.tier == IslandStore.TIER_ACTIVITY }.map { it.key }.toSet() else emptySet()
            engine.presentationChanged()
        }
    }

    private fun layoutCapsuleNow() {
        val view = bar ?: run { IslandPresentationOwnership.show(null); return }
        val pill = capsule ?: run { IslandPresentationOwnership.show(null); return }
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && card != null) collapse("landscape")
        selected = IslandStore.compactSelection(items, selected, card != null, ::showsCapsule)
        val activity = items.find { it.key == selected }?.data
        // The capsule and the card are mutually exclusive: an open card hides every capsule.
        if (activity == null || card != null || !showsCapsule(activity) || view.alpha == 0f || keyguard.isShowing ||
                shade.isAnyExpanded.value || headsUp.isHeadsUpEntry(activity.key)) {
            IslandPresentationOwnership.show(if (card != null) activity else null)
            pill.visibility = View.GONE
            view.setTakuIslandProbeWidth(0)
            restoreCentered()
            return
        }
        val cutout = view.rootWindowInsets?.displayCutout?.boundingRectTop ?: Rect()
        val physical = if (!cutout.isEmpty && cutout.centerX() in view.width / 3..view.width * 2 / 3) cutout.width() else 0
        val contents = view.findViewById<View>(R.id.status_bar_contents)
        val contentLeft = view.paddingLeft + (contents?.paddingLeft ?: 0)
        val contentRight = view.width - view.paddingRight - (contents?.paddingRight ?: 0)
        val clock = maxOf(view.findViewById<View>(R.id.clock)?.width ?: 0,
            view.findViewById<View>(R.id.clock_center)?.width ?: 0, dp(52))
        val endWidth = view.findViewById<View>(R.id.status_bar_end_side_content)?.width ?: dp(64)
        val icons = view.findViewById<View>(R.id.statusIcons)?.takeIf { it.visibility != View.GONE }?.width ?: 0
        // Figma 1379:889 anchors the island to the camera: the disc extends CAMERA_DP to its
        // left and the pill grows to its right. FrameLayout centering would include asymmetric
        // status-bar padding, so position from the actual display cutout instead.
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val cameraX = if (physical > 0) cutout.centerX() - location[0] else view.width / 2
        val cameraLeftWidth = dp(IslandCapsuleView.CAMERA_DP)
        val maximumWidth = IslandCapsuleGeometry.maximumWidth(contentLeft, contentRight,
            cameraX, cameraLeftWidth, clock, endWidth, icons, dp(12))
        val wantsSecondary = items.count { showsCapsule(it.data) } > 1
        val width = pill.layoutWidth(activity, maximumWidth, wantsSecondary)
        if (width == 0) {
            IslandPresentationOwnership.show(null)
            pill.visibility = View.GONE
            view.setTakuIslandProbeWidth(0)
            restoreCentered()
            return
        }
        relocateCentered()
        pill.stale = items.find { it.key == selected }?.stale(SystemClock.elapsedRealtime()) == true
        pill.bind(activity, secondary(activity))
        pill.translationX = 0f
        pill.visibility = View.VISIBLE
        IslandPresentationOwnership.show(if (view.isShown) activity else null)
        val height = minOf(dp(32), view.height.coerceAtLeast(dp(24)))
        val params = FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT)
        params.leftMargin = cameraX - dp(IslandCapsuleView.CAMERA_DP) - view.paddingLeft
        params.topMargin = (view.height - height) / 2 - view.paddingTop
        val previous = pill.layoutParams as FrameLayout.LayoutParams
        if (previous.width != width || previous.height != height || previous.gravity != params.gravity ||
            previous.leftMargin != params.leftMargin || previous.topMargin != params.topMargin) pill.layoutParams = params
        view.setTakuIslandProbeWidth(IslandCapsuleGeometry.reservationWidth(
            contentLeft, contentRight, cameraX, cameraLeftWidth, width))
    }

    private fun relocateCentered() {
        val view = bar ?: return
        val target = view.findViewById<LinearLayout>(R.id.status_bar_start_side_except_heads_up) ?: return
        for (id in listOf(R.id.clock_center, R.id.network_traffic_holder_center)) {
            val item = view.findViewById<View>(id) ?: continue
            if (item.visibility != View.VISIBLE || item.parent === target || relocated.any { it.view === item }) continue
            val parent = item.parent as? ViewGroup ?: continue
            relocated.add(Relocated(item, parent, parent.indexOfChild(item), item.layoutParams))
            parent.removeView(item)
            target.addView(item, 0, LinearLayout.LayoutParams(-2, -1))
        }
    }

    private fun restoreCentered() {
        for (item in relocated.asReversed()) {
            (item.view.parent as? ViewGroup)?.removeView(item.view)
            item.parent.addView(item.view, minOf(item.index, item.parent.childCount), item.params)
        }
        relocated.clear()
    }

    /** The activity after the selected one, drawn as the system-managed secondary disc. */
    private fun secondary(activity: IslandActivity): IslandActivity? {
        if (capsule?.secondary != true) return null
        val candidates = items.filter { showsCapsule(it.data) }
        val index = candidates.indexOfFirst { it.key == activity.key }
        return candidates.getOrNull((index + 1) % candidates.size.coerceAtLeast(1))?.data?.takeIf { it.key != activity.key }
    }

    override fun state(): JSONObject = JSONObject().put("selected", selected ?: JSONObject.NULL)
        .put("compactVisible", capsule?.visibility == View.VISIBLE && bar?.alpha != 0f).put("reason", reason)
        .put("foregroundPackage", foreground.foregroundPackage ?: JSONObject.NULL)
        .put("suppressedByForeground", JSONArray(items.filter { it.data.compactVisible && foreground.suppresses(it.data) }.map { it.key }))
        .put("secondaryKey", items.find { it.key == selected }?.data?.let { secondary(it)?.key } ?: JSONObject.NULL)
        .put("tap", items.find { it.key == selected }?.data?.tap ?: JSONObject.NULL)
        .put("compact", capsule?.takeIf { it.visibility == View.VISIBLE }?.state() ?: JSONObject.NULL)
        .put("expanded", card != null).put("card", card?.state() ?: JSONObject.NULL)

    private fun invoke(method: String, arg: String?, extra: String? = null) {
        val result = engine.command(method, arg, extra)
        if (!result.optBoolean("ok")) Toast.makeText(context, result.optString("error"), Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
