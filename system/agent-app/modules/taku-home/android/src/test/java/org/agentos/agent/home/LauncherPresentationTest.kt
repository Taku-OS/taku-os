package org.agentos.agent.home

import org.agentos.agent.home.apps.LaunchableApp
import org.agentos.agent.home.apps.LauncherPresentation
import org.agentos.agent.home.apps.Slot
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LauncherPresentationTest {
  private fun app(pkg: String, activity: String = "Main") = LaunchableApp(
    "$pkg/.$activity", pkg, pkg, false, false, -1,
    LauncherPresentation.iconAsset(pkg, null),
  )

  @Test fun groupsShippedTakuAppsWithoutLosingActivitiesOrGroupingUnrelatedApps() {
    val apps = listOf(app("org.agentos.agent"), app("org.agentos.agent", "Second"),
      app("org.agentos.assistant"), app("org.agentos.memorytests"), app("example.social"))
    val grid = LauncherPresentation.grid(apps, "My Taku")
    val folder = grid.filterIsInstance<Slot.Folder>().single()
    assertEquals("My Taku", folder.name)
    assertEquals(LauncherPresentation.TAKU_FOLDER_ID, folder.id)
    assertEquals(3, folder.apps.size)
    val keys = grid.flatMap { when (it) { is Slot.Widget -> emptyList(); is Slot.App -> listOf(it.key); is Slot.Folder -> it.apps } }
    assertEquals(apps.map { it.key }.sorted(), keys.sorted())
    assertEquals(keys.size, keys.toSet().size)
    assertTrue(grid.contains(Slot.App("org.agentos.memorytests/.Main")))
  }

  @Test fun singleTakuAppStaysLaunchableWithoutAnEmptyFolder() {
    assertEquals(listOf(Slot.App("org.agentos.agent/.Main")), LauncherPresentation.grid(listOf(app("org.agentos.agent"))))
    assertTrue(LauncherPresentation.grid(emptyList()).isEmpty())
  }

  @Test fun coreAppsFollowDesignOrderAndUseAvailableLocalAssets() {
    val apps = listOf(app("com.android.camera2"), app("com.android.messaging"), app("com.android.dialer"), app("com.android.chrome"))
    val byKey = apps.associateBy { it.key }
    assertEquals(listOf("phone", "browser", "message", "camera"),
      LauncherPresentation.grid(apps).map { byKey[(it as Slot.App).key]?.iconAsset })
    (apps.mapNotNull { it.iconAsset } + LauncherPresentation.takuIcons.values).forEach {
      assertTrue("Missing vector $it", File("src/main/assets/launcher/$it.svg").length() > 0)
    }
    assertNull(LauncherPresentation.iconAsset("example.social", null))
    assertEquals("browser", LauncherPresentation.iconAsset("example.browser", "browser"))
  }

  @Test fun brandIconsStayExceptCoreRolesAndAospUtilities() {
    // Issue #32 H6: Taku, Google and third-party apps keep their icons.
    for (pkg in listOf("org.agentos.agent", "org.agentos.memory", "com.google.android.apps.photos", "com.android.vending", "com.google.android.calendar"))
      assertNull(pkg, LauncherPresentation.iconAsset(pkg, null))
    assertNull(LauncherPresentation.iconAsset("com.google.android.apps.photos", "gallery"))
    assertEquals("camera", LauncherPresentation.iconAsset("com.example.camera", "camera"))
    assertEquals("settings", LauncherPresentation.iconAsset("com.android.settings", "settings"))
  }
}
