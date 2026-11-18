// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject

/** Authorized adb/device transport. EOF closes the connection and its native sessions. */
object AudioPipe {
    @JvmStatic fun main(args: Array<String>) {
        try {
            android.os.Looper.prepareMainLooper()
            val activity=Class.forName("android.app.ActivityThread").getDeclaredMethod("systemMain").invoke(null)
            val context=activity.javaClass.getDeclaredMethod("getSystemContext").invoke(activity) as Context
            val expected=context.packageManager.getApplicationInfo("org.agentos.audio",0).uid
            // app_process is not an Android application process and cannot bind directly.
            // The DUMP-protected receiver binds inside the app without recording or playing.
            val start=ProcessBuilder("/system/bin/am","broadcast","--async","--receiver-foreground","--include-stopped-packages",
                "-n","org.agentos.audio/.AudioBridgeReceiver").redirectErrorStream(true).start()
            start.inputStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) };check(start.waitFor()==0)
            var connected: LocalSocket?=null
            for(attempt in 0..49) {
                val candidate=LocalSocket()
                try { candidate.connect(LocalSocketAddress("taku-audio-engine"));connected=candidate;break }
                catch(e: java.io.IOException) { candidate.close();if(attempt==49)throw e;Thread.sleep(100) }
            }
            checkNotNull(connected).use { socket ->
                check(socket.peerCredentials.uid==expected) { "Unexpected audio service identity" }
                Thread({
                    try { System.`in`.copyTo(socket.outputStream);socket.shutdownOutput() }
                    catch (_: Exception) { runCatching { socket.close() } }
                },"audio-pipe-input").apply { isDaemon=true;start() }
                socket.inputStream.copyTo(System.out);System.out.flush()
            }
        } catch (error: Exception) { System.err.println("AudioPipe: "+error.javaClass.simpleName+": "+error.message);println(JSONObject().put("type","transportError").put("message","Audio bridge unavailable").toString()) }
    }
}
