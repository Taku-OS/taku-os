// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import java.io.File
import org.json.JSONObject

object ModelResources {
    /** ROM models are read-only, survive data wipes, and update with the image.
     * Standalone development installs keep using the verified private pack. */
    fun root(context: android.content.Context): File {
        val system = File("/system_ext/etc/taku-audio/models")
        return if (File(system, "manifest.json").isFile) system else File(context.filesDir, "models")
    }
    fun ttsReady(base: File): Boolean = runCatching {
        val required=listOf("tts/model.onnx","tts/voices.bin","tts/tokens.txt","tts/lexicon-us-en.txt","tts/lexicon-zh.txt","tts/date-zh.fst","tts/number-zh.fst","tts/phone-zh.fst")
        val files=JSONObject(File(base,"manifest.json").readText()).getJSONArray("files")
        val sizes=mutableMapOf<String,Long>()
        for(i in 0 until files.length()){val entry=files.getJSONObject(i);sizes[entry.getString("path")]=entry.getLong("size")}
        required.all { path -> val file=File(base,path);file.isFile && file.length()==sizes[path] } && File(base,"tts/espeak-ng-data").isDirectory && File(base,"tts/dict").isDirectory
    }.getOrDefault(false)
    fun ready(base: File, language: String): Boolean = runCatching {
        val role=if(language=="zh-CN") "asr-zh" else "asr"
        val files=JSONObject(File(base,"manifest.json").readText()).getJSONArray("files")
        var count=0
        for(i in 0 until files.length()) {
            val entry=files.getJSONObject(i);val path=entry.getString("path")
            if(path.startsWith("$role/") && (path.endsWith(".onnx") || path.endsWith("tokens.txt"))) {
                val file=File(base,path)
                check(file.isFile && file.length()==entry.getLong("size"));count++
            }
        }
        count==4
    }.getOrDefault(false)
}
