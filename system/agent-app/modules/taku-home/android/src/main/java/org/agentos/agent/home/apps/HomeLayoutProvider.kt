package org.agentos.agent.home.apps

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import org.agentos.agent.home.feed.FeedException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent access to the home screen pages (widgets, apps and folders on every page):
 * `content call --uri content://org.agentos.agent.home-layout --method apps/list --arg '{}'`.
 * Same [AppCatalog], [HomeLayoutRepository] and the standard widget host the GUI uses.
 */
class HomeLayoutProvider : ContentProvider() {
  override fun onCreate() = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    val out = Bundle()
    val callerUid = Binder.getCallingUid()
    val appCtx = context
    if (appCtx != null && !org.agentos.agent.home.TakuCallerAccess.isAllowed(appCtx, callerUid)) {
      out.putString("result", JSONObject().put("ok", false).put("code", "PERMISSION_DENIED").put("message", "Home layout access denied by App Permission Center").toString())
      return out
    }
    val identity = Binder.clearCallingIdentity()
    try {
      val ctx = context ?: throw FeedException("UNAVAILABLE", "provider not attached")
      val p = if (arg.isNullOrBlank()) JSONObject() else JSONObject(arg)
      if (method == "gestures/get") {
        val result = onMain { org.agentos.agent.home.ui.HomeGestureSession.snapshot() }
        out.putString("result", JSONObject().put("ok", true).put("result", result).toString())
        return out
      }
      val catalog = AppCatalog.get(ctx)
      awaitApps(catalog)
      val layout = HomeLayoutRepository.get(ctx)
      // The pages follow the installed apps even while the home screen is not running.
      layout.sync(catalog.apps.value)
      val result: Any = when (method) {
        "apps/list" -> {
          val placed = layout.layout.value
          JSONObject().put("apps", JSONArray(catalog.apps.value.map { a ->
            val at = placed.locate(a.key)
            JSONObject().put("key", a.key).put("package", a.packageName).put("label", a.label).put("system", a.system)
              .put("glyphIcon", a.monochrome).put("iconAsset", a.iconAsset?.let { "launcher/$it.svg" } ?: JSONObject.NULL)
              .put("page", at?.page ?: JSONObject.NULL).put("index", at?.index ?: JSONObject.NULL)
              .put("folderId", at?.folderId ?: JSONObject.NULL)
              .put("hidden", a.key in placed.hidden)
          }))
        }
        "apps/launch" -> onMain { if (!catalog.launch(key(p, catalog))) throw FeedException("LAUNCH_FAILED", "could not start ${p.optString("key")}"); JSONObject().put("effect", "launched") }
        "apps/info" -> onMain { catalog.openAppInfo(key(p, catalog)); JSONObject().put("effect", "opened-app-info") }
        "apps/share" -> onMain { catalog.share(key(p, catalog)) }
        "apps/hide" -> layout.setHidden(key(p, catalog), true, ORIGIN)
        "apps/show" -> layout.setHidden(key(p, catalog), false, ORIGIN)
        "layout/editing/get" -> HomeEditSession.read()
        "layout/editing/set" -> onMain {
          if (!p.has("editing") || p.get("editing") !is Boolean) throw FeedException("INVALID_ARGUMENT", "editing must be a boolean")
          HomeEditSession.set(p.getBoolean("editing"))
        }
        "apps/uninstall" -> onMain {
          val app = catalog.app(key(p, catalog))!!
          if (app.system) throw FeedException("SYSTEM_APP", "${app.packageName} is a system app and cannot be uninstalled")
          catalog.uninstall(app.packageName); JSONObject().put("effect", "uninstall-confirmation-shown")
        }
        "layout/get" -> layout.toJson()
        "layout/move" -> {
          val folderId = p.optString("folderId").ifBlank { null }
          val key = if (p.has("key")) key(p, catalog) else null
          if (!p.has("page")) throw FeedException("INVALID_ARGUMENT", "page is required (layout/get; the page count = a new page)")
          layout.move(key, folderId, p.getInt("page"), if (p.has("index")) p.getInt("index") else null, p.optBoolean("into"), ORIGIN)
        }
        "layout/set-mode" -> layout.setMode(p.optString("mode"), ORIGIN)
        "layout/ungroup" -> layout.ungroup(p.optString("folderId"), ORIGIN)
        "layout/rename-folder" -> layout.renameFolder(p.optString("id"), p.optString("name"), ORIGIN)
        "layout/reset" -> layout.reset(ORIGIN)
        "widgets/providers" -> onMain { org.agentos.agent.home.widgets.HomeWidgetHost.get(ctx).providersJson() }
        "widgets/content" -> onMain { org.agentos.agent.home.widgets.HomeWidgetHost.get(ctx).content(p.getInt("appWidgetId")) }
        "widgets/action" -> onMain { org.agentos.agent.home.widgets.HomeWidgetHost.get(ctx).action(p.getInt("appWidgetId"), p.getString("path")) }
        "widgets/list" -> onMain { org.agentos.agent.home.widgets.HomeWidgetHost.get(ctx).instancesJson() }
        "widgets/add" -> onMain { org.agentos.agent.home.widgets.HomeWidgetHost.get(ctx).add(p.getString("provider"), p.optInt("page", 0), if (p.has("index")) p.getInt("index") else null, if (p.has("spanX")) p.getInt("spanX") else null, if (p.has("spanY")) p.getInt("spanY") else null) }
        "widgets/move" -> layout.move(null, null, p.getInt("page"), if (p.has("index")) p.getInt("index") else null, false, ORIGIN, p.getInt("appWidgetId"))
        "widgets/resize" -> onMain { org.agentos.agent.home.widgets.HomeWidgetHost.get(ctx).resize(p.getInt("appWidgetId"), p.getInt("spanX"), p.getInt("spanY")) }
        "widgets/remove" -> onMain { org.agentos.agent.home.widgets.HomeWidgetHost.get(ctx).remove(p.getInt("appWidgetId")) }
        "widgets/configure" -> onMain { org.agentos.agent.home.widgets.HomeWidgetHost.get(ctx).configure(p.getInt("appWidgetId")) }
        "widgets/cancel" -> onMain { org.agentos.agent.home.widgets.HomeWidgetHost.get(ctx).cancel(p.getInt("appWidgetId")) }
        "widgets/resume" -> onMain { org.agentos.agent.home.widgets.HomeWidgetHost.get(ctx).resume(p.getInt("appWidgetId")) }
        "widgets/get" -> onMain { org.agentos.agent.home.widgets.HomeWidgetHost.get(ctx).instancesJson() }
        "widgets/media" -> clockCommand(ctx, p.optString("command"))
        "widgets/alarms" -> clockCommand(ctx, "alarms")
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

  private fun clockCommand(ctx: android.content.Context, command: String): JSONObject {
    val raw = ctx.contentResolver.call(Uri.parse("content://org.taku.widgets.data"), "command", JSONObject().put("command", command).toString(), null)?.getString("result")
      ?: throw FeedException("PROVIDER_UNAVAILABLE", "Clock application is unavailable")
    val result = JSONObject(raw)
    if (!result.optBoolean("ok")) throw FeedException("COMMAND_FAILED", result.optString("error"))
    return result.getJSONObject("result")
  }

  private fun key(p: JSONObject, catalog: AppCatalog): String {
    val key = p.optString("key")
    if (key.isBlank()) throw FeedException("INVALID_ARGUMENT", "key is required (from apps/list)")
    if (catalog.app(key) == null) throw FeedException("APP_NOT_FOUND", "$key is not an installed launchable app")
    return key
  }

  private fun awaitApps(catalog: AppCatalog) {
    val deadline = System.currentTimeMillis() + 3_000
    while (catalog.apps.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
  }

  private fun onMain(block: () -> JSONObject): JSONObject =
    org.agentos.agent.home.MainThread.call(onTimeout = { FeedException("TIMEOUT", "main thread busy") }, block = block)

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
    throw UnsupportedOperationException("Use content call; see apps/list")
  override fun getType(uri: Uri) = "application/json"
  override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Use layout/move")
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Use apps/hide or apps/uninstall")

  private companion object { const val ORIGIN = "agent" }
}
