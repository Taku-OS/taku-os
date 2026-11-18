// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import org.json.JSONArray
import org.json.JSONObject
import org.msgpack.core.MessagePack

/** fal realtime framing used by Grok. Kotoba's endpoint uses plain JSON. */
object FalMessagePack {
    fun encode(value: JSONObject): ByteArray = MessagePack.newDefaultBufferPacker().use { packer ->
        fun pack(item: Any?) {
            when(item) {
                null,JSONObject.NULL -> packer.packNil()
                is JSONObject -> {packer.packMapHeader(item.length());item.keys().forEach { key -> packer.packString(key);pack(item.get(key)) }}
                is JSONArray -> {packer.packArrayHeader(item.length());for(i in 0 until item.length())pack(item.get(i))}
                is Boolean -> packer.packBoolean(item)
                is Float,is Double -> packer.packDouble((item as Number).toDouble())
                is Number -> packer.packLong(item.toLong())
                is String -> packer.packString(item)
                else -> error("Unsupported realtime value")
            }
        }
        pack(value);packer.toByteArray()
    }
    fun decode(bytes: ByteArray): JSONObject = MessagePack.newDefaultUnpacker(bytes).use { input ->
        require(bytes.size<=1_000_000)
        val value=input.unpackValue();check(!input.hasNext())
        JSONObject(value.toJson())
    }
}
