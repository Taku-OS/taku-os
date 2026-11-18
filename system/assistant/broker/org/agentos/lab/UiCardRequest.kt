package org.agentos.lab

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.HashSet

/** The shared V2 presentation contract. Data never grants execution authority. */
class UiCardRequest {
    companion object {
        private val SCHEMA_KEYWORDS = setOf("$" + "schema", "type", "properties", "required", "additionalProperties", "anyOf", "const", "enum",
            "items", "minItems", "maxItems", "minLength", "maxLength", "pattern", "minimum", "maximum", "exclusiveMinimum")
        @JvmField
        val KINDS: Set<String> = java.util.Set.of("metric", "collection", "choices", "timeline", "route", "media", "progress", "input", "text", "sources", "overlay")
        const val ROLES = " Use generic roles metric, collection, choices, timeline, route, media, progress, input, text, sources, overlay." +
            " Every block, item and action needs a semantic stable lowercase a-z0-9-hyphen ID (1-64 characters), never an index or display label." +
            " Preserve IDs when the same entity changes or moves. Values are tagged text/number/money/boolean/instant/duration objects;" +
            " null means unknown. Money uses an integer minor-unit string plus currency and scale; instant uses an ISO offset timestamp plus timeZone; duration uses milliseconds." +
            " config contains only declared optional layout/emphasis/size/group/icon/editor/min/max/step/multi/mediaType/captureSource/language/targetLanguage/recordAudio/imageFilter/filterStrength/analysisPrompt fields. Omit unused optional fields." +
            " Choose size from user intent: full_size when weather is the main request, medium_size when it supports an explanation, small_size when embedded in a travel plan or larger task. Related metric/forecast/time blocks share a stable group ID; keep the same data and source identity across sizes." +
            " Camera style uses imageFilter warm or mono and filterStrength 0..1; composition requests use analysisPrompt (up to 1000 characters). Preserve the existing camera key and block IDs on followups." +
            " Recording and live captions use media blocks with captureSource microphone or playback; native session controls require an actual user start, never invent an active session."

        @JvmStatic
        @Throws(Exception::class)
        fun schema(): JSONObject {
            val item = `object`(JSONObject().put("id", id()).put("label", string(0, 80)).put("value", valueSchema())
                .put("detail", string(0, 200)).put("url", linkSchema()).put("image", linkSchema()).put("icon", string(0, 40)))
            val config = `object`(JSONObject().put("layout", enumeration("list", "horizontal", "table", "grid"))
                .put("emphasis", enumeration("hero", "inline")).put("editor", enumeration("text", "number", "range", "toggle", "time"))
                .put("size", enumeration("full_size", "medium_size", "small_size")).put("group", id()).put("icon", string(0, 40))
                .put("min", type("number")).put("max", type("number")).put("step", type("number"))
                .put("multi", type("boolean")).put("mediaType", enumeration("image", "audio", "video", "camera"))
                .put("captureSource", enumeration("microphone", "playback"))
                .put("language", string(0, 80)).put("targetLanguage", string(0, 80)).put("recordAudio", type("boolean"))
                .put("imageFilter", enumeration("warm", "mono")).put("filterStrength", type("number").put("minimum", 0).put("maximum", 1))
                .put("analysisPrompt", string(0, 1000)), JSONArray())
            val block = `object`(JSONObject().put("id", id())
                .put("kind", type("string").put("enum", JSONArray(KINDS.stream().sorted().toArray())))
                .put("title", string(0, 80)).put("text", string(0, 1000)).put("value", valueSchema())
                .put("image", linkSchema()).put("url", linkSchema()).put("items", array(item, 0, 20)).put("config", config))
            val actionProperties = JSONObject().put("id", id()).put("label", string(1, 40)).put("reply", string(1, 300))
                .put("style", enumeration("primary", "secondary")).put("actionRef", string(1, 200))
            val action = `object`(actionProperties, JSONArray().put("id").put("label").put("reply").put("style"))
            return JSONObject().put("version", type("integer").put("enum", JSONArray().put(2)))
                .put("key", id()).put("mode", enumeration("card", "panel")).put("layout", enumeration("compose", "generate"))
                .put("title", string(0, 80)).put("summary", string(0, 160))
                .put("blocks", array(block, 1, 8)).put("actions", array(action, 0, 32))
        }

        @Throws(Exception::class)
        private fun valueSchema(): JSONObject {
            val variants = JSONArray()
            for (kind in arrayOf("text", "number", "money", "boolean", "instant", "duration")) {
                val scalar = when (kind) {
                    "number" -> type("number")
                    "duration" -> type("number").put("minimum", 0)
                    "boolean" -> type("boolean")
                    "money" -> string(1, 200).put("pattern", "^-?(0|[1-9][0-9]*)$")
                    "instant" -> string(1, 200).put("pattern", "^\\d{4}-\\d{2}-\\d{2}T.*(?:Z|[+-]\\d{2}:\\d{2})$")
                    else -> string(0, 1000)
                }
                val properties = JSONObject().put("type", enumeration(kind))
                    .put("value", JSONObject().put("anyOf", JSONArray().put(scalar).put(type("null"))))
                    .put("unit", string(0, 40)).put("currency", string(3, 3).put("pattern", "^[A-Z]{3}$"))
                    .put("scale", type("integer").put("minimum", 0).put("maximum", 6)).put("timeZone", string(1, 80))
                val required = JSONArray().put("type").put("value")
                if (kind == "money") required.put("currency").put("scale")
                if (kind == "instant") required.put("timeZone")
                variants.put(`object`(properties, required))
            }
            return JSONObject().put("anyOf", variants)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun validate(request: JSONObject?): JSONObject {
            val projected = project(request)
            return JSONObject().put("shown", true).put("key", projected.getString("key"))
        }

        /** Returns a current projection without mutating historical source records. */
        @JvmStatic
        @Throws(Exception::class)
        fun project(request: JSONObject?): JSONObject {
            val normalized = historicalRequest(request)
            validateSchema(normalized, `object`(schema(), JSONArray().put("version").put("key").put("mode")
                .put("title").put("summary").put("blocks").put("actions")))
            val blocks = normalized!!.getJSONArray("blocks")
            val actions = normalized.getJSONArray("actions")
            unique(blocks)
            unique(actions)
            for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                val config = block.getJSONObject("config")
                validateValue(block.getJSONObject("value"))
                val items = block.getJSONArray("items")
                unique(items)
                for (j in 0 until items.length()) validateValue(items.getJSONObject(j).getJSONObject("value"))
                if (block.getString("kind") == "input") {
                    val editor = config.optString("editor", "text")
                    val valueType = block.getJSONObject("value").getString("type")
                    if ((java.util.Set.of("number", "range").contains(editor) && valueType != "number") ||
                        (editor == "toggle" && valueType != "boolean") ||
                        (editor == "time" && valueType != "instant") ||
                        (editor == "text" && valueType != "text")) {
                        throw IllegalArgumentException("input editor and value type differ")
                    }
                }
                if (config.has("min") && config.has("max") && config.getDouble("min") >= config.getDouble("max"))
                    throw IllegalArgumentException("config requires increasing bounds")
                if (config.optString("editor") == "range" && (!config.has("min") || !config.has("max")))
                    throw IllegalArgumentException("range requires bounds")
                if (config.has("step") && config.getDouble("step") <= 0)
                    throw IllegalArgumentException("step must be positive")
            }
            for (i in 0 until actions.length()) {
                val action = actions.getJSONObject(i)
                if ((action.getString("label") as java.lang.String).isBlank() ||
                    (action.getString("reply") as java.lang.String).isBlank() ||
                    action.has("actionRef") && (action.getString("actionRef") as java.lang.String).isBlank()) {
                    throw IllegalArgumentException("action text must be nonblank")
                }
            }
            return normalized
        }

        private fun historicalRequest(request: JSONObject?): JSONObject? {
            val blocks = request?.optJSONArray("blocks") ?: return request
            var normalized: JSONObject? = null
            var normalizedBlocks: JSONArray? = null
            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                val config = block.optJSONObject("config") ?: continue
                if (!config.has("tone") && !config.has("supportingColor") && !config.has("gradient")) continue
                if (config.has("tone")) validateSchema(config.get("tone"), enumeration("light", "dark"))
                if (config.has("supportingColor")) validateSchema(config.get("supportingColor"), string(0, 80))
                if (config.has("gradient")) validateSchema(config.get("gradient"), array(string(0, 80), 0, 8))
                val currentConfig = JSONObject()
                val configKeys = config.keys()
                while (configKeys.hasNext()) {
                    val key = configKeys.next()
                    if (key != "tone" && key != "supportingColor" && key != "gradient") currentConfig.put(key, config.get(key))
                }
                val currentBlock = JSONObject()
                val blockKeys = block.keys()
                while (blockKeys.hasNext()) {
                    val key = blockKeys.next()
                    currentBlock.put(key, if (key == "config") currentConfig else block.get(key))
                }
                if (normalizedBlocks == null) {
                    normalizedBlocks = JSONArray()
                    for (j in 0 until blocks.length()) normalizedBlocks.put(blocks.get(j))
                    normalized = JSONObject()
                    val requestKeys = request.keys()
                    while (requestKeys.hasNext()) {
                        val key = requestKeys.next()
                        normalized.put(key, if (key == "blocks") normalizedBlocks else request.get(key))
                    }
                }
                normalizedBlocks.put(i, currentBlock)
            }
            return normalized ?: request
        }

        @Throws(Exception::class)
        private fun validateValue(value: JSONObject) {
            if (value.has("timeZone")) ZoneId.of(value.getString("timeZone"))
            if (value.getString("type") == "instant" && !value.isNull("value"))
                OffsetDateTime.parse(value.getString("value"))
        }

        @Throws(Exception::class)
        private fun unique(values: JSONArray) {
            val seen = HashSet<String>()
            for (i in 0 until values.length())
                if (!seen.add(values.getJSONObject(i).getString("id"))) throw IllegalArgumentException("duplicate presentation ID")
        }

        /** Shared strict schema evaluator. Optional properties stay optional and unions retain exact JSON types. */
        @JvmStatic
        @Throws(Exception::class)
        fun validateSchema(value: Any?, rule: JSONObject) {
            if (rule.length() == 0) return
            val keywords = rule.keys()
            while (keywords.hasNext()) if (keywords.next() !in SCHEMA_KEYWORDS) throw IllegalArgumentException("unsupported presentation schema keyword")
            if (rule.has("const") && rule.get("const") != value) throw IllegalArgumentException("unsupported presentation constant")
            val alternatives = rule.optJSONArray("anyOf")
            if (alternatives != null) {
                for (i in 0 until alternatives.length()) {
                    try {
                        validateSchema(value, alternatives.getJSONObject(i))
                        return
                    } catch (invalid: IllegalArgumentException) {
                        // Try the next declared union member.
                    }
                }
                throw IllegalArgumentException("presentation value does not match union")
            }
            val type = rule.optString("type")
            when (type) {
                "object" -> {
                    if (value !is JSONObject) throw IllegalArgumentException("presentation object expected")
                    val properties = rule.optJSONObject("properties") ?: JSONObject()
                    val required = rule.optJSONArray("required") ?: JSONArray()
                    for (i in 0 until required.length())
                        if (!value.has(required.getString(i))) throw IllegalArgumentException("missing presentation field")
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (properties.has(key)) validateSchema(value.get(key), properties.getJSONObject(key))
                        else if (rule.opt("additionalProperties") is JSONObject) validateSchema(value.get(key), rule.getJSONObject("additionalProperties"))
                        else if (!rule.optBoolean("additionalProperties", true)) throw IllegalArgumentException("unknown presentation field: $key")
                    }
                }
                "array" -> {
                    if (value !is JSONArray) throw IllegalArgumentException("presentation array expected")
                    if (value.length() < rule.optInt("minItems", 0) || value.length() > rule.optInt("maxItems", Int.MAX_VALUE))
                        throw IllegalArgumentException("presentation array size invalid")
                    for (i in 0 until value.length()) validateSchema(value.get(i), rule.getJSONObject("items"))
                }
                "string" -> {
                    if (value !is String) throw IllegalArgumentException("presentation text expected")
                    if (value.length < rule.optInt("minLength", 0) || value.length > rule.optInt("maxLength", Int.MAX_VALUE) ||
                        (rule.has("pattern") && !java.util.regex.Pattern.matches(rule.getString("pattern"), value))) {
                        throw IllegalArgumentException("presentation text invalid")
                    }
                }
                "number", "integer" -> {
                    if (value !is Number) throw IllegalArgumentException("presentation number expected")
                    val number = value.toDouble()
                    if (!java.lang.Double.isFinite(number) || (type == "integer" && number != Math.rint(number)) ||
                        number < rule.optDouble("minimum", -Double.MAX_VALUE) || number > rule.optDouble("maximum", Double.MAX_VALUE) ||
                        rule.has("exclusiveMinimum") && number <= rule.getDouble("exclusiveMinimum")) {
                        throw IllegalArgumentException("presentation number invalid")
                    }
                }
                "boolean" -> if (value !is Boolean) throw IllegalArgumentException("presentation boolean expected")
                "null" -> if (value !== JSONObject.NULL) throw IllegalArgumentException("presentation null expected")
                "" -> if (!rule.has("enum") && !rule.has("const")) throw IllegalArgumentException("unsupported presentation schema")
                else -> throw IllegalArgumentException("unsupported presentation schema")
            }
            val allowed = rule.optJSONArray("enum")
            if (allowed != null) {
                for (i in 0 until allowed.length()) {
                    val candidate = allowed.get(i)
                    if (candidate.equals(value) || candidate is Number && value is Number && candidate.toDouble() == value.toDouble()) return
                }
                throw IllegalArgumentException("unsupported presentation value")
            }
        }

        @Throws(Exception::class)
        private fun id(): JSONObject = string(1, 64).put("pattern", "^[a-z0-9-]{1,64}$")

        @Throws(Exception::class)
        private fun linkSchema(): JSONObject = string(0, 500).put("pattern", "^(|https?://\\S+)$")

        @Throws(Exception::class)
        private fun type(type: String): JSONObject = JSONObject().put("type", type)

        @Throws(Exception::class)
        private fun enumeration(vararg values: String): JSONObject = type("string").put("enum", JSONArray(values))

        @Throws(Exception::class)
        private fun array(items: JSONObject, min: Int, max: Int): JSONObject = type("array").put("minItems", min).put("maxItems", max).put("items", items)

        @JvmStatic
        @Throws(Exception::class)
        fun string(min: Int, max: Int): JSONObject = type("string").put("minLength", min).put("maxLength", max)

        @JvmStatic
        @Throws(Exception::class)
        fun `object`(properties: JSONObject): JSONObject {
            val required = JSONArray()
            val keys = properties.keys()
            while (keys.hasNext()) required.put(keys.next())
            return `object`(properties, required)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun `object`(properties: JSONObject, required: JSONArray): JSONObject =
            type("object").put("additionalProperties", false).put("required", required).put("properties", properties)
    }
}
