package org.agentos.assistant

import android.app.Activity
import android.os.Bundle

/** Released keyboards package-scope the settings action to this app. */
class SystemAiActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AgentFrontendLauncher.openRoute(this, "/system-ai"))
            AgentFrontendLauncher.showUnavailable(this, R.string.taku_settings_unavailable)
        finish()
    }
}
