package org.agentos.agent.home

import org.agentos.agent.home.assistant.ThreadProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Issue #32 G1: the island shows readable text, never Markdown source such as table pipes. */
class IslandTextTest {
  @Test fun tablesBecomeReadableRows() {
    val text = ThreadProjection.plainText("""
      ## 建议
      - 多喝水
      > 注意防晒

      | 设置 | 建议 |
      | --- | :---: |
      | 亮度 | 自动 |
    """.trimIndent())
    assertEquals("建议\n• 多喝水\n注意防晒\n\n设置 · 建议\n亮度 · 自动", text)
    assertFalse(text.contains("|"))
  }

  @Test fun codeAndInlineMarkupStayAsBefore() {
    assertEquals("Use a **bold**".let { ThreadProjection.plainText(it) }, "Use a bold")
    assertEquals("| not a table", ThreadProjection.plainText("| not a table"))
  }

}
