package com.android.systemui.taku.lockscreen

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.settings.UserTracker
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

/**
 * Taku's lock screen layout: the stock sections (clock, shortcuts, device entry, indication area,
 * notifications, accessibility, AOD) on top of the Taku background, plus the authenticated surface
 * (notification cards and the Taku button). Keeping the stock sections keeps authentication,
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
) : KeyguardBlueprint {
    override val id: String = ID

    // Z-order follows this list. The background is the bottom; the cards sit under every stock view
    // (shortcuts, lock icon, indication text keep their touches); the Taku button is on top.
    override val sections: List<KeyguardSection> =
        listOf(backgroundSection, notificationSection) + defaultBlueprint.sections + assistantSection

    companion object {
        const val ID = "taku"
    }
}

/** Adds [TakuLockscreenBackgroundView] under every other keyguard view and fades it out while dozing. */
@SysUISingleton
class TakuBackgroundSection
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    private val userTracker: UserTracker,
    @Background private val backgroundExecutor: Executor,
) : KeyguardSection() {
    private val viewId = View.generateViewId()
    private var binding: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<View>(viewId) != null) return
        val view = TakuLockscreenBackgroundView(constraintLayout.context, { userTracker.userId }, backgroundExecutor)
        view.id = viewId
        constraintLayout.addView(view, 0)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val view = constraintLayout.findViewById<TakuLockscreenBackgroundView>(viewId) ?: return
        binding?.dispose()
        binding = view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The always-on display stays black: the background follows 1 − doze amount.
                keyguardInteractor.dozeAmount.collect { amount -> view.alpha = 1f - amount }
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(viewId, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(viewId, ConstraintSet.MATCH_CONSTRAINT)
            connect(viewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(viewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            connect(viewId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(viewId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        binding?.dispose()
        binding = null
        constraintLayout.findViewById<View>(viewId)?.let(constraintLayout::removeView)
    }
}

/** Taku lock-screen policy shared with the upstream hooks in `image/lockscreen`. */
object TakuLockscreenPolicy {
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
    fun clockSize(authenticated: Boolean): ClockSize = if (authenticated) ClockSize.SMALL else ClockSize.LARGE

    /** Cards and the Taku button fade in and out over this time. */
    const val FADE_MS = 200L
}
