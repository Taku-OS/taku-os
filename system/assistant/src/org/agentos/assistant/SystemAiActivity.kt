package org.agentos.assistant

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Released keyboards package-scope the settings action to this app. */
class SystemAiActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AgentFrontendLauncher.openRoute(this, "/system-ai"))
            Toast.makeText(this, "Taku AI settings are unavailable.", Toast.LENGTH_LONG).show()
        finish()
    }
}
