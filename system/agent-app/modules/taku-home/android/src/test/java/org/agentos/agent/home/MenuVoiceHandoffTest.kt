package org.agentos.agent.home

import kotlinx.coroutines.runBlocking
import org.agentos.agent.home.assistant.GatewayException
import org.agentos.agent.home.assistant.HomeComposer
import org.agentos.agent.home.menuscan.MenuVoiceHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MenuVoiceHandoffTest {
  @Test fun keepsRawSpeechForTheAgentAndSentenceCasesOnlyTheDisplay() {
    val utterance = "HELP ME BUILD AN ORDERING AP. CAN I PICK LUNCH?"
    val message = MenuVoiceHandoff.message(utterance, "{}")
    assertEquals(utterance, message.lineSequence().first())
    assertEquals("Help me build an ordering ap. Can I pick lunch?", MenuVoiceHandoff.spokenText(message))
    assertEquals(utterance, MenuVoiceHandoff.spokenText(utterance))
  }

  @Test fun keepsTheKnownOrderingRecoveryInTheAgentContext() {
    val utterance = "HELP ME BEARD IN ORDERING NAP"
    val message = MenuVoiceHandoff.message(utterance, "{}")
    assertEquals(utterance, message.lineSequence().first())
    assertEquals("Help me beard in ordering nap", MenuVoiceHandoff.spokenText(message))
    assertTrue(message.contains("Interpret this known transcription as: Help me build an ordering app."))
  }

  @Test fun preservesNaturalCasingAndUncasedWriting() {
    listOf(
      "Help me build an Android app",
      "What is an ordering app?",
      "Don't help me build an ordering app",
      "帮我做一个点餐 App",
      "帮我做一个点餐应用",
    ).forEach { assertEquals(it, MenuVoiceHandoff.spokenText(MenuVoiceHandoff.message(it, "{}"))) }
  }

  @Test fun givesTheAgentMenuContextForNaturalRequests() {
    val spoken = "Build an app for me"
    val scan = """{"dishes":[{"name":"Pastrami"}]}"""
    val message = MenuVoiceHandoff.message(spoken, scan)
    assertEquals(spoken, message.lineSequence().first())
    assertEquals(spoken, MenuVoiceHandoff.spokenText(message))
    assertEquals(spoken, MenuVoiceHandoff.spokenText(message.replace("[taku.menu-context/1]\n", "")))
    assertTrue(message.endsWith(scan))
    assertTrue(message.contains("If the speech is incomplete, clarify before starting a build."))
  }

  @Test fun eachMenuVoiceSendStagesOnlyItsPhotoAndTextInANewThread() = runBlocking {
    val composer = MenuComposer()
    val sourcePhoto = File("current-menu.jpg")
    val first = composer.prepare(HomeComposer.MenuSubmission("first utterance with menu context", sourcePhoto))
    val second = composer.prepare(HomeComposer.MenuSubmission("second utterance with menu context", sourcePhoto))
    assertEquals("thread-1", first.threadId)
    assertEquals("thread-2", second.threadId)
    assertEquals("first utterance with menu context", first.text)
    assertEquals("second utterance with menu context", second.text)
    assertEquals(listOf(sourcePhoto, sourcePhoto), composer.photos)
    assertEquals(listOf("image-thread-1"), first.attachments.map { it.id })
    assertEquals(listOf("image-thread-2"), second.attachments.map { it.id })
  }

  @Test fun retryReusesTheNewThreadAndDoesNotAttachThePhotoTwiceAfterALostReply() = runBlocking {
    val composer = MenuComposer().apply { loseNextAttachmentReply = true }
    val submission = HomeComposer.MenuSubmission("utterance with menu context", File("menu.jpg"))
    assertThrows(GatewayException::class.java) { runBlocking { composer.prepare(submission) } }
    val recovered = composer.prepare(submission)
    assertEquals("thread-1", recovered.threadId)
    assertEquals(listOf("image-thread-1"), recovered.attachments.map { it.id })
    assertEquals(1, composer.photos.size)
    assertEquals(1, composer.handoffIds.distinct().size)
  }

  private class MenuComposer {
    private var creations = 0
    private val drafts = mutableMapOf<String, HomeComposer.Draft>()
    val photos = mutableListOf<File>()
    val handoffIds = mutableListOf<String>()
    var loseNextAttachmentReply = false

    suspend fun prepare(submission: HomeComposer.MenuSubmission): HomeComposer.Draft = submission.prepare(
      create = { "thread-${++creations}" },
      handoff = { thread, id, text ->
        handoffIds += id
        drafts.getOrPut(thread) { HomeComposer.Draft(thread, "text", text, emptyList(), null) }
      },
      attach = { draft, photo ->
        photos += photo
        val attached = draft.copy(revision = "photo", attachments = listOf(
          HomeComposer.Attachment("image-${draft.threadId}", HomeComposer.MENU_PHOTO_NAME),
        ))
        drafts[draft.threadId] = attached
        if (loseNextAttachmentReply) {
          loseNextAttachmentReply = false
          throw GatewayException("GATEWAY_UNAVAILABLE", "Lost reply")
        }
        attached
      },
    )
  }
}
