// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Owns native Kokoro resources. Callback samples stay inside the engine process. */
class LocalSynthesizer(base: File) : AutoCloseable {
    private val tts: OfflineTts
    val sampleRate: Int
    init {
        if(!ModelResources.ttsReady(base)) throw AudioFailure("MODEL_MISSING","Install local reading resources first")
        val path=File(base,"tts")
        tts=OfflineTts(config=OfflineTtsConfig(model=OfflineTtsModelConfig(kokoro=OfflineTtsKokoroModelConfig(
            model="$path/model.onnx",voices="$path/voices.bin",tokens="$path/tokens.txt",dataDir="$path/espeak-ng-data",
            lexicon="$path/lexicon-us-en.txt,$path/lexicon-zh.txt",dictDir="$path/dict"),numThreads=2),
            ruleFsts="$path/date-zh.fst,$path/number-zh.fst,$path/phone-zh.fst",maxNumSentences=1))
        sampleRate=tts.sampleRate()
    }
    fun generate(text: String,voice: LocalVoice,speed: Float,callback: (FloatArray)->Int) {
        tts.generateWithCallback(text,voice.speaker,speed,SpeechOutputCallback(callback))
    }
    override fun close(){tts.release()}
}
/** JNI needs invoke(float[]): Integer; an indy lambda does not expose that method. */
internal class SpeechOutputCallback(private val action: (FloatArray)->Int) : (FloatArray)->Int {
    override fun invoke(samples: FloatArray): Int=action(samples)
}
