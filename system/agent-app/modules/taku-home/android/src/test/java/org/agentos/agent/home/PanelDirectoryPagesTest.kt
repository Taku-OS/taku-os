package org.agentos.agent.home

import org.agentos.agent.home.panels.PanelCall
import org.agentos.agent.home.panels.PanelDirectoryPages
import org.agentos.agent.home.panels.PanelReadException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun entry(panelId: String, position: Long) =
  JSONObject().put("panelId", panelId).put("title", panelId.uppercase()).put("position", position)

private fun page(items: List<JSONObject>, next: String?, revision: Long): JSONObject {
  val array = JSONArray()
  items.forEach { array.put(it) }
  return JSONObject().put("items", array).put("listRevision", revision).put("nextCursor", next ?: JSONObject.NULL)
}

class PanelDirectoryPagesTest {
  private class Fake : PanelCall {
    val listArgs = ArrayList<JSONObject>()
    var describe: () -> JSONObject = { JSONObject().put("version", PanelDirectoryPages.CONTRACT) }
    var list: (JSONObject) -> JSONObject = { page(emptyList(), null, 1) }

    override fun call(method: String, arg: JSONObject): JSONObject = when (method) {
      "panels/describe" -> describe()
      "panels/list" -> list(arg).also { listArgs += JSONObject(arg.toString()) }
      else -> throw PanelReadException("UNKNOWN_METHOD", method)
    }
  }

  @Test fun readsEveryPageAndOrdersTheDirectoryByPosition() {
    val fake = Fake()
    fake.list = { arg ->
      when (arg.optString("cursor")) {
        "" -> page(listOf(entry("second", 2)), "cursor-2", 7)
        "cursor-2" -> page(listOf(entry("first", 1)), null, 7)
        else -> throw AssertionError("unexpected cursor ${arg.optString("cursor")}")
      }
    }

    val result = PanelDirectoryPages.read(fake)

    assertEquals(listOf("first", "second"), result.entries.map { it.panelId })
    assertEquals(7L, result.listRevision)
    assertEquals(2, fake.listArgs.size)
    assertEquals("cursor-2", fake.listArgs[1].getString("cursor"))
    assertEquals(100, fake.listArgs[0].getInt("limit"))
  }

  @Test fun aMovedRevisionRestartsTheWalkFromTheFirstPage() {
    val fake = Fake()
    var staleCursorSeen = 0
    fake.list = { arg ->
      if (arg.has("cursor")) {
        staleCursorSeen++
        throw PanelReadException(PanelDirectoryPages.LIST_CHANGED, "revision moved")
      }
      page(listOf(entry("a", 1), entry("b", 2)), if (staleCursorSeen == 0) "stale" else null, 9)
    }

    val result = PanelDirectoryPages.read(fake)

    assertEquals(listOf("a", "b"), result.entries.map { it.panelId })
    assertEquals(9L, result.listRevision)
    assertEquals(1, staleCursorSeen)
    assertTrue("the restart begins at the first page", !fake.listArgs.last().has("cursor"))
  }

  @Test fun aForeignContractIsUpdateRequiredAndReadsNoPages() {
    val fake = Fake()
    fake.describe = { JSONObject().put("version", "taku.panels/2") }
    try {
      PanelDirectoryPages.read(fake)
      fail("expected UPDATE_REQUIRED")
    } catch (e: PanelReadException) {
      assertEquals(PanelDirectoryPages.UPDATE_REQUIRED, e.code)
    }
    assertTrue(fake.listArgs.isEmpty())
  }

  @Test fun anAssistantWithoutTheContractIsUpdateRequired() {
    val fake = Fake()
    fake.describe = { throw PanelReadException("UNKNOWN_METHOD", "Unknown open-access method: panels/describe") }
    try {
      PanelDirectoryPages.read(fake)
      fail("expected UPDATE_REQUIRED")
    } catch (e: PanelReadException) {
      assertEquals(PanelDirectoryPages.UPDATE_REQUIRED, e.code)
    }
  }

  @Test fun aDirectoryThatKeepsMovingFailsInsteadOfLooping() {
    val fake = Fake()
    var cursorPages = 0
    fake.list = { arg ->
      if (arg.has("cursor")) {
        cursorPages++
        throw PanelReadException(PanelDirectoryPages.LIST_CHANGED, "revision moved")
      }
      page(listOf(entry("a", 1)), "moving", 1)
    }
    try {
      PanelDirectoryPages.read(fake)
      fail("expected LIST_CHANGED after the restart budget")
    } catch (e: PanelReadException) {
      assertEquals(PanelDirectoryPages.LIST_CHANGED, e.code)
    }
    assertTrue("restarts are bounded", cursorPages <= 4)
  }
}
