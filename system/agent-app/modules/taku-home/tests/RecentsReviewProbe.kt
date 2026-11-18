package org.agentos.agent.home.tests

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import org.json.JSONObject
import java.io.File

/** Creates/removes only one temporary fixture task; never clears the user's recent history. */
class RecentsReviewProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun call(method: String, arg: String? = null): JSONObject {
        // Exercise the documented Agent CLI. The instrumented app does not declare Recents
        // in its Android package-visibility queries (not a provider access-permission gate).
        check(method in setOf("list", "show", "home", "open", "remove"))
        val task = arg?.toInt()
        val output = shell("content call --uri content://com.android.launcher3.taku.recents --method $method" +
            if (task != null) " --arg $task" else "")
        return JSONObject(output.substringAfter("result=").substringBeforeLast("}]"))
    }
    private fun ids(): Set<Int> {
        val result = call("list"); check(result.getBoolean("ok"))
        val tasks = result.getJSONArray("tasks")
        return (0 until tasks.length()).map { tasks.getJSONObject(it).getInt("taskId") }.toSet()
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun awaitFocus(component: String) {
        val until = SystemClock.uptimeMillis() + 6000
        do {
            if (shell("dumpsys window displays").lineSequence().any { "mCurrentFocus=Window{" in it && component in it }) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < until)
        error("No focused window for $component")
    }
    private fun shot(name: String, landscape: Boolean) {
        val until = SystemClock.uptimeMillis() + 6000
        do {
            val bitmap = uiAutomation.takeScreenshot()
            try {
                if ((bitmap.width > bitmap.height) == landscape) {
                    val folder = File(targetContext.filesDir, "recents-review").apply { mkdirs() }
                    File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    return
                }
            } finally { bitmap.recycle() }
            SystemClock.sleep(150)
        } while (SystemClock.uptimeMillis() < until)
        error("Display did not reach expected orientation")
    }
    override fun onStart() {
        val report = JSONObject().put("passed", false)
        val rotation = Settings.System.getInt(targetContext.contentResolver, "user_rotation", 0)
        val auto = Settings.System.getInt(targetContext.contentResolver, "accelerometer_rotation", 0)
        var created: Int? = null
        try {
            val before = ids()
            check(uiAutomation.setRotation(0))
            runOnMainSync { targetContext.startActivity(Intent().setClassName("org.agentos.island.fixture", "org.agentos.island.fixture.Fixture")
                .putExtra("editorOnly", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) }
            awaitFocus("org.agentos.island.fixture/")
            created = (ids() - before).single()
            check(call("show").getBoolean("ok")); awaitFocus("RecentsActivity"); SystemClock.sleep(700)
            shot("portrait", false)
            val bitmap = uiAutomation.takeScreenshot(); val x = bitmap.width / 2f; val y = bitmap.height / 2f; bitmap.recycle()
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(uiAutomation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(80)
            }
            awaitFocus("org.agentos.island.fixture/"); report.put("guiCardLaunch", true)
            check(call("show").getBoolean("ok")); awaitFocus("RecentsActivity"); SystemClock.sleep(500)
            check(!call("show").getBoolean("queued")); report.put("showIsIdempotent", true)
            check(call("open", created.toString()).getBoolean("ok")); awaitFocus("org.agentos.island.fixture/")
            report.put("agentOpensSameTask", true)
            check(uiAutomation.setRotation(1)); shot("landscape-app", true)
            check(call("show").getBoolean("ok")); awaitFocus("RecentsActivity"); SystemClock.sleep(700); shot("landscape-overview", true)
            report.put("landscapeOverview", true)
            check(!call("open", "-999").getBoolean("ok")); report.put("invalidTaskRejected", true)
            check(call("remove", created.toString()).getBoolean("ok")); SystemClock.sleep(500)
            check(created !in ids() && ids().containsAll(before)); created = null
            report.put("removesOnlyFixtureTask", true).put("passed", true)
        } catch (error: Throwable) { report.put("error", error.toString()) }
        finally {
            created?.let { runCatching { call("remove", it.toString()) } }
            uiAutomation.setRotation(-2)
            shell("settings put system user_rotation $rotation")
            shell("settings put system accelerometer_rotation $auto")
            runCatching { call("home") }
            finish(if (report.optBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
        }
    }
}
