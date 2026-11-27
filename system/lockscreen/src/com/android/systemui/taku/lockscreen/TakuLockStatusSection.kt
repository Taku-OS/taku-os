package com.android.systemui.taku.lockscreen

import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import com.android.systemui.taku.island.IslandLockStatus
import com.android.systemui.taku.island.IslandStatusView
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The lock state in the island: a padlock capsule at the camera, closed while locked and open for a
 * moment after authenticating ([IslandLockStatus] owns the state and the timing). The status bar's
 * own island is hidden on the keyguard, so this section hosts [IslandStatusView], the same capsule,
 * in the keyguard root. After an unlock that leaves the lock screen, the status-bar island takes
 * over the open padlock.
 *
 * It replaces the stock padlock above the shortcuts: the device entry icon's lock and unlock glyphs
 * are drawn fully transparent here (image alpha, not view alpha, so TalkBack still sees the view).
 * Only the picture goes. The icon view keeps its place, its accessibility label and action, and its
 * long press, and the fingerprint glyph (UDFPS) is untouched.
 *
 * Shown on the lock screen without the shade, like the cards, and not while dozing.
 */
@SysUISingleton
class TakuLockStatusSection
@Inject
constructor(
    private val lockStatus: IslandLockStatus,
    private val keyguardInteractor: KeyguardInteractor,
    private val containerViewModel: SharedNotificationContainerViewModel,
    private val deviceEntryIconViewModel: DeviceEntryIconViewModel,
) : KeyguardSection() {
    private val viewId = View.generateViewId()
    private var binding: DisposableHandle? = null
    private var island: IslandStatusView? = null
    private var carrier: View? = null
    private var carrierPaddingEnd = 0
    private val reserve = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reserveCarrierText() }
    private var root: ConstraintLayout? = null
    /** The stock icon currently shows its lock or unlock picture, which the island replaces. */
    private var stockPadlock = false
    // Re-applied after every layout: the stock section may rebuild its icon view at any time.
    private val enforce = ViewTreeObserver.OnGlobalLayoutListener { hideStockPadlock() }

    private fun hideStockPadlock() {
        val alpha = if (stockPadlock) 0 else 255
        root?.findViewById<ImageView>(R.id.device_entry_icon_fg)?.takeIf { it.imageAlpha != alpha }?.imageAlpha = alpha
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<View>(viewId) != null) return
        constraintLayout.addView(IslandStatusView(constraintLayout.context).apply {
            id = viewId
            visibility = View.INVISIBLE
        })
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val view = constraintLayout.findViewById<IslandStatusView>(viewId) ?: return
        island?.removeOnLayoutChangeListener(reserve)
        island = view
        view.addOnLayoutChangeListener(reserve)
        root?.viewTreeObserver?.removeOnGlobalLayoutListener(enforce)
        root = constraintLayout
        constraintLayout.viewTreeObserver.addOnGlobalLayoutListener(enforce)
        binding?.dispose()
        binding = view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    lockStatus.state.collect {
                        view.status = lockStatus.activity()
                        reserveCarrierText()
                    }
                }
                launch {
                    combine(containerViewModel.isOnLockscreenWithoutShade, keyguardInteractor.isDozing) {
                        onLockscreen, dozing -> onLockscreen && !dozing
                    }.distinctUntilChanged().collect { shown ->
                        view.fadeShown(shown)
                        reserveCarrierText()
                    }
                }
                launch {
                    deviceEntryIconViewModel.iconType.collect { type ->
                        stockPadlock = type == DeviceEntryIconView.IconType.LOCK || type == DeviceEntryIconView.IconType.UNLOCK
                        hideStockPadlock()
                    }
                }
            }
        }
    }

    /**
     * The capsule's disc reaches left of the camera, past the space the keyguard status bar keeps
     * clear for the cutout. A long carrier text ("Emergency calls only") would run under it, so it
     * gets that much end padding while the capsule is drawn.
     */
    private fun reserveCarrierText() {
        val view = island ?: return
        val text = carrier ?: view.rootView.findViewById<View>(R.id.keyguard_carrier_text)?.also {
            carrier = it
            carrierPaddingEnd = it.paddingEnd
        } ?: return
        val capsuleLeft = view.takeIf { it.visibility == View.VISIBLE }?.capsuleLeftOnScreen()
        val origin = IntArray(2)
        text.getLocationOnScreen(origin)
        val gap = (CARRIER_GAP_DP * view.resources.displayMetrics.density).toInt()
        val overlap = if (capsuleLeft == null || text.layoutDirection == View.LAYOUT_DIRECTION_RTL) 0
            else (origin[0] + text.width - capsuleLeft + gap).coerceAtLeast(0)
        val padding = carrierPaddingEnd + overlap
        if (text.paddingEnd != padding) text.setPaddingRelative(text.paddingStart, text.paddingTop, padding, text.paddingBottom)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(viewId, ConstraintSet.MATCH_CONSTRAINT)
            // The view sizes itself to the status bar height.
            constrainHeight(viewId, ConstraintSet.WRAP_CONTENT)
            connect(viewId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(viewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(viewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            // Visibility follows the lock screen state, not blueprint transitions.
            setVisibilityMode(viewId, ConstraintSet.VISIBILITY_MODE_IGNORE)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        binding?.dispose()
        binding = null
        island?.removeOnLayoutChangeListener(reserve)
        island = null
        carrier?.let { it.setPaddingRelative(it.paddingStart, it.paddingTop, carrierPaddingEnd, it.paddingBottom) }
        carrier = null
        constraintLayout.viewTreeObserver.removeOnGlobalLayoutListener(enforce)
        stockPadlock = false
        hideStockPadlock()
        root = null
        constraintLayout.findViewById<View>(viewId)?.let(constraintLayout::removeView)
    }

    /** For `cmd statusbar lockscreen state`: the status and whether the lock screen draws it now. */
    fun stateJson(): JSONObject = JSONObject()
        .put("status", lockStatus.state.value.wire ?: JSONObject.NULL)
        .put("visible", island?.state()?.optBoolean("visible") ?: false)

    private companion object {
        const val CARRIER_GAP_DP = 8f
    }
}
