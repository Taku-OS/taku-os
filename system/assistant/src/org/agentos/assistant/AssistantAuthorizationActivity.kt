package org.agentos.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Keeps credential input and browser challenges in the backend process. */
class AssistantAuthorizationActivity : Activity() {
    private lateinit var ui: AgentSettingsUi
    private lateinit var body: LinearLayout
    private lateinit var details: LinearLayout
    private lateinit var status: TextView
    private lateinit var page: String
    private var visible = false
    private var busy = false
    private var rendered = ""
    private var browserOpened = false
    private var editor: AlertDialog? = null
    private val poll = Runnable { if (visible && !busy) readAccount() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = intent.getStringExtra("page") ?: ""
        if (page !in setOf("account", "byok", "magpie")) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val theme = DrawerTheme(this, dark)
        ui = AgentSettingsUi(this, theme)
        body = ui.column().apply { setBackgroundColor(theme.surface) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(theme.surface)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(ui.dp(20), bars.top + ui.dp(16), ui.dp(20), bars.bottom + ui.dp(16))
                insets
            }
            addView(body)
        }
        setContentView(scroll)
        button(body, "Back to Taku AI", action = ::finish)
        ui.add(body, ui.text(when (page) { "account" -> "ChatGPT account"; "byok" -> "OpenRouter API keys"; else -> "Magpie models and routes" }, 26, true), 20)
        status = ui.text("", 14, false)
        ui.add(body, status, 12)
        details = ui.column()
        ui.add(body, details, 8)
        browserOpened = savedInstanceState?.getBoolean("browserOpened") == true
        if (page == "magpie") {
            button(details, "Open Magpie management", true, ::openManagement)
            if (!browserOpened) openManagement()
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putBoolean("browserOpened", browserOpened)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (page == "account") readAccount()
        if (page == "byok" && editor == null) readKeys()
    }

    override fun onPause() {
        visible = false
        NativeClient.main.removeCallbacks(poll)
        if (::page.isInitialized && page == "account") {
            details.removeAllViews()
            rendered = ""
        }
        super.onPause()
    }

    override fun onDestroy() {
        editor?.dismiss()
        super.onDestroy()
    }

    private fun button(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit) =
        ui.button(label, primary) { if (!busy) action() }.also { ui.add(parent, it, 10) }

    private fun <T> work(operation: () -> T, ready: (T) -> Unit = {}) {
        if (busy) return
        busy = true
        status.text = "Working…"
        NativeClient.io.execute {
            val result = runCatching(operation)
            NativeClient.main.post {
                busy = false
                if (isFinishing || isDestroyed) return@post
                status.text = ""
                if (page == "account" && result.isSuccess) rendered = ""
                result.fold(ready) { status.text = errorText(it.message) }
                if (page == "account" && visible) schedulePoll()
            }
        }
    }

    private fun errorText(code: String?): String = when (code) {
        "INVALID_CREDENTIAL" -> "Enter one complete API key without spaces."
        "ACCOUNT_LOGIN_FAILED" -> "ChatGPT sign-in could not start."
        "ACCOUNT_CANCEL_FAILED" -> "ChatGPT sign-in could not be cancelled."
        "INVALID_AUTHORIZATION_URL" -> "The account service returned an invalid authorization page."
        "KEY_OPERATION_FAILED" -> "The key operation was not confirmed. Refresh and check the saved keys."
        else -> "The service is unavailable. Try again."
    }

    private fun schedulePoll() {
        NativeClient.main.removeCallbacks(poll)
        NativeClient.main.postDelayed(poll, 1000)
    }

    private fun readAccount() {
        if (busy || !visible) return
        busy = true
        NativeClient.io.execute {
            val result = runCatching { AssistantAccounts.accountView() }
            NativeClient.main.post {
                busy = false
                if (!visible || isFinishing || isDestroyed) return@post
                result.fold(::renderAccount) { status.text = "Account status is unavailable." }
                schedulePoll()
            }
        }
    }

    private fun renderAccount(view: JSONObject) {
        val next = view.toString()
        if (next == rendered) return
        rendered = next
        status.text = view.optString("message")
        details.removeAllViews()
        when (view.optString("phase")) {
            "pending" -> {
                view.optString("code").takeIf(String::isNotEmpty)?.let { ui.add(details, ui.text(it, 22, true), 8) }
                button(details, "Open official authorization page", true) {
                    try { browser(AssistantAccounts.officialPage(view)) }
                    catch (error: Exception) { status.text = errorText(error.message) }
                }
                button(details, "Cancel this sign-in") { work({ AssistantAccounts.cancel(); AssistantAccounts.accountView() }, ::renderAccount) }
            }
            "starting", "verifying" -> Unit
            else -> if (!view.optBoolean("connected")) {
                button(details, "Sign in with a device code", true) { login("chatgptDeviceCode") }
                button(details, "Authorize in browser") { login("chatgpt") }
            } else view.optString("plan").takeIf(String::isNotEmpty)?.let { ui.add(details, ui.text(it, 14, true), 8) }
        }
        button(details, "Refresh connection status") { work({ AssistantAccounts.refresh(); AssistantAccounts.accountView() }, ::renderAccount) }
    }

    private fun login(type: String) = work({ AssistantAccounts.login(type); AssistantAccounts.accountView() }, ::renderAccount)

    private fun readKeys() = work(AssistantAccounts::keys, ::renderKeys)

    private fun renderKeys(view: JSONObject) {
        details.removeAllViews()
        val keys = view.getJSONArray("keys")
        var found = false
        for (i in 0 until keys.length()) {
            val key = keys.getJSONObject(i)
            if (key.optString("provider") != "openrouter") continue
            found = true
            val card = ui.card()
            ui.add(card, ui.text("${key.getString("name")} · ${key.getString("masked")}", 14, true), 0)
            button(card, "Replace key") { editKey(key) }
            button(card, "Remove key") {
                AlertDialog.Builder(this).setTitle("Remove this OpenRouter key?")
                    .setMessage(key.getString("masked")).setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        work({ AssistantAccounts.removeKey(key.getString("id"), key.getString("ref")) }, ::renderKeys)
                    }.show()
            }
            ui.add(details, card, 12)
        }
        if (!found) ui.add(details, ui.text("No OpenRouter keys saved", 14, false), 8)
        button(details, "Add OpenRouter API key", true) { editKey(null) }
        button(details, "Refresh", action = ::readKeys)
    }

    private fun editKey(existing: JSONObject?) {
        val field = secureInput("OpenRouter API key")
        val form = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(field) }
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Add API key" else "Replace API key")
            .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnDismissListener { field.text.clear(); editor = null }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = field.text.toString()
                if (key.isBlank()) { field.error = "Enter an API key"; return@setOnClickListener }
                dialog.dismiss()
                work({ AssistantAccounts.saveKey(key, existing?.getString("id") ?: "", existing?.getString("ref") ?: "") }, ::renderKeys)
            }
        }
        editor = dialog
        dialog.show()
    }

    private fun secureInput(label: String): EditText = ui.input(label,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
        setSingleLine()
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    private fun openManagement() = work(AssistantAccounts::magpieManagement) { uri ->
        if (browser(uri)) browserOpened = true
    }

    private fun browser(uri: Uri): Boolean = try {
        startActivityForResult(Intent(Intent.ACTION_VIEW, uri), BROWSER_REQUEST)
        true
    } catch (_: android.content.ActivityNotFoundException) {
        status.text = "A browser is required to open this page."
        false
    }

    @Deprecated("Activity result API unavailable in the backend package")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BROWSER_REQUEST && page == "magpie") finish()
    }

    private companion object { const val BROWSER_REQUEST = 1 }
}
