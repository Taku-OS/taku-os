package org.agentos.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * One-step connections for the AI services, shared by the Home onboarding card (gateway
 * `connectChatGpt`, `cancelChatGpt`, `readJev`, `connectJev`, `enableJev`) and Agents (open access
 * `account/login/start`, `account/login/cancel`, `jev/status`, `jev/connect`, `jev/enable`). Each path runs the
 * same broker operation as the native settings pages. Call off the main thread.
 */
internal object AiConnections {
    private const val TAG = "TakuAiConnections"
    private val AUTH_HOSTS = setOf("auth.openai.com", "chatgpt.com")
    private val LOGIN_TYPES = setOf("chatgpt", "chatgptDeviceCode")
    private const val WATCH_MS = 15 * 60_000L
    /** The current sign-in's watcher; a new sign-in or a cancel retires the previous one. */
    private val watchGeneration = AtomicLong()

    // ChatGPT

    /** Lock for [restoreModel] and the watcher flags. */
    private val signInLock = Any()
    /**
     * The Magpie model to switch back to when a sign-in that switched away from Magpie does not
     * land (cancelled, failed, refused or timed out). Null when there is nothing to restore.
     */
    private var restoreModel: String? = null
    /** Some caller of the running sign-in asked to bring Home back when it lands (Home onboarding). */
    @Volatile private var returnHome = false
    private lateinit var app: Context

    /**
     * Starts ChatGPT sign-in through the same broker control as the account page. Switches the
     * assistant from Magpie to ChatGPT first, as the Model connection page does (refused while
     * tasks run); if that sign-in then does not land, Magpie and its model are switched back.
     * With [open], a browser sign-in (`chatgpt`) opens the official page in the browser, and a
     * device-code sign-in (`chatgptDeviceCode`) or a device with no browser opens the account page
     * with the one-time code. Home onboarding passes neither [open] nor the account page: it shows
     * the code and official URL ([includeChallenge]) in its own card. With [returnHome] (the Home onboarding card, never
     * Agents), brings Home forward once the account connects.
     */
    @JvmStatic
    fun startChatGpt(context: Context, type: String, open: Boolean, includeChallenge: Boolean, returnHome: Boolean): JSONObject {
        if (type !in LOGIN_TYPES) throw IOException("INVALID_LOGIN_TYPE")
        app = context.applicationContext
        var view = account()
        if (chatGptConnected(view)) return result(view, "none", includeChallenge)
        // This sign-in replaces any earlier one: retire its watcher before anything can fail.
        watchGeneration.incrementAndGet()
        this.returnHome = false
        val magpie = magpieModel()
        if (magpie != null) {
            switchConnection(context, "openai", "")
            synchronized(signInLock) { if (restoreModel == null) restoreModel = magpie }
        }
        try {
            if (magpie != null) view = settle()
            try {
                TaskRequests.control("account/login/start", JSONObject().put("type", type))
            } catch (e: Exception) {
                Log.w(TAG, "login start refused: ${e.message}")
                throw IOException(if (e.message == "execution active") "TASK_RUNNING" else "LOGIN_UNAVAILABLE")
            }
            view = awaitChallenge()
            if (chatGptConnected(view)) { markLanded(); return result(view, "none", includeChallenge) }
            if (view.optString("phase") != "pending") throw IOException("LOGIN_UNAVAILABLE")
        } catch (e: Exception) {
            restoreMagpie()
            throw e
        }
        // Watch before opening: if the page cannot open, the watcher still restores Magpie.
        this.returnHome = returnHome
        watch()
        val opened = if (open) open(context, view) else "none"
        return result(view, opened, includeChallenge)
    }

    /** Cancels a pending sign-in, as "Cancel this sign-in" on the account page does; Magpie comes back if it was switched away. */
    @JvmStatic
    fun cancelChatGpt(): JSONObject {
        try {
            TaskRequests.control("account/login/cancel", JSONObject())
        } catch (e: Exception) {
            Log.w(TAG, "login cancel failed: ${e.message}")
            throw IOException("LOGIN_UNAVAILABLE")
        }
        // The cancelled sign-in's watcher must not act on a later one; Magpie is restored here.
        watchGeneration.incrementAndGet()
        returnHome = false
        restoreMagpie()
        return AccountStatus.status(account())
    }

    private fun chatGptConnected(view: JSONObject) = view.optBoolean("connected") && view.optString("kind") == "chatgpt"

    /** The account view; an unreachable broker or bad view is `ACCOUNT_UNAVAILABLE`, as `account/status` reports it. */
    private fun account(): JSONObject = try {
        AccountStatus.raw()
    } catch (e: IOException) {
        throw IOException("ACCOUNT_UNAVAILABLE", e)
    }

    /** The selected Magpie model when the assistant runs in Magpie mode, else null. */
    private fun magpieModel(): String? = try {
        val state = NativeClient.exchange(JSONObject().put("op", "modelConnection").put("action", "read"))
        if (state.optString("mode") == "magpie") state.optString("model") else null
    } catch (_: Exception) {
        null
    }

    private fun switchConnection(context: Context, mode: String, model: String) {
        try {
            ComposerOptions.switchConnection(context, mode, model)
        } catch (e: Exception) {
            Log.w(TAG, "mode switch to $mode refused: ${e.message}")
            throw IOException("TASK_RUNNING")
        }
    }

    /** The sign-in landed: ChatGPT stays, nothing to restore. */
    private fun markLanded() = synchronized(signInLock) { restoreModel = null }

    /** A sign-in that switched away from Magpie did not land: switch Magpie and its model back. */
    private fun restoreMagpie() {
        val model = synchronized(signInLock) { restoreModel.also { restoreModel = null } } ?: return
        try {
            if (chatGptConnected(AccountStatus.raw())) return
            switchConnection(app, "magpie", model)
        } catch (e: Exception) {
            // Model connection still shows ChatGPT; the user can pick Magpie there again.
            Log.w(TAG, "could not switch back to Magpie: ${e.message}")
        }
    }

    /** After a mode switch the app-server restarts; wait until the account is read again. */
    private fun settle(): JSONObject = poll(20_000, 300) { it.optString("phase") != "loading" && it.optString("kind") != "magpie" }
        ?: throw IOException("ACCOUNT_UNAVAILABLE")

    private fun awaitChallenge(): JSONObject = poll(10_000, 200) {
        val phase = it.optString("phase")
        chatGptConnected(it) || phase == "failed" || phase == "disconnected" || (phase == "pending" && it.optString("url").isNotEmpty())
    } ?: account()

    /**
     * Reads the account until [done] or the deadline; a brief unreachable broker (the app-server
     * restarting) is retried. Null on timeout; `ACCOUNT_UNAVAILABLE` if it never answered.
     */
    private fun poll(timeoutMs: Long, stepMs: Long, done: (JSONObject) -> Boolean): JSONObject? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var answered = false
        while (true) {
            val view = try { AccountStatus.raw().also { answered = true } } catch (e: IOException) { null }
            if (view != null && done(view)) return view
            if (SystemClock.elapsedRealtime() > deadline) {
                if (!answered) throw IOException("ACCOUNT_UNAVAILABLE")
                return null
            }
            Thread.sleep(stepMs)
        }
    }

    /** The official page in the browser, else the account page (device code). */
    private fun open(context: Context, view: JSONObject): String {
        val url = Uri.parse(view.optString("url"))
        val official = url.scheme == "https" && url.host in AUTH_HOSTS && view.optString("code").isEmpty()
        if (official) try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "browser"
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "no browser; opening the account page")
        }
        if (!AgentFrontendLauncher.openRoute(context, "/system-ai?section=account")) throw IOException("AGENT_UI_UNAVAILABLE")
        return "accountPage"
    }

    /**
     * Follows the sign-in until it lands, fails, is cancelled or times out. Landing brings Home
     * back only if Home started it ([returnHome]); not landing switches Magpie back. Each sign-in
     * has its own watcher: a newer sign-in or a cancel retires this one, and a retired watcher
     * leaves [returnHome] and the model connection to the sign-in that replaced it.
     */
    private fun watch() {
        val generation = watchGeneration.incrementAndGet()
        fun current() = watchGeneration.get() == generation
        Thread({
            var landed = false
            try {
                val deadline = SystemClock.elapsedRealtime() + WATCH_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(1_000)
                    if (!current()) return@Thread
                    val view = try { AccountStatus.raw() } catch (_: IOException) { continue }
                    if (!current()) return@Thread
                    if (chatGptConnected(view)) {
                        landed = true
                        markLanded()
                        if (returnHome) app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return@Thread
                    }
                    if (view.optString("phase") in setOf("failed", "disconnected")) return@Thread
                }
            } catch (e: Exception) {
                Log.w(TAG, "sign-in watch ended", e)
            } finally {
                if (current()) {
                    returnHome = false
                    if (!landed) restoreMagpie()
                }
            }
        }, "taku-chatgpt-signin").apply { isDaemon = true }.start()
    }

    private fun result(view: JSONObject, opened: String, includeChallenge: Boolean): JSONObject {
        val out = AccountStatus.status(view).put("opened", opened)
        // Agents receive what the account page shows the user: the official URL and device code.
        if (includeChallenge) out.put("url", view.optString("url")).put("code", view.optString("code"))
        return out
    }

    // Jev

    /** Jev is ready when it is switched on and bound to a decision model (`jev` scene route). */
    @JvmStatic
    fun jevStatus(context: Context): JSONObject {
        val model = SceneRoutes.get(context, "jev")
        val enabled = context.getSharedPreferences("agent-settings", 0).getBoolean("jevEnabled", false)
        return JSONObject().put("enabled", enabled).put("model", model).put("ready", enabled && model.isNotEmpty())
    }

    /** The model Jev decisions use, or "" when Jev is off or unbound. */
    @JvmStatic
    fun jevModel(context: Context): String = jevStatus(context).let { if (it.getBoolean("ready")) it.getString("model") else "" }

    /**
     * Onboarding's Jev key, OpenRouter (`sk-or-v1-…`) or TypeSafe (`apikey_…`): the broker adds the
     * matching Magpie provider in the background (no Magpie key or admin page), then Jev is bound
     * to its Jev decision model and switched on, as the Jev settings page does. When no Jev model is
     * listed within the broker's budget the key stays saved and this throws `NO_DECISION_MODEL`, or
     * `MAGPIE_UNAVAILABLE` when Magpie's catalog never answered; [enableJev] finishes the job later.
     */
    @JvmStatic
    @Synchronized
    fun connectJev(context: Context, key: String): JSONObject {
        val result = try {
            NativeClient.exchange(JSONObject().put("op", "decision").put("action", "connect").put("key", key.trim()))
        } catch (e: Exception) {
            Log.w(TAG, "Jev key not saved: ${e.message}")
            throw IOException(if (e.message == "INVALID_KEY") "INVALID_KEY" else "KEY_NOT_SAVED")
        }
        val model = result.optString("model")
        // The key is saved either way; say whether Magpie lists no Jev model for it or never answered.
        if (model.isEmpty()) throw IOException(if (result.optBoolean("catalog", true)) "NO_DECISION_MODEL" else "MAGPIE_UNAVAILABLE")
        return enable(context, model)
    }

    /**
     * Switches Jev on with a Jev model Magpie already serves (the current binding when it is still
     * listed, else jev-latest, else any Jev), so a configured Magpie needs no key. `NO_DECISION_MODEL`
     * when there is none, which is when onboarding asks for a key.
     */
    @JvmStatic
    @Synchronized
    fun enableJev(context: Context): JSONObject {
        val models = try {
            JevApiClient().models().getJSONArray("models")
        } catch (e: Exception) {
            Log.w(TAG, "decision catalog unavailable: ${e.message}")
            throw IOException("MAGPIE_UNAVAILABLE")
        }
        val bound = SceneRoutes.get(context, "jev")
        val listed = (0 until models.length()).map { models.getJSONObject(it).getString("id") }
        val model = bound.takeIf { it.isNotEmpty() && it in listed }
            ?: listed.firstOrNull { it.lowercase().endsWith("jev-latest") }
            ?: listed.firstOrNull { it.contains("jev", ignoreCase = true) }
            ?: throw IOException("NO_DECISION_MODEL")
        return enable(context, model)
    }

    private fun enable(context: Context, model: String): JSONObject {
        SceneRoutes.bind(context, "jev", model, true)
        if (!context.getSharedPreferences("agent-settings", 0).edit().putBoolean("jevEnabled", true).commit())
            throw IOException("SETTINGS_WRITE_FAILED")
        return jevStatus(context)
    }
}
