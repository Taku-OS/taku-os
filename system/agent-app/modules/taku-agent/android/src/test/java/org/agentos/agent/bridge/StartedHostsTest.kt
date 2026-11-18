package org.agentos.agent.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartedHostsTest {
  @Test fun oneVisibleHostKeepsTheSurfaceInDemand() {
    val changes = ArrayList<Boolean>()
    val hosts = StartedHosts<Any> { changes += it }
    val chat = Any()
    hosts.start(chat)
    assertTrue(hosts.visible)
    hosts.stop(chat)
    assertFalse(hosts.visible)
    assertTrue(changes == listOf(true, false))
  }

  @Test fun aTranslucentSecondHostDoesNotReleaseTheSurface() {
    val hosts = StartedHosts<Any> { }
    val desktop = Any()
    val chat = Any()
    hosts.start(desktop)
    hosts.start(chat)
    hosts.stop(desktop)
    assertTrue(hosts.visible)
    hosts.stop(chat)
    assertFalse(hosts.visible)
  }

  @Test fun aReplacementInstanceOnlyRetiresItsOwnHost() {
    val hosts = StartedHosts<Any> { }
    val first = Any()
    val second = Any()
    hosts.start(first)
    hosts.start(second)
    hosts.stop(first)
    assertTrue(hosts.visible)
    hosts.stop(second)
    assertFalse(hosts.visible)
  }

  @Test fun repeatedStartsAndStopsOfOneHostDoNotFlapTheSignal() {
    val changes = ArrayList<Boolean>()
    val hosts = StartedHosts<Any> { changes += it }
    val chat = Any()
    hosts.start(chat)
    hosts.start(chat)
    hosts.stop(chat)
    hosts.stop(chat)
    assertFalse(hosts.visible)
    assertTrue(changes == listOf(true, false))
  }
}
