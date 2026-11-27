/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.Context
import android.content.res.Configuration
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val lock: IslandLockStatus,
    @Main private val handler: Handler,
    @Application private val scope: CoroutineScope,
) : IslandPresentation {
    private var bar: PhoneStatusBarView? = null
    private var capsule: IslandCapsuleView? = null
    private var items: List<IslandStore.Entry<IslandActivity>> = emptyList()
    private var selected: String? = null
    private var arbiterPrimary: String? = null
    private var knownKeys: Set<String> = emptySet()
    private var card: IslandCardWindow? = null
    private var cardKey: String? = null
    private var cardRevision = -1L
    private var reason = "startup"
    private var automatic = false
    /** The capsule currently draws the lock status instead of an activity. */
    private var statusShown = false
    /** Keys whose heads-up the island replaces right now; read by the interruption filter. */
    @Volatile private var presentable: Set<String> = emptySet()
    // Consume an accepted alert window once, not once per notification revision. Quiet updates,
    // held alerts and cooldown-rejected flips all retain the same deadline in IslandStore.
    private val alertedUntil = mutableMapOf<String, Long>()
    private data class Relocated(val view: View, val parent: ViewGroup, val index: Int, val params: ViewGroup.LayoutParams)
    private val relocated = mutableListOf<Relocated>()

    /**
     * What the lock screen draws. The status bar's window is under the keyguard, so the lock screen
     * hosts its own views for the same island (system/lockscreen: [IslandStatusView] for the capsule,
     * [IslandLockScreenCard] for the card). [activity] is the selected activity the lock screen may
     * show; [redacted] limits it to its glyph; [card] is set while its card is pinned.
     */
    data class LockScreen(val activity: IslandActivity? = null, val redacted: Boolean = false, val card: IslandActivity? = null)
    private val lockScreenState = MutableStateFlow(LockScreen())
    val lockScreen: StateFlow<LockScreen> = lockScreenState.asStateFlow()
    /** Lock-screen cards the user folded; they stay folded until the keyguard goes or a new alert. */
    private val folded = mutableSetOf<String>()

    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutCapsule() }

    fun start() {
        engine.attachPresentation(this)
        foreground.start {
            // Opening the card's own app (from the card or anywhere else) hands its content over.
            val open = items.find { it.key == cardKey }?.data
            if (open != null && foreground.suppresses(open)) collapse("app_opened")
            layoutCapsule()
        }
        keyguard.addCallback(object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() {
                if (keyguard.isShowing) collapse("keyguard")
                layoutCapsule()
            }
            // Authentication changes what the lock screen may show.
            override fun onUnlockedChanged() = layoutCapsule()
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
        scope.launch { lock.state.collect { layoutCapsule() } }
    }

    fun attach(view: PhoneStatusBarView) {
        if (view.context.displayId != Display.DEFAULT_DISPLAY || bar === view) return
        detach()
        bar = view
        capsule = IslandCapsuleView(view.context,
            // The open padlock has no card or app: any gesture on it just ends it early.
            tap = { if (statusShown) lock.dismiss() else if (card != null) invoke("collapse", null) else selected?.let { invoke("tap", it) } },
            longPress = { if (!statusShown) selected?.let { invoke("expand", it) } },
            // One activity: nothing to switch to, and no error toast for the gesture.
            next = { if (statusShown) lock.dismiss() else engine.command("next", null, null) },
            hide = { if (statusShown) lock.dismiss() else selected?.let { invoke("dismiss", it) } },
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
        IslandPresentationOwnership.show(null)
    }

    override fun render(activities: List<IslandStore.Entry<IslandActivity>>) {
        items = activities
        val old = items.find { it.key == selected }
        val first = items.firstOrNull()
        // A user's choice holds until something newer is triggered (a new activity, a rising alert
        // or a system-tier source) and takes the head of the order, or the chosen one ends. The
        // old head merely ending promotes the next one without overriding that choice.
        val overtaken = first != null && first.key != arbiterPrimary &&
            (items.any { it.key == arbiterPrimary } || first.key !in knownKeys)
        if (old == null || overtaken) {
            if (selected != first?.key && card != null) collapse("priority_changed")
            selected = first?.key
        }
        arbiterPrimary = first?.key
        knownKeys = items.map { it.key }.toSet()
        alertedUntil.keys.retainAll(items.map { it.key }.toSet())
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
        if (first != null && first.alertUntil > SystemClock.elapsedRealtime() && alertedUntil[first.key] != first.alertUntil) {
            alertedUntil[first.key] = first.alertUntil
            // On the lock screen an alert pins the activity's card again; nothing times it out there.
            if (keyguard.isShowing && engine.lockScreenExposure(first.data) != IslandLockScreenPolicy.HIDDEN) {
                folded.remove(first.key)
                selected = first.key
                layoutCapsule()
            }
            // The app in front already shows its own news; its alert neither expands nor waits.
            if (card == null && canExpand() && !foreground.suppresses(first.data)) {
                selected = first.key
                open(first, cover = covers(first.data), isAlert = true)
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
        val candidates = if (keyguard.isShowing) lockScreenCandidates(lockScreenExposures()) else items.filter { showsCapsule(it.data) }
        if (candidates.size < 2) return false
        val index = candidates.indexOfFirst { it.key == selected }.coerceAtLeast(0)
        return select(candidates[(index + 1) % candidates.size].key)
    }

    /**
     * Whether the island takes over this notification's heads-up: it can present right now, the
     * activity is an ordinary Taku-format one (calls, alarms and plain Live Updates keep Android's
     * heads-up), and the user has not hidden it. Brand-new posts are not extracted yet, so the
     * engine pre-checks those.
     */
    fun replacesHeadsUp(key: String, newIslandCandidate: () -> Boolean): Boolean =
        engine.policy.enabled && (key in presentable || (presentableNow && engine.isUnknown(key) && newIslandCandidate()))

    @Volatile private var presentableNow = false

    override fun expand(key: String): Boolean {
        val item = items.find { it.key == key } ?: return false
        if (keyguard.isShowing) {
            // The lock screen pins the card in its own column; only content it may show in full.
            if (!item.data.compactVisible || engine.lockScreenExposure(item.data) != IslandLockScreenPolicy.FULL) return false
            selected = key
            folded.remove(key)
            layoutCapsule()
            return lockScreenState.value.card?.key == key
        }
        if (!canExpand()) return false
        selected = key
        if (cardKey == key && card != null) { automatic = false; return true }
        collapse("switch")
        return open(item, covers(item.data), false)
    }

    /** The publisher's `cardPosition`; Taku Agent's own cards always cover the camera (Figma 297:374). */
    private fun covers(activity: IslandActivity) =
        activity.packageName == IslandPayload.TAKU_PACKAGE || activity.cardPosition != "below"

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
            layoutCapsule()
            true
        } catch (failure: RuntimeException) {
            candidate?.close()
            reason = "window_failed:${failure.javaClass.simpleName}"
            false
        }
    }

    override fun collapse(reason: String) {
        closeCard(reason)
        // An Agent's collapse folds the lock screen's pinned card, as the card's own handle does.
        if (reason == "agent") lockScreenState.value.card?.let { folded.add(it.key) }
        layoutCapsule()
    }

    /** The lock screen card's handle, swipe up or Close button. */
    fun foldLockScreenCard() {
        val pinned = lockScreenState.value.card ?: return
        folded.add(pinned.key)
        reason = "lock_screen_folded"
        layoutCapsule()
    }

    override fun expandedKey(): String? = cardKey.takeIf { card != null } ?: lockScreenState.value.card?.key

    /** [IslandLockScreenPolicy] exposure of every capsule-capable activity, computed once per pass. */
    private fun lockScreenExposures(): Map<String, Int> =
        items.filter { it.data.compactVisible }.associate { it.key to engine.lockScreenExposure(it.data) }

    /** Activities the lock screen may show at all, in island order. */
    private fun lockScreenCandidates(exposures: Map<String, Int>) = items.filter {
        (exposures[it.key] ?: IslandLockScreenPolicy.HIDDEN) != IslandLockScreenPolicy.HIDDEN
    }

    private fun layoutLockScreen() {
        if (!keyguard.isShowing) {
            folded.clear()
            lockScreenState.value = LockScreen()
            return
        }
        val exposures = lockScreenExposures()
        val candidates = lockScreenCandidates(exposures)
        val current = candidates.find { it.key == selected } ?: candidates.firstOrNull()
        if (current == null) {
            lockScreenState.value = LockScreen()
            return
        }
        // The lock screen's choice is the island's selection: taps, `next` and Agents act on it.
        selected = current.key
        val full = exposures[current.key] == IslandLockScreenPolicy.FULL
        lockScreenState.value = LockScreen(current.data, !full, current.data.takeIf { full && current.key !in folded })
    }

    private fun closeCard(reason: String) {
        card?.close()
        card = null
        cardKey = null
        automatic = false
        this.reason = reason
    }

    private fun canExpand(): Boolean = bar?.isAttachedToWindow == true && !keyguard.isShowing &&
        !shade.isAnyExpanded.value && !headsUp.hasPinnedHeadsUp() &&
        context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    private fun layoutCapsule() {
        try { layoutCapsuleNow() } finally {
            layoutLockScreen()
            // An alert only expands for the head of the order. While a call, alarm, recording or
            // cast holds it, ordinary activities keep their stock heads-up instead of losing both.
            val now = canExpand() && items.none { it.data.tier < IslandStore.TIER_ACTIVITY }
            presentableNow = now
            // Only Taku-format activities can alert, so only they replace their stock heads-up.
            // Plain Live Updates keep Android's heads-up for their important moments.
            presentable = if (now) items.filter { it.data.tier == IslandStore.TIER_ACTIVITY && it.data.source != IslandActivity.SOURCE_LIVE_UPDATE }
                .map { it.key }.toSet() else emptySet()
            engine.presentationChanged()
        }
    }

    private fun layoutCapsuleNow() {
        val view = bar ?: run { IslandPresentationOwnership.show(null); return }
        val pill = capsule ?: run { IslandPresentationOwnership.show(null); return }
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && card != null) {
            closeCard("landscape")
        }
        // The status-bar capsule is hidden under the keyguard; there the lock screen's rules choose
        // (layoutLockScreen). Foreground suppression must not move a selection made on the lock screen.
        if (!keyguard.isShowing) selected = IslandStore.compactSelection(items, selected, card != null, ::showsCapsule)
        val live = items.find { it.key == selected }?.data
        // Just unlocked: the open padlock takes the capsule for a moment, and the live activity
        // waits beside it. The lock screen draws its own padlock (IslandStatusView).
        val status = lock.unlocked().takeIf { card == null }
        val activity = status ?: live
        statusShown = false
        // The capsule and the card are mutually exclusive: an open card hides every capsule.
        if (activity == null || card != null || (status == null && !showsCapsule(activity)) || view.alpha == 0f || keyguard.isShowing ||
                shade.isAnyExpanded.value || headsUp.isHeadsUpEntry(activity.key)) {
            IslandPresentationOwnership.show(if (card != null) activity else null)
            pill.visibility = View.GONE
            view.setTakuIslandProbeWidth(0)
            restoreCentered()
            return
        }
        val contents = view.findViewById<View>(R.id.status_bar_contents)
        val contentLeft = view.paddingLeft + (contents?.paddingLeft ?: 0)
        val contentRight = view.width - view.paddingRight - (contents?.paddingRight ?: 0)
        val clock = maxOf(view.findViewById<View>(R.id.clock)?.width ?: 0,
            view.findViewById<View>(R.id.clock_center)?.width ?: 0, dp(52))
        val endWidth = view.findViewById<View>(R.id.status_bar_end_side_content)?.width ?: dp(64)
        val icons = view.findViewById<View>(R.id.statusIcons)?.takeIf { it.visibility != View.GONE }?.width ?: 0
        // Figma 1:4742 anchors the island to the camera: the disc extends to its left and the pill
        // grows to its right. FrameLayout centering would include asymmetric status-bar padding,
        // so position from the actual display cutout path (IslandCameraGeometry.locate).
        val camera = IslandCameraGeometry.locate(view, dp(64).toFloat())
        val cameraX = camera.x
        val cameraRadius = camera.radius
        val cameraY = camera.y
        pill.configure(view.height.coerceAtLeast(dp(16)), cameraRadius)
        val cameraLeft = pill.cameraOffset()
        // Another live activity waits as a circle left of the disc, only where the bar has room;
        // the main capsule keeps its camera-anchored position either way.
        val waitingLive = live?.takeIf { status != null && showsCapsule(it) }
        val wantsSecondary = if (status != null) waitingLive != null else items.count { showsCapsule(it.data) } > 1
        val withSecondary = wantsSecondary && IslandCapsuleGeometry.maximumWidth(contentLeft, contentRight,
            cameraX, cameraLeft + pill.leadWidth(), clock, endWidth, icons, dp(12)) > 0
        val leftWidth = cameraLeft + if (withSecondary) pill.leadWidth() else 0
        val maximumWidth = IslandCapsuleGeometry.maximumWidth(contentLeft, contentRight,
            cameraX, leftWidth, clock, endWidth, icons, dp(12))
        val width = pill.layoutWidth(activity, maximumWidth, withSecondary)
        if (width == 0) {
            IslandPresentationOwnership.show(null)
            pill.visibility = View.GONE
            view.setTakuIslandProbeWidth(0)
            restoreCentered()
            return
        }
        relocateCentered()
        pill.stale = status == null && items.find { it.key == selected }?.stale(SystemClock.elapsedRealtime()) == true
        val waiting = if (status != null) waitingLive.takeIf { pill.secondary } else secondary(activity)
        statusShown = status != null
        pill.bind(activity, waiting)
        pill.translationX = 0f
        pill.visibility = View.VISIBLE
        IslandPresentationOwnership.show(if (view.isShown) activity else null, if (view.isShown) waiting else null)
        // Tall enough for the 32 dp shapes (scaled on short bars) and the camera circle, centred
        // on the camera so the necks meet the real hole.
        val height = minOf(view.height, maxOf((32 * pill.unit).toInt(), kotlin.math.ceil(2 * cameraRadius + 2).toInt()))
        val params = FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT)
        params.leftMargin = cameraX - leftWidth - view.paddingLeft
        val top = Math.round(cameraY - height / 2f).coerceIn(0, (view.height - height).coerceAtLeast(0))
        params.topMargin = top - view.paddingTop
        pill.centerShift = (cameraY - top - height / 2f)
        val previous = pill.layoutParams as FrameLayout.LayoutParams
        if (previous.width != width || previous.height != height || previous.gravity != params.gravity ||
            previous.leftMargin != params.leftMargin || previous.topMargin != params.topMargin) pill.layoutParams = params
        view.setTakuIslandProbeWidth(IslandCapsuleGeometry.reservationWidth(
            contentLeft, contentRight, cameraX, leftWidth, width))
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

    override fun state(): JSONObject {
        val visible = capsule?.takeIf { it.visibility == View.VISIBLE && bar?.alpha != 0f && card == null }
        return JSONObject().put("selected", selected ?: JSONObject.NULL)
        .put("compactVisible", visible != null).put("reason", reason)
        .put("foregroundPackage", foreground.foregroundPackage ?: JSONObject.NULL)
        .put("suppressedByForeground", JSONArray(items.filter { it.data.compactVisible && foreground.suppresses(it.data) }.map { it.key }))
        .put("secondaryKey", visible?.let { items.find { it.key == selected }?.data?.let { if (statusShown) it.key.takeIf { visible.secondary } else secondary(it)?.key } } ?: JSONObject.NULL)
        .put("tap", items.find { it.key == selected }?.data?.tap ?: JSONObject.NULL)
        .put("compact", visible?.state() ?: JSONObject.NULL)
        .put("expanded", card != null || lockScreenState.value.card != null).put("card", card?.state() ?: JSONObject.NULL)
        // Where the island is drawn now, and what the lock screen's own views show of it.
        .put("surface", if (keyguard.isShowing) "lock-screen" else "status-bar")
        .put("lockScreen", lockScreenState.value.let { JSONObject().put("key", it.activity?.key ?: JSONObject.NULL)
            .put("redacted", if (it.activity == null) JSONObject.NULL else if (it.redacted) "glyph" else "none")
            .put("card", it.card?.key ?: JSONObject.NULL) })
        // The lock status (IslandLockStatus), and whether this capsule is the one drawing it now.
        .put("lockStatus", lock.state.value.wire ?: JSONObject.NULL).put("lockStatusInCapsule", visible != null && statusShown)
    }

    private fun invoke(method: String, arg: String?, extra: String? = null) {
        val result = engine.command(method, arg, extra)
        if (!result.optBoolean("ok")) Toast.makeText(context, result.optString("error"), Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
