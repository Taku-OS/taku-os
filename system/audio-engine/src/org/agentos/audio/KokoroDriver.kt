// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Serial synthesis with AudioTrack backpressure and confirmation of consumed playback frames. */
class KokoroDriver(private val context: Context,private val request: AudioRequest) : AudioDriver {
    private data class Chunk(val index: Long,val text: String)
    private val chunks=ArrayBlockingQueue<Chunk>(8)
    private val queuedChars=AtomicInteger()
    private val activeChars=AtomicInteger()
    private val canceled=AtomicBoolean()
    private val finished=AtomicBoolean()
    private val interrupted=AtomicBoolean()
    @Volatile private var capture: BargeInCapture?=null
    @Volatile private var player: AudioTrack?=null
    @Volatile private var worker: Thread?=null
    override fun start(sink: AudioDriver.Sink) {
        worker=Thread({
            var synthesizer: LocalSynthesizer?=null
            val manager=context.getSystemService(AudioManager::class.java)
            var focus: AudioFocusRequest?=null
            var acquired=false
            try {
                val voice=VoiceCatalog.select(request.language,request.voiceId)
                synthesizer=LocalSynthesizer(ModelResources.root(context))
                if(canceled.get())return@Thread
                if(request.interruptible)capture=BargeInCapture(context,request.language,{text,final->
                    if(!canceled.get()) {
                        if(interrupted.compareAndSet(false,true)) {
                            chunks.clear();queuedChars.set(0)
                            runCatching{player?.pause();player?.flush()}
                            sink.interrupted()
                        }
                        sink.text(text,final)
                    }
                },{code->if(!canceled.get())sink.failed(code)})
                if(canceled.get())return@Thread
                val rate=synthesizer.sampleRate
                val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener({ change -> if(change<0 && !canceled.get())sink.failed("AUDIO_FOCUS_LOST") },Handler(Looper.getMainLooper())).build()
                if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw AudioFailure("RESOURCE_BUSY","Playback focus is in use")
                acquired=true
                val track=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(rate/5*2,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                player=track
                check(track.state==AudioTrack.STATE_INITIALIZED)
                if(canceled.get())return@Thread
                track.play();sink.ready();capture?.start()
                var written=0L
                while(!canceled.get() && !interrupted.get()) {
                    val chunk=chunks.poll(100,TimeUnit.MILLISECONDS)
                    if(chunk==null){if(finished.get())break else continue}
                    activeChars.set(chunk.text.length);queuedChars.updateAndGet{maxOf(0,it-chunk.text.length)}
                    var nonSilent=false
                    var first=true
                    var audible=false
                    val before=written
                    synthesizer.generate(chunk.text,voice,request.speed) { samples ->
                        if(canceled.get() || interrupted.get())0 else {
                            // Bound conversion blocks independently of the native sentence callback size.
                            for(offset in samples.indices step 2048) {
                                if(canceled.get() || interrupted.get())break
                                val count=minOf(2048,samples.size-offset)
                                val pcm=ShortArray(count){(samples[offset+it].coerceIn(-1f,1f)*32767).toInt().toShort()}
                                nonSilent=nonSilent || pcm.any{kotlin.math.abs(it.toInt())>100}
                                var sent=0
                                while(sent<count && !canceled.get() && !interrupted.get()) {
                                    val n=track.write(pcm,sent,count-sent,AudioTrack.WRITE_BLOCKING)
                                    if(n<=0 && !canceled.get() && !interrupted.get())throw AudioFailure("PLAYBACK_FAILED","Playback did not complete")
                                    if(n>0){sent+=n;written+=n}
                                }
                                if(first && sent>0){first=false;sink.playback(chunk.index,"started",written-before,rate,nonSilent)}
                                if(nonSilent && !audible && sent>0){audible=true;sink.playback(chunk.index,"audiblePcm",written-before,rate,true)}
                            }
                            if(canceled.get() || interrupted.get())0 else 1
                        }
                    }
                    val deadline=SystemClock.elapsedRealtime()+maxOf(3000L,(written-(track.playbackHeadPosition.toLong() and 0xffffffffL))*1000/rate+3000)
                    while(!canceled.get() && !interrupted.get() && (track.playbackHeadPosition.toLong() and 0xffffffffL)<written) {
                        if(SystemClock.elapsedRealtime()>deadline)throw AudioFailure("PLAYBACK_TIMEOUT","Playback did not finish in time")
                        Thread.sleep(15)
                    }
                    if(!canceled.get() && !interrupted.get())sink.playback(chunk.index,"chunkComplete",written-before,rate,nonSilent)
                    activeChars.set(0)
                }
                // Keep the same microphone/recognizer until the interruption's final phrase.
                if(interrupted.get() && !canceled.get() && capture?.awaitEndpoint()!=true)
                    throw AudioFailure("INTERRUPTION_TIMEOUT","Interruption did not complete; audio stopped")
            }catch(e: Exception){if(!canceled.get())sink.failed((e as? AudioFailure)?.code ?: "SYNTHESIS_FAILED")}
            finally {
                capture?.closeAndAwait();capture=null
                val track=player;player=null
                if(track!=null){runCatching{track.pause();track.flush();track.stop()};track.release()}
                synthesizer?.close()
                if(acquired && focus!=null)manager.abandonAudioFocusRequest(focus)
                sink.released()
            }
            if(!canceled.get() && finished.get())sink.completed()
        },"audio-kokoro").also{it.start()}
    }
    override fun submit(index: Long,text: String) {
        if(finished.get() || canceled.get())throw AudioFailure("INPUT_CLOSED","Reading has stopped accepting text")
        val count=queuedChars.addAndGet(text.length)
        if(count>4000 || !chunks.offer(Chunk(index,text))){queuedChars.addAndGet(-text.length);throw AudioFailure("QUEUE_BACKLOG","Too much text is queued for reading. Wait for playback")}
    }
    override fun drainTimeoutMs(): Long = maxOf(5000L,((queuedChars.get()+activeChars.get())*(if(request.language=="zh-CN")350 else 120)/request.speed).toLong()+15000)
    override fun finish(){finished.set(true)}
    override fun close(){
        canceled.set(true);chunks.clear();queuedChars.set(0);capture?.close()
        runCatching{player?.pause();player?.flush()};worker?.interrupt()
    }
}
