package com.android.systemui.taku.lockscreen

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.settings.UserTracker
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

/**
 * Taku's lock screen layout: the stock sections (clock, shortcuts, device entry, indication area,
 * notifications, accessibility, AOD) on top of the Taku background, plus the authenticated surface
 * (notification cards and the Taku button) and the lock status in the island. Keeping the stock sections keeps authentication,
 * biometric messages and accessibility exactly as upstream.
 */
@SysUISingleton
class TakuKeyguardBlueprint
@Inject
constructor(
    defaultBlueprint: DefaultKeyguardBlueprint,
    backgroundSection: TakuBackgroundSection,
    notificationSection: TakuNotificationSection,
    assistantSection: TakuAssistantSection,
    stockDateSection: TakuStockDateSection,
    lockStatusSection: TakuLockStatusSection,
    // Not used here: injecting it registers `cmd statusbar lockscreen` with the Taku lock screen.
    @Suppress("UNUSED_PARAMETER") command: TakuLockscreenCommand,
) : KeyguardBlueprint {
    override val id: String = ID

    // Z-order follows this list. The background is the root's background drawable; the cards sit
    // under every stock view (shortcuts, lock icon, indication text keep their touches); the Taku button
    // is on top. The stock-date section adds no view and must apply after the smartspace section.
    // The lock status is a strip at the camera that takes no touches.
    override val sections: List<KeyguardSection> =
        listOf(backgroundSection, notificationSection) + defaultBlueprint.sections +
            listOf(assistantSection, stockDateSection, lockStatusSection)

    companion object {
        const val ID = "taku"
    }
}

/**
 * Installs [TakuLockscreenBackgroundDrawable] as the keyguard root's background (under every keyguard
 * view) and fades it out while dozing. A background drawable instead of a child view, so it does not
 * count as a visible view at y = 0 for the burn-in correction that would push the large clock down.
 */
@SysUISingleton
class TakuBackgroundSection
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    private val userTracker: UserTracker,
    @Background private val backgroundExecutor: Executor,
) : KeyguardSection() {
    private var drawable: TakuLockscreenBackgroundDrawable? = null
    private var previous: Drawable? = null
    private var binding: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (drawable != null && constraintLayout.background === drawable) return
        previous = constraintLayout.background
        drawable = TakuLockscreenBackgroundDrawable(constraintLayout.context, { userTracker.userId }, backgroundExecutor)
        constraintLayout.background = drawable
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val background = drawable ?: return
        background.attach()
        binding?.dispose()
        binding = constraintLayout.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The always-on display stays black: the background follows 1 − doze amount.
                keyguardInteractor.dozeAmount.collect { amount ->
                    background.alpha = ((1f - amount) * 255).toInt().coerceIn(0, 255)
                }
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {}

    override fun removeViews(constraintLayout: ConstraintLayout) {
        binding?.dispose()
        binding = null
        drawable?.detach()
        if (constraintLayout.background === drawable) constraintLayout.background = previous
        drawable = null
        previous = null
    }
}

/**
 * The Taku clock draws its own date (Figma 979:468), so the stock Smartspace and legacy Slice dates
 * stay hidden while it is the current clock; other clock faces keep them. Placed after the stock
 * sections so its constraints win, and re-checked after every layout because the stock binder and
 * smartspace controller also change those views' visibility.
 */
@SysUISingleton
class TakuStockDateSection
@Inject
constructor(private val keyguardClockInteractor: KeyguardClockInteractor) : KeyguardSection() {
    private var root: ConstraintLayout? = null
    private val stockVisibility = mutableMapOf<View, Int>()
    private val enforce = ViewTreeObserver.OnGlobalLayoutListener { root?.let(::hideStockDate) }

    private val takuClock: Boolean get() = keyguardClockInteractor.currentClock.value is TakuClockController

    private fun hideStockDate(constraintLayout: ConstraintLayout) {
        if (!takuClock) {
            restoreStockDate()
            return
        }
        for (id in STOCK_DATE_IDS) {
            constraintLayout.findViewById<View>(id)?.let { view ->
                stockVisibility.putIfAbsent(view, view.visibility)
                if (view.visibility != View.GONE) view.visibility = View.GONE
            }
        }
    }

    private fun restoreStockDate() {
        for ((view, visibility) in stockVisibility) view.visibility = visibility
        stockVisibility.clear()
    }

    override fun addViews(constraintLayout: ConstraintLayout) {}

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (root === constraintLayout) return
        root?.viewTreeObserver?.removeOnGlobalLayoutListener(enforce)
        restoreStockDate()
        root = constraintLayout
        hideStockDate(constraintLayout)
        constraintLayout.viewTreeObserver.addOnGlobalLayoutListener(enforce)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!takuClock) {
            for ((view, visibility) in stockVisibility) constraintSet.setVisibility(view.id, visibility)
            restoreStockDate()
            return
        }
        root?.let(::hideStockDate)
        for (id in STOCK_DATE_IDS) {
            constraintSet.setVisibility(id, View.GONE)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.viewTreeObserver.removeOnGlobalLayoutListener(enforce)
        if (root === constraintLayout) {
            restoreStockDate()
            root = null
        }
    }

    private companion object {
        val STOCK_DATE_IDS = intArrayOf(
            // Lineage without a Smartspace provider uses this instead of date_smartspace_view.
            R.id.keyguard_slice_view,
            sharedR.id.date_smartspace_view,
            sharedR.id.date_smartspace_view_large,
            sharedR.id.weather_smartspace_view,
            sharedR.id.weather_smartspace_view_large,
        )
    }
}

/** Taku lock-screen policy shared with the upstream hooks in `image/lockscreen`. */
object TakuLockscreenPolicy {
    /**
     * Stock large clocks are designed for a 0.9 awake scale. Taku's outlines and top date already
     * have their final Figma dimensions, so normalize that baseline while preserving AOD's relative
     * shrink. Otherwise the 320 dp clock becomes 288 dp and the centred pivot moves its date down.
     */
    @JvmStatic
    fun clockScale(view: View, scale: Float): Float =
        if (view is TakuClockFaceView) scale / BurnInModel.MAX_LARGE_CLOCK_SCALE else scale

    /**
     * Notifications stay hidden on the lock screen until the user authenticates (face unlock keeps the
     * user on the lock screen; see docs/lock-screen.md). Applied in KeyguardNotificationVisibilityProvider,
     * which already re-filters when the keyguard's unlocked state changes.
     */
    const val HIDE_NOTIFICATIONS_UNTIL_AUTHENTICATED = true

    /**
     * The tall clock while locked, the compact one once authenticated, whether or not notifications
     * exist (the stock rule keys off visible notifications). Used by the panel hook in
     * NotificationPanelViewController and by [TakuNotificationSection] when authentication changes.
     */
    @JvmStatic
    fun clockSize(authenticated: Boolean): ClockSize =
        if (authenticated || islandCardPinned) ClockSize.SMALL else ClockSize.LARGE

    /**
     * An island card is pinned below the clock (a recording, Now Playing). It needs the room, so the
     * clock is compact even while locked. Set by [TakuNotificationSection]; main thread.
     */
    @JvmField
    @Volatile
    var islandCardPinned = false

    /** Cards and the Taku button fade in and out over this time. */
    const val FADE_MS = 200L

    /** Figma 979:455 / 979:463: white at 92 % behind a `#1B1B1B` 24 dp icon. */
    private val SHORTCUT_FILL = Color.argb(0.92f, 1f, 1f, 1f)
    private val SHORTCUT_ICON = Color.rgb(0x1B, 0x1B, 0x1B)

    /**
     * Shortcut button colours (hook in KeyguardQuickAffordanceViewBinder). The design only defines the
     * resting state; an activated shortcut (torch on) keeps the stock colours so it stays distinct.
     */
    @JvmStatic
    fun shortcutBackgroundColor(activated: Boolean, stockColor: Int): Int = if (activated) stockColor else SHORTCUT_FILL

    @JvmStatic
    fun shortcutIconColor(activated: Boolean, stockColor: Int): Int = if (activated) stockColor else SHORTCUT_ICON
}
