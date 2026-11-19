package org.agentos.assistant

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class BackgroundWorkspacesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AgentFrontendLauncher.openRoute(this, "/workspaces"))
            Toast.makeText(this, R.string.workspace_unavailable_copy, Toast.LENGTH_LONG).show()
        finish()
    }
}
