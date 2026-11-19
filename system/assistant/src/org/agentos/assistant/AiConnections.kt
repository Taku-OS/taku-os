package org.agentos.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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
    private val watching = AtomicBoolean(false)

    // ChatGPT

    /**
     * Starts ChatGPT sign-in through the same broker control as the account page. Switches the
     * assistant from Magpie to ChatGPT first, as the Model connection page does (refused while
     * tasks run). With [open], opens the official page in the browser; with no browser, opens the
     * account page so the device code can be used. Brings Home forward once the account connects.
     */
    @JvmStatic
    fun startChatGpt(context: Context, type: String, open: Boolean, includeChallenge: Boolean): JSONObject {
        if (type !in LOGIN_TYPES) throw IOException("INVALID_LOGIN_TYPE")
        var view = AccountStatus.raw()
        if (chatGptConnected(view)) return result(view, "none", includeChallenge)
        if (magpieMode()) {
            switchToChatGpt(context)
            view = settle()
        }
        try {
            TaskRequests.control("account/login/start", JSONObject().put("type", type))
        } catch (e: Exception) {
            Log.w(TAG, "login start refused: ${e.message}")
            throw IOException(if (e.message == "execution active") "TASK_RUNNING" else "LOGIN_UNAVAILABLE")
        }
        view = awaitChallenge()
        if (chatGptConnected(view)) return result(view, "none", includeChallenge)
        if (view.optString("phase") != "pending") throw IOException("LOGIN_UNAVAILABLE")
        val opened = if (open) open(context, view) else "none"
        watch(context)
        return result(view, opened, includeChallenge)
    }

    /** Cancels a pending sign-in, as "Cancel this sign-in" on the account page does. */
    @JvmStatic
    fun cancelChatGpt(): JSONObject {
        try {
            TaskRequests.control("account/login/cancel", JSONObject())
        } catch (e: Exception) {
            Log.w(TAG, "login cancel failed: ${e.message}")
            throw IOException("LOGIN_UNAVAILABLE")
        }
        return AccountStatus.status(AccountStatus.raw())
    }

    private fun chatGptConnected(view: JSONObject) = view.optBoolean("connected") && view.optString("kind") == "chatgpt"

    private fun magpieMode(): Boolean = try {
        NativeClient.exchange(JSONObject().put("op", "modelConnection").put("action", "read")).optString("mode") == "magpie"
    } catch (_: Exception) {
        false
    }

    private fun switchToChatGpt(context: Context) {
        try {
            NativeClient.exchange(JSONObject().put("op", "modelConnection").put("action", "select").put("mode", "openai").put("model", ""))
        } catch (e: Exception) {
            Log.w(TAG, "mode switch refused: ${e.message}")
            throw IOException("TASK_RUNNING")
        }
        ModelConnectionView.saveOverrides(context.getSharedPreferences("agent-settings", 0), "openai", "")
    }

    /** After a mode switch the app-server restarts; wait until the account is read again. */
    private fun settle(): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (true) {
            val view = AccountStatus.raw()
            if (view.optString("phase") != "loading" && view.optString("kind") != "magpie") return view
            if (SystemClock.elapsedRealtime() > deadline) throw IOException("ACCOUNT_UNAVAILABLE")
            Thread.sleep(300)
        }
    }

    private fun awaitChallenge(): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (true) {
            val view = AccountStatus.raw()
            val phase = view.optString("phase")
            if (chatGptConnected(view) || phase == "failed" || phase == "disconnected" || (phase == "pending" && view.optString("url").isNotEmpty())) return view
            if (SystemClock.elapsedRealtime() > deadline) return view
            Thread.sleep(200)
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
        context.startActivity(Intent(context, PanelActivity::class.java).putExtra("page", "account").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "accountPage"
    }

    /** Brings Home back once sign-in finishes; ends on failure, cancel or timeout. One watcher at a time. */
    private fun watch(context: Context) {
        if (!watching.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                val deadline = SystemClock.elapsedRealtime() + WATCH_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(1_000)
                    val view = try { AccountStatus.raw() } catch (_: IOException) { continue }
                    if (chatGptConnected(view)) {
                        app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return@Thread
                    }
                    if (view.optString("phase") in setOf("failed", "disconnected")) return@Thread
                }
            } catch (e: Exception) {
                Log.w(TAG, "sign-in watch ended", e)
            } finally {
                watching.set(false)
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
     * to its Jev decision model and switched on, as the Jev settings page does.
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
        if (model.isEmpty()) throw IOException("NO_DECISION_MODEL")
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
