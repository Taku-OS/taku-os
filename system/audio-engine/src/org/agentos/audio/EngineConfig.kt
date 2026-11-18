// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Root-owned configuration stream; no audio and no public credential API. */
class EngineConfig(private val update: (JSONObject) -> Unit, private val unavailable: () -> Unit) : Closeable {
    companion object {
        fun control(request: JSONObject): JSONObject = Control(request).use{it.execute()}
    }
    class Control(private val request: JSONObject) : Closeable {
        private val socket=LocalSocket()
        fun execute(): JSONObject {
            socket.connect(LocalSocketAddress("taku-audio-config"));check(socket.peerCredentials.uid==0)
            socket.soTimeout=if(request.optString("op")=="translate")30000 else 5000
            socket.outputStream.write((request.toString()+"\n").toByteArray())
            val answer=JSONObject(socket.inputStream.bufferedReader().readLine() ?: error("Audio configuration service did not respond"))
            // Keep the broker's reason code (PROVIDER_NOT_CONFIGURED, POLICY_REVOKED, …) for the drivers and UI.
            if(!answer.optBoolean("ok"))throw AudioFailure(answer.optString("code","CONFIG_UNAVAILABLE"),answer.optString("message","Audio configuration or text translation did not complete"))
            return answer.getJSONObject("result")
        }
        override fun close(){runCatching{socket.close()}}
    }
    private val closed = AtomicBoolean()
    @Volatile private var socket: LocalSocket? = null
    fun start() {
        Thread({
            while (!closed.get()) {
                try {
                    LocalSocket().use { local ->
                        socket = local; local.connect(LocalSocketAddress("taku-audio-config"))
                        check(local.peerCredentials.uid == 0)
                        local.outputStream.write("{\"op\":\"subscribe\"}\n".toByteArray())
                        val input = local.inputStream.bufferedReader()
                        while (!closed.get()) {
                            val line = input.readLine() ?: break
                            require(line.length <= 65536)
                            update(JSONObject(line))
                        }
                    }
                } catch (_: Exception) { }
                socket = null
                if (!closed.get()) {
                    unavailable()
                    try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
                }
            }
        },"audio-config").apply { isDaemon = true; start() }
    }
    override fun close() { closed.set(true); runCatching { socket?.close() } }
}
