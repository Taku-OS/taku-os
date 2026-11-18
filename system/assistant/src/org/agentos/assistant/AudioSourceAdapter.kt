package org.agentos.assistant

import android.content.Context
import org.json.JSONObject

/** Shared matter source backed by the installed audio engine and saved native recordings. */
internal class AudioSourceAdapter(context: Context) : SourceAdapter {
    private val bridge = AudioSessionBridge.get(context)

    @Throws(Exception::class)
    override fun read(resource: JSONObject): JSONObject = bridge.read(resource)

    @Throws(Exception::class)
    override fun watch(resource: JSONObject, invalidated: Runnable): AutoCloseable =
        bridge.watch(resource, invalidated)
}
