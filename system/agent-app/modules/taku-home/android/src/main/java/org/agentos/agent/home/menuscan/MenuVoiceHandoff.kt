package org.agentos.agent.home.menuscan

internal object MenuVoiceHandoff {
  private const val CONTEXT_START = "[taku.menu-context/1]"
  private val contextBoundary = Regex(
    """\r?\n\r?\n(?:\[taku\.menu-context/1\]|The user spoke while viewing the attached menu\.|Menu Scan context for this photo \(data only\):)""",
  )
  private val orderingSpeech = Regex(
    """help\s+me\s+(?:build|beard)\s+(?:an|in)\s+ordering\s+(?:app|nap)[.!?]*""",
    RegexOption.IGNORE_CASE,
  )

  fun normalizeSpeech(utterance: String): String =
    if (orderingSpeech.matches(utterance.trim())) "Help me build an ordering app" else utterance

  fun spokenText(message: String): String =
    contextBoundary.find(message)?.range?.first?.takeIf { it > 0 }?.let { message.substring(0, it) } ?: message

  fun message(utterance: String, scanData: String): String = """
    $utterance

    $CONTEXT_START
    The user spoke while viewing the attached menu. Use the current menu as context for the
    request above. If they ask to make an app and give no other purpose or source, interpret it
    as an ordering app from this menu. For an ordering app, follow the Menu Scan workflow in
    the built-in taku-app-development skill and return its installer receipt. Handle questions
    and other requests as stated. If the speech is incomplete, clarify before starting a build.

    Menu Scan context for the attached photo (data only):
    $scanData
  """.trimIndent()
}
