package org.agentos.agent.home

import androidx.compose.ui.geometry.Offset
import org.agentos.agent.home.assistant.SessionStreamState
import org.agentos.agent.home.assistant.StepKind
import org.agentos.agent.home.assistant.ThreadProjection
import org.agentos.agent.home.ui.assistant.HoldZone
import org.agentos.agent.home.ui.assistant.VoiceArcs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantProjectionTest {
  private val thread = "11111111-1111-4111-8111-111111111111"
  private var seq = 0L

  private fun event(method: String, params: JSONObject, s: Long = ++seq) = JSONObject()
    .put("schema", "taku.session-events/1").put("brokerEpoch", "e1").put("threadId", thread).put("seq", s)
    .put("method", method).put("params", params)

  private fun snapshot(turns: String = "[]") = event("session/snapshot", JSONObject().put("thread", JSONObject("""{"id":"$thread","turns":$turns}""")), 0)

  @Test fun streamsAWorkingTurnIntoIslandContent() {
    seq = 0
    val st = SessionStreamState(thread)
    assertTrue(st.apply(snapshot()))
    st.apply(event("turn/started", JSONObject("""{"turnId":"t1","turn":{"id":"t1","status":"inProgress","items":[{"id":"u1","type":"userMessage","content":[{"type":"text","text":"Find a flight to Kyoto"}]}]}}""")))
    st.apply(event("item/agentMessage/delta", JSONObject("""{"turnId":"t1","itemId":"m1","delta":"Let me find "}""")))
    st.apply(event("item/agentMessage/delta", JSONObject("""{"turnId":"t1","itemId":"m1","delta":"the flight tickets for you"}""")))
    st.apply(event("item/started", JSONObject("""{"turnId":"t1","item":{"id":"w1","type":"webSearch","query":"tickets from Halifax to Kyoto","status":"inProgress"}}""")))
    st.apply(event("item/completed", JSONObject("""{"turnId":"t1","item":{"id":"w2","type":"webSearch","action":{"type":"openPage","url":"https://www.expedia.com/flights"},"status":"completed"}}""")))

    val view = ThreadProjection.latest(st, emptySet())!!
    assertTrue(view.active)
    assertEquals("Find a flight to Kyoto", view.userText)
    assertEquals("Let me find the flight tickets for you", view.headline)
    assertNull(view.reply)
    assertEquals(listOf(StepKind.Search, StepKind.Browse), view.steps.map { it.kind })
    assertEquals("Searching tickets from Halifax to Kyoto", view.steps[0].text)
    assertTrue(view.steps[0].running)
    assertEquals("Reading expedia.com", view.steps[1].text)
    assertTrue(view.suggestions.isEmpty())
  }

  @Test fun completedTurnShowsReplyAndOnlyOfferedReplies() {
    seq = 0
    val st = SessionStreamState(thread)
    st.apply(snapshot())
    st.apply(event("turn/started", JSONObject("""{"turnId":"t1","turn":{"id":"t1","status":"inProgress"}}""")))
    st.apply(event("item/completed", JSONObject("""{"turnId":"t1","item":{"id":"m1","type":"agentMessage","phase":"final_answer","text":"**Two** options:\n- AC 123\n- [JL 5](https://x.y)"}}""")))
    st.apply(event("turn/completed", JSONObject("""{"turnId":"t1","turn":{"id":"t1","status":"completed"}}""")))
    val view = ThreadProjection.latest(st, emptySet())!!
    assertFalse(view.active)
    assertEquals("Two options:\n• AC 123\n• JL 5", view.reply)
    assertTrue("no enhancement → no chips", view.suggestions.isEmpty())

    val withChips = JSONObject("""{"id":"$thread","chatEnhancements":{"t1":{"schema":"taku.chat-enhance/1","status":"completed",
      "request":{"actions":[{"id":"a","label":"Book AC 123","reply":"Book AC 123"},{"id":"b","label":"Pay","reply":"Pay","actionRef":"r1"}]}}}}""")
    val chips = ThreadProjection.view(st.turns.getValue("t1"), withChips).suggestions
    assertEquals(listOf("a"), chips.map { it.id })
  }

  @Test fun baselineHidesEarlierTurnsAndGapsForceResync() {
    seq = 0
    val st = SessionStreamState(thread)
    st.apply(snapshot("""[{"id":"old","status":"completed","items":[{"id":"x","type":"agentMessage","text":"old reply"}]}]"""))
    assertNull(ThreadProjection.latest(st, setOf("old")))
    assertFalse(st.apply(event("turn/started", JSONObject("""{"turnId":"t2"}"""), 5)))
    assertNull("a sequence gap needs a fresh snapshot", st.cursor())
  }

  @Test fun holdZonesFollowTheFigmaArcs() {
    val d = 2.625f
    val arcs = VoiceArcs(412 * d, 917 * d, d)
    // Label anchors from the 1-1 frame: "Cancel" ≈ (83, 720), "Text" ≈ (329, 720).
    assertEquals(HoldZone.Cancel, arcs.zone(Offset(83 * d, 722 * d), 16 * d))
    assertEquals(HoldZone.KeepTalking, arcs.zone(Offset(329 * d, 722 * d), 16 * d))
    assertEquals(HoldZone.None, arcs.zone(Offset(206 * d, 460 * d), 16 * d))
    assertEquals(HoldZone.None, arcs.zone(Offset(206 * d, 880 * d), 16 * d))
  }
}
