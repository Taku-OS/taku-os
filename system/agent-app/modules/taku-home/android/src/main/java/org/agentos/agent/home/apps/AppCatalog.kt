package org.agentos.agent.home.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/** One launchable activity. [key] is the flattened component (`package/.Activity`). */
data class LaunchableApp(
  val key: String,
  val packageName: String,
  val label: String,
  /** Drawn as a dark glyph on the glass backplate, from a local asset or a package monochrome layer. */
  val monochrome: Boolean,
  val system: Boolean,
  val category: Int,
  /** Local vector asset, or null when the package supplies its own icon. */
  val iconAsset: String? = null,
)

/**
 * Installed launchable apps for the current user, from [LauncherApps] (the launcher API that
 * also reports package changes). Shared by the home pages and Agent access.
 */
class AppCatalog private constructor(private val context: Context) {
  private val launcher = context.getSystemService(LauncherApps::class.java)
  private val user: UserHandle = Process.myUserHandle()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
  val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()
  private val infos = HashMap<String, LauncherActivityInfo>()
  private val icons = LruCache<String, ImageBitmap>(160)

  private val callback = object : LauncherApps.Callback() {
    override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
    override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
    override fun onPackageChanged(packageName: String, user: UserHandle) { icons.evictAll(); refresh() }
    override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
    override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
  }

  init {
    launcher.registerCallback(callback, Handler(Looper.getMainLooper()))
    refresh()
  }

  fun refresh() {
    scope.launch {
      try {
        val core = corePackages()
        val list = launcher.getActivityList(null, user)
          .filterNot { it.componentName.packageName == context.packageName && !it.componentName.className.endsWith(".MainActivity") }
        val collator = Collator.getInstance(Locale.getDefault())
        val apps = synchronized(infos) {
          infos.clear()
          list.map { info ->
            val key = info.componentName.flattenToShortString()
            infos[key] = info
            val appInfo = info.applicationInfo
            val system = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val pkg = info.componentName.packageName
            // Only the glyph apps are monochrome; every other app, system or not, keeps its icon.
            val asset = LauncherPresentation.iconAsset(pkg, core[pkg])
            LaunchableApp(key, pkg, info.label.toString(), asset != null, system, appInfo.category, asset)
          }
        }.sortedWith(compareBy(collator) { it.label })
        _apps.value = apps
      } catch (e: RuntimeException) {
        Log.e(TAG, "cannot list apps", e)
      }
    }
  }

  fun app(key: String): LaunchableApp? = _apps.value.firstOrNull { it.key == key }

  /** Icon at [sizePx]: the monochrome glyph for core apps, else the full icon. Off the main thread. */
  fun icon(key: String, sizePx: Int): ImageBitmap? {
    val app = app(key)
    val cacheKey = "$key@$sizePx@${app?.iconAsset}@${app?.monochrome}"
    icons.get(cacheKey)?.let { return it }
    val info = synchronized(infos) { infos[key] } ?: return null
    return try {
      if (app?.iconAsset != null) {
        return renderAsset(app.iconAsset, sizePx).asImageBitmap().also { icons.put(cacheKey, it) }
      }
      val drawable = info.getIcon(context.resources.displayMetrics.densityDpi)
      val glyph = if (app?.monochrome == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) (drawable as? AdaptiveIconDrawable)?.monochrome else null
      val bitmap = if (glyph != null) render(glyph, sizePx, adaptive = true, tint = 0xFF1B1B1B.toInt()) else render(drawable, sizePx, adaptive = drawable is AdaptiveIconDrawable, tint = null)
      bitmap.asImageBitmap().also { icons.put(cacheKey, it) }
    } catch (e: Exception) {
      Log.w(TAG, "icon failed for $key", e); null
    }
  }

  /** Preserve Figma's 48 dp viewport, including the centered 40 dp Message asset. */
  private fun renderAsset(name: String, size: Int): Bitmap {
    val svg = context.assets.open("launcher/$name.svg").use { SVG.getFromInputStream(it) }
    val sourceSize = svg.documentWidth
    val viewport = if (name in setOf("phone", "browser", "message", "camera")) 48f else 32f
    val scale = size / viewport
    val inset = (viewport - sourceSize) / 2f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.save()
    canvas.scale(scale, scale)
    canvas.translate(inset, inset)
    svg.renderToCanvas(canvas)
    canvas.restore()
    canvas.drawColor(0xFF1B1B1B.toInt(), PorterDuff.Mode.SRC_IN)
    return bitmap
  }

  /** Adaptive layers are 108 dp with a 72 dp safe zone; draw them so the safe zone fills [size]. */
  private fun render(drawable: Drawable, size: Int, adaptive: Boolean, tint: Int?): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val d = drawable.mutate()
    if (tint != null) d.setTint(tint)
    if (adaptive) {
      val inset = size * (108f / 72f - 1f) / 2f
      d.setBounds((-inset).toInt(), (-inset).toInt(), (size + inset).toInt(), (size + inset).toInt())
      // Brand icons keep their own background edge to edge; Home clips them to the icon's
      // rounded rectangle, so the artwork fills the shape rather than a circle.
    } else d.setBounds(0, 0, size, size)
    d.draw(canvas)
    return bitmap
  }


  /** Default handlers of core roles; these render as glyphs like the design's Phone/Browser/Message/Camera. */
  private fun corePackages(): Map<String, String> {
    val pm = context.packageManager
    fun resolve(intent: Intent) = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    return listOf(
      "phone" to Intent(Intent.ACTION_DIAL),
      "message" to Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
      "camera" to Intent(MediaStore.ACTION_IMAGE_CAPTURE),
      "browser" to Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
      "settings" to Intent(Settings.ACTION_SETTINGS),
      "contacts" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),
      "calendar" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
      "calculator" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR),
      "gallery" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
      "files" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_FILES),
      "clock" to Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS),
    ).mapNotNull { (role, intent) -> resolve(intent)?.takeUnless { it == "android" }?.let { it to role } }.toMap()
  }

  fun launch(key: String, sourceBounds: android.graphics.Rect? = null): Boolean {
    val info = synchronized(infos) { infos[key] } ?: return false
    return try {
      launcher.startMainActivity(info.componentName, user, sourceBounds, null); true
    } catch (e: RuntimeException) {
      Log.w(TAG, "launch failed for $key", e); false
    }
  }

  fun openAppInfo(key: String) {
    val component = ComponentName.unflattenFromString(key) ?: return
    runCatching { launcher.startAppDetailsActivity(component, user, null, null) }
  }

  fun uninstall(packageName: String) {
    runCatching {
      context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }

  /** Share through Android's chooser; never send to a recipient or share the APK ourselves. */
  fun share(key: String): org.json.JSONObject {
    val app = app(key) ?: throw org.agentos.agent.home.feed.FeedException("APP_NOT_FOUND", key)
    val fromPlay = runCatching { context.packageManager.getInstallSourceInfo(app.packageName).installingPackageName == "com.android.vending" }.getOrDefault(false)
    val details = if (fromPlay) "https://play.google.com/store/apps/details?id=${app.packageName}" else app.packageName
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
      .putExtra(Intent.EXTRA_SUBJECT, app.label).putExtra(Intent.EXTRA_TEXT, "${app.label}\n$details")
    try {
      context.startActivity(Intent.createChooser(send, context.getString(org.agentos.agent.home.R.string.home_share_app)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: RuntimeException) { throw org.agentos.agent.home.feed.FeedException("NO_ACTIVITY", "no sharing activity is available") }
    return org.json.JSONObject().put("effect", "share-chooser-shown").put("key", key)
  }

  companion object {
    private const val TAG = "TakuHomeApps"
    @Volatile private var instance: AppCatalog? = null
    fun get(context: Context): AppCatalog = instance ?: synchronized(this) {
      instance ?: AppCatalog(context.applicationContext).also { instance = it }
    }
  }
}
