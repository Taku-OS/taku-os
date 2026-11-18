package org.agentos.agent.home.island

import java.security.MessageDigest

/** Bounded opaque presentation identity; no task state or authority is stored in this token. */
object IslandIdentity {
  fun token(thread: String?, activity: String?): String {
    val first = thread.orEmpty()
    val second = activity.orEmpty()
    val bytes = "${first.length}:$first${second.length}:$second".toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
  }
}
