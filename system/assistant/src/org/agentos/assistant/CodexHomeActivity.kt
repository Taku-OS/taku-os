package org.agentos.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Old pinned desktop links retain their component identity across package updates. */
class CodexHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forward()
    }

    private fun forward() {
        if (!AgentFrontendLauncher.openHome(this))
            Toast.makeText(this, "Taku Home is unavailable.", Toast.LENGTH_LONG).show()
        finish()
    }
}
