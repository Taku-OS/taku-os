package org.agentos.agent.home

import org.junit.Assert.*
import org.junit.Test

class ActivityHostLifecycleTest {
  // A strict single-owner host models the RN invariant that caused BUG-0007.
  private class Host {
    var current: Any? = null
    var active = false
    val events = ArrayList<String>()
    val lifecycle = ActivityHostLifecycle<Any>(
      resumeHost = { current = it; active = true; events.add("resume:$it") },
      pauseHost = { check(current === it) { "Pausing a non-current activity" }; active = false; events.add("pause:$it") },
      destroyHost = { if (current === it) { current = null; active = false; events.add("destroy:$it") } },
    )
  }

  @Test fun lateChatPauseCannotPauseTheResumedHome() {
    val host = Host()
    val chat = Any(); val home = Any()
    host.lifecycle.resume(chat)
    host.lifecycle.resume(home)
    val before = host.events.toList()
    host.lifecycle.pause(chat)
    assertSame(home, host.current)
    assertTrue(host.active)
    assertEquals(before, host.events)
    host.lifecycle.pause(home)
    assertFalse(host.active)
  }

  @Test fun closingTopActivityResumesAnotherStillActiveActivity() {
    val host = Host()
    val chat = Any(); val home = Any()
    host.lifecycle.resume(chat)
    host.lifecycle.resume(home)
    host.lifecycle.pause(home)
    assertSame(chat, host.current)
    assertTrue(host.active)
    host.lifecycle.pause(chat)
    assertFalse(host.active)
  }

  @Test fun oldDestroyDoesNotDestroyTheNewOwner() {
    val host = Host()
    val chat = Any(); val home = Any()
    host.lifecycle.resume(chat)
    host.lifecycle.resume(home)
    host.lifecycle.destroy(chat)
    assertSame(home, host.current)
    assertTrue(host.active)
    assertFalse(host.events.any { it.startsWith("destroy:") })
  }

  @Test fun currentDestroyReturnsToOtherResumedPage() {
    val host = Host()
    val chat = Any(); val home = Any()
    host.lifecycle.resume(chat)
    host.lifecycle.resume(home)
    host.lifecycle.destroy(home)
    assertSame(chat, host.current)
    assertTrue(host.active)
    host.lifecycle.destroy(chat)
    assertNull(host.current)
    assertFalse(host.active)
  }

  @Test fun ordinaryPauseAndDestroyReachTheHost() {
    val host = Host(); val chat = Any()
    host.lifecycle.resume(chat)
    host.lifecycle.pause(chat)
    assertFalse(host.active)
    host.lifecycle.destroy(chat)
    assertNull(host.current)
  }

  @Test fun repeatedResumeDoesNotLeaveDuplicateOwners() {
    val host = Host(); val chat = Any()
    host.lifecycle.resume(chat)
    host.lifecycle.resume(chat)
    host.lifecycle.pause(chat)
    assertFalse(host.active)
    assertEquals(1, host.events.count { it.startsWith("pause:") })
  }

  @Test fun replacementInstancesDoNotShareOwnershipEvenWhenEqual() {
    data class Page(val name: String)
    val host = Host(); val old = Page("chat"); val replacement = Page("chat")
    host.lifecycle.resume(old)
    host.lifecycle.resume(replacement)
    host.lifecycle.pause(old)
    host.lifecycle.destroy(old)
    assertSame(replacement, host.current)
    assertTrue(host.active)
  }

  @Test fun clearingGlobalLifecycleDoesNotResurrectOldActivities() {
    val host = Host(); val chat = Any(); val home = Any()
    host.lifecycle.resume(chat)
    host.lifecycle.clear()
    host.lifecycle.resume(home)
    host.lifecycle.pause(home)
    assertFalse(host.active)
    assertSame(home, host.current)
  }
}
