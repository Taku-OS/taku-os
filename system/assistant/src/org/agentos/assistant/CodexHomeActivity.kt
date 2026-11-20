package org.agentos.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle

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
            AgentFrontendLauncher.showUnavailable(this, R.string.taku_home_unavailable)
        finish()
    }
}
