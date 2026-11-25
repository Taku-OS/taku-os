package com.android.systemui.taku.lockscreen

import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import com.android.systemui.statusbar.notification.collection.BundleEntry
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.PipelineEntry

/** One notification action as a card button. Reply (RemoteInput) and contextual actions are not shown. */
data class TakuCardAction(val index: Int, val title: CharSequence)

/**
 * What one lock-screen card shows for one real notification (Figma 979:574). The content comes from
 * the notification itself; nothing is invented. [sbn] identifies the posted revision, so a card is
 * rebound only when the app posts an update or its sensitivity changes.
 */
class TakuNotificationCard(
    val key: String,
    val sbn: StatusBarNotification,
    val packageName: String,
    val appLabel: CharSequence,
    val appIcon: Drawable?,
    /** Large icon or the latest message's sender picture; null falls back to [appIcon]. */
    val image: Icon?,
    val title: CharSequence,
    val text: CharSequence?,
    /** Conversation or channel name ("#Product"), or the notification's sub text. */
    val context: CharSequence?,
    val whenMillis: Long,
    val showWhen: Boolean,
    val conversation: Boolean,
    val actions: List<TakuCardAction>,
    val sensitive: Boolean,
    val dismissable: Boolean,
    val clickable: Boolean,
) {
    /** Conversations and notifications with buttons use the large card (979:642); the rest are compact (979:680). */
    val expanded: Boolean get() = !sensitive && (conversation || actions.isNotEmpty())

    fun sameContent(other: TakuNotificationCard): Boolean =
        other.sbn === sbn && other.sensitive == sensitive && other.dismissable == dismissable

    companion object {
        /** Top-level pipeline entries as one flat list: groups and bundles contribute their children. */
        fun flatten(entries: List<PipelineEntry>): List<NotificationEntry> {
            val out = ArrayList<NotificationEntry>()
            fun add(entry: PipelineEntry) {
                when (entry) {
                    is NotificationEntry -> out += entry
                    is GroupEntry -> {
                        val children = entry.children
                        if (children.isEmpty()) entry.summary?.let { out += it } else out += children
                    }
                    is BundleEntry -> entry.children.forEach(::add)
                    else -> {}
                }
            }
            entries.forEach(::add)
            return out
        }

        fun from(context: Context, entry: NotificationEntry, sensitive: Boolean, apps: AppInfoCache): TakuNotificationCard {
            val sbn = entry.sbn
            // A sensitive notification shows its public version when the app provides one, else only the app.
            val notification = if (sensitive) sbn.notification.publicVersion else sbn.notification
            val app = apps.get(context, sbn)
            val extras = notification?.extras ?: Bundle.EMPTY
            val messaging = extras.getString(Notification.EXTRA_TEMPLATE) == MESSAGING_STYLE
            val latest = if (messaging) latestMessage(extras) else null
            val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            val title = latest?.sender?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                ?: app.label
            val text = latest?.text
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            val actions = if (sensitive || notification == null) emptyList() else
                notification.actions.orEmpty().withIndex()
                    .filter { (_, action) ->
                        action.actionIntent != null && !action.isContextual && action.remoteInputs.isNullOrEmpty() &&
                            !action.title.isNullOrBlank()
                    }
                    .map { (index, action) -> TakuCardAction(index, action.title) }
                    .takeLast(MAX_ACTIONS)
            val original = sbn.notification
            return TakuNotificationCard(
                key = entry.key,
                sbn = sbn,
                packageName = sbn.packageName,
                appLabel = app.label,
                appIcon = app.icon,
                image = if (notification == null) null else latest?.icon ?: notification.getLargeIcon(),
                title = title,
                text = text,
                context = if (notification == null) null else
                    conversationTitle?.takeIf { messaging && it.isNotBlank() }
                        ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.takeIf { it.isNotBlank() },
                whenMillis = original.`when`.takeIf { it > 0 } ?: sbn.postTime,
                showWhen = extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true),
                conversation = messaging,
                actions = actions,
                sensitive = sensitive,
                dismissable = entry.row?.canViewBeDismissed() ?: entry.isClearable,
                clickable = original.contentIntent != null || original.fullScreenIntent != null,
            )
        }

        private class Message(val sender: CharSequence?, val text: CharSequence?, val icon: Icon?)

        /** The newest message of a MessagingStyle notification (the bundles `Notification` writes). */
        private fun latestMessage(extras: Bundle): Message? {
            val messages: Array<Parcelable> =
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java) ?: return null
            val last = messages.lastOrNull() as? Bundle ?: return null
            val person = last.getParcelable(KEY_SENDER_PERSON, Person::class.java)
            return Message(
                sender = person?.name ?: last.getCharSequence(KEY_SENDER),
                text = last.getCharSequence(KEY_TEXT),
                icon = person?.icon,
            )
        }

        private val MESSAGING_STYLE = Notification.MessagingStyle::class.java.name
        private const val KEY_TEXT = "text"
        private const val KEY_SENDER = "sender"
        private const val KEY_SENDER_PERSON = "sender_person"
        /** Figma shows at most two buttons per card. */
        const val MAX_ACTIONS = 2
    }
}

/** App label and icon per package, read from the ApplicationInfo the notification carries. */
class AppInfoCache {
    class App(val label: CharSequence, val icon: Drawable?)

    private val cache = HashMap<String, Pair<Long, App>>()

    fun get(context: Context, sbn: StatusBarNotification): App {
        val info = sbn.notification.extras.getParcelable(EXTRA_APPLICATION_INFO, ApplicationInfo::class.java)
            ?: try {
                context.packageManager.getApplicationInfo(sbn.packageName, PackageManager.ApplicationInfoFlags.of(0))
            } catch (missing: PackageManager.NameNotFoundException) {
                null
            }
        val cacheKey = "${sbn.user}:${sbn.packageName}"
        val version = info?.let { it.sourceDir.hashCode().toLong() } ?: 0L
        cache[cacheKey]?.let { (cachedVersion, app) -> if (cachedVersion == version) return app }
        val pm = context.packageManager
        val app = App(info?.let(pm::getApplicationLabel) ?: sbn.packageName, info?.let(pm::getApplicationIcon))
        cache[cacheKey] = version to app
        return app
    }

    fun retain(packages: Set<String>) {
        cache.keys.retainAll { key -> key.substringAfter(':') in packages }
    }

    private companion object {
        /** `Notification.EXTRA_BUILDER_APPLICATION_INFO`: every Builder-made notification carries it. */
        const val EXTRA_APPLICATION_INFO = "android.appInfo"
    }
}
