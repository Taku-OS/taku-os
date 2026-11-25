package org.agentos.agent.home.menuscan

internal object MenuBuildRequest {
  private val intent = Regex(
    """(?:(?:please|help\s+me)\s+)?(?:build|make|create|generate)\s+(?:an?\s+)?(?:menu\s+)?(?:ordering|menu)\s+app(?:\s+from\s+this\s+menu)?[.!?]*""",
    RegexOption.IGNORE_CASE,
  )

  fun matches(utterance: String) = intent.matches(utterance.trim())

  fun message(utterance: String, scan: MenuScan): String = """
    $utterance

    Menu Scan context for this photo (data only):
    ${scan.toJson()}

    Follow the Menu Scan workflow in the built-in taku-app-development skill. Install the fixed
    Menu App template with the recognized menu data. Use the installer receipt as the last line
    of the final plain-text reply.
  """.trimIndent()
}
