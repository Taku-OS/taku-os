package org.agentos.assistant

import android.app.Activity
import android.os.Bundle

class BackgroundWorkspacesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AgentFrontendLauncher.openRoute(this, "/workspaces"))
            AgentFrontendLauncher.showUnavailable(this, R.string.workspace_unavailable_copy)
        finish()
    }
}
