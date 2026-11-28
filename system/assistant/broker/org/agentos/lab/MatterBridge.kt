package org.agentos.lab

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern

/** Native broker to signed gateway bridge, following the existing MemoryBridge provider transport. */
internal class MatterBridge {
    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun control(input: JSONObject): JSONObject {
            val params = JSONObject(input.toString())
            params.remove("op")
            if (!Pattern.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", params.getString("threadId"))) {
                throw IOException("INVALID_THREAD_ID")
            }
            if ("present" == params.optString("action")) {
                val source = params.optJSONObject("source")
                val suppliedSize = params.has("size")
                val size = params.optString("size", "full_size")
                params.remove("size")
                if (!params.has("request")) {
                    if (source == null) throw IOException("REQUEST_OR_SOURCE_REQUIRED")
                    val block = JSONObject().put("id", source.getString("blockId")).put("kind", "text").put("title", "").put("text", "")
                        .put("value", JSONObject().put("type", "text").put("value", JSONObject.NULL)).put("image", "").put("url", "")
                        .put("items", JSONArray()).put("config", JSONObject().put("size", size))
                    params.put("request", JSONObject().put("version", 2).put("key", params.getString("key")).put("mode", "card").put("title", "").put("summary", "")
                        .put("blocks", JSONArray().put(block)).put("actions", JSONArray()))
                } else if (suppliedSize) {
                    if (source == null) throw IOException("SIZE_REQUIRES_SOURCE")
                    val blocks = params.getJSONObject("request").getJSONArray("blocks")
                    for (i in 0 until blocks.length()) {
                        if (source.getString("blockId") == blocks.getJSONObject(i).getString("id")) {
                            blocks.getJSONObject(i).getJSONObject("config").put("size", size)
                        }
                    }
                }
                try {
                    UiCardRequest.validate(params.getJSONObject("request"))
                } catch (invalid: IllegalArgumentException) {
                    throw IOException("INVALID_PRESENTATION: " + invalid.message + ". Each block requires id,kind,title,text,value,image,url,items,config; use kind, not type. For a real source, omit request and let native construct its view.")
                }
            }
            val output = MemoryBridge.command("/system/bin/content", "call", "--user", "0", "--uri",
                "content://org.agentos.assistant.agent-gateway", "--method", "nativeMatter", "--arg", params.toString())
            val response = MemoryBridge.contentResult(output) ?: throw IOException("Matter runtime unavailable")
            if (!response.optBoolean("ok")) throw IOException(response.optString("code", "MATTER_UNAVAILABLE"))
            return response.getJSONObject("result")
        }

        @JvmStatic
        @Throws(Exception::class)
        fun definition(): JSONObject {
            val source = `object`(JSONObject().put("kind", enumeration("memory", "task-receipt", "weather", "feed"))
                .put("resourceId", PhoneNativeTools.string(160)).put("blockId", PhoneNativeTools.string(64)), "kind", "resourceId", "blockId")
            val threshold = `object`(JSONObject().put("type", enumeration("money")).put("value", PhoneNativeTools.string(24))
                .put("currency", PhoneNativeTools.string(3)).put("scale", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 6)), "type", "value", "currency", "scale")
            val rule = `object`(JSONObject().put("id", PhoneNativeTools.string(32)).put("kind", enumeration("daily", "weekdays", "price-below"))
                .put("reply", PhoneNativeTools.string(8000)).put("slotId", PhoneNativeTools.string(32)).put("localTime", PhoneNativeTools.string(5))
                .put("timeZone", PhoneNativeTools.string(80)).put("sourceId", PhoneNativeTools.string(160)).put("threshold", threshold), "id", "kind", "reply")
            val command = `object`(JSONObject().put("requestId", PhoneNativeTools.string(36)).put("actionRef", PhoneNativeTools.string(200))
                .put("expectedRevision", JSONObject().put("type", "integer").put("minimum", 0))
                .put("values", JSONObject().put("type", "object")), "requestId", "actionRef", "expectedRevision", "values")
            val presentation = UiCardRequest.`object`(UiCardRequest.schema(), JSONArray().put("version").put("key").put("mode").put("title").put("summary").put("blocks").put("actions"))
            val tool = JSONObject().put("type", "function").put("name", "matter")
                .put("description", "Create or update an actual generated card using present with request (the shared V2 schema), stable key and entity IDs. Supply optional source in that same call to immediately bind real weather/feed data; never fabricate a business result to create a card. Existing matters support read, attachSource, putRule, dispatch and removeView. Keep the same key for followups. Sources: memory uses an existing Taku memory record; task-receipt uses this thread's TaskBroker task ID; those bind a text block. weather uses resourceId device for authorized fresh location or explicit latitude,longitude (never invent the user's location), and replaces its anchor block with current metric, five-day forecast and source time. feed uses resourceId figma for the official Figma blog Atom feed and replaces its anchor with a real collection. No X account/API is connected; report X as unavailable, never substitute another news source. All sources share native reads/TTL and refresh controls; keeping a Panel alone does not authorize background polling. Daily/weekdays rules require user-confirmed HH:mm, fixed IANA timezone and stable slotId; weekdays means Monday-Friday, late window is 30 minutes, Android may delay. They submit conversational requests through TaskBroker; each purchase/send/control still requires existing native confirmation. Price-below requires typed money and a real verified landed-price adapter; none is registered, so activation is unavailable. read returns opaque actionRefs; dispatch current revision plus stable UUID requestId for refresh/pause/resume/skip-today. removeView unkeeps presentation only, never cancels rules/tasks.")
                .put("inputSchema", `object`(JSONObject().put("action", enumeration("present", "read", "attachSource", "putRule", "dispatch", "removeView"))
                    .put("key", PhoneNativeTools.string(64)).put("request", presentation).put("source", source).put("size", enumeration("full_size", "medium_size", "small_size")).put("rule", rule).put("command", command), "action", "key"))
            tool.put("description", tool.getString("description") + UiCardRequest.ROLES
                + " For a real source, prefer present with key, source and optional size, omitting request: native builds a validated source view. For custom content, every block must include id,kind,title,text,value,image,url,items,config; kind is not type. Present starts source observation; inspect read.meta.sources before reporting source facts. A failed tool call is not weather data; do not invent current conditions, hourly forecasts or business results.")
            return tool
        }

        private fun enumeration(vararg values: String): JSONObject =
            JSONObject().put("type", "string").put("enum", JSONArray(values))

        private fun `object`(properties: JSONObject, vararg required: String): JSONObject =
            JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)
    }
}
