package org.agentos.agent.home

import org.agentos.agent.home.assistant.SystemAssistantEntry
import org.agentos.agent.home.assistant.SystemAssistantEntry.Route
import org.agentos.agent.home.assistant.allowsHomeAssistantCall
import org.agentos.agent.home.assistant.menuHoldCanOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAssistantRouteTest {
  @Test fun quickstepAndAppsWithTakuControlCanDeliverGestures() {
    assertTrue(allowsHomeAssistantCall("gesture/event", { true }, { false }))
    assertTrue(allowsHomeAssistantCall("gesture/event", { false }, { true }))
    assertFalse(allowsHomeAssistantCall("gesture/event", { false }, { false }))
    assertFalse(allowsHomeAssistantCall("assistant/get", { true }, { false }))
    assertTrue(allowsHomeAssistantCall("assistant/get", { false }, { true }))
  }

  @Test fun menuHoldDoesNotRequireInvocationAuthorization() {
    assertTrue(menuHoldCanOpen(held = true, locked = false))
    assertFalse(menuHoldCanOpen(held = false, locked = false))
    assertFalse(menuHoldCanOpen(held = true, locked = true))
  }

  @Test fun powerKeyOpensTextOverlayInPlace() {
    // Long-press power / invocation_type 6 carries no navigation-handle trigger.
    val route = SystemAssistantEntry.routeFor(null)
    assertTrue(route.autoScreenshot)
    assertEquals(Route.Hardware, route)
    assertEquals("text", route.mode)
    assertFalse("hardware entry is not a held gesture", route.navGesture)
    assertEquals(Route.Hardware, SystemAssistantEntry.routeFor(""))
    assertEquals(Route.Hardware, SystemAssistantEntry.routeFor("unknown_trigger"))
  }

  @Test fun navigationHandleGesturesKeepTheirModes() {
    val hold = SystemAssistantEntry.routeFor("nav_handle_long_press")
    assertEquals(Route.NavHold, hold)
    assertEquals("voice", hold.mode)
    assertTrue(hold.navGesture)
    assertFalse(hold.autoScreenshot)
    val tap = SystemAssistantEntry.routeFor("nav_handle_double_tap")
    assertEquals(Route.NavDoubleTap, tap)
    assertEquals("text", tap.mode)
    assertTrue(tap.navGesture)
    assertFalse(tap.autoScreenshot)
  }
}
