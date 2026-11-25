package org.agentos.agent.home

import org.agentos.agent.home.menuscan.MenuVoiceHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuVoiceHandoffTest {
  @Test fun correctsMisheardMenuCommand() {
    assertEquals(
      "Help me build an ordering app",
      MenuVoiceHandoff.normalizeSpeech("HELP ME BEARD IN ORDERING NAP"),
    )
  }

  @Test fun keepsClearMenuCommands() {
    val request = "Create a menu app from this menu."
    assertEquals(request, MenuVoiceHandoff.normalizeSpeech(request))
  }

  @Test fun leavesOtherUtterancesAlone() {
    listOf(
      "Help me build an Android app",
      "What is an ordering app?",
      "Don't help me build an ordering app",
      "Help me take a nap",
      "IN ORDER",
    ).forEach { assertEquals(it, MenuVoiceHandoff.normalizeSpeech(it)) }
  }

  @Test fun givesTheAgentMenuContextForNaturalRequests() {
    val spoken = "Build an app for me"
    val scan = """{"dishes":[{"name":"Pastrami"}]}"""
    val message = MenuVoiceHandoff.message(MenuVoiceHandoff.normalizeSpeech(spoken), scan)
    assertEquals(spoken, message.lineSequence().first())
    assertEquals(spoken, MenuVoiceHandoff.spokenText(message))
    assertEquals(spoken, MenuVoiceHandoff.spokenText(message.replace("[taku.menu-context/1]\n", "")))
    assertTrue(message.endsWith(scan))
    assertTrue(message.contains("If the speech is incomplete, clarify before starting a build."))
  }
}
