package org.agentos.agent.home.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.agentos.agent.home.feed.FeedActions
import org.agentos.agent.home.feed.FeedException
import org.agentos.agent.home.feed.FeedOrigin
import org.agentos.agent.home.feed.FeedRepository
import org.json.JSONObject
import java.io.File

/** Only this task's synthetic isolated-AVD data; no production injection/test interface. */
class FeedStoredActionProbe : Instrumentation() {
  override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

  override fun onStart() {
    val result = JSONObject().put("synthetic", true).put("passed", false)
    try {
      check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
      val ctx = targetContext
      val legacy = JSONObject(File(ctx.filesDir, "home-feed/state.json").readText())
      check(legacy.getString("syntheticFixture") == "Taku_Character_Legacy_Feed_20261121") { "Synthetic fixture required" }
      val guard = GuardedContext(ctx)
      val repo = FeedRepository.get(ctx)
      var gui = 0
      var agent = 0
      val before = head(ctx)
      val cards = legacy.getJSONArray("published")
      for (index in 0 until cards.length()) {
        val card = cards.getJSONObject(index)
        val actions = card.getJSONObject("actions")
        for (actionId in actions.keys()) {
          if (actions.getJSONObject(actionId).getJSONObject("intent").getString("type") != "panel") continue
          try {
            runBlocking { FeedActions.dispatch(guard, card.getString("id"), actionId, JSONObject(), FeedOrigin.GUI) }
            error("GUI accepted an unavailable action")
          } catch (error: FeedException) {
            check(error.code == "ACTION_UNAVAILABLE" && error.message == "This action is no longer available.")
            gui++
          }
          val args = JSONObject().put("id", card.getString("id")).put("actionId", actionId)
          val reply = ctx.contentResolver.call(Uri.parse("content://org.agentos.agent.home-feed"), "feed/action", args.toString(), null)
          val envelope = JSONObject(reply!!.getString("result")!!)
          check(!envelope.getBoolean("ok") && envelope.getString("code") == "ACTION_UNAVAILABLE"
            && envelope.getString("message") == "This action is no longer available.")
          agent++
        }
        try {
          repo.publishGeneratedCard(card, "synthetic.never-generated.$index", FeedOrigin.AGENT)
          error("Generation accepted a new panel input")
        } catch (error: FeedException) { check(error.code == "INVALID_CARD") }
      }
      repo.card("synthetic.saved.two")!!.actions.getValue("valid").requireAvailable()
      check(gui == 3 && agent == 3 && guard.starts == 0 && head(ctx) == before && before == 50L)
      result.put("passed", true).put("guiRejections", gui).put("agentRejections", agent)
        .put("startActivityCalls", guard.starts).put("historyBefore", before).put("historyAfter", head(ctx))
        .put("generatedPanelInputsRejected", 2).put("validStoredActionStillAvailable", true)
    } catch (failure: Throwable) {
      result.put("error", failure.javaClass.simpleName).put("message", failure.message?.take(160))
    }
    val bundle = Bundle().apply { putString("result", result.toString()) }
    finish(if (result.optBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED, bundle)
  }

  private fun head(context: Context): Long = SQLiteDatabase.openDatabase(context.getDatabasePath("home-feed.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
    db.rawQuery("SELECT COALESCE(MAX(sequence),0) FROM feed_events", null).use { it.moveToFirst(); it.getLong(0) }
  }

  private class GuardedContext(base: Context) : ContextWrapper(base) {
    var starts = 0
    override fun startActivity(intent: Intent) { starts++; error("Unavailable action attempted an Activity") }
  }
}
