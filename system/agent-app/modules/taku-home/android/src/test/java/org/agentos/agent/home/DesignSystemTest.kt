package org.agentos.agent.home

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Screens take colours from TakuTheme roles; only the design package defines colour values. */
class DesignSystemTest {
  private val screens = File("src/main/java/org/agentos/agent/home/ui")
  private val literal = Regex("""Color\(\s*0x|Color\(\s*[0-9.]+f|Color\.(White|Black|Red|Green|Blue|Gray|DarkGray|LightGray)\b""")

  @Test fun screensDoNotHardcodeColours() {
    val offenders = screens.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
      file.readLines().mapIndexedNotNull { i, line -> if (literal.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null }
    }.toList()
    assertTrue("Use TakuTheme.colors roles instead:\n" + offenders.joinToString("\n"), offenders.isEmpty())
  }

  @Test fun screensDoNotUseTheRawPalette() {
    val offenders = screens.walkTopDown().filter { it.extension == "kt" && it.readText().contains("TakuPalette") }.map { it.name }.toList()
    assertTrue("TakuPalette is private to the design package: $offenders", offenders.isEmpty())
  }
}
