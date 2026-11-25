package org.agentos.agent.home.menuscan

import java.util.Locale

internal object MenuVoiceHandoff {
  private const val CONTEXT_START = "[taku.menu-context/1]"
  private val contextBoundary = Regex(
    """\r?\n\r?\n(?:\[taku\.menu-context/1\]|The user spoke while viewing the attached menu\.|Menu Scan context for this photo \(data only\):)""",
  )
  private val uppercase = Regex("\\p{Lu}")
  private val lowercase = Regex("\\p{Ll}")
  private val sentenceStart = Regex("""(^|[.!?]\s+)([^\p{L}\p{N}]*)(\p{Ll})""")
  private val pronoun = Regex("""(^|[^\p{L}\p{N}_])i(?![\p{L}\p{N}_])""")
  private val orderingSpeech = Regex(
    """help\s+me\s+(build|beard)\s+(an|in)\s+ordering\s+(app|nap)[.!?]*""", RegexOption.IGNORE_CASE,
  )

  private fun boundary(message: String): Int? =
    contextBoundary.find(message)?.range?.first?.takeIf { it > 0 }

  fun spokenText(message: String): String {
    val end = boundary(message) ?: return message
    val utterance = message.substring(0, end)
    if (!uppercase.containsMatchIn(utterance) || lowercase.containsMatchIn(utterance)) return utterance
    val sentenceCase = sentenceStart.replace(utterance.lowercase(Locale.ROOT)) { match ->
      match.groupValues[1] + match.groupValues[2] + match.groupValues[3].uppercase(Locale.ROOT)
    }
    return pronoun.replace(sentenceCase, "$1I")
  }

  private fun orderingHint(utterance: String): String {
    val (build, an, app) = orderingSpeech.matchEntire(utterance)?.destructured ?: return ""
    if (build.equals("build", true) && an.equals("an", true) && app.equals("app", true)) return ""
    return "\n    Interpret this known transcription as: Help me build an ordering app."
  }

  fun message(utterance: String, scanData: String): String = """
    $utterance

    $CONTEXT_START
    The user spoke while viewing the attached menu. Use the current menu as context for the
    request above. If they ask to make an app and give no other purpose or source, interpret it
    as an ordering app from this menu. For an ordering app, follow the Menu Scan workflow in
    the built-in taku-app-development skill and return its installer receipt. Handle questions
    and other requests as stated. If the speech is incomplete, clarify before starting a build.${orderingHint(utterance)}

    Menu Scan context for the attached photo (data only):
    $scanData
  """.trimIndent()
}
