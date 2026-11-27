package com.android.systemui.taku.lockscreen

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.internal.widget.LockPatternView
import com.android.keyguard.EmergencyButton
import com.android.keyguard.NumPadButton
import com.android.keyguard.NumPadKey
import com.android.keyguard.PasswordTextView
import com.android.keyguard.PinShapeInput
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import java.util.concurrent.Executor
import javax.inject.Inject
import org.json.JSONObject

/**
 * The unlock screen (the stock primary bouncer) in the Taku style: the Taku lock wallpaper under a
 * subtle dark overlay, white type, and liquid-glass PIN keys, pattern dots and password field
 * ([TakuGlass]). Which of the three shows is Android's choice, from the configured screen lock.
 *
 * Only the look changes. The bouncer is the stock `KeyguardSecurityContainer` with its own
 * controllers: credential checks, lockout, the emergency call button, accessibility and what happens
 * after a correct credential (the pending action, else the screen underneath) are upstream code.
 *
 * The bouncer lives in the same window as the lock screen, above every scrim
 * (`keyguard_bouncer_container` in `super_notification_shade`), so no upstream hook is needed:
 * - a background view is inserted under the security container. It follows the container's alpha,
 *   so it fades in with the bouncer's expansion and out with the unlock, revealing the screen below;
 * - before each frame the security container is restyled. Its views are reinflated whenever the
 *   bouncer shows, and they reload stock colours on theme changes, so styling is idempotent and
 *   repeated rather than done once. White type is a colour-filter render effect, which no colour
 *   reload can undo.
 *
 * Part of [TakuKeyguardBlueprint]: with the stock blueprint (split shade) the bouncer is stock too.
 */
@SysUISingleton
class TakuBouncerSection
@Inject
constructor(
    private val userTracker: UserTracker,
    @Background private val backgroundExecutor: Executor,
) : KeyguardSection() {
    private var container: ViewGroup? = null
    private var backgroundView: View? = null
    private var drawable: TakuLockscreenBackgroundDrawable? = null
    private val glass = TakuGlass {
        val view = backgroundView
        val page = drawable?.backdrop()
        if (view != null && page != null) TakuGlass.Backdrop(page, view) else null
    }
    private val glassViews = ArrayList<View>()
    private var lastSecurityY = Float.NaN
    private var lastBackdrop = false
    private var input: String? = null

    private val frame = ViewTreeObserver.OnPreDrawListener {
        container?.let(::sync)
        true
    }

    override fun addViews(constraintLayout: ConstraintLayout) {}

    override fun bindData(constraintLayout: ConstraintLayout) {
        val found = constraintLayout.rootView.findViewById<ViewGroup>(R.id.keyguard_bouncer_container)
        if (found == null) {
            // Not attached to the shade window yet: try again once it is.
            constraintLayout.post { if (container == null) bindData(constraintLayout) }
            return
        }
        if (found === container) return
        detach()
        container = found
        val background = TakuLockscreenBackgroundDrawable(found.context, { userTracker.userId }, backgroundExecutor,
            TakuLockscreenBackgroundDrawable.Style.BOUNCER)
        drawable = background
        val view = View(found.context).apply {
            this.background = background
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = 0f
        }
        backgroundView = view
        found.addView(view, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        background.attach()
        found.viewTreeObserver.addOnPreDrawListener(frame)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {}

    override fun removeViews(constraintLayout: ConstraintLayout) = detach()

    private fun detach() {
        val old = container ?: return
        old.viewTreeObserver.removeOnPreDrawListener(frame)
        backgroundView?.let(old::removeView)
        drawable?.detach()
        backgroundView = null
        drawable = null
        container = null
        glassViews.clear()
        input = null
    }

    /** The security container: the bouncer container's child that is not our background. */
    private fun security(group: ViewGroup): View? =
        (0 until group.childCount).map(group::getChildAt).firstOrNull { it !== backgroundView }

    private fun sync(group: ViewGroup) {
        val security = security(group) ?: return
        backgroundView?.alpha = if (group.visibility == View.VISIBLE) security.alpha else 0f
        if (group.visibility != View.VISIBLE) return
        glassViews.clear()
        input = null
        style(security)
        // The lens shows the wallpaper behind each shape: while the bouncer slides in, redraw them.
        // The wallpaper arriving later changes what they show too.
        val backdrop = drawable?.backdrop() != null
        if (security.translationY != lastSecurityY || backdrop != lastBackdrop) {
            lastSecurityY = security.translationY
            lastBackdrop = backdrop
            glassViews.forEach(View::invalidate)
        }
    }

    private fun style(view: View) {
        when (view) {
            is NumPadKey -> {
                input = input ?: "pin"
                if (view.getTag(R.id.keyguard_bouncer_container) == null) {
                    view.setTag(R.id.keyguard_bouncer_container, STYLED)
                    view.background = TakuGlassDrawable(glass)
                    view.findViewById<TextView>(R.id.digit_text)?.apply {
                        typeface = TakuLockscreenFonts.spaceGrotesk
                        setRenderEffect(WHITE)
                    }
                    view.findViewById<TextView>(R.id.klondike_text)?.apply {
                        typeface = TakuLockscreenFonts.hankenGroteskMedium
                        letterSpacing = 0.2f
                        setRenderEffect(SECONDARY)
                    }
                }
                glassViews.add(view)
                return
            }
            is NumPadButton -> {
                // Delete and enter are plain white glyphs, like the text buttons under an iPhone keypad.
                // NumPadButton's appear/disabled animations call background.mutate().setAlpha().
                // Keep a transparent drawable, including after upstream reloads the button style.
                val background = view.background
                if (background !is ColorDrawable || background.color != Color.TRANSPARENT) {
                    view.background = ColorDrawable(Color.TRANSPARENT)
                }
                once(view) { setRenderEffect(WHITE) }
                return
            }
            is PasswordTextView -> { once(view) { setRenderEffect(WHITE) }; return }
            is LockPatternView -> {
                input = "pattern"
                once(view) {
                    view.setDotColors(Color.WHITE, Color.WHITE)
                    view.setColors(PATH, Color.WHITE, ERROR)
                    view.background = TakuGlassGridDrawable(glass)
                }
                glassViews.add(view)
                return
            }
            is EditText -> {
                input = input ?: "password"
                once(view) {
                    view.background = TakuGlassDrawable(glass, PASSWORD_CORNER_DP)
                    view.setTextColor(Color.WHITE)
                    view.setHintTextColor(SECONDARY_COLOR)
                }
                glassViews.add(view)
                return
            }
            is EmergencyButton -> {
                if (view.background != null) view.background = null
                once(view) {
                    view.typeface = TakuLockscreenFonts.hankenGroteskMedium
                    view.setRenderEffect(WHITE)
                }
                return
            }
            is TextView -> {
                // Bouncer messages ("Enter PIN", errors, lockout countdown).
                once(view) {
                    view.typeface = TakuLockscreenFonts.hankenGrotesk
                    view.setRenderEffect(WHITE)
                }
                return
            }
        }
        if (view is PinShapeInput) { once(view) { setRenderEffect(WHITE) }; return }
        if (view is ViewGroup) for (index in 0 until view.childCount) style(view.getChildAt(index))
    }

    private inline fun once(view: View, apply: View.() -> Unit) {
        if (view.getTag(R.id.keyguard_bouncer_container) != null) return
        view.setTag(R.id.keyguard_bouncer_container, STYLED)
        view.apply()
    }

    /** For `cmd statusbar lockscreen state`: whether the bouncer shows, in the Taku style, and its input. */
    fun stateJson(): JSONObject {
        val group = container
        val visible = group != null && group.visibility == View.VISIBLE && group.isShown
        return JSONObject().put("visible", visible)
            .put("styled", group != null)
            .put("input", if (visible) input ?: JSONObject.NULL else JSONObject.NULL)
            .put("glassControls", if (visible) glassViews.size else 0)
            .put("wallpaper", drawable?.backdrop() != null)
    }

    private companion object {
        const val STYLED = "taku"
        const val PASSWORD_CORNER_DP = 24f
        val SECONDARY_COLOR = Color.argb(0.8f, 1f, 1f, 1f)
        /** The pattern's path: white at 70 %; a wrong pattern turns it a soft red. */
        val PATH = Color.argb(0.7f, 1f, 1f, 1f)
        val ERROR = Color.rgb(0xFF, 0x6B, 0x6B)
        val WHITE: RenderEffect = RenderEffect.createColorFilterEffect(PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN))
        val SECONDARY: RenderEffect =
            RenderEffect.createColorFilterEffect(PorterDuffColorFilter(SECONDARY_COLOR, PorterDuff.Mode.SRC_IN))
    }
}
