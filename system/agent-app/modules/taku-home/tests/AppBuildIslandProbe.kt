package org.agentos.agent.home.tests

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.agentos.agent.home.assistant.*
import org.json.JSONObject
import java.io.File

/** Explicit isolated UI fixture: no model call, no audio, no submitted or stored conversation. */
class AppBuildIslandProbe : Instrumentation() {
  override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
  private val uri = Uri.parse("content://org.agentos.agent.home-assistant")
  private fun call(method: String, arg: JSONObject? = null): JSONObject = JSONObject(
    targetContext.contentResolver.call(uri, method, arg?.toString(), null)!!.getString("result")!!)
  private fun presentation(): JSONObject {
    val json = targetContext.contentResolver.call(Uri.parse("content://com.android.systemui.island"), "state", null, null)!!.getString("json")!!
    return JSONObject(json).getJSONObject("presentation")
  }
  private fun awaitCompact(expected: Boolean) {
    val end = SystemClock.uptimeMillis() + 7000
    do { SystemClock.sleep(200); if (presentation().optBoolean("compactVisible") == expected) return } while (SystemClock.uptimeMillis() < end)
    error("compactVisible did not become $expected: ${presentation()}")
  }
  override fun onStart() {
    val report = JSONObject()
    var original: AssistantState? = null
    var flow: MutableStateFlow<AssistantState>? = null
    try {
      startActivitySync(Intent().setClassName(targetContext, "org.agentos.agent.home.HomeActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
      SystemClock.sleep(700)
      val session = AssistantSession.get(targetContext)
      @Suppress("UNCHECKED_CAST")
      val state = AssistantSession::class.java.getDeclaredField("flow").apply { isAccessible = true }.get(session) as MutableStateFlow<AssistantState>
      flow = state
      original = state.value
      check(!original.working && original.entry == EntryMode.None && original.draft.isEmpty()) { "Requires idle empty Home session" }
      val fixture = AssistantState(threadId = "building-ui-fixture", activityId = "building-ui-fixture",
        island = IslandMode.Collapsed, task = "running", turn =
          TurnView("fixture-turn", null, true, false, "Isolated UI acceptance", null, emptyList(), emptyList()))
      runOnMainSync { state.value = fixture }
      SystemClock.sleep(700)
      awaitCompact(false)
      val args = JSONObject().put("threadId", fixture.threadId).put("turnId", "fixture-turn").put("active", true)
      val stale = call("assistant/app-build", JSONObject(args.toString()).put("turnId", "stale"))
      check(stale.optString("code") == "STALE_TURN")
      val started = call("assistant/app-build", args)
      check(started.getBoolean("ok") && started.getJSONObject("result").getBoolean("buildingApp"))
      awaitCompact(true)
      val folder = File(targetContext.filesDir, "building-island-acceptance").apply { mkdirs() }
      // Observe more than a complete designer cycle, not a single convenient still.
      repeat(10) { index ->
        uiAutomation.takeScreenshot().let { bitmap ->
          File(folder, "frame-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
          bitmap.recycle()
        }
        SystemClock.sleep(125)
      }
      report.put("buildingReadWriteAndGui", true).put("animationFrames", 10)
      check(call("assistant/app-build", args.put("active", false)).getJSONObject("result").getBoolean("buildingApp").not())
      awaitCompact(false)
      val states = mapOf(
        "listening" to fixture.copy(entry = EntryMode.Voice),
        "error" to fixture.copy(appBuildTurnId = "fixture-turn", error = "VOICE_UNAVAILABLE"),
        "approval" to fixture.copy(appBuildTurnId = "fixture-turn", task = "needs_user"),
        "reply" to fixture.copy(appBuildTurnId = "fixture-turn", task = "completed", turn = fixture.turn!!.copy(active = false)),
        "ordinaryWork" to fixture)
      for ((name, next) in states) {
        runOnMainSync { state.value = next }
        SystemClock.sleep(500)
        check(!call("assistant/get").getJSONObject("result").getBoolean("buildingApp"))
        awaitCompact(false)
        report.put(name + "Hidden", true)
      }
      report.put("passed", true)
    } catch (failure: Throwable) {
      report.put("passed", false).put("error", failure.toString())
    } finally {
      if (original != null && flow != null) runOnMainSync { flow!!.value = original!! }
      SystemClock.sleep(800)
    }
    finish(if (report.optBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
  }
}
