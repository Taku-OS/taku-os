/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.app.Activity
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/** Settings and Agents call the same provider; this Activity owns no policy copy. */
class IslandSettingsActivity : Activity() {
    private val uri = Uri.parse("content://com.android.systemui.island")
    private lateinit var enabled: Switch
    private lateinit var allowed: EditText
    private lateinit var summary: TextView
    private var refreshing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { read(false) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Activity island"
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
        val scroller = ScrollView(this).apply { addView(content) }
        scroller.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        content.addView(TextView(this).apply { text = "Activity island"; textSize = 24f; isAccessibilityHeading = true })
        enabled = Switch(this).apply { text = "Show activities in the status bar"; minHeight = padding * 3 }
        content.addView(enabled)
        summary = TextView(this)
        content.addView(summary)
        val label = TextView(this).apply {
            text = "Apps allowed to publish custom island content\nEnter package names separated by commas. Android Live Updates work without this list. Platform-signed publishers retain their signature permission."
            setPadding(0, padding, 0, padding / 2)
        }
        content.addView(label)
        allowed = EditText(this).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "org.example.app,org.example.other"
            minLines = 3
            setSingleLine(false)
        }
        label.labelFor = allowed.id
        content.addView(allowed)
        content.addView(Button(this).apply {
            text = "Save allowed apps"
            setOnClickListener {
                val result = call("setAllowed", allowed.text.toString())
                if (result.optBoolean("ok")) { allowed.clearFocus(); read(true) }
                else showError(result)
            }
        })
        content.addView(Button(this).apply { text = "Done"; setOnClickListener { finish() } })
        enabled.setOnCheckedChangeListener { _, checked ->
            if (!refreshing) {
                val result = call("setEnabled", if (checked) "1" else "0")
                if (!result.optBoolean("ok")) showError(result)
                read(false)
            }
        }
        setContentView(scroller)
    }
    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(uri, true, observer)
        read(true)
    }
    override fun onStop() { contentResolver.unregisterContentObserver(observer); super.onStop() }
    private fun read(replaceDraft: Boolean) {
        val result = call("settings", null)
        if (!result.optBoolean("ok")) { summary.text = result.optString("error"); return }
        val state = result.getJSONObject("settings")
        refreshing = true
        enabled.isChecked = state.getBoolean("enabled")
        refreshing = false
        val packages = state.getJSONArray("allowedPackages")
        if (replaceDraft || !allowed.hasFocus()) allowed.setText((0 until packages.length()).joinToString(",") { packages.getString(it) })
        summary.text = "Protocol ${state.getInt("protocol")} · ${packages.length()} allowed apps\n" +
            "Turning this off hides islands only. Notifications and running tasks continue."
    }
    private fun call(method: String, arg: String?): JSONObject = try {
        JSONObject(contentResolver.call(uri, method, arg, null)?.getString("json") ?: "{}")
    } catch (failure: Exception) { IslandRuntime.error(failure.message ?: "systemui_unavailable") }
    private fun showError(result: JSONObject) { Toast.makeText(this, result.optString("error", "operation_failed"), Toast.LENGTH_LONG).show() }
}
