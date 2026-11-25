package org.agentos.agent.home

import org.agentos.agent.home.menuscan.MenuBuildRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MenuBuildRequestTest {
  @Test fun correctsMisheardMenuCommand() {
    assertEquals(
      "Help me build an ordering app",
      MenuBuildRequest.resolveSpeech("HELP ME BEARD IN ORDERING NAP"),
    )
  }

  @Test fun keepsClearMenuCommands() {
    val request = "Create a menu app from this menu."
    assertEquals(request, MenuBuildRequest.resolveSpeech(request))
  }

  @Test fun leavesOtherUtterancesAlone() {
    listOf(
      "Help me build an Android app",
      "What is an ordering app?",
      "Don't help me build an ordering app",
      "Help me take a nap",
    ).forEach { assertNull(it, MenuBuildRequest.resolveSpeech(it)) }
  }
}
