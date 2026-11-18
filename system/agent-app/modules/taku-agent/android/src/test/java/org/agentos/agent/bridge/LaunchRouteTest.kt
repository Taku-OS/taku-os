package org.agentos.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchRouteTest {
  private val first = LaunchRoute.Path("thread-a", "panel-a", "thread-a")
  private val second = LaunchRoute.Path("thread-b", "panel-b", "thread-b")

  @Test fun aRepeatedIntentKeepsItsGeneration() {
    val route = LaunchRoute()
    val observed = route.observe(first)
    assertTrue(observed.fresh)
    assertTrue(route.current(observed.generation))
    val again = route.observe(first)
    assertFalse(again.fresh)
    assertEquals(observed.generation, again.generation)
    assertTrue(route.current(again.generation))
  }

  @Test fun aLateResolutionCannotPublishOverASupersededRoute() {
    val route = LaunchRoute()
    val a = route.observe(first)
    val b = route.observe(second)
    assertFalse(route.current(a.generation))
    assertEquals(second, route.path())
    assertTrue(route.current(b.generation))
  }

  @Test fun aPlainConversationIsStillARoute() {
    val route = LaunchRoute()
    val plain = route.observe(LaunchRoute.Path("thread-c", null, null))
    assertNull(route.path()?.panelId)
    assertTrue(route.current(plain.generation))
  }

  @Test fun aRouteWithNothingObservedPublishesNothing() {
    val route = LaunchRoute()
    assertNull(route.path())
    assertFalse(route.current(0L))
  }

  @Test fun anExplicitReopenOfTheSamePanelHasAnIndependentGeneration() {
    val route = LaunchRoute()
    val old = route.observe(first.copy(launchId = "first-open"))
    val reopened = route.observe(first.copy(launchId = "second-open"))
    assertFalse(route.current(old.generation))
    assertTrue(route.current(reopened.generation))
    assertTrue(reopened.fresh)
  }
}
