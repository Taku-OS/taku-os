package org.agentos.agent.home

import org.agentos.agent.home.ui.assistant.ComposerImeDismiss
import org.junit.Assert.*
import org.junit.Test

class ComposerImeDismissTest {
  @Test fun failedImeShowDoesNotDismissANewTextEntry() {
    val ime = ComposerImeDismiss()
    assertFalse(ime.update(true, false, 0, false))
    assertFalse(ime.update(true, true, 0, false))
    assertFalse(ime.update(true, false, 0, false))
    assertFalse(ime.update(true, true, 0, false))
    assertFalse(ime.update(true, false, 0, false))
  }

  @Test fun aDisplayedKeyboardDismissesOnlyAfterItsHideAnimationCompletes() {
    val ime = ComposerImeDismiss()
    assertFalse(ime.update(true, true, 0, false))
    assertFalse(ime.update(true, true, 300, false))
    assertFalse(ime.update(true, false, 180, false))
    assertTrue(ime.update(true, false, 0, false))
    assertFalse(ime.update(true, false, 0, false))
  }

  @Test fun closedInputCannotArmTheNextEntryFromItsPreviousKeyboard() {
    val ime = ComposerImeDismiss()
    assertFalse(ime.update(true, true, 300, false))
    assertFalse(ime.update(false, true, 300, false))
    assertFalse(ime.update(true, false, 0, false))
    assertFalse(ime.update(true, true, 0, false))
    assertFalse(ime.update(true, false, 0, false))
  }

  @Test fun dictationKeepsInputOpenAcrossKeyboardLossAndCaptureStop() {
    val ime = ComposerImeDismiss()
    assertFalse(ime.update(true, true, 300, true))
    assertFalse(ime.update(true, false, 0, true))
    assertFalse(ime.update(true, false, 0, false))
    assertFalse(ime.update(true, true, 300, false))
    assertTrue(ime.update(true, false, 0, false))
  }
}
