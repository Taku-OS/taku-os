package org.agentos.assistant

import org.json.JSONObject

/** Real read-only sources; command execution belongs to TaskBroker and its existing approval flow. */
internal interface SourceAdapter {
    @Throws(Exception::class)
    fun read(resource: JSONObject): JSONObject

    @Throws(Exception::class)
    fun watch(resource: JSONObject, invalidated: Runnable): AutoCloseable
}
