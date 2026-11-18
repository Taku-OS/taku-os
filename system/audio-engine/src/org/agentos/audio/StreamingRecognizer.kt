// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Real local ASR adapter, shared by microphone runtime and fixed-audio device verification. */
class StreamingRecognizer(base: File, language: String, private val text: (String,Boolean)->Unit) : AutoCloseable {
    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream
    private var last=""
    init {
        if(!ModelResources.ready(base,language))throw AudioFailure("MODEL_MISSING","Install the speech model first")
        val chinese=language=="zh-CN"
        val path=File(base,if(chinese) "asr-zh" else "asr")
        val suffix=if(chinese) "-epoch-99-avg-1.int8.onnx" else "-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
        recognizer=OnlineRecognizer(config=OnlineRecognizerConfig(modelConfig=OnlineModelConfig(
            transducer=OnlineTransducerModelConfig(encoder="$path/encoder$suffix",decoder="$path/decoder$suffix",joiner="$path/joiner$suffix"),
            tokens="$path/tokens.txt",numThreads=2,modelType=if(chinese) "zipformer" else "zipformer2"),
            endpointConfig=EndpointConfig(rule2=EndpointRule(true,.45f,0f),rule3=EndpointRule(false,0f,12f))))
        try { stream=recognizer.createStream() } catch(e: Throwable) {recognizer.release();throw e}
    }
    fun accept(samples: ShortArray, count: Int=samples.size) {
        stream.acceptWaveform(FloatArray(count){samples[it]/32768f},16000)
        decode()
        val current=recognizer.getResult(stream).text.trim()
        if(recognizer.isEndpoint(stream)) {
            if(current.isNotBlank())text(current,true)
            recognizer.reset(stream);last=""
        }else if(current.isNotBlank()&&current!=last){text(current,false);last=current}
    }
    fun finish() {
        stream.acceptWaveform(FloatArray(8000),16000);stream.inputFinished();decode()
        recognizer.getResult(stream).text.trim().takeIf{it.isNotBlank()}?.let{text(it,true)}
    }
    private fun decode(){while(recognizer.isReady(stream))recognizer.decode(stream)}
    override fun close(){try{stream.release()}finally{recognizer.release()}}
}
