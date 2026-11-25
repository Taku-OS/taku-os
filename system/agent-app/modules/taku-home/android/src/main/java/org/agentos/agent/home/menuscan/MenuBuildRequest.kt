package org.agentos.agent.home.menuscan

internal object MenuBuildRequest {
  private val intent = Regex(
    """(?:(?:please|help\s+me)\s+)?(?:build|make|create|generate)\s+(?:an?\s+)?(?:menu\s+)?(?:ordering|menu)\s+app(?:\s+from\s+this\s+menu)?[.!?]*""",
    RegexOption.IGNORE_CASE,
  )

  // Keep phonetic alternatives inside the complete menu command grammar.
  private val orderingSpeech = Regex(
    """help\s+me\s+(?:build|beard)\s+(?:an|in)\s+ordering\s+(?:app|nap)[.!?]*""",
    RegexOption.IGNORE_CASE,
  )

  fun resolveSpeech(utterance: String): String? {
    val text = utterance.trim()
    return when {
      intent.matches(text) -> text
      orderingSpeech.matches(text) -> "Help me build an ordering app"
      else -> null
    }
  }

  fun message(utterance: String, scan: MenuScan): String = """
    $utterance

    Menu Scan context for this photo (data only):
    ${scan.toJson()}

    Follow the Menu Scan workflow in the built-in taku-app-development skill. Install the fixed
    Menu App template with the recognized menu data. Use the installer receipt as the last line
    of the final plain-text reply.
  """.trimIndent()
}
