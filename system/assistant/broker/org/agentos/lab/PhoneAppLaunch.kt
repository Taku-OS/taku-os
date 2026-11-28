package org.agentos.lab

import java.io.IOException
import org.json.JSONObject

internal object PhoneAppLaunch {
    @JvmStatic
    @Throws(Exception::class)
    fun open(screen: PhoneExecutionScreens.Screen, packageName: String, component: String?): JSONObject =
        open(screen, packageName, component, null)

    /**
     * [resolved]: the caller's own requireLaunchableComponent result for this package and component,
     * so one launch (and its relaunch after a main-screen takeover) probes the catalog once rather
     * than two or three times, each an app_process helper of up to 8 s (code review).
     */
    @JvmStatic
    @Throws(Exception::class)
    fun open(screen: PhoneExecutionScreens.Screen, packageName: String, component: String?, resolved: JSONObject?): JSONObject {
        PhoneGestures.prepare(screen)
        PhoneGestures.requireIdle(screen.displayId)
        val app = resolved ?: if (component == null) PhoneAppCatalog.requireLaunchable(packageName)
            else PhoneAppCatalog.requireLaunchableComponent(packageName, component)
        if (app.getInt("userId") != screen.userId) throw IOException("ANDROID_USER_CHANGED")
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        WorkspacePolicy.requireAppAllowed(packageName)
        if (PhoneAppCatalog.currentUser() != screen.userId) throw IOException("ANDROID_USER_CHANGED")
        val here = PhoneTasks.DEVICE.list().any { it.displayId() == screen.displayId && it.packageName() == packageName }
        val command = arrayListOf("/system/bin/am", "start", "--user", screen.userId.toString(),
            "--display", screen.displayId.toString())
        if (!here) command.add("--activity-multiple-task")
        command.add("-n")
        command.add(app.getString("launchComponent"))
        val launched = TaskBroker.command(*command.toTypedArray())
        if (launched.contains("Error:") || launched.contains("Error type ")) throw IOException("APP_LAUNCH_FAILED")
        return PhoneNativeTools.text(launched)
    }
}
