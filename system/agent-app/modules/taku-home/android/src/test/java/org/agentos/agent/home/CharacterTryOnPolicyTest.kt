package org.agentos.agent.home

import org.agentos.agent.home.assistant.CharacterTryOnPolicy
import org.junit.Assert.*
import org.junit.Test
import org.taku.cosplay.api.CosplayContract

class CharacterTryOnPolicyTest {
  @Test fun onlySystemLongPressCanOfferAReference() {
    assertTrue(CharacterTryOnPolicy.acceptsEntry("nav_handle_long_press", "gesture_123", emptySet(), true))
    assertFalse(CharacterTryOnPolicy.acceptsEntry("nav_handle_double_tap", "gesture_123", emptySet(), true))
    assertFalse(CharacterTryOnPolicy.acceptsEntry("nav_handle_long_press", "gesture_123", emptySet(), false))
    assertFalse(CharacterTryOnPolicy.acceptsEntry("nav_handle_long_press", "", emptySet(), true))
    for (key in listOf("page", "conversationId", "jevTestToken", "manualScreen", "nativeControlsOnly")) {
      assertFalse(key, CharacterTryOnPolicy.acceptsEntry("nav_handle_long_press", "gesture_123", setOf(key), true))
    }
  }

  @Test fun packageAdmissionDoesNotAcceptLookalikesOrOtherApps() {
    assertEquals("com.zhiliaoapp.musically", CharacterTryOnPolicy.source("com.zhiliaoapp.musically/.MainActivity"))
    assertEquals("com.ss.android.ugc.trill", CharacterTryOnPolicy.source("com.ss.android.ugc.trill"))
    assertNull(CharacterTryOnPolicy.source("com.zhiliaoapp.musically.fake/.MainActivity"))
    assertNull(CharacterTryOnPolicy.source("com.android.chrome/.Main"))
    assertNull(CharacterTryOnPolicy.source(null))
  }

  @Test fun systemDoubleTapTextRequiresItsOwnCaptureAdmission() {
    assertTrue(CharacterTryOnPolicy.acceptsTextEntry("nav_handle_double_tap", emptySet(), true))
    assertFalse(CharacterTryOnPolicy.acceptsTextEntry("nav_handle_double_tap", emptySet(), false))
    assertFalse(CharacterTryOnPolicy.acceptsTextEntry("nav_handle_long_press", emptySet(), true))
    assertFalse(CharacterTryOnPolicy.acceptsTextEntry(null, emptySet(), true))
    for (key in listOf("page", "conversationId", "jevTestToken", "manualScreen", "nativeControlsOnly")) {
      assertFalse(key, CharacterTryOnPolicy.acceptsTextEntry("nav_handle_double_tap", setOf(key), true))
    }
  }

  @Test fun completeCharacterRequestsWorkInBothSupportedDictationLanguages() {
    for (text in listOf("帮我换成这个角色", "帮我变成屏幕里的角色。", "Turn me into this character!",
      "Make me this character.", "I wanna cosplay as her", "ＣＯＳＰＬＡＹ this character")) {
      assertTrue(text, CharacterTryOnPolicy.command(text))
    }
  }

  @Test fun naturalOutfitRequestsReferToTheCurrentVideo() {
    assertTrue(CharacterTryOnPolicy.command("HELP ME PUT ON THIS OUTFIT OF HIS"))
    assertTrue(CharacterTryOnPolicy.command("Try on this outfit"))
    for (prefix in listOf("", "Help me ", "Please ", "Please help me ")) {
      for (verb in listOf("put on ", "try on ")) {
        for (reference in listOf("this outfit", "this outfit of his", "this outfit of her",
          "this outfit of hers", "his outfit", "her outfit")) {
          val text = "$prefix$verb$reference."
          assertTrue(text, CharacterTryOnPolicy.command(text))
        }
      }
    }
  }

  @Test fun negationPartialTranscriptsAndUnrelatedRequestsStayGeneric() {
    for (text in listOf("", "Turn me into", "Don't turn me into this character", "帮我换成这个角色吗？",
      "帮我换成这个角色，然后发给朋友", "Turn me into this character and post it",
      "Make me this character!.", "What character is this?", "a".repeat(161))) {
      assertFalse(text, CharacterTryOnPolicy.command(text))
    }
  }

  @Test fun outfitRequestsMustBeCompleteDirectInstructions() {
    for (text in listOf("Help me put on", "Help me put on this", "Help me put on this outfit of",
      "Don't help me put on this outfit of his", "Do not try on this outfit",
      "I don't want to try on this outfit", "Tell me how to try on this outfit",
      "Explain how to put on her outfit", "The caption says try on this outfit",
      "He said help me put on this outfit of his", "\"Help me put on this outfit of his\"",
      "Say \"try on this outfit\"", "If I say try on this outfit, what happens?",
      "Help me put on this outfit and send a photo", "Help me put on this outfit of his and post it",
      "Try on this outfit!.", "Try on this outfit from your shopping catalog")) {
      assertFalse(text, CharacterTryOnPolicy.command(text))
    }
  }

  @Test fun referencesCannotPrecedeInvocationOrOutliveEitherDeadline() {
    assertTrue(CharacterTryOnPolicy.fresh(1_000, 1_100, 1_200))
    assertTrue(CharacterTryOnPolicy.fresh(1_000, 1_100, 1_000 + CosplayContract.MAX_INVOCATION_AGE_MS))
    assertFalse(CharacterTryOnPolicy.fresh(1_000, 999, 1_200))
    assertFalse(CharacterTryOnPolicy.fresh(1_000, 1_100, 1_099))
    assertFalse(CharacterTryOnPolicy.fresh(-1, 1_100, 1_200))
    assertFalse(CharacterTryOnPolicy.fresh(1_000, 1_100, 1_001 + CosplayContract.MAX_INVOCATION_AGE_MS))
  }

  @Test fun ticketsAreOpaqueAndBoundedByTheirSharedLease() {
    val ticket = "a".repeat(32)
    assertTrue(CharacterTryOnPolicy.ticketValid(ticket, 2_000, 1_000))
    assertTrue(CharacterTryOnPolicy.ticketValid(ticket, 1_000 + CosplayContract.TICKET_TTL_MS, 1_000))
    assertFalse(CharacterTryOnPolicy.ticketValid(ticket, 1_000, 1_000))
    assertFalse(CharacterTryOnPolicy.ticketValid(ticket, 1_001 + CosplayContract.TICKET_TTL_MS, 1_000))
    assertFalse(CharacterTryOnPolicy.ticketValid("../$ticket", 2_000, 1_000))
  }

  @Test fun binaryPayloadCannotExceedTheSharedBinderBudget() {
    fun jpeg(size: Int) = ByteArray(size).apply { this[0] = 0xff.toByte(); this[1] = 0xd8.toByte(); this[size - 2] = 0xff.toByte(); this[size - 1] = 0xd9.toByte() }
    assertTrue(CharacterTryOnPolicy.boundedJpeg(jpeg(CosplayContract.MAX_JPEG_BYTES)))
    assertFalse(CharacterTryOnPolicy.boundedJpeg(jpeg(CosplayContract.MAX_JPEG_BYTES + 1)))
    assertFalse(CharacterTryOnPolicy.boundedJpeg(byteArrayOf()))
    assertFalse(CharacterTryOnPolicy.boundedJpeg(byteArrayOf(0, 0, 0, 0)))
  }
}
