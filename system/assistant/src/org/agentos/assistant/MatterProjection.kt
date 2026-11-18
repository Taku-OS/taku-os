package org.agentos.assistant

import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Typed source blocks retain stable view identities independently of changing source values. */
internal class MatterProjection {
    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun source(request: JSONObject, binding: JSONObject, observation: JSONObject) {
            val original = request.getJSONArray("blocks")
            val projected = JSONArray()
            val target = binding.getString("blockId")
            for (i in 0 until original.length()) {
                val block = original.getJSONObject(i)
                if (target != block.getString("id")) { projected.put(block); continue }
                if ("denied" == observation.optString("state")) {
                    block.put("title", "").put("text", "").put("image", "").put("url", "").put("items", JSONArray())
                    block.getJSONObject("value").put("value", JSONObject.NULL)
                    projected.put(block)
                    continue
                }
                val values = observation.optJSONArray("blocks")
                if (values != null && values.length() > 0) {
                    for (j in 0 until values.length()) {
                        val value = JSONObject(values.getJSONObject(j).toString())
                        val suffix = value.getString("id")
                        value.put("id", if (j == 0) target else suffix + "-" + UUID.nameUUIDFromBytes((target + "\n" + suffix).toByteArray(StandardCharsets.UTF_8)))
                        val config = value.getJSONObject("config")
                        val requested = block.getJSONObject("config")
                        config.put("group", requested.optString("group", target))
                        if (requested.has("size")) config.put("size", requested.getString("size"))
                        projected.put(value)
                    }
                } else {
                    if (block.getString("kind") == "text" && observation.has("text"))
                        block.put("title", observation.optString("title")).put("text", observation.optString("text"))
                    projected.put(block)
                }
            }
            request.put("blocks", projected)
        }
    }
}
