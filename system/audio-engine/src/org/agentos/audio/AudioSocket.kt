// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.net.*
import android.os.*
import org.json.JSONObject
import java.io.Closeable

/** CLI only: caller identity comes from Unix peer credentials, never JSON. */
class AudioSocket(private val receive: (Message) -> Unit) : Closeable {
    private val main=Handler(Looper.getMainLooper())
    private val closed=java.util.concurrent.atomic.AtomicBoolean()
    private val server=LocalServerSocket("taku-audio-engine")
    private val connections=java.util.concurrent.ConcurrentHashMap.newKeySet<LocalSocket>()
    private val listener=Thread({
        try { while(!closed.get()) {
            val socket=server.accept()
            if(closed.get()){socket.close();break}
            connections.add(socket)
            Thread({ serve(socket) },"audio-cli-peer").apply { isDaemon=true;start() }
        } } catch (_: Exception) { }
    },"audio-cli-listener").apply { isDaemon=true;start() }
    private fun serve(socket: LocalSocket) {
        val uid=socket.peerCredentials.uid
        var reply: Messenger?=null
        var writer: Thread?=null
        var attached=false
        try { socket.use { local ->
            if(uid!=0 && uid!=2000)return
            main.post { AudioCliHost.attached() };attached=true
            val output=local.outputStream
            val outgoing=java.util.concurrent.ArrayBlockingQueue<String>(128)
            writer=Thread({
                try { while(true) { output.write((outgoing.take()+"\n").toByteArray());output.flush() } }
                catch (_: Exception) { runCatching { local.close() } }
            },"audio-cli-output").apply { isDaemon=true;start() }
            reply=Messenger(Handler(Looper.getMainLooper()) { message ->
                if(!outgoing.offer(message.data.getString("json","{}"))) runCatching { local.close() }
                true
            })
            val input=local.inputStream.bufferedReader()
            while(true) {
                val line=input.readLine() ?: break
                require(line.length<=40000)
                val request=JSONObject(line)
                val message=Message.obtain(null,1).apply {
                    sendingUid=uid;replyTo=reply
                    data=Bundle().apply { putString("id",request.getString("id"));putString("method",request.getString("method"));putString("params",request.optJSONObject("params")?.toString() ?: "{}") }
                }
                main.post { receive(message) }
            }
        } } catch (_: Exception) { }
        finally {
            writer?.interrupt();connections.remove(socket)
            main.post {
                reply?.let { callback -> receive(Message.obtain(null,2).apply {sendingUid=uid;replyTo=callback}) }
                if(attached)AudioCliHost.detached()
            }
        }
    }
    override fun close() {
        if(!closed.compareAndSet(false,true))return
        // Linux accept keeps the listener alive after a cross-thread close. Wake it before rebinding.
        runCatching { android.system.Os.shutdown(server.fileDescriptor,android.system.OsConstants.SHUT_RDWR) }
        runCatching { server.close() }
        // Rebinding must wait until accept has dropped its native reference to the old listener.
        listener.join(1000)
        connections.forEach { runCatching { it.close() } };connections.clear()
    }
}
