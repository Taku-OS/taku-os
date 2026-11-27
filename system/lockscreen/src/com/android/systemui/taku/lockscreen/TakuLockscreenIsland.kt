package com.android.systemui.taku.lockscreen

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.taku.island.IslandEngine
import com.android.systemui.taku.island.IslandPresenter
import com.android.systemui.taku.island.IslandRuntime
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * The Dynamic Island on the lock screen, for the sections that draw it: [TakuLockStatusSection] (the
 * capsule at the camera) and [TakuNotificationSection] (the pinned card). The island itself, its
 * selection and what the lock screen may show are `system/island`'s ([IslandPresenter.lockScreen]);
 * this class only carries the lock screen's touches to it.
 *
 * Every touch runs the same island operation an Agent would call (`cmd statusbar island ...`), after
 * the stock falsing check for its kind of touch: taps and long presses are checked as such; the
 * capsule's swipes already need a 24 dp horizontal fling, which the tap classifier would reject. An operation the island refuses with `locked` needs the user: the GUI
 * then asks for the credential and runs it again once the user has authenticated. An Agent gets the
 * `locked` error instead, because it can never authenticate for the user.
 */
@SysUISingleton
class TakuLockscreenIsland
@Inject
constructor(
    private val presenter: IslandPresenter,
    private val falsingManager: FalsingManager,
    private val activityStarter: Lazy<ActivityStarter>,
) {
    val state: StateFlow<IslandPresenter.LockScreen> get() = presenter.lockScreen

    /** The kind of touch that asked for an operation, for the falsing check. */
    enum class Touch { TAP, LONG_PRESS, SWIPE }

    /** A touch on the capsule or the card: the island operation [method] on [key], with its JSON [extra]. */
    fun run(method: String, key: String?, extra: String?, touch: Touch = Touch.TAP) {
        val rejected = when (touch) {
            Touch.TAP -> falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)
            Touch.LONG_PRESS -> falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)
            Touch.SWIPE -> false
        }
        if (rejected) return
        val result = IslandRuntime.call(method, key, extra)
        if (result.optBoolean("ok") || result.optString("error") != IslandEngine.LOCKED) return
        activityStarter.get().dismissKeyguardThenExecute(
            object : ActivityStarter.OnDismissAction {
                override fun onDismiss(): Boolean {
                    IslandRuntime.call(method, key, extra)
                    return false
                }
            },
            null,
            false,
        )
    }

    /** The pinned card's handle, swipe up or Close button. */
    fun fold() = presenter.foldLockScreenCard()

    /** For `cmd statusbar lockscreen state`: the activity the lock screen shows, and its pinned card. */
    fun stateJson(): JSONObject = state.value.let {
        JSONObject().put("key", it.activity?.key ?: JSONObject.NULL)
            .put("redacted", if (it.activity == null) JSONObject.NULL else if (it.redacted) "glyph" else "none")
            .put("card", it.card?.key ?: JSONObject.NULL)
    }
}
