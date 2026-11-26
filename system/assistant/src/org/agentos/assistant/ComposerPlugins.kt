package org.agentos.assistant

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** God Mode plugins the user attaches to the next message (`@` in the composer). No Android or socket access here. */
internal object ComposerPlugins {
    const val LIMIT = 4
    private const val TOOL_LIMIT = 64
    private val ID = Regex("[A-Za-z0-9_.@:-]{1,128}")

    @JvmStatic
    fun isId(value: String): Boolean = ID.matches(value)

    /**
     * The stored `{id, name, tools}` for a God Mode `show` envelope of an installed, enabled plugin. Anything
     * else, including an unreachable God Mode or an unknown id, is `PLUGIN_UNAVAILABLE`.
     */
    @JvmStatic
    fun resolve(response: JSONObject, id: String): JSONObject {
        val record = if (response.optBoolean("ok")) response.optJSONObject("result") else null
        if (record == null || record.optString("id") != id || record.opt("enabled") != true) throw IOException("PLUGIN_UNAVAILABLE")
        val name = clean(record.optJSONObject("manifest")?.opt("name") as? String ?: "", 128).ifEmpty { id }
        val tools = LinkedHashSet<String>()
        val registered = record.optJSONObject("registrations")?.optJSONArray("tools") ?: JSONArray()
        for (index in 0 until registered.length()) {
            val tool = registered.optJSONObject(index) ?: continue
            val label = clean(tool.optString("name").ifEmpty { tool.optString("id") }, 128)
            if (label.isNotEmpty() && tools.size < TOOL_LIMIT) tools.add(label)
        }
        return JSONObject().put("id", id).put("name", name).put("tools", JSONArray(tools))
    }

    private fun clean(value: String, limit: Int): String =
        value.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(limit)
}
