package org.agentos.agent.bridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

internal object MenuAppLaunch {
  private val packagePattern = Regex("org\\.agentos\\.generated\\.menu\\.m[a-f0-9]{32}")
  private const val OPEN_ACTION = "org.agentos.generated.menu.OPEN"
  private const val TEMPLATE_VERSION = "org.agentos.generated.menu.TEMPLATE_VERSION"

  private fun action() = Intent(OPEN_ACTION).addCategory(Intent.CATEGORY_DEFAULT)

  private fun resolve(packages: PackageManager, packageName: String): ActivityInfo? {
    if (!packagePattern.matches(packageName)) return null
    val candidates = packages.queryIntentActivities(action().setPackage(packageName),
      PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_META_DATA)
    return candidates.asSequence().map { it.activityInfo }.firstOrNull {
      it.packageName == packageName && it.name == "$packageName.MainActivity" && it.exported && it.enabled &&
        it.applicationInfo.enabled && it.permission.isNullOrEmpty() && it.metaData?.getInt(TEMPLATE_VERSION) == 1
    }
  }

  fun read(packages: PackageManager, packageName: String): Map<String, String>? {
    val activity = resolve(packages, packageName) ?: return null
    val label = activity.loadLabel(packages).toString().trim().take(160)
    return buildMap {
      put("title", label.takeUnless { it == packageName || it == activity.name }.orEmpty())
      cover(packages, activity)?.let { put("coverUri", it) }
    }
  }

  private fun cover(packages: PackageManager, activity: ActivityInfo): String? = try {
    val resources = packages.getResourcesForApplication(activity.applicationInfo)
    val resId = resources.getIdentifier("taku_menu_app_cover", "drawable", activity.packageName)
    if (resId == 0) null else decodeCover(resources, resId)
  } catch (_: Exception) { null }

  private fun decodeCover(resources: Resources, resId: Int): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
    BitmapFactory.decodeResource(resources, resId, bounds)
    if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192) return null
    val options = BitmapFactory.Options().apply {
      inScaled = false
      inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 144).coerceAtLeast(1)
    }
    val bitmap = BitmapFactory.decodeResource(resources, resId, options) ?: return null
    return try {
      val bytes = ByteArrayOutputStream()
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bytes)) null
      else "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    } finally {
      bitmap.recycle()
    }
  }

  fun open(host: Activity, packageName: String): Boolean {
    val activity = resolve(host.packageManager, packageName) ?: return false
    return try {
      host.startActivity(action().setClassName(activity.packageName, activity.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
  }
}
