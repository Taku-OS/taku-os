package org.agentos.agent.home.feed

import org.json.JSONArray
import org.json.JSONObject

/** Thrown for any contract violation; [code] is returned verbatim to Agent callers. */
class FeedException(val code: String, message: String) : Exception(message)

enum class CardSpan { FULL, HALF }
/** card: glass Surface/900, radius 24, padding 12; control: Surface/600 track; media: no glass, no padding (the body draws a photo). */
enum class CardSurface { CARD, CONTROL, MEDIA }

sealed interface PinIcon {
  data class Image(val src: String, val circle: Boolean) : PinIcon
  data class Pixel(val glyph: String) : PinIcon
}

data class PinTrailing(val icon: String, val actionId: String)

data class Pin(
  val label: String,
  val detail: String?,
  val value: String?,
  val icon: PinIcon,
  val trailing: PinTrailing?,
)

sealed interface Intent {
  data class AskTaku(val prompt: String) : Intent
  data class OpenApp(val packageName: String, val uri: String?) : Intent
  data class OpenUrl(val url: String) : Intent
  /** Opens WhatsApp's share UI for the user to choose a recipient and confirm. */
  data class ShareText(val packageName: String, val text: String) : Intent {
    init {
      if (packageName != PACKAGE) FeedCard.fail("INVALID_CARD", "share-text package must be $PACKAGE")
      if (text.isBlank() || text.length > MAX_TEXT) {
        FeedCard.fail("INVALID_CARD", "share-text text must contain 1 to $MAX_TEXT characters and cannot be blank")
      }
    }

    companion object {
      const val PACKAGE = "com.whatsapp"
      const val MAX_TEXT = 16_000
    }
  }
  data object ConfirmDiningShare : Intent {
    const val ID = "confirm-dining-share"
  }
  data object Dismiss : Intent
  data object None : Intent
}

data class CardAction(val id: String, val label: String, val intent: Intent)

/**
 * One feed card (`taku.feed-card/1`, see CONTRACT.md). The validated fields drive the
 * native shell; [json] is the canonical form handed to React Native and to Agents.
 */
data class FeedCard(
  val id: String,
  val sourceId: String,
  val app: String,
  val packageName: String?,
  val span: CardSpan,
  val surface: CardSurface,
  val rank: Double,
  val createdAt: Long,
  val expiresAt: Long?,
  val pinnedByDefault: Boolean,
  val summary: String,
  val pin: Pin,
  val actions: Map<String, CardAction>,
  val json: JSONObject,
) {
  fun expired(now: Long) = expiresAt != null && expiresAt <= now

  companion object {
    const val SCHEMA = "taku.feed-card/1"
    private val ID = Regex("^[A-Za-z0-9._:-]{1,96}$")
    private val DINING_RESERVATION = Regex("dining\\.reservation\\.(?:[0-9a-f]{24}|[0-9a-f]{40})")
    private val SOURCES = setOf("sample", "agent", "recommendation", "system")
    private const val MAX_BYTES = 256 * 1024
    private val GENERATION_NUMBERS = arrayOf("rank", "createdAt", "expiresAt")

    fun isValidId(id: String) = ID.matches(id)

    /** Parses and validates; the returned card's [json] is a defensive copy. */
    fun parse(input: JSONObject, forcedSource: String? = null): FeedCard {
      val raw = JSONObject(input.toString())
      if (raw.toString().length > MAX_BYTES) fail("CARD_TOO_LARGE", "card exceeds $MAX_BYTES bytes")
      if (raw.optString("schema") != SCHEMA) fail("INVALID_CARD", "schema must be $SCHEMA")
      val id = raw.optString("id")
      if (!ID.matches(id)) fail("INVALID_CARD", "id must match ${ID.pattern}")
      val source = raw.optJSONObject("source") ?: fail("INVALID_CARD", "source is required")
      if (forcedSource != null) source.put("id", forcedSource)
      val sourceId = source.optString("id")
      if (sourceId !in SOURCES) fail("INVALID_CARD", "source.id must be one of $SOURCES")
      val app = source.optString("app").ifBlank { fail("INVALID_CARD", "source.app is required") }
      source.optString("icon").takeIf { it.isNotEmpty() }?.let(::checkAsset)
      val layout = raw.optJSONObject("layout") ?: JSONObject().also { raw.put("layout", it) }
      val span = when (layout.optString("span", "full")) {
        "full" -> CardSpan.FULL; "half" -> CardSpan.HALF
        else -> fail("INVALID_CARD", "layout.span must be full or half")
      }
      val surface = when (layout.optString("surface", "card")) {
        "card" -> CardSurface.CARD; "control" -> CardSurface.CONTROL; "media" -> CardSurface.MEDIA
        else -> fail("INVALID_CARD", "layout.surface must be card, control or media")
      }
      val ui = raw.optJSONObject("ui") ?: fail("INVALID_CARD", "ui is required")
      if (ui.optString("catalog") != "taku-feed-1") fail("INVALID_CARD", "ui.catalog must be taku-feed-1")
      val spec = ui.optJSONObject("spec") ?: fail("INVALID_CARD", "ui.spec is required")
      val elements = spec.optJSONObject("elements") ?: fail("INVALID_CARD", "ui.spec.elements is required")
      val root = spec.optString("root")
      if (!elements.has(root)) fail("INVALID_CARD", "ui.spec.root must name an element")
      val actionJson = raw.optJSONObject("actions") ?: JSONObject()
      val nativeDining = sourceId == "agent" && source.optString("package") == "com.opentable" &&
        DINING_RESERVATION.matches(id) && root == "card" &&
        elements.optJSONObject(root)?.optString("type") == "Column" &&
        elements.optJSONObject(root)?.optJSONArray("children") != null &&
        elements.optJSONObject("share")?.optJSONObject("on")?.optJSONObject("press")
          ?.optJSONObject("params")?.optString("actionId") == "share" &&
        actionJson.optJSONObject("share")?.optJSONObject("intent")?.optString("type") == "share-text"
      if (nativeDining) addDiningShareConfirmation(actionJson, elements, root)
      val actions = parseActions(actionJson, nativeDining)
      val pin = parsePin(raw.optJSONObject("pin") ?: fail("INVALID_CARD", "pin is required"), actions)
      val expires = if (raw.isNull("expiresAt") || !raw.has("expiresAt")) null else raw.getLong("expiresAt")
      return FeedCard(
        id = id, sourceId = sourceId, app = app, packageName = source.optString("package").ifBlank { null },
        span = span, surface = surface, rank = raw.optDouble("rank", 0.0),
        createdAt = raw.optLong("createdAt", 0L), expiresAt = expires,
        pinnedByDefault = raw.optBoolean("pinned", false),
        summary = raw.optString("summary").ifBlank { fail("INVALID_CARD", "summary is required") },
        pin = pin, actions = actions, json = raw,
      )
    }

    fun parseGenerated(input: JSONObject): FeedCard {
      fun objectAt(parent: JSONObject, key: String, required: Boolean = true): JSONObject? {
        if (!required && (!parent.has(key) || parent.isNull(key))) return null
        return parent.opt(key) as? JSONObject ?: fail("INVALID_CARD", "$key must be an object")
      }
      fun stringAt(parent: JSONObject, key: String, required: Boolean = false) {
        if (!required && parent.isNull(key)) return
        if ((required || parent.has(key)) && parent.opt(key) !is String) fail("INVALID_CARD", "$key must be a string")
      }
      stringAt(input, "schema", true); stringAt(input, "id", true); stringAt(input, "summary", true)
      val source = objectAt(input, "source")!!
      stringAt(source, "id", true); stringAt(source, "app", true)
      stringAt(source, "package"); stringAt(source, "icon")
      objectAt(input, "layout", false)?.let { stringAt(it, "span"); stringAt(it, "surface") }
      if (input.has("pinned") && input.opt("pinned") !is Boolean) fail("INVALID_CARD", "pinned must be a boolean")
      for (key in GENERATION_NUMBERS) {
        if (!input.has(key) || key == "expiresAt" && input.isNull(key)) continue
        val value = input.opt(key) as? Number ?: fail("INVALID_CARD", "$key must be a number")
        val number = value.toDouble()
        if (!number.isFinite() || key != "rank" && (number % 1.0 != 0.0 || number < 0 || number >= Long.MAX_VALUE.toDouble())) {
          fail("INVALID_CARD", "$key is outside its numeric range")
        }
      }
      val ui = objectAt(input, "ui")!!
      stringAt(ui, "catalog", true); objectAt(ui, "spec"); objectAt(ui, "state", false)
      val pin = objectAt(input, "pin")!!
      stringAt(pin, "label"); stringAt(pin, "detail"); stringAt(pin, "value")
      val icon = objectAt(pin, "icon")!!
      stringAt(icon, "kind", true)
      stringAt(icon, "glyph"); stringAt(icon, "src"); stringAt(icon, "shape")
      objectAt(pin, "trailing", false)?.let { stringAt(it, "actionId", true); stringAt(it, "icon") }
      objectAt(input, "actions", false)?.let { actions ->
        for (key in actions.keys()) {
          val action = objectAt(actions, key)!!
          stringAt(action, "label")
          val intent = objectAt(action, "intent")!!
          stringAt(intent, "type", true)
          stringAt(intent, "prompt"); stringAt(intent, "package"); stringAt(intent, "uri"); stringAt(intent, "url")
        }
      }
      if (DINING_RESERVATION.matches(input.optString("id"))) fail("INVALID_CARD", "reserved dining receipt id")
      return parse(input, forcedSource = "agent")
    }

    private fun parsePin(o: JSONObject, actions: Map<String, CardAction>): Pin {
      val value = if (o.isNull("value")) null else o.optString("value").ifBlank { null }
      if (value != null && value.length > 12) fail("INVALID_CARD", "pin.value must be at most 12 characters")
      val label = o.optString("label")
      if (label.isBlank() && value == null) fail("INVALID_CARD", "pin.label is required unless pin.value is set")
      if (label.length > 24) fail("INVALID_CARD", "pin.label must be at most 24 characters")
      val detail = if (o.isNull("detail")) null else o.optString("detail").ifBlank { null }
      if (detail != null && detail.length > 32) fail("INVALID_CARD", "pin.detail must be at most 32 characters")
      val iconJson = o.optJSONObject("icon") ?: fail("INVALID_CARD", "pin.icon is required")
      val icon = when (iconJson.optString("kind")) {
        "image" -> PinIcon.Image(checkAsset(iconJson.optString("src")), iconJson.optString("shape", "circle") == "circle")
        "pixel" -> PinIcon.Pixel(iconJson.optString("glyph").ifBlank { fail("INVALID_CARD", "pin.icon.glyph is required") })
        else -> fail("INVALID_CARD", "pin.icon.kind must be image or pixel")
      }
      val trailing = o.optJSONObject("trailing")?.let {
        val actionId = it.optString("actionId")
        if (actionId !in actions) fail("INVALID_CARD", "pin.trailing.actionId must name an action")
        PinTrailing(it.optString("icon", "play"), actionId)
      }
      return Pin(label, detail, value, icon, trailing)
    }

    // Decorate existing stored receipts when Home reads them, preserving remove/pin state.
    private fun addDiningShareConfirmation(actions: JSONObject, elements: JSONObject, root: String) {
      val children = elements.getJSONObject(root).getJSONArray("children")
      val actionId = Intent.ConfirmDiningShare.ID
      val label = "I sent it to the original group"
      actions.put(actionId, JSONObject().put("label", label).put("intent", JSONObject().put("type", actionId)))
      elements.put(actionId, JSONObject().put("type", "Button")
        .put("props", JSONObject().put("label", label).put("variant", "secondary"))
        .put("children", JSONArray())
        .put("on", JSONObject().put("press", JSONObject().put("action", "dispatch")
          .put("params", JSONObject().put("actionId", actionId)))))
      if ((0 until children.length()).none { children.optString(it) == actionId }) children.put(actionId)
    }

    private fun parseActions(o: JSONObject, nativeDining: Boolean): Map<String, CardAction> = buildMap {
      for (key in o.keys()) {
        val a = o.optJSONObject(key) ?: fail("INVALID_CARD", "actions.$key must be an object")
        val i = a.optJSONObject("intent") ?: fail("INVALID_CARD", "actions.$key.intent is required")
        val intent = when (i.optString("type")) {
          "ask-taku" -> Intent.AskTaku(i.optString("prompt").ifBlank { fail("INVALID_CARD", "actions.$key.intent.prompt is required") })
          "open-app" -> Intent.OpenApp(i.optString("package").ifBlank { fail("INVALID_CARD", "actions.$key.intent.package is required") }, i.optString("uri").ifBlank { null })
          "open-url" -> Intent.OpenUrl(i.optString("url").also { if (!it.startsWith("https://")) fail("INVALID_CARD", "actions.$key.intent.url must be https") })
          "share-text" -> Intent.ShareText(
            i.opt("package") as? String ?: fail("INVALID_CARD", "actions.$key.intent.package must be a string"),
            i.opt("text") as? String ?: fail("INVALID_CARD", "actions.$key.intent.text must be a string"),
          )
          Intent.ConfirmDiningShare.ID -> {
            if (!nativeDining || key != Intent.ConfirmDiningShare.ID || i.length() != 1) {
              fail("INVALID_CARD", "confirm-dining-share requires a native dining reservation card")
            }
            Intent.ConfirmDiningShare
          }
          "dismiss" -> Intent.Dismiss
          "none" -> Intent.None
          else -> fail("INVALID_CARD", "actions.$key.intent.type is not supported")
        }
        put(key, CardAction(key, a.optString("label", key), intent))
      }
    }

    fun checkAsset(ref: String): String {
      val ok = (ref.startsWith("asset:feed/") && !ref.contains("..")) || ref.startsWith("https://")
      if (!ok) fail("INVALID_CARD", "asset reference must be asset:feed/… or https://…")
      return ref
    }

    fun fail(code: String, message: String): Nothing = throw FeedException(code, message)
  }
}

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
