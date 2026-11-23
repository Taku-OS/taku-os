package org.agentos.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.widget.Toast

internal object AgentFrontendLauncher {
    const val RETURN_WORKSPACE_HOME = "org.agentos.agent.home.extra.RETURN_FROM_WORKSPACE"
    private const val PACKAGE = "org.agentos.agent"
    private const val MAIN_ACTIVITY = "$PACKAGE.MainActivity"
    private const val HOME_ACTIVITY = "$PACKAGE.home.HomeActivity"
    private const val FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    private val routes = setOf("/", "/task-detail", "/system-ai", "/workspaces", "/plugin-market", "/assistant-suggestion", "/assistant-management")

    @JvmStatic
    fun openHome(context: Context): Boolean = start(context, HOME_ACTIVITY, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))

    @JvmStatic
    fun returnToWorkspaceHome(context: Context): Boolean = start(context, HOME_ACTIVITY,
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).putExtra(RETURN_WORKSPACE_HOME, true))

    @JvmStatic
    fun openThread(context: Context, threadId: String): Boolean = AgentGatewayProtocol.isUuid(threadId) &&
        start(context, MAIN_ACTIVITY, Intent(Intent.ACTION_MAIN).putExtra("threadId", threadId))

    @JvmStatic
    fun openRoute(context: Context, path: String): Boolean {
        if (!path.startsWith('/') || path.startsWith("//")) return false
        val uri = Uri.parse("taku-agent://$path")
        return uri.encodedPath in routes && start(context, MAIN_ACTIVITY, Intent(Intent.ACTION_VIEW, uri))
    }

    /**
     * Tells the user why a link did not open. Taku runs for the phone's owner once the phone has
     * been unlocked; anyone else gets that reason instead of the caller's [fallback].
     */
    @JvmStatic
    fun showUnavailable(context: Context, fallback: Int) {
        val reason = when (ComposerState.userError(context, Process.myUid())) {
            "USER_UNAVAILABLE" -> R.string.taku_owner_only
            "USER_LOCKED" -> R.string.taku_unlock_first
            else -> fallback
        }
        Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
    }

    private fun start(context: Context, activity: String, intent: Intent): Boolean = try {
        if (ComposerState.userError(context, Process.myUid()) != null ||
            context.packageManager.checkSignatures(context.packageName, PACKAGE) != PackageManager.SIGNATURE_MATCH) false
        else {
            context.startActivity(intent.setClassName(PACKAGE, activity).addFlags(FLAGS))
            true
        }
    } catch (_: RuntimeException) {
        false
    }
}
