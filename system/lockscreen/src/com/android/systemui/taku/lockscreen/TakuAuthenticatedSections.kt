package com.android.systemui.taku.lockscreen

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import com.android.systemui.taku.island.IslandLockScreenCard
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Where the authenticated surface shows: the lock screen itself (no shade or QS pulled over it, not
 * dozing) after the user has authenticated. Shared by the cards and the Taku button.
 */
private fun authenticatedSurfaceShown(
    container: SharedNotificationContainerViewModel,
    keyguard: KeyguardInteractor,
): Flow<Boolean> =
    combine(container.isOnLockscreenWithoutShade, keyguard.isKeyguardDismissible, keyguard.isDozing) {
        onLockscreen, authenticated, dozing -> onLockscreen && authenticated && !dozing
    }.distinctUntilChanged()

/** Fades a surface in or out; a hidden surface is INVISIBLE so touches reach the lock screen below. */
internal fun View.fadeShown(shown: Boolean) {
    animate().cancel()
    if (shown) {
        if (visibility != View.VISIBLE) {
            alpha = 0f
            visibility = View.VISIBLE
        }
        animate().alpha(1f).setDuration(TakuLockscreenPolicy.FADE_MS).setListener(null).start()
    } else if (visibility == View.VISIBLE) {
        animate().alpha(0f).setDuration(TakuLockscreenPolicy.FADE_MS)
            .withEndAction { visibility = View.INVISIBLE }.start()
    }
}

private fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

/**
 * The lock screen's notifications (Figma 979:641), iPhone style. When the lock screen appears they are
 * collapsed: the tall clock, and the first item resting above the shortcuts. Dragging the notification
 * area up opens them into the full list under the compact clock ([TakuLockscreenExpansion]); dragging
 * down closes them. Swiping up from the gesture bar is the system unlock and never reaches the list.
 *
 * Authentication only decides the content: notification cards appear once the user has authenticated
 * (the keyguard-filtered pipeline is empty before), the Dynamic Island's card also while locked.
 *
 * While the lock screen shows without the shade, the stock notification stack is made INVISIBLE: the
 * cards present the same pipeline entries instead, and the stack would otherwise draw (and take
 * touches) over them. Pulling the shade down shows the stock stack again, unchanged. The section also
 * owns the clock size ([TakuLockscreenPolicy.clockSize], the same rule the panel's hook applies).
 *
 * The Dynamic Island's card is the list's first item ([TakuLockscreenIsland],
 * docs/dynamic-island-lock-screen-spec.md).
 */
@SysUISingleton
class TakuNotificationSection
@Inject
constructor(
    private val notifications: TakuLockscreenNotifications,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardClockInteractor: KeyguardClockInteractor,
    private val sharedNotificationContainer: SharedNotificationContainer,
    private val containerViewModel: SharedNotificationContainerViewModel,
    private val falsingManager: FalsingManager,
    private val island: TakuLockscreenIsland,
    private val expansion: TakuLockscreenExpansion,
) : KeyguardSection() {
    private val viewId = View.generateViewId()
    private var binding: DisposableHandle? = null
    private var islandCard: IslandLockScreenCard? = null

    /** For `cmd statusbar lockscreen state`: the pinned island card as drawn, or null. */
    fun islandCardJson(): JSONObject? = islandCard?.state()?.takeIf { it.optBoolean("visible") }

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<View>(viewId) != null) return
        val list = TakuNotificationListView(constraintLayout.context, expansion).apply {
            id = viewId
            visibility = View.INVISIBLE
        }
        constraintLayout.addView(list)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val list = constraintLayout.findViewById<TakuNotificationListView>(viewId) ?: return
        list.callbacks = object : TakuNotificationCardView.Callbacks {
            override fun onOpen(key: String) {
                if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return
                notifications.open(key)
            }

            override fun onDismiss(key: String) {
                if (!notifications.dismiss(key).ok) list.restore(key)
            }

            override fun onAction(key: String, index: Int) {
                if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return
                notifications.action(key, index)
            }
        }
        // Card controls are taps: the island checks them as taps.
        val pinned = IslandLockScreenCard(list.context, { method, key, extra -> island.run(method, key, extra) }, island::fold)
        islandCard = pinned
        list.header = pinned
        binding?.dispose()
        // CREATED, not STARTED: the stock stack must become visible again even when the keyguard
        // root is hidden (unlocking), or the shade over apps would lose its notifications.
        binding = list.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch { notifications.cards.collect(list::submit) }
                launch {
                    island.state.collect { state ->
                        pinned.show(state.card)
                        // Whatever the island shows is not also a notification card.
                        notifications.represented = state.activity?.key
                        list.submit(notifications.cards.value)
                    }
                }
                launch { expansion.progress.collect(list::setProgress) }
                launch {
                    // The clock follows the drag, not authentication.
                    expansion.expanded.collect {
                        if (keyguardInteractor.isKeyguardShowing.value) {
                            keyguardClockInteractor.setClockSize(TakuLockscreenPolicy.clockSize())
                        }
                    }
                }
                launch {
                    // Every time the lock screen appears (wake, lock) it starts collapsed.
                    combine(keyguardInteractor.isKeyguardShowing, keyguardInteractor.isDozing) { showing, dozing ->
                        showing && !dozing
                    }.distinctUntilChanged().collect { awake ->
                        if (!awake) expansion.collapse(animate = false)
                    }
                }
                launch {
                    // Whatever content the list has shows with the lock screen; authentication only
                    // decides that content.
                    combine(containerViewModel.isOnLockscreenWithoutShade, keyguardInteractor.isDozing) { onLockscreen, dozing ->
                        onLockscreen && !dozing
                    }.distinctUntilChanged().collect(list::fadeShown)
                }
                launch {
                    combine(containerViewModel.isOnLockscreenWithoutShade, keyguardInteractor.statusBarState) {
                        onLockscreen, state -> onLockscreen && state == StatusBarState.KEYGUARD
                    }.distinctUntilChanged().collect { cardsOwnLockscreen ->
                        sharedNotificationContainer.visibility = if (cardsOwnLockscreen) View.INVISIBLE else View.VISIBLE
                    }
                }
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val context = sharedNotificationContainer.context
        constraintSet.apply {
            constrainWidth(viewId, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(viewId, ConstraintSet.MATCH_CONSTRAINT)
            connect(viewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(viewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            // Figma: 32 dp between the compact clock group and the first card.
            connect(viewId, ConstraintSet.TOP, ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, ConstraintSet.BOTTOM,
                context.dp(CARDS_TOP_GAP_DP))
            connect(viewId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            // Visibility follows authentication, not blueprint transitions.
            setVisibilityMode(viewId, ConstraintSet.VISIBILITY_MODE_IGNORE)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        binding?.dispose()
        binding = null
        islandCard = null
        notifications.represented = null
        expansion.collapse(animate = false)
        sharedNotificationContainer.visibility = View.VISIBLE
        constraintLayout.findViewById<View>(viewId)?.let(constraintLayout::removeView)
    }

    private companion object {
        const val CARDS_TOP_GAP_DP = 32f
    }
}

/** The Taku button (Figma 979:739), centred in the shortcut row, for the authenticated lock screen. */
@SysUISingleton
class TakuAssistantSection
@Inject
constructor(
    private val notifications: TakuLockscreenNotifications,
    private val keyguardInteractor: KeyguardInteractor,
    private val containerViewModel: SharedNotificationContainerViewModel,
    private val falsingManager: FalsingManager,
) : KeyguardSection() {
    private val viewId = View.generateViewId()
    private var binding: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<View>(viewId) != null) return
        constraintLayout.addView(TakuAssistantButton(constraintLayout.context).apply {
            id = viewId
            visibility = View.INVISIBLE
        })
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val button = constraintLayout.findViewById<TakuAssistantButton>(viewId) ?: return
        button.setOnClickListener {
            if (!falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) notifications.openTaku()
        }
        binding?.dispose()
        binding = button.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                authenticatedSurfaceShown(containerViewModel, keyguardInteractor).collect(button::fadeShown)
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(viewId, ConstraintSet.WRAP_CONTENT)
            constrainHeight(viewId, ConstraintSet.WRAP_CONTENT)
            connect(viewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(viewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            // Same baseline as the shortcuts, including their display-cutout inset.
            connect(viewId, ConstraintSet.BOTTOM, R.id.start_button, ConstraintSet.BOTTOM)
            setVisibilityMode(viewId, ConstraintSet.VISIBILITY_MODE_IGNORE)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        binding?.dispose()
        binding = null
        constraintLayout.findViewById<View>(viewId)?.let(constraintLayout::removeView)
    }
}

/** 56 dp `#1B1B1B` circle with Taku's eyes: two 6 × 16 dp white bars, 6 dp apart (Figma 979:740). */
class TakuAssistantButton(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val eyes = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(FILL)
        }
        background = RippleDrawable(ColorStateList.valueOf(PRESSED), circle, null)
        isClickable = true
        isFocusable = true
        contentDescription = "Taku"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (SIZE_DP * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val bar = 6f * density
        val eyeHeight = 16f * density
        val gap = 6f * density
        // Figma centres the eyes 7 dp left of and 4 dp above the button's centre.
        val centerX = width / 2f - 7f * density
        val centerY = height / 2f - 4f * density
        val left = centerX - (bar * 2 + gap) / 2f
        val top = centerY - eyeHeight / 2f
        val radius = bar / 2f
        canvas.drawRoundRect(left, top, left + bar, top + eyeHeight, radius, radius, eyes)
        canvas.drawRoundRect(left + bar + gap, top, left + bar * 2 + gap, top + eyeHeight, radius, radius, eyes)
    }

    companion object {
        const val SIZE_DP = 56f
        private val FILL = Color.rgb(0x1B, 0x1B, 0x1B)
        private val PRESSED = Color.argb(0.24f, 1f, 1f, 1f)
    }
}
