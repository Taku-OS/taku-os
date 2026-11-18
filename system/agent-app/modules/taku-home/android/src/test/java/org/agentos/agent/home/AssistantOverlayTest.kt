package org.agentos.agent.home

import org.agentos.agent.home.assistant.*
import org.junit.Assert.*
import org.junit.Test

class AssistantOverlayTest {
  @Test fun releaseClosesInputActivityWhileSystemUiOwnsTheResult() {
    val held = AssistantState(entry = EntryMode.Voice, island = IslandMode.Expanded)
    val released = held.copy(entry = EntryMode.None)
    assertTrue(held.overlayVisible)
    assertFalse(released.overlayVisible)
    assertFalse(released.copy(sending = true).overlayVisible)
    assertFalse(released.copy(error = "VOICE_UNAVAILABLE").overlayVisible)
    assertFalse(released.copy(island = IslandMode.Full).overlayVisible)
    assertEquals(IslandMode.Expanded, released.island)
  }

  @Test fun dismissalReturnsFocusWithoutWaitingForTheBackgroundTask() {
    assertFalse(AssistantState().overlayVisible)
    assertFalse(AssistantState(entry = EntryMode.None, island = IslandMode.Collapsed, sending = true).overlayVisible)
    assertTrue(AssistantState(entry = EntryMode.Text, island = IslandMode.Collapsed).overlayVisible)
  }
}
