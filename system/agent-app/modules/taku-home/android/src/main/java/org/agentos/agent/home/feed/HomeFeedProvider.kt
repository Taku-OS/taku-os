package org.agentos.agent.home.feed

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.FileNotFoundException

/**
 * Device-local Agent access to the home feed:
 * `content call --uri content://org.agentos.agent.home-feed --method feed/list --arg '{}'`.
 * Every method calls the same [FeedRepository] operation the home screen uses.
 * Open to device-local callers by product decision (see AGENTS.md); no extra permission.
 */
class HomeFeedProvider : ContentProvider() {
  override fun onCreate() = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    val out = Bundle()
    val identity = Binder.clearCallingIdentity()
    try {
      val ctx = context ?: throw FeedException("UNAVAILABLE", "provider not attached")
      if (method == "feed/generate") {
        val result = FeedGeneration.generate(ctx, arg)
        out.putString("result", JSONObject().put("ok", true).put("result", result).toString())
        return out
      }
      val p = if (arg.isNullOrBlank()) JSONObject() else JSONObject(arg)
      val repo = FeedRepository.get(ctx)
      val result: Any = when (method) {
        "feed/list" -> { awaitFirstPage(repo); repo.snapshot() }
        "feed/get" -> {
          awaitFirstPage(repo)
          val card = repo.card(id(p)) ?: throw FeedException("NOT_FOUND", "no loaded card ${id(p)}")
          JSONObject().put("card", card.json).put("pinned", repo.state.value.pins.any { it.id == card.id })
            .put("removed", repo.isRemoved(card.id))
        }
        "feed/load-more" -> { repo.loadMore(); awaitIdle(repo); repo.snapshot() }
        "feed/remove" -> repo.remove(id(p), ORIGIN)
        "feed/restore" -> repo.restore(id(p), ORIGIN)
        "feed/pin" -> repo.pin(id(p), ORIGIN, if (p.has("index")) p.getInt("index") else null)
        "feed/unpin" -> repo.unpin(id(p), ORIGIN)
        "feed/reorder-pins" -> repo.reorderPins(p.optJSONArray("ids")?.strings() ?: throw FeedException("INVALID_ARGUMENT", "ids is required"), ORIGIN)
        "feed/publish" -> repo.publishCard(p.optJSONObject("card") ?: throw FeedException("INVALID_ARGUMENT", "card is required"), ORIGIN)
        "feed/retract" -> repo.retract(id(p), ORIGIN)
        "feed/action" -> onMain {
          val values = if (!p.has("values")) JSONObject() else p.optJSONObject("values")
            ?: throw FeedException("INVALID_ARGUMENT", "values must be an object")
          FeedActions.dispatch(ctx, id(p), p.optString("actionId").ifBlank { throw FeedException("INVALID_ARGUMENT", "actionId is required") }, values, ORIGIN)
        }
        "feed/ask-taku" -> onMain { FeedActions.askAbout(ctx, id(p), ORIGIN) }
        "feed/reset" -> repo.reset(ORIGIN)
        else -> throw FeedException("UNKNOWN_METHOD", "unknown method $method")
      }
      out.putString("result", JSONObject().put("ok", true).put("result", result).toString())
    } catch (e: FeedException) {
      out.putString("result", JSONObject().put("ok", false).put("code", e.code).put("message", e.message).toString())
    } catch (e: Exception) {
      out.putString("result", JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT").put("message", e.message ?: e.javaClass.simpleName).toString())
    } finally {
      Binder.restoreCallingIdentity(identity)
    }
    return out
  }

  private fun id(p: JSONObject) = p.optString("id").also { if (!FeedCard.isValidId(it)) throw FeedException("INVALID_ARGUMENT", "id is required") }

  private fun awaitFirstPage(repo: FeedRepository) { repo.ensureLoaded(); awaitIdle(repo) }

  private fun awaitIdle(repo: FeedRepository) {
    val deadline = System.currentTimeMillis() + 5_000
    while (repo.state.value.loading && System.currentTimeMillis() < deadline) Thread.sleep(20)
  }

  /** Activity starts and shell callbacks must run on the main thread. */
  private fun onMain(block: () -> JSONObject): JSONObject =
    org.agentos.agent.home.MainThread.call(onTimeout = { FeedException("TIMEOUT", "main thread busy") }, block = block)

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    val identity = Binder.clearCallingIdentity()
    try {
      if (mode != "r") throw FeedException("INVALID_ARGUMENT", "Feed events are read-only")
      val request = FeedEventRequest.parse(uri.toString())
      val ctx = context ?: throw FeedException("UNAVAILABLE", "provider not attached")
      return FeedEventStreams.open(FeedRepository.get(ctx), request)
    } catch (e: FeedException) {
      throw FileNotFoundException("${e.code}: ${e.message}")
    } catch (_: Exception) {
      throw FileNotFoundException("STREAM_OPEN_FAILED: Could not open Feed events")
    } finally {
      Binder.restoreCallingIdentity(identity)
    }
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
    throw UnsupportedOperationException("Use content call; see feed/list")
  override fun getType(uri: Uri) = if (uri.authority == FeedEventRequest.AUTHORITY && uri.path == "/events") "application/x-ndjson" else "application/json"
  override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Use feed/publish")
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Use feed/remove")

  private companion object { val ORIGIN = FeedOrigin.AGENT }
}
