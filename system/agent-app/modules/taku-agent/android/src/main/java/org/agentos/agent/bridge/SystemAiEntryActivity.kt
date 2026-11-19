package org.agentos.agent.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

class SystemAiEntryActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("taku-agent:///system-ai"))
        .setClassName(packageName, "$packageName.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    } catch (_: android.content.ActivityNotFoundException) {
      Toast.makeText(this, R.string.taku_ai_unavailable, Toast.LENGTH_LONG).show()
    }
    finish()
  }
}
