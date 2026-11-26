/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.app.ActivityOptions
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import java.util.concurrent.Executor
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject

interface IslandPresentation {
    fun render(activities: List<IslandStore.Entry<IslandActivity>>)
    fun expand(key: String): Boolean
    fun select(key: String): Boolean
    fun next(): Boolean
    fun collapse(reason: String)
    fun state(): JSONObject
}

/** All source, GUI and Agent mutations are serialized on SystemUI's main thread. */
@SysUISingleton
class IslandEngine @Inject constructor(
    private val context: Context,
    private val pipeline: NotifPipeline,
    private val extractor: IslandExtractor,
    val policy: IslandPolicy,
    private val users: UserTracker,
    private val lockscreenUsers: NotificationLockscreenUserManager,
    @Main private val handler: Handler,
    @Main private val mainExecutor: Executor,
    @Background private val worker: Executor,
) {
    private val store = IslandStore<IslandActivity>()
    private val diagnostics = ArrayDeque<String>()
    private val prefs = context.getSharedPreferences("taku_island_lifetimes", Context.MODE_PRIVATE)
    private var presentation: IslandPresentation? = null
    @Volatile var snapshot = "{}"
        private set
    /** Every key the store has extracted (including hidden and rejected); safe to read off-main. */
    @Volatile private var trackedKeys: Set<String> = emptySet()

    private val tick = Runnable { changed() }
    private val publishPresentation = Runnable { publishSnapshot() }

    /** GUI-only transitions (swipe, rotation, IME and animation) also notify Agent observers. */
    fun presentationChanged() {
        handler.removeCallbacks(publishPresentation)
        handler.post(publishPresentation)
    }

    fun start() {
        IslandRuntime.engine = this
        policy.start { refresh() }
        users.addCallback(object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                presentation?.collapse("user_changed")
                store.clear()
                refresh()
            }
            override fun onProfilesChanged(profiles: List<android.content.pm.UserInfo>) {
                presentation?.collapse("profiles_changed")
                store.clear()
                refresh()
            }
        }, mainExecutor)
        pipeline.addCollectionListener(object : NotifCollectionListener {
            override fun onEntryAdded(entry: NotificationEntry) = update(entry)
            override fun onEntryUpdated(entry: NotificationEntry) = update(entry)
            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                store.remove(entry.key)
                forgetLifetime(entry.key)
                changed()
            }
        })
        refresh()

    }

    fun attachPresentation(value: IslandPresentation) {
        presentation = value
        changed()
    }

    fun refresh() {
        pipeline.getAllNotifs().forEach(::update)
        changed()
    }

    private fun update(entry: NotificationEntry) {
        val sbn = entry.sbn
        if (!lockscreenUsers.isCurrentProfile(sbn.userId) || !lockscreenUsers.isProfileAvailable(sbn.userId)) {
            store.remove(entry.key)
            changed()
            return
        }
        val extras = sbn.notification.extras
        val candidate = sbn.notification.isPromotedOngoing || extras.containsKey(IslandExtractor.EXTRA_V2) ||
            IslandSystemNotifications.candidate(sbn.notification)
        if (!candidate) {
            store.remove(entry.key)
            changed()
            return
        }
        val revision = store.begin(entry.key)
        if (revision < 0) { diagnose("capacity_limit:${sbn.packageName}"); return }
        val allowed = policy.canPost(sbn.packageName, sbn.userId)
        worker.execute {
            val result = extractor.extract(sbn, allowed)
            handler.post {
                if (!store.isCurrent(sbn.key, revision)) return@post
                if (result.error != null) diagnose("${sbn.key}:${result.error}")
                val activity = result.activity
                if (activity == null) store.reject(sbn.key, revision)
                else {
                    val payload = activity.payload
                    val now = SystemClock.elapsedRealtime()
                    // Without a declared end the island leaves 1 h after the first post (owner decision).
                    val lifetime = minOf(payload.optInt("timeoutS", IslandStore.DEFAULT_LIFETIME_S),
                        prefs.getInt("lifetime|${sbn.key}", IslandStore.MAX_LIFETIME_S))
                    if (store.put(sbn.key, revision, now, firstSeen(sbn.key, now),
                            lifetime, payload.optInt("staleAfterS", 0),
                            activity.tier, payload.optBoolean("alert"), activity)) {
                        prefs.edit().putInt("lifetime|${sbn.key}", lifetime).apply()
                        if (prefs.getBoolean("dismissed|${sbn.key}", false)) store.dismiss(sbn.key)
                    }
                }
                changed()
            }
        }
    }

    fun putSystem(activity: IslandActivity, now: Long) {
        store.put(activity.key, store.begin(activity.key), now, firstSeen(activity.key, now),
            IslandStore.MAX_LIFETIME_S, 0, activity.tier, false, activity)
        if (prefs.getBoolean("dismissed|${activity.key}", false)) store.dismiss(activity.key)
        changed()
    }

    fun removeSystem(key: String) { store.remove(key); forgetLifetime(key); changed() }

    fun reconcileSystemKeys(current: Set<String>) {
        prefs.all.keys.filter { it.startsWith("elapsed|taku:system:") }
            .map { it.removePrefix("elapsed|") }.filter { it !in current }.forEach(::removeSystem)
    }

    private fun forgetLifetime(key: String) {
        prefs.edit().remove("elapsed|$key").remove("wall|$key").remove("boot|$key")
            .remove("dismissed|$key").remove("lifetime|$key").apply()
    }

    private fun firstSeen(key: String, now: Long): Long {
        val boot = Settings.Global.getInt(context.contentResolver, "boot_count", -1)
        val wallNow = System.currentTimeMillis()
        if (prefs.getInt("boot|$key", -2) == boot) return prefs.getLong("elapsed|$key", now).coerceAtMost(now)
        val age = (wallNow - prefs.getLong("wall|$key", wallNow)).coerceIn(0, IslandStore.MAX_LIFETIME_MS)
        val first = now - age
        prefs.edit().putInt("boot|$key", boot).putLong("elapsed|$key", first)
            .putLong("wall|$key", wallNow - age).apply()
        return first
    }

    private fun diagnose(message: String) {
        if (diagnostics.size >= 64) diagnostics.removeFirst()
        diagnostics.addLast(message.take(512))
    }

    private fun visible() = if (policy.enabled) store.active(SystemClock.elapsedRealtime()) else emptyList()

    /** Not extracted yet: a brand-new post whose heads-up decision precedes asynchronous extraction. */
    fun isUnknown(key: String) = key !in trackedKeys

    /**
     * Synchronous pre-check for a brand-new post: would it become an ordinary Taku-format island
     * activity, whose `alert` replaces the heads-up? It runs the real extraction (same user, publisher,
     * payload, icon, action and content-service checks), so a notification the island will reject
     * always keeps Android's heads-up. Calls, alarms and plain Live Updates are excluded.
     *
     * The store must also admit it: with every slot taken the island turns the post away
     * (`capacity_limit`), so its heads-up stays. The slot is reserved here, last, so nothing posted
     * between this decision and the post's own [update] can take it.
     */
    fun headsUpCandidate(sbn: android.service.notification.StatusBarNotification): Boolean = try {
        val n = sbn.notification
        lockscreenUsers.isCurrentProfile(sbn.userId) && lockscreenUsers.isProfileAvailable(sbn.userId) &&
            !IslandSystemNotifications.candidate(n) && n.extras.containsKey(IslandExtractor.EXTRA_V2) &&
            extractor.extract(sbn, policy.canPost(sbn.packageName, sbn.userId)).activity?.source == IslandActivity.SOURCE_CUSTOM &&
            admitted(sbn.key)
    } catch (unreadable: Exception) { false }

    /** The store is main-thread state; any other thread gets no reservation and keeps the heads-up. */
    private fun admitted(key: String) = Looper.myLooper() == handler.looper && store.admit(key)

    private fun changed() {
        trackedKeys = store.keys()
        val active = visible()
        presentation?.render(active)
        publishSnapshot()
        handler.removeCallbacks(tick)
        val now = SystemClock.elapsedRealtime()
        val deadlines = active.flatMap { listOf(it.expiresAt, it.staleAt, it.alertUntil) }.filter { it > now }
        var next = deadlines.minOrNull()
        if (active.any { it.data.timed }) next = minOf(next ?: Long.MAX_VALUE, now + 1000)
        next?.let { handler.postDelayed(tick, (it - now).coerceAtLeast(1)) }
    }

    private fun publishSnapshot() {
        val nextSnapshot = JSONObject().put("settings", policy.snapshot()).put("activities", list())
            .put("presentation", presentation?.state() ?: JSONObject.NULL)
            .put("diagnostics", JSONArray(diagnostics.toList())).toString()
        if (nextSnapshot != snapshot) {
            snapshot = nextSnapshot
            context.contentResolver.notifyChange(Uri.parse("content://com.android.systemui.island"), null)
        }
    }

    private fun item(entry: IslandStore.Entry<IslandActivity>): JSONObject = JSONObject()
        .put("key", entry.key).put("package", entry.data.packageName).put("userId", entry.data.userId)
        .put("revision", entry.revision).put("firstSeenElapsedMs", entry.firstSeen)
        .put("expiresElapsedMs", entry.expiresAt).put("stale", entry.stale(SystemClock.elapsedRealtime()))
        .put("dismissed", entry.dismissed).put("active", entry.active(SystemClock.elapsedRealtime()))
        .put("triggeredAtElapsedMs", entry.triggeredAt).put("source", entry.data.source).put("tier", entry.data.tier)
        .put("timeoutKind", timeoutKind(entry))
        .put("availableActions", JSONArray((entry.data.actions.map { (id, action) -> JSONObject().put("id", id).put("title", action.title) } +
            entry.data.nativeActions.map { (id, action) -> JSONObject().put("id", id).put("title", action.title) })))
        .put("payload", entry.data.payload).put("fallback", entry.data.fallback ?: JSONObject.NULL)

    /** Which rule ends this island: the publisher's own timeout, the 1 h default, or the 12 h cap. */
    private fun timeoutKind(entry: IslandStore.Entry<IslandActivity>): String {
        val requested = entry.data.payload.optInt("timeoutS", 0)
        return when {
            entry.key.startsWith("taku:system:") || requested >= IslandStore.MAX_LIFETIME_S -> "cap"
            requested > 0 -> "explicit"
            else -> "default"
        }
    }

    /** Active activities within [scope]: all of them, or only those the calling UID published. */
    private fun list(scope: Int = IslandAccessPolicy.ALL_ACTIVITIES): JSONArray = JSONArray().apply {
        store.active(SystemClock.elapsedRealtime())
            .filter { IslandAccessPolicy.owns(scope, it.data.uid) }.forEach { put(item(it)) }
    }

    /**
     * What a scoped (third-party) caller may read: whether the island is on, the protocol, and its
     * own activities. The publisher allow-list, other apps' activities, the presentation (which
     * names whichever activity is selected or in front) and diagnostics are left out.
     */
    private fun scopedSettings() = JSONObject().put("enabled", policy.enabled).put("protocol", IslandPolicy.PROTOCOL_VERSION)

    private fun state(scope: Int): JSONObject =
        if (scope == IslandAccessPolicy.ALL_ACTIVITIES) JSONObject().put("settings", policy.snapshot())
            .put("activities", list()).put("presentation", presentation?.state() ?: JSONObject.NULL)
        else JSONObject().put("settings", scopedSettings()).put("activities", list(scope))

    /** The entry [key] names when it is within [scope]; another app's activity is absent, like a missing one. */
    private fun owned(key: String?, scope: Int): IslandStore.Entry<IslandActivity>? =
        store.get(key)?.takeIf { IslandAccessPolicy.target(scope, it.data.uid) == null }

    /**
     * [scope] is [IslandAccessPolicy.ALL_ACTIVITIES] for SystemUI's own gestures, `cmd` and trusted
     * provider callers, else the calling UID: such a caller reads and operates only the activities
     * that UID published, and never the operations that act on whatever the island presents.
     */
    fun command(method: String, arg: String?, extra: String?, scope: Int = IslandAccessPolicy.ALL_ACTIVITIES): JSONObject {
        val scoped = scope != IslandAccessPolicy.ALL_ACTIVITIES
        if (scoped && IslandAccessPolicy.tier(method).let { it == IslandAccessPolicy.Tier.GLOBAL || it == IslandAccessPolicy.Tier.ADMIN })
            return IslandRuntime.error(IslandAccessPolicy.DENIED)
        if (scoped && IslandAccessPolicy.targeted(method) && owned(arg, scope) == null)
            return IslandRuntime.error(IslandAccessPolicy.NOT_FOUND)
        return when (method) {
            "capabilities" -> JSONObject().put("ok", true).put("protocol", IslandPolicy.PROTOCOL_VERSION)
                .put("enabled", policy.enabled).put("presentationAttached", presentation != null)
                .put("takuProducer", IslandRuntime.producerStatus)
                .put("compiledAndDeviceVerified", false)
                .put("operations", JSONArray(listOf("list", "get", "select", "next", "tap", "expand", "collapse", "dismiss", "hide", "action", "open", "settings", "setEnabled", "setAllowed")))
            "canShowIsland" -> JSONObject().put("ok", true).put("enabled", policy.enabled)
                .put("package", arg ?: JSONObject.NULL).put("customAllowed", arg != null && policy.canPost(arg))
                .put("platformSigned", arg != null && policy.platformSigned(arg))
                .put("liveUpdates", true)
            "list" -> JSONObject().put("ok", true).put("activities", list(scope))
            "state" -> state(scope).put("ok", true)
            "get" -> owned(arg, scope)?.let { JSONObject().put("ok", true).put("activity", item(it)) }
                ?: IslandRuntime.error("not_found")
            "settings" -> JSONObject().put("ok", true).put("settings", if (scoped) scopedSettings() else policy.snapshot())
            "setEnabled" -> mutate { policy.setEnabled(requireNotNull(arg) { "missing_value" }) }
            "setAllowed" -> mutate { policy.setAllowed(arg.orEmpty()) }
            "setProtocol" -> IslandRuntime.error("read_only_protocol")
            "expand" -> mutate(scope) {
                require(policy.enabled) { "island_disabled" }
                require(store.get(arg)?.active(SystemClock.elapsedRealtime()) == true) { "not_found" }
                require(presentation?.expand(requireNotNull(arg)) == true) { "presentation_unavailable" }
            }
            "collapse" -> mutate { presentation?.collapse("agent") }
            "select" -> mutate(scope) { require(presentation?.select(requireNotNull(arg) { "missing_key" }) == true) { "not_found" } }
            "next" -> mutate { require(presentation?.next() == true) { "no_other_activity" } }
            // The capsule's own tap: open the app when the publisher chose `open`, else expand.
            "tap" -> mutate(scope) {
                // A capsule tap exists only while the island is on, whichever way the tap resolves.
                require(policy.enabled) { "island_disabled" }
                val activity = requireActivity(arg)
                val intent = activity.openIntent
                if (activity.tap == "open" && intent != null) opened(activity.key, intent)
                else require(presentation?.expand(activity.key) == true) { "presentation_unavailable" }
            }
            "dismiss", "hide" -> mutate(scope) {
                require(arg != null && store.dismiss(arg)) { "not_found" }
                prefs.edit().putBoolean("dismissed|$arg", true).apply()
            }
            "open" -> mutate(scope) {
                val activity = requireActivity(arg)
                opened(activity.key, activity.openIntent ?: throw IllegalArgumentException("action_unavailable"))
            }
            "action" -> mutate(scope) {
                val activity = requireActivity(arg)
                val request = JSONObject(extra ?: throw IllegalArgumentException("missing_action"))
                val id = request.getString("id")
                val native = activity.nativeActions[id]
                if (native != null) {
                    require(!request.has("reply")) { "reply_unavailable" }
                    native.run()
                    return@mutate
                }
                val action = activity.actions[id] ?: throw IllegalArgumentException("action_unavailable")
                val fill = Intent()
                if (request.has("reply")) {
                    val reply = request.getString("reply")
                    require(reply.length <= 4000) { "reply_too_long" }
                    val inputs = action.remoteInputs ?: throw IllegalArgumentException("reply_unavailable")
                    val values = Bundle()
                    inputs.filter { it.allowFreeFormInput }.forEach { values.putCharSequence(it.resultKey, reply) }
                    require(!values.isEmpty) { "reply_unavailable" }
                    RemoteInput.addResultsToIntent(inputs, fill, values)
                }
                if (action.actionIntent.isActivity) opened(activity.key, action.actionIntent, fill) else send(action.actionIntent, fill)
            }
            else -> IslandRuntime.error("unknown_operation")
        }
    }

    private fun requireActivity(key: String?): IslandActivity {
        val entry = store.get(key) ?: throw IllegalArgumentException("not_found")
        require(entry.active(SystemClock.elapsedRealtime())) { "not_found" }
        return entry.data
    }

    /**
     * Launches the publisher's screen and folds the card: the app now shows that content, so the
     * card neither covers it nor lingers when the user comes back to the home screen.
     */
    private fun opened(key: String, intent: android.app.PendingIntent, fill: Intent = Intent()) {
        send(intent, fill)
        presentation?.collapse("opened:$key")
    }

    private fun send(intent: android.app.PendingIntent, fill: Intent = Intent()) {
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        intent.send(context, 0, fill, null, handler, null, options.toBundle())
    }

    /** The returned state is cut to [scope] like every read. */
    private fun mutate(scope: Int = IslandAccessPolicy.ALL_ACTIVITIES, action: () -> Unit): JSONObject = try {
        action()
        changed()
        JSONObject().put("ok", true).put("state",
            if (scope == IslandAccessPolicy.ALL_ACTIVITIES) JSONObject(snapshot) else state(scope))
    } catch (failure: Exception) { IslandRuntime.error(failure.message ?: "operation_failed") }
}
