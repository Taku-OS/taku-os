// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio.tests

import android.app.Instrumentation
import android.os.Bundle
import android.content.pm.PackageManager
import org.agentos.audio.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** No microphone, cloud provider or personal data: exercise ROM resources and real JNI. */
class RomModelsProbe : Instrumentation() {
    private var allowUnsupported = false
    override fun onCreate(args: Bundle?) { super.onCreate(args); allowUnsupported = args?.getString("allowUnsupportedPermissions") == "true"; start() }
    override fun onStart() {
        val report = JSONObject().put("passed", false).put("qualityApproved", false)
        try {
            val context = targetContext
            check(context.applicationInfo.sourceDir.startsWith("/system_ext/priv-app/TakuAudioEngine/"))
            check(!File(context.filesDir, "models").exists()) { "Private development models must not mask ROM resources" }
            val models = ModelResources.root(context)
            check(models.path == "/system_ext/etc/taku-audio/models")
            val permissions = listOf("CAPTURE_AUDIO_OUTPUT", "MODIFY_AUDIO_ROUTING", "CALL_AUDIO_INTERCEPTION",
                "BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION", "START_FOREGROUND_SERVICES_FROM_BACKGROUND", "CAPTURE_VIDEO_OUTPUT")
            val unsupported = mutableListOf<String>()
            val granted = mutableListOf<String>()
            for (permission in permissions) {
                val defined = try { context.packageManager.getPermissionInfo("android.permission.$permission", 0); true }
                    catch (_: PackageManager.NameNotFoundException) { false }
                if (!defined && allowUnsupported) unsupported.add(permission)
                else {
                    check(context.checkSelfPermission("android.permission.$permission") == PackageManager.PERMISSION_GRANTED) { permission }
                    granted.add(permission)
                }
            }
            val entries = JSONObject(File(models, "manifest.json").readText()).getJSONArray("files")
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val file = File(models, entry.getString("path"))
                check(file.length() == entry.getLong("size"))
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == entry.getString("sha256"))
            }
            val results = JSONArray()
            LocalSynthesizer(models).use { synth ->
                for ((language, text) in listOf("en-US" to "The meeting is tomorrow at three.", "zh-CN" to "明天下午三点开会。")) {
                    check(ModelResources.ready(models, language))
                    val chunks = mutableListOf<FloatArray>()
                    synth.generate(text, VoiceCatalog.select(language, ""), 1f) { chunks.add(it.copyOf()); 1 }
                    val samples = FloatArray(chunks.sumOf { it.size })
                    var offset = 0
                    for (chunk in chunks) { chunk.copyInto(samples, offset); offset += chunk.size }
                    check(samples.any { kotlin.math.abs(it) > 0.001f })
                    val pcm = ShortArray((samples.size.toLong() * 16000 / synth.sampleRate).toInt()) { i ->
                        (samples[(i.toLong() * synth.sampleRate / 16000).toInt()] * 32767).toInt().coerceIn(-32768,32767).toShort()
                    }
                    val words = mutableListOf<String>()
                    StreamingRecognizer(models, language) { value, final -> if (final) words.add(value) }.use { recognizer ->
                        for (i in pcm.indices step 1600) recognizer.accept(pcm.copyOfRange(i, minOf(i + 1600, pcm.size)))
                        recognizer.finish()
                    }
                    check(words.any { it.isNotBlank() })
                    results.put(JSONObject().put("language", language).put("frames", samples.size).put("recognized", words.joinToString(" ")))
                }
            }
            report.put("passed", true).put("apk", context.applicationInfo.sourceDir).put("modelRoot", models.path)
                .put("modelFilesVerified", entries.length()).put("privilegedPermissions", JSONArray(granted)).put("platformUnsupportedPermissions", JSONArray(unsupported)).put("allPrivilegedPermissionsVerified", unsupported.isEmpty())
                .put("results", results).put("privateModelsAbsent", true)
        } catch (error: Throwable) {
            report.put("error", error.javaClass.simpleName).put("message", error.message?.take(250))
        }
        finish(if (report.optBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
    }
}
