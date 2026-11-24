package org.agentos.agent.home

import org.agentos.agent.home.assistant.HomeComposer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeComposerPanelRequestTest {
  private val draft = HomeComposer.Draft("original-thread", "revision-2", "Edit the breakfast card",
    listOf(HomeComposer.Attachment("attachment-1", "Photo")), null)

  @Test fun continuedPanelSendPersistsItsOriginalSourceForRetry() {
    val first = HomeComposer.Request.new(draft, "active-turn", HomeComposer.PanelContinuation("breakfast", 5L))
    val saved = first.submission().put("text", first.text).toString()
    val retry = HomeComposer.Request.parse(JSONObject(saved)).submission()
    val next = HomeComposer.Request.new(draft, null, HomeComposer.PanelContinuation("breakfast", 6L)).submission()

    assertEquals("continue", retry.getString("action"))
    assertEquals("original-thread", retry.getString("threadId"))
    assertEquals("breakfast", retry.getString("panelId"))
    assertEquals(5L, retry.getLong("expectedSourceVersion"))
    assertEquals("revision-2", retry.getString("revision"))
    assertEquals("attachment-1", retry.getJSONArray("attachmentIds").getString(0))
    assertEquals(first.id, retry.getString("requestId"))
    assertFalse(retry.has("expectedTurnId"))
    assertEquals(6L, next.getLong("expectedSourceVersion"))
    assertNotEquals(retry.getString("requestId"), next.getString("requestId"))
  }

  @Test fun ordinaryAndSteeredSendsDoNotCarryPanelFields() {
    val ordinary = HomeComposer.Request.new(draft, null, null).submission()
    val steer = HomeComposer.Request.new(draft, "active-turn", null).submission()

    assertEquals("sendMessage", ordinary.getString("action"))
    assertFalse(ordinary.has("panelId"))
    assertFalse(ordinary.has("expectedSourceVersion"))
    assertEquals("steer", steer.getString("action"))
    assertEquals("active-turn", steer.getString("expectedTurnId"))
    assertFalse(steer.has("panelId"))
    assertFalse(steer.has("expectedSourceVersion"))
  }
}
