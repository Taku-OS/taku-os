package org.agentos.agent.home.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.agentos.agent.home.apps.*
import org.agentos.agent.home.feed.FeedException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** Framework bindings and HomeLayoutRepository are the only authoritative widget state. */
class HomeWidgetHost private constructor(val context: Context) {
  val manager = AppWidgetManager.getInstance(context)
  val host = object : AppWidgetHost(context, 4102) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo): android.appwidget.AppWidgetHostView =
      object : android.appwidget.AppWidgetHostView(context) {
        override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
          super.setAppWidget(appWidgetId, info)
          // Android calls this again after a provider update; keep the grid's own 16dp gutter.
          setPadding(0, 0, 0, 0)
        }
      }
  }
  private val pending = context.getSharedPreferences("widget-pending", Context.MODE_PRIVATE)
  private val layout get() = HomeLayoutRepository.get(context)
  private var listeners = 0
  private val views = HashMap<Int, java.lang.ref.WeakReference<android.appwidget.AppWidgetHostView>>()
  fun rememberView(id: Int, view: android.appwidget.AppWidgetHostView) { views[id] = java.lang.ref.WeakReference(view) }
  private fun contentView(id: Int): android.appwidget.AppWidgetHostView {
    layout.widgetJson(id)
    views[id]?.get()?.let { return it }
    val provider = info(id) ?: throw FeedException("PROVIDER_UNAVAILABLE", "provider unavailable")
    return host.createView(context, id, provider).also { view ->
      val slot = layout.layout.value.pages.flatten().filterIsInstance<Slot.Widget>().first { it.appWidgetId == id }
      val d = context.resources.displayMetrics.density
      val w = ((cellDp() * slot.spanX + 16 * (slot.spanX - 1)) * d).toInt()
      val h = ((cellDp() * slot.spanY + 16 * (slot.spanY - 1)) * d).toInt()
      view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
      view.layout(0, 0, w, h)
      rememberView(id, view)
    }
  }
  fun content(id: Int): JSONObject {
    fun node(v: android.view.View, path: String, parentVisible: Boolean = true): JSONObject {
      val visible = parentVisible && v.visibility == android.view.View.VISIBLE
      val o = JSONObject().put("path", path).put("class", v.javaClass.name).put("viewId", v.id)
        .put("visible", visible).put("enabled", v.isEnabled).put("clickable", v.isClickable)
        .put("description", v.contentDescription?.toString() ?: JSONObject.NULL)
      if (v is android.widget.TextView) o.put("text", v.text?.toString() ?: "")
      if (v is android.widget.ImageView && v.contentDescription.isNullOrBlank()) o.put("unavailable", "IMAGE_HAS_NO_SEMANTIC_DESCRIPTION")
      if (v is android.view.ViewGroup) o.put("children", JSONArray((0 until v.childCount).map { i -> node(v.getChildAt(i), if (path.isEmpty()) "$i" else "$path/$i", visible) }))
      return o
    }
    return JSONObject().put("appWidgetId", id).put("view", node(contentView(id), ""))
  }
  fun action(id: Int, path: String): JSONObject {
    var view: android.view.View = contentView(id)
    if (path.isNotEmpty()) for (part in path.split('/')) {
      val parent = view as? android.view.ViewGroup ?: throw FeedException("NOT_FOUND", "view path no longer exists; read widgets/content again")
      if (parent.visibility != android.view.View.VISIBLE || !parent.isEnabled) throw FeedException("ACTION_UNAVAILABLE", "ancestor is not visible or enabled")
      val index = part.toIntOrNull() ?: throw FeedException("INVALID_ARGUMENT", "path must contain child indices")
      view = if (index in 0 until parent.childCount) parent.getChildAt(index) else throw FeedException("NOT_FOUND", "view path no longer exists")
    }
    if (!view.isEnabled || view.visibility != android.view.View.VISIBLE || !view.isClickable) throw FeedException("ACTION_UNAVAILABLE", "view is not an enabled visible click target")
    if (!view.performClick()) throw FeedException("ACTION_UNAVAILABLE", "provider did not handle this click")
    return JSONObject().put("effect", "clicked").put("appWidgetId", id).put("path", path)
  }

  fun start() {
    if (listeners++ == 0) {
      reconcilePending()
      // Recover IDs allocated before a process died, or whose framework deletion was interrupted.
      val kept = layout.layout.value.pages.flatten().filterIsInstance<Slot.Widget>().map { it.appWidgetId }.toSet() + pending.all.keys.mapNotNull { it.toIntOrNull() }
      host.appWidgetIds.filterNot { it in kept }.forEach { host.deleteAppWidgetId(it) }
      host.startListening()
    }
  }
  fun stop() { if (listeners > 0 && --listeners == 0) host.stopListening() }
  fun info(id: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(id)
  fun providers(): List<AppWidgetProviderInfo> = manager.installedProviders.sortedBy { it.loadLabel(context.packageManager) }
  fun provider(name: String): AppWidgetProviderInfo = providers().firstOrNull { it.provider.flattenToString() == name || it.provider.flattenToShortString() == name }
    ?: throw FeedException("PROVIDER_NOT_FOUND", "unknown provider; see widgets/providers")

  fun providersJson(): JSONObject = JSONObject().put("providers", JSONArray(providers().map { p ->
    val span = defaultSpan(p)
    JSONObject().put("provider", p.provider.flattenToString()).put("label", p.loadLabel(context.packageManager))
      .put("spanX", span.first).put("spanY", span.second).put("resizeMode", p.resizeMode)
      .put("minWidthDp", pxDp(p.minWidth)).put("minHeightDp", pxDp(p.minHeight))
      .put("minResizeWidthDp", pxDp(p.minResizeWidth)).put("minResizeHeightDp", pxDp(p.minResizeHeight))
      .put("configure", p.configure?.flattenToString() ?: JSONObject.NULL)
  }))

  fun instancesJson(): JSONObject {
    reconcilePending()
    return JSONObject().put("widgets", JSONArray(layout.layout.value.pages.flatten().filterIsInstance<Slot.Widget>().map {
    layout.widgetJson(it.appWidgetId).put("available", info(it.appWidgetId) != null)
    })).put("pending", JSONArray(pending.all.filterKeys { !isPlaced(it.toInt()) }.values.map { JSONObject(it as String) }))
  }

  fun isPlaced(id: Int) = layout.layout.value.pages.flatten().filterIsInstance<Slot.Widget>().any { it.appWidgetId == id }
  private fun clearPending(id: Int) {
    // The persisted layout is the commit point. A failed cleanup is safe to retry; it must
    // never turn a successful placement into a failure that deletes its framework binding.
    if (!pending.edit().remove(id.toString()).commit()) android.util.Log.w("TakuWidgets", "pending cleanup deferred for $id")
  }
  private fun reconcilePending() { pending.all.keys.mapNotNull { it.toIntOrNull() }.filter(::isPlaced).forEach(::clearPending) }

  private fun pxDp(px: Int) = ceil(px / context.resources.displayMetrics.density).toInt()
  private fun cellDp() = (context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density - 80f) / 4f
  fun defaultSpan(p: AppWidgetProviderInfo): Pair<Int, Int> {
    fun span(px: Int, cells: Int, max: Int) = (if (cells > 0) cells else ceil((pxDp(px) + 16f) / (cellDp() + 16f)).toInt()).coerceIn(1, max)
    return span(p.minWidth, p.targetCellWidth, 4) to span(p.minHeight, p.targetCellHeight, 6)
  }
  fun validateSize(p: AppWidgetProviderInfo, x: Int, y: Int, old: Slot.Widget? = null) {
    HomeGrid.validate(Slot.Widget(1, "", x, y))
    if (old != null) {
      if (x != old.spanX && p.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL == 0) throw FeedException("NOT_RESIZABLE", "provider does not support horizontal resize")
      if (y != old.spanY && p.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL == 0) throw FeedException("NOT_RESIZABLE", "provider does not support vertical resize")
    }
    val width = x * cellDp() + (x - 1) * 16
    // Grid cell height depends on available display height; use conservative square cells for minimums.
    val height = y * cellDp() + (y - 1) * 16
    val minW = if (p.resizeMode and 1 != 0 && p.minResizeWidth > 0) p.minResizeWidth else p.minWidth
    val minH = if (p.resizeMode and 2 != 0 && p.minResizeHeight > 0) p.minResizeHeight else p.minHeight
    if (width + 1 < pxDp(minW) || height + 1 < pxDp(minH)) throw FeedException("SIZE_TOO_SMALL", "provider minimum is ${pxDp(minW)} x ${pxDp(minH)} dp")
    if ((p.maxResizeWidth > 0 && width > pxDp(p.maxResizeWidth) + 1) || (p.maxResizeHeight > 0 && height > pxDp(p.maxResizeHeight) + 1)) throw FeedException("SIZE_TOO_LARGE", "provider maximum exceeded")
  }

  fun add(name: String, page: Int, index: Int?, x: Int?, y: Int?): JSONObject {
    val p = provider(name); val span = defaultSpan(p)
    val sx = x ?: span.first; val sy = y ?: span.second
    validateSize(p, sx, sy)
    val pages = layout.layout.value.pages
    if (page !in 0..pages.size || (index != null && index !in 0..(pages.getOrNull(page)?.size ?: 0))) throw FeedException("INVALID_ARGUMENT", "invalid destination page/index")
    val id = host.allocateAppWidgetId()
    val request = JSONObject().put("appWidgetId", id).put("provider", p.provider.flattenToString()).put("page", page)
      .put("index", index ?: JSONObject.NULL).put("spanX", sx).put("spanY", sy).put("stage", "bind")
    if (!pending.edit().putString(id.toString(), request.toString()).commit()) { host.deleteAppWidgetId(id); throw FeedException("STATE_WRITE_FAILED", "cannot save pending widget") }
    return try {
      if (manager.bindAppWidgetIdIfAllowed(id, p.provider, options(sx, sy))) {
        if (!requiresConfiguration(p)) return finish(id)
        request.put("stage", "configure"); pending.edit().putString(id.toString(), request.toString()).commit()
      }
      launchSetup(id)
      JSONObject().put("appWidgetId", id).put("status", "pending-user-configuration")
    } catch (e: Exception) { cancel(id); throw e }
  }
  fun requiresConfiguration(p: AppWidgetProviderInfo): Boolean = p.configure != null &&
    !(p.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0 && p.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0)
  fun request(id: Int): JSONObject = pending.getString(id.toString(), null)?.let(::JSONObject) ?: throw FeedException("NOT_FOUND", "no pending widget $id")
  fun launchSetup(id: Int) { context.startActivity(Intent(context, WidgetSetupActivity::class.java).putExtra("widgetId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  fun resume(id: Int): JSONObject {
    if (isPlaced(id)) return finish(id)
    request(id)
    launchSetup(id)
    return JSONObject().put("status", "configuration-opened").put("appWidgetId", id)
  }
  fun finish(id: Int): JSONObject {
    if (isPlaced(id)) {
      clearPending(id)
      return layout.widgetJson(id).put("status", "added")
    }
    val r = request(id)
    val p = info(id) ?: throw FeedException("NOT_BOUND", "widget is not bound")
    val result = layout.addWidget(Slot.Widget(id, p.provider.flattenToString(), r.getInt("spanX"), r.getInt("spanY")), r.getInt("page"), if (r.isNull("index")) null else r.getInt("index"), "widget-host")
    clearPending(id)
    return result.put("status", "added")
  }
  fun cancel(id: Int): JSONObject {
    if (isPlaced(id)) {
      clearPending(id)
      return JSONObject().put("cancelled", false).put("status", "already-added").put("appWidgetId", id)
    }
    request(id)
    host.deleteAppWidgetId(id); pending.edit().remove(id.toString()).commit()
    return JSONObject().put("cancelled", id)
  }
  fun remove(id: Int): JSONObject {
    val result = layout.removeWidget(id, "widget-host")
    host.deleteAppWidgetId(id)
    views.remove(id)
    return result
  }
  fun resize(id: Int, x: Int, y: Int): JSONObject {
    val old = layout.layout.value.pages.flatten().filterIsInstance<Slot.Widget>().firstOrNull { it.appWidgetId == id } ?: throw FeedException("NOT_FOUND", "no widget $id")
    val p = info(id) ?: throw FeedException("PROVIDER_UNAVAILABLE", "provider is not available")
    validateSize(p, x, y, old)
    val result = layout.resizeWidget(id, x, y, "widget-host")
    manager.updateAppWidgetOptions(id, options(x, y))
    return result
  }
  fun configure(id: Int): JSONObject {
    val p = info(id) ?: throw FeedException("NOT_FOUND", "no widget $id")
    if (p.configure == null) throw FeedException("NOT_CONFIGURABLE", "provider has no configuration activity")
    context.startActivity(Intent(context, WidgetSetupActivity::class.java).putExtra("widgetId", id).putExtra("reconfigure", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return JSONObject().put("status", "configuration-opened").put("appWidgetId", id)
  }
  /** The host has zero padding. Report its actual content size without Android's default 8dp inset. */
  fun updateMeasuredSize(id: Int, width: Int, height: Int) {
    val previous = manager.getAppWidgetOptions(id)
    if (previous.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) == width && previous.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) == height) return
    manager.updateAppWidgetOptions(id, Bundle().apply {
      putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
      putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
      putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
      putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
      putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(android.util.SizeF(width.toFloat(), height.toFloat())))
    })
  }

  fun options(x: Int, y: Int) = Bundle().apply {
    val w = (cellDp() * x + 16 * (x - 1)).toInt(); val h = (cellDp() * y + 16 * (y - 1)).toInt()
    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
    putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
  }

  /** One-time migration also seeds existing launchers; never respawns a user-deleted widget. */
  fun seedDefaults() {
    if (!layout.needsDefaultWidgets()) return
    val defaults = listOf(Triple("org.taku.widgets/.ClockWidget", 2, 2), Triple("org.taku.widgets/.AlarmWidget", 2, 1),
      Triple("org.taku.weather/.WeatherWidget", 1, 1), Triple("org.taku.widgets/.DateWidget", 1, 1),
      Triple("org.taku.widgets/.DayWidget", 2, 2), Triple("org.taku.widgets/.MusicWidget", 2, 2))
    val available = providers().associateBy { it.provider.flattenToShortString() }
    if (defaults.any { it.first !in available }) return
    val created = mutableListOf<Slot.Widget>()
    try {
      for ((name, x, y) in defaults) {
        val id = host.allocateAppWidgetId()
        created += Slot.Widget(id, available.getValue(name).provider.flattenToString(), x, y)
        if (!manager.bindAppWidgetIdIfAllowed(id, available.getValue(name).provider, options(x, y))) throw FeedException("BIND_PERMISSION_REQUIRED", "default widgets need host binding permission")
      }
      layout.seedWidgets(created)
    } catch (e: Exception) { created.forEach { host.deleteAppWidgetId(it.appWidgetId) }; android.util.Log.e("TakuWidgets", "default widget binding failed", e) }
  }

  companion object {
    @Volatile private var instance: HomeWidgetHost? = null
    fun get(context: Context): HomeWidgetHost = instance ?: synchronized(this) { instance ?: HomeWidgetHost(context.applicationContext).also { instance = it } }
  }
}

/** Resumable binding/configuration with explicit cancellation cleanup; no launcher ID leaks. */
class WidgetSetupActivity : Activity() {
  private val widgets get() = HomeWidgetHost.get(this)
  private val id get() = intent.getIntExtra("widgetId", -1)
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    if (state != null) return
    try {
      if (!intent.getBooleanExtra("reconfigure", false) && widgets.isPlaced(id)) { widgets.finish(id); finish() }
      else if (intent.getBooleanExtra("reconfigure", false)) configure()
      else if (widgets.info(id) == null) {
        val r = widgets.request(id)
        startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
          .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, ComponentName.unflattenFromString(r.getString("provider")))
          .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, widgets.options(r.getInt("spanX"), r.getInt("spanY"))), 1)
      } else configure()
    } catch (e: Exception) { fail(e) }
  }
  private fun configure() {
    val p = widgets.info(id) ?: error("Widget binding was not granted")
    if (p.configure != null && (intent.getBooleanExtra("reconfigure", false) || widgets.requiresConfiguration(p))) widgets.host.startAppWidgetConfigureActivityForResult(this, id, 0, 2, null)
    else { widgets.finish(id); finish() }
  }
  @Deprecated("Activity result bridge for AppWidgetHost")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    try {
      if (resultCode != RESULT_OK) {
        if (!intent.getBooleanExtra("reconfigure", false)) widgets.cancel(id)
        finish(); return
      }
      if (requestCode == 1) configure()
      else { if (!intent.getBooleanExtra("reconfigure", false)) widgets.finish(id); finish() }
    } catch (e: Exception) { fail(e) }
  }
  private fun fail(e: Exception) {
    if (!intent.getBooleanExtra("reconfigure", false)) runCatching { widgets.cancel(id) }
    android.widget.Toast.makeText(this, e.message ?: "Widget configuration failed", android.widget.Toast.LENGTH_LONG).show()
    finish()
  }
}
