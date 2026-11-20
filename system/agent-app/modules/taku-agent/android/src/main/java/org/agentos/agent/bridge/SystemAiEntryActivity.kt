package org.agentos.agent.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * The Settings tile and `org.agentos.action.AI_SETTINGS`. A caller may name one page of the
 * settings in the `section` extra: `connection`, `account`, `usage`, `jev` or `routing`.
 */
class SystemAiEntryActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val section = intent?.getStringExtra("section")?.takeIf { it in SECTIONS }
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("taku-agent:///system-ai" + (section?.let { "?section=$it" } ?: "")))
        .setClassName(packageName, "$packageName.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    } catch (_: android.content.ActivityNotFoundException) {
      Toast.makeText(this, R.string.taku_ai_unavailable, Toast.LENGTH_LONG).show()
    }
    finish()
  }

  private companion object {
    val SECTIONS = setOf("connection", "account", "usage", "jev", "routing")
  }
}
