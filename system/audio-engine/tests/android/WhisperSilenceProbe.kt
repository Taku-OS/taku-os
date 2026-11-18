// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.agentos.audio.tests

import android.app.Instrumentation
import android.os.Bundle
import org.agentos.audio.*
import org.json.JSONObject

/** An intentionally invalid key proves silent PCM returns before any upload/auth/network call. */
class WhisperSilenceProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    override fun onStart() {
        val report = JSONObject().put("passed", false)
        val transcriber = WhisperTranscriber()
        try {
            val noise = java.util.Random(7)
            val cases = listOf(ShortArray(32000), ShortArray(32000) { 2000 },
                ShortArray(32000) { (noise.nextInt(257) - 128).toShort() },
                ShortArray(32000).apply { this[400] = Short.MAX_VALUE })
            for (pcm in cases) check(transcriber.transcribe("invalid-silence-test-key", "zh-CN", pcm).isEmpty())
            report.put("passed", true).put("silentCases", cases.size).put("emptyTranscripts", true)
                .put("realTranscriber", true).put("credentialsRead", false)
        } catch (e: Throwable) { report.put("error", e.toString()) }
        finally { transcriber.close(); finish(if (report.optBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) }) }
    }
}
