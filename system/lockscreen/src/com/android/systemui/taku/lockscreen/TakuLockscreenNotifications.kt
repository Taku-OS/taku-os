package com.android.systemui.taku.lockscreen

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.android.internal.statusbar.NotificationVisibility
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationClickNotifier
import com.android.systemui.statusbar.notification.NotificationActivityStarter
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The authenticated lock screen's notifications and Taku entry: one model and one set of operations
 * shared by the cards (GUI) and `cmd statusbar lockscreen` ([TakuLockscreenCommand], Agent), so both
 * read the same data and pass the same checks. Main thread only.
 *
 * The source is the keyguard-filtered shade list from the notification pipeline: the same entries
 * the stock stack would show, after lock-screen settings, VISIBILITY_SECRET and Taku's
 * hide-until-authenticated rule. Clicks, dismissals and action buttons use the stock SystemUI paths
 * (activity starter, row dismissal, click notifier), so statistics, auto-cancel, work challenge and
 * bubbles behave as in the shade.
 */
@SysUISingleton
class TakuLockscreenNotifications
@Inject
constructor(
    private val context: Context,
    pipeline: NotifPipeline,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardClockInteractor: KeyguardClockInteractor,
    private val userTracker: UserTracker,
    private val activityStarter: Lazy<ActivityStarter>,
    private val notificationActivityStarter: Lazy<NotificationActivityStarter>,
    private val clickNotifier: NotificationClickNotifier,
) {
    private val apps = AppInfoCache()
    private val entries = LinkedHashMap<String, NotificationEntry>()
    private val _cards = MutableStateFlow<List<TakuNotificationCard>>(emptyList())

    /** Newest first, one card per notification, no grouping or pagination. */
    val cards: StateFlow<List<TakuNotificationCard>> = _cards.asStateFlow()

    init {
        pipeline.addOnAfterRenderListListener { list -> rebuild(TakuNotificationCard.flatten(list)) }
    }

    private fun rebuild(list: List<NotificationEntry>) {
        val previous = _cards.value.associateBy { it.key }
        val sorted = list.filter { !it.isRowDismissed }.sortedByDescending { it.sbn.postTime }
        entries.clear()
        val cards = sorted.map { entry ->
            entries[entry.key] = entry
            val sensitive = entry.isSensitive().value
            val old = previous[entry.key]
            if (old != null && old.sbn === entry.sbn && old.sensitive == sensitive) old
            else TakuNotificationCard.from(context, entry, sensitive, apps)
        }
        apps.retain(cards.mapTo(HashSet()) { it.packageName })
        _cards.value = cards
    }

    /** Operation result: `{ok:true,...}` or `{ok:false,error:CODE}`, identical for GUI and Agent. */
    class Result private constructor(val ok: Boolean, val error: String?) {
        fun json(): JSONObject = JSONObject().put("ok", ok).apply { if (error != null) put("error", error) }

        companion object {
            val OK = Result(true, null)
            fun error(code: String) = Result(false, code)
        }
    }

    /** The keyguard is showing and the user has authenticated: cards and Taku are available. */
    val isAvailable: Boolean
        get() = keyguardInteractor.isKeyguardShowing.value && keyguardInteractor.isKeyguardDismissible.value

    private fun checkAvailable(): Result? = when {
        !keyguardInteractor.isKeyguardShowing.value -> Result.error("KEYGUARD_NOT_SHOWING")
        !keyguardInteractor.isKeyguardDismissible.value -> Result.error("LOCKED")
        else -> null
    }

    /** Tap on a card: the stock notification click (bouncer is not needed; the user has authenticated). */
    fun open(key: String): Result {
        checkAvailable()?.let { return it }
        val entry = entries[key] ?: return Result.error("NOT_FOUND")
        val card = _cards.value.firstOrNull { it.key == key } ?: return Result.error("NOT_FOUND")
        if (!card.clickable && !entry.isBubble) return Result.error("NOT_CLICKABLE")
        val row = entry.row ?: return Result.error("NOT_READY")
        notificationActivityStarter.get().onNotificationClicked(entry, row)
        return Result.OK
    }

    /** Swipe on a card: the stock row dismissal, honouring the same dismissibility as the shade. */
    fun dismiss(key: String): Result {
        checkAvailable()?.let { return it }
        val entry = entries[key] ?: return Result.error("NOT_FOUND")
        val row = entry.row ?: return Result.error("NOT_READY")
        if (!row.canViewBeDismissed()) return Result.error("NOT_DISMISSABLE")
        row.performDismiss(false)
        return Result.OK
    }

    /** A card button. Only the buttons the card shows are accepted (no reply or contextual actions). */
    fun action(key: String, index: Int): Result {
        checkAvailable()?.let { return it }
        val entry = entries[key] ?: return Result.error("NOT_FOUND")
        val cards = _cards.value
        val position = cards.indexOfFirst { it.key == key }
        if (position < 0) return Result.error("NOT_FOUND")
        if (cards[position].actions.none { it.index == index }) return Result.error("INVALID_ACTION")
        val action = entry.sbn.notification.actions?.getOrNull(index) ?: return Result.error("INVALID_ACTION")
        val intent = action.actionIntent ?: return Result.error("INVALID_ACTION")
        val visibility = NotificationVisibility.obtain(key, position, cards.size, true,
            NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA)
        if (intent.isActivity) {
            activityStarter.get().startPendingIntentDismissingKeyguard(intent)
        } else {
            try {
                intent.send()
            } catch (canceled: PendingIntent.CanceledException) {
                Log.w(TAG, "Action $index of $key was canceled", canceled)
                return Result.error("CANCELED")
            }
        }
        clickNotifier.onNotificationActionClick(key, index, action, visibility, false)
        return Result.OK
    }

    /**
     * The Taku button: leave the lock screen (already authenticated, so no bouncer), then open Taku's
     * assistant through Taku Home's existing ACTION_ASSIST entry, which only opens once the keyguard
     * is gone.
     */
    fun openTaku(): Result {
        checkAvailable()?.let { return it }
        val intent = Intent(Intent.ACTION_ASSIST).setPackage(TAKU_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val user = userTracker.userHandle
        if (context.packageManager.queryIntentActivities(intent, 0).isEmpty()) return Result.error("TAKU_UNAVAILABLE")
        activityStarter.get().dismissKeyguardThenExecute(
            object : ActivityStarter.OnDismissAction {
                override fun onDismiss(): Boolean {
                    context.startActivityAsUser(intent, user)
                    return false
                }
            },
            null,
            true,
        )
        return Result.OK
    }

    fun stateJson(): JSONObject = JSONObject()
        .put("keyguardShowing", keyguardInteractor.isKeyguardShowing.value)
        .put("authenticated", keyguardInteractor.isKeyguardDismissible.value)
        .put("dozing", keyguardInteractor.isDozing.value)
        .put("available", isAvailable)
        .put("notificationCount", if (isAvailable) _cards.value.size else 0)
        .put("clockSize", keyguardClockInteractor.clockSize.value.name.lowercase())

    /** The cards exactly as shown; empty while locked, when the lock screen shows no notifications. */
    fun notificationsJson(): JSONArray {
        val array = JSONArray()
        if (!isAvailable) return array
        for (card in _cards.value) {
            array.put(JSONObject()
                .put("key", card.key)
                .put("package", card.packageName)
                .put("app", card.appLabel.toString())
                .put("title", card.title.toString())
                .put("text", card.text?.toString() ?: JSONObject.NULL)
                .put("context", card.context?.toString() ?: JSONObject.NULL)
                .put("when", card.whenMillis)
                .put("conversation", card.conversation)
                .put("sensitive", card.sensitive)
                .put("dismissable", card.dismissable)
                .put("clickable", card.clickable)
                .put("layout", if (card.expanded) "expanded" else "compact")
                .put("actions", JSONArray().apply {
                    card.actions.forEach { put(JSONObject().put("index", it.index).put("title", it.title.toString())) }
                }))
        }
        return array
    }

    companion object {
        private const val TAG = "TakuLockscreen"
        const val TAKU_PACKAGE = "org.agentos.agent"
    }
}
