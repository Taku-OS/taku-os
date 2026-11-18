package org.agentos.agent.home.apps

/** Shared presentation policy for the launcher and its on-device Agent API. */
object LauncherPresentation {
  const val TAKU_FOLDER_ID = "builtin-taku"
  val takuIcons = mapOf(
    "org.agentos.agent" to "agent", "org.agentos.assistant" to "assistant",
    "org.agentos.surfacedemo" to "preview", "org.agentos.surfaces" to "preview",
    "org.agentos.memory" to "memory", "org.agentos.proactivity" to "proactivity",
    "org.agentos.inputmethod" to "keyboard",
  )
  private val packageIcons = mapOf(
    "com.android.dialer" to "phone", "com.google.android.dialer" to "phone",
    "com.android.messaging" to "message", "com.google.android.apps.messaging" to "message",
    "com.android.camera2" to "camera", "com.android.camera" to "camera", "com.google.android.GoogleCamera" to "camera",
    "com.android.browser" to "browser", "com.android.chrome" to "browser", "org.chromium.webview_shell" to "browser",
    // Unbranded AOSP utilities only; Google, third-party and Taku apps keep their own icons.
    "com.android.calendar" to "calendar", "com.android.deskclock" to "clock", "com.android.contacts" to "contacts",
    "com.android.documentsui" to "files", "com.google.android.documentsui" to "files", "com.android.gallery3d" to "gallery",
    "com.android.settings" to "settings", "com.android.quicksearchbox" to "search",
    "com.android.stk" to "sim", "com.android.calculator2" to "calculator",
  )
  /** The four 4-1 core roles drawn as #1B1B1B glyphs whichever app holds them (Figma 4-1). */
  val coreRoles = setOf("phone", "browser", "message", "camera")

  /**
   * The local glyph an app is drawn with, or null to keep its own icon (issue #32 H6): the core
   * roles and unbranded AOSP utilities only. Taku apps keep their brand icons ([takuIcons] only
   * decides the Taku folder).
   */
  fun iconAsset(packageName: String, resolvedRole: String?): String? =
    packageIcons[packageName] ?: resolvedRole?.takeIf { it in coreRoles }

  /** Keep every installed activity discoverable, grouping only our shipped Taku apps. */
  fun grid(apps: List<LaunchableApp>, folderName: String = "Taku"): List<Slot> {
    val taku = apps.filter { it.packageName in takuIcons }.map { it.key }
    val grouped = if (taku.size >= 2) taku.toSet() else emptySet()
    val priority = listOf("phone", "browser", "message", "camera")
    val ordered = apps.sortedBy { priority.indexOf(it.iconAsset).let { i -> if (i < 0) priority.size else i } }
    val slots = ordered.filterNot { it.key in grouped }.map<LaunchableApp, Slot> { Slot.App(it.key) }.toMutableList()
    if (grouped.isNotEmpty()) slots.add(minOf(8, slots.size), Slot.Folder(TAKU_FOLDER_ID, folderName, taku))
    return slots
  }
}
