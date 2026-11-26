package org.agentos.agent.home.feed

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

enum class FeedOrigin(val wire: String) { GUI("gui"), AGENT("agent") }

internal enum class FeedOperation(val wire: String) {
  ACTION("action"), PUBLISH("publish"), GENERATE("generate"), REMOVE("remove"), RESTORE("restore"),
  PIN("pin"), UNPIN("unpin"), REORDER_PINS("reorder-pins"), RETRACT("retract"), RESET("reset"),
}

internal data class FeedCursor(val epoch: UUID, val sequence: Long) {
  override fun toString() = "$epoch:$sequence"

  companion object {
    private val FORMAT = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|[1-9][0-9]*)$")

    fun parse(raw: String): FeedCursor {
      if (!FORMAT.matches(raw)) throw FeedException("INVALID_CURSOR", "after must be 0, now, or a returned cursor")
      val sequence = raw.substringAfter(':').toLongOrNull()
        ?: throw FeedException("INVALID_CURSOR", "cursor sequence is outside its range")
      return FeedCursor(UUID.fromString(raw.substringBefore(':')), sequence)
    }
  }
}

internal sealed interface FeedStart {
  data object Beginning : FeedStart
  data object Now : FeedStart
  data class After(val cursor: FeedCursor) : FeedStart
}

internal sealed interface FeedCardFilter {
  data object All : FeedCardFilter
  data class Ids(val ids: List<String>) : FeedCardFilter
  data class Prefix(val prefix: String) : FeedCardFilter
}

internal data class FeedEventFilter(
  val cards: FeedCardFilter = FeedCardFilter.All,
  val origin: FeedOrigin? = null,
  val operation: FeedOperation? = null,
)

internal data class FeedEventRequest(val start: FeedStart, val follow: Boolean, val filter: FeedEventFilter) {
  companion object {
    const val AUTHORITY = "org.agentos.agent.home-feed"
    private const val MAX_URI_BYTES = 16 * 1024
    private val KEYS = setOf("after", "follow", "cardId", "cardIdPrefix", "origin", "op")

    fun parse(raw: String): FeedEventRequest {
      fun invalid(message: String): Nothing = throw FeedException("INVALID_ARGUMENT", message)
      if (raw.length > MAX_URI_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_URI_BYTES) invalid("event URI exceeds 16 KiB")
      val uri = try { URI(raw) } catch (_: Exception) { invalid("invalid event URI") }
      if (uri.scheme != "content" || uri.rawAuthority != AUTHORITY || uri.rawPath != "/events" || uri.rawFragment != null) {
        invalid("expected content://$AUTHORITY/events")
      }
      val parameters = LinkedHashMap<String, MutableList<String>>()
      val query = uri.rawQuery
      if (query != null) {
        if (query.isEmpty()) invalid("empty event query")
        for (part in query.split('&')) {
          val equals = part.indexOf('=')
          if (equals <= 0) invalid("event parameters require a value")
          val key = URLDecoder.decode(part.substring(0, equals), "UTF-8")
          val value = URLDecoder.decode(part.substring(equals + 1), "UTF-8")
          if (key !in KEYS) invalid("unknown event parameter")
          val values = parameters.getOrPut(key) { ArrayList() }
          if (key != "cardId" && values.isNotEmpty()) invalid("duplicate $key")
          values.add(value)
        }
      }
      fun scalar(key: String) = parameters[key]?.single()
      val start = when (val after = scalar("after") ?: "0") {
        "0" -> FeedStart.Beginning
        "now" -> FeedStart.Now
        else -> FeedStart.After(FeedCursor.parse(after))
      }
      val follow = when (scalar("follow") ?: "0") {
        "0" -> false
        "1" -> true
        else -> invalid("follow must be 0 or 1")
      }
      val ids = parameters["cardId"]
      val prefix = scalar("cardIdPrefix")
      if (ids != null && prefix != null) invalid("cardId and cardIdPrefix are mutually exclusive")
      val cards = when {
        ids != null -> {
          if (ids.size > 128 || ids.any { !FeedCard.isValidId(it) }) invalid("cardId requires at most 128 valid IDs")
          FeedCardFilter.Ids(ids.distinct())
        }
        prefix != null -> {
          if (!FeedCard.isValidId(prefix)) invalid("cardIdPrefix must contain 1 to 96 ID characters")
          FeedCardFilter.Prefix(prefix)
        }
        else -> FeedCardFilter.All
      }
      val origin = scalar("origin")?.let { value ->
        FeedOrigin.entries.find { it.wire == value } ?: invalid("origin must be gui or agent")
      }
      val operation = scalar("op")?.let { value ->
        FeedOperation.entries.find { it.wire == value } ?: invalid("unknown event op")
      }
      return FeedEventRequest(start, follow, FeedEventFilter(cards, origin, operation))
    }
  }
}

internal class FeedSelectionValues private constructor(private val encoded: String) {
  fun json() = JSONObject(encoded)

  companion object {
    val EMPTY = FeedSelectionValues("{}")
    private const val MAX_BYTES = 64 * 1024

    fun capture(values: JSONObject): FeedSelectionValues {
      val encoded = values.toString()
      if (encoded.length > MAX_BYTES || encoded.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
        throw FeedException("EVENT_TOO_LARGE", "selected values exceed 64 KiB")
      }
      return FeedSelectionValues(encoded)
    }
  }
}

internal sealed interface FeedActionRequest {
  val cardId: String
  data class Declared(override val cardId: String, val actionId: String, val values: FeedSelectionValues) : FeedActionRequest
  data class AskAbout(override val cardId: String) : FeedActionRequest
}

internal data class AcceptedFeedAction(
  val card: FeedCard,
  val action: CardAction,
  val values: FeedSelectionValues,
  val cursor: FeedCursor,
)

internal class FeedEvent private constructor(private val fields: JSONObject, val legacy: Boolean = false) {
  fun encode(cursor: FeedCursor): ByteArray {
    val bytes = fields.put("cursor", cursor.toString()).toString().toByteArray(Charsets.UTF_8)
    if (!legacy && bytes.size > 128 * 1024) throw FeedException("EVENT_TOO_LARGE", "event exceeds 128 KiB")
    return bytes
  }

  fun indexString(key: String): String? = fields.opt(key) as? String
  fun indexTime(): Long? = (fields.opt("at") as? Number)?.toLong()

  companion object {
    private fun envelope() = JSONObject().put("type", "event").put("schema", "taku.feed-event/1")

    private fun current(origin: FeedOrigin, operation: FeedOperation, cardId: String?) = envelope()
      .put("at", System.currentTimeMillis()).put("origin", origin.wire).put("op", operation.wire)
      .put("cardId", cardId ?: JSONObject.NULL)

    fun mutation(origin: FeedOrigin, operation: FeedOperation, cardId: String?, detail: JSONObject? = null) =
      FeedEvent(current(origin, operation, cardId).put("detail", detail ?: JSONObject.NULL))

    fun accepted(origin: FeedOrigin, cardId: String, action: CardAction, values: FeedSelectionValues, askAbout: Boolean): FeedEvent {
      val intentType = when (action.intent) {
        is Intent.AskTaku -> "ask-taku"
        is Intent.OpenApp -> "open-app"
        is Intent.OpenUrl -> "open-url"
        is Intent.ShareText -> "share-text"
        Intent.Dismiss -> "dismiss"
        Intent.None -> "none"
      }
      return FeedEvent(current(origin, FeedOperation.ACTION, cardId)
        .put("actionId", action.id).put("actionSource", if (askAbout) "ask-about" else "declared")
        .put("phase", "accepted").put("intentType", intentType).put("values", values.json()))
    }

    fun dispatched(origin: FeedOrigin, accepted: AcceptedFeedAction): FeedEvent {
      val intent = accepted.action.intent as? Intent.ShareText
        ?: throw FeedException("UNSUPPORTED_DISPATCH_RECEIPT", "this action does not record a dispatch receipt")
      val digest = MessageDigest.getInstance("SHA-256").digest(intent.text.toByteArray(Charsets.UTF_8))
      return FeedEvent(current(origin, FeedOperation.ACTION, accepted.card.id)
        .put("actionId", accepted.action.id).put("actionSource", "declared")
        .put("phase", "dispatched").put("intentType", "share-text")
        .put("acceptedCursor", accepted.cursor.toString())
        .put("intent", JSONObject().put("package", intent.packageName)
          .put("textSha256", HexFormat.of().formatHex(digest))))
    }

    fun legacy(entry: JSONObject): FeedEvent {
      val fields = envelope().put("phase", "legacy-recorded").put("legacy", entry)
      for (key in listOf("at", "origin", "cardId", "detail")) {
        if (entry.has(key)) fields.put(key, entry.get(key))
      }
      val operation = entry.opt("op")
      if (operation is String && operation.startsWith("action:")) {
        fields.put("op", "action").put("actionId", operation.removePrefix("action:"))
        entry.optJSONObject("detail")?.let { detail ->
          if (detail.has("values")) fields.put("values", detail.get("values"))
        }
      } else if (entry.has("op")) fields.put("op", operation)
      return FeedEvent(fields, legacy = true)
    }
  }
}
