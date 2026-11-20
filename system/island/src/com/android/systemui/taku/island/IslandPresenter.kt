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
import org.json.JSONObject

/** One presentation state machine; input and Agent operations converge here through IslandEngine. */
@SysUISingleton
class IslandPresenter @Inject constructor(
    private val context: Context,
    private val engine: IslandEngine,
    private val shade: ShadeInteractor,
    private val keyguard: KeyguardStateController,
    private val headsUp: HeadsUpManager,
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
    private var choosing = false
    private val alerted = mutableMapOf<String, Long>()
    private data class Relocated(val view: View, val parent: ViewGroup, val index: Int, val params: ViewGroup.LayoutParams)
    private val relocated = mutableListOf<Relocated>()
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutCapsule() }

    fun start() {
        engine.attachPresentation(this)
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
            tap = { if (card != null) invoke("collapse", null) else selected?.let { invoke("expand", it) } },
            cycle = ::cycle,
            choose = { invoke("choose", null) },
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
        if (old == null || (first?.key != arbiterPrimary && first != null &&
                (first.priority < old.priority || first.alertUntil > SystemClock.elapsedRealtime()))) {
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
                open(first, cover = false, isAlert = true)
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

    private fun cycle(delta: Int) {
        val candidates = items.filter { it.data.compactVisible }
        if (candidates.isEmpty()) return
        val index = candidates.indexOfFirst { it.key == selected }.coerceAtLeast(0)
        invoke("select", candidates[(index + delta + candidates.size) % candidates.size].key)
    }

    override fun choose(): Boolean {
        val item = items.firstOrNull { it.key == selected } ?: return false
        if (!canExpand()) return false
        collapse("choose")
        return open(item, cover = false, isAlert = false, choices = items.map { it.data })
    }

    override fun expand(key: String): Boolean {
        val item = items.find { it.key == key } ?: return false
        if (!canExpand()) return false
        selected = key
        if (cardKey == key && card != null && !choosing) { automatic = false; return true }
        collapse("switch")
        val cover = item.data.payload.optString("statusBar", "auto") != "below"
        return open(item, cover, false)
    }

    private fun open(item: IslandStore.Entry<IslandActivity>, cover: Boolean, isAlert: Boolean,
        choices: List<IslandActivity>? = null): Boolean {
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
                }, changed = engine::presentationChanged, choices = choices)
            candidate.show()
            card = candidate
            cardKey = item.key
            cardRevision = item.revision
            automatic = isAlert
            choosing = choices != null
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
        choosing = false
        this.reason = reason
        engine.presentationChanged()
    }

    private fun canExpand(): Boolean = bar?.isAttachedToWindow == true && !keyguard.isShowing &&
        !shade.isAnyExpanded.value && !headsUp.hasPinnedHeadsUp() &&
        context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    private fun layoutCapsule() {
        try { layoutCapsuleNow() } finally { engine.presentationChanged() }
    }

    private fun layoutCapsuleNow() {
        val view = bar ?: run { IslandPresentationOwnership.show(null); return }
        val pill = capsule ?: run { IslandPresentationOwnership.show(null); return }
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && card != null) collapse("landscape")
        selected = IslandStore.compactSelection(items, selected, card != null) { it.compactVisible }
        val activity = items.find { it.key == selected }?.data
        if (activity == null || !activity.compactVisible || view.alpha == 0f || keyguard.isShowing || shade.isAnyExpanded.value || headsUp.isHeadsUpEntry(activity.key)) {
            IslandPresentationOwnership.show(if (card != null) activity else null)
            pill.visibility = View.GONE
            view.setTakuIslandProbeWidth(0)
            restoreCentered()
            return
        }
        val cutout = view.rootWindowInsets?.displayCutout?.boundingRectTop ?: Rect()
        val physical = if (!cutout.isEmpty && cutout.centerX() in view.width / 3..view.width * 2 / 3) cutout.width() else 0
        val clock = maxOf(view.findViewById<View>(R.id.clock)?.width ?: 0, dp(52))
        val icons = maxOf(view.findViewById<View>(R.id.status_bar_end_side_content)?.width ?: 0, dp(64))
        // Figma 1379:889 anchors the island to the camera: the disc extends CAMERA_DP to its
        // left and the pill grows to its right. FrameLayout centering would include asymmetric
        // status-bar padding, so position from the actual display cutout instead.
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val cameraX = if (physical > 0) cutout.centerX() - location[0] else view.width / 2
        val leftRoom = cameraX - clock - dp(12)
        val rightRoom = view.width - cameraX - icons - dp(12)
        val wantsSecondary = !activity.building && items.count { it.data.compactVisible } > 1
        val width = if (leftRoom < dp(IslandCapsuleView.CAMERA_DP)) 0
            else pill.layoutWidth(activity, dp(IslandCapsuleView.CAMERA_DP) + rightRoom, wantsSecondary)
        if (width == 0) {
            IslandPresentationOwnership.show(null)
            pill.visibility = View.GONE
            view.setTakuIslandProbeWidth(0)
            restoreCentered()
            return
        }
        relocateCentered()
        pill.stale = items.find { it.key == selected }?.stale(SystemClock.elapsedRealtime()) == true
        pill.bind(activity, if (pill.secondary) items.firstOrNull { it.key != selected && it.data.compactVisible }?.data else null)
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
        view.setTakuIslandProbeWidth(width)
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

    override fun state(): JSONObject = JSONObject().put("selected", selected ?: JSONObject.NULL)
        .put("compactVisible", capsule?.visibility == View.VISIBLE && bar?.alpha != 0f).put("reason", reason)
        .put("compact", capsule?.takeIf { it.visibility == View.VISIBLE }?.state() ?: JSONObject.NULL)
        .put("expanded", card != null).put("card", card?.state() ?: JSONObject.NULL)

    private fun invoke(method: String, arg: String?, extra: String? = null) {
        val result = engine.command(method, arg, extra)
        if (!result.optBoolean("ok")) Toast.makeText(context, result.optString("error"), Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
