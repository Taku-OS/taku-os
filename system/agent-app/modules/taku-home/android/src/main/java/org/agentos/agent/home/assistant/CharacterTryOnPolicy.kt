package org.agentos.agent.home.assistant

import java.text.Normalizer
import java.util.Locale
import org.taku.cosplay.api.CosplayContract

/** Admission uses a real system gesture and a complete utterance, never text found in a frame. */
internal object CharacterTryOnPolicy {
  private val sources = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
  private val excluded = setOf("page", "conversationId", "jevTestToken", "manualScreen", "nativeControlsOnly")
  private val commands = setOf(
    "帮我变成这个角色", "把我变成这个角色", "变成这个角色",
    "帮我换成这个角色", "把我换成这个角色", "换成这个角色",
    "帮我变成屏幕里的角色", "把我变成屏幕里的角色",
    "帮我换成视频里的角色", "把我换成视频里的角色",
    "makemethischaracter", "helpmebecomethischaracter", "cosplaythischaracter",
    "turnmeintothischaracter", "changemeintothischaracter",
    "iwannacosplayashim", "iwannacosplayasher", "iwanttocosplayashim", "iwanttocosplayasher",
  )

  fun source(foreground: String?): String? = foreground?.takeIf { it.length <= 256 }
    ?.substringBefore('/')?.takeIf { it in sources }

  fun acceptsEntry(trigger: String?, gestureId: String?, keys: Set<String>, systemGesture: Boolean): Boolean =
    systemGesture && trigger == "nav_handle_long_press" &&
      gestureId?.matches(Regex("[A-Za-z0-9_-]{8,128}")) == true && keys.none { it in excluded }

  fun command(text: String): Boolean {
    if (text.length > 160) return false
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
      .replace(Regex("\\s+"), "")
    val command = if (normalized.lastOrNull() in setOf('.', '!', '。')) normalized.dropLast(1) else normalized
    return command in commands
  }

  fun fresh(invoked: Long, captured: Long, now: Long): Boolean = invoked >= 0 && captured >= invoked &&
    now >= captured && now - invoked <= CosplayContract.MAX_INVOCATION_AGE_MS &&
    now - captured <= CosplayContract.MAX_CAPTURE_AGE_MS

  fun ticketValid(ticket: String, expires: Long, now: Long): Boolean = ticket.matches(Regex("[a-f0-9]{32}")) &&
    now >= 0 && expires > now && expires - now <= CosplayContract.TICKET_TTL_MS

  fun boundedJpeg(jpeg: ByteArray): Boolean = jpeg.size in 4..CosplayContract.MAX_JPEG_BYTES &&
    jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte() &&
    jpeg[jpeg.lastIndex - 1] == 0xff.toByte() && jpeg.last() == 0xd9.toByte()
}

enum class CharacterTryOnPhase { Checking, Confirming, Starting, Failed }

/** Public projection deliberately excludes the screenshot and the one-use capability ticket. */
data class CharacterTryOnState(val requestId: String, val phase: CharacterTryOnPhase, val message: String? = null)
