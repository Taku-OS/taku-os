// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.media.*
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Cloud bursts are bounded separately from AudioTrack; no PCM travels through Binder. */
class StreamingPlayback(private val sink: AudioDriver.Sink,private val rate: Int=24000,output: AudioTrack?=null) : AutoCloseable {
    private val queue=ArrayBlockingQueue<ShortArray>(256)
    private val accepted=AtomicLong()
    private val closed=AtomicBoolean()
    private val finished=AtomicBoolean()
    private val written=AtomicLong()
    private val audible=AtomicBoolean()
    @Volatile private var error: String?=null
    private val ownsTrack=output==null
    private val player=output ?: AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(rate/5*2,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT))).build()
    private fun consumed()=player.playbackHeadPosition.toLong() and 0xffffffffL
    private val worker=Thread({
        var first=true;var backlogSince=0L
        try {
            check(player.state==AudioTrack.STATE_INITIALIZED);player.play()
            while(!closed.get()) {
                val pcm=queue.poll(100,TimeUnit.MILLISECONDS)
                if(pcm!=null) {
                    var offset=0
                    while(offset<pcm.size && !closed.get()) {
                        val count=player.write(pcm,offset,pcm.size-offset,AudioTrack.WRITE_BLOCKING)
                        if(count<=0)throw AudioFailure("PLAYBACK_FAILED","Translated-speech playback was interrupted")
                        offset+=count;written.addAndGet(count.toLong())
                    }
                    if(first && pcm.any{kotlin.math.abs(it.toInt())>100}) {first=false;audible.set(true);sink.playback(0,"audiblePcm",written.get(),rate,true)}
                }
                val pending=accepted.get()-consumed()
                if(pending>rate*8){if(backlogSince==0L)backlogSince=SystemClock.elapsedRealtime();if(SystemClock.elapsedRealtime()-backlogSince>20000)throw AudioFailure("QUEUE_BACKLOG","Translated-speech backlog persists")}
                else backlogSince=0L
                if(finished.get() && queue.isEmpty() && consumed()>=written.get())break
            }
        }catch(e: Exception){if(!closed.get())error=(e as? AudioFailure)?.code ?: "PLAYBACK_FAILED"}
    },"audio-cloud-playback")
    init {worker.start()}
    fun accept(bytes: ByteArray) {
        if(closed.get() || finished.get())throw AudioFailure("INPUT_CLOSED","Translated-speech playback stopped")
        checkHealthy()
        val count=bytes.size/2
        // 15-second individual bursts, 20-second aggregate including AudioTrack. Tune with real profiles.
        if(bytes.size%2!=0 || count>rate*15 || accepted.get()+count-consumed()>rate*20)throw AudioFailure("QUEUE_BACKLOG","Translated-speech output exceeds capacity")
        accepted.addAndGet(count.toLong())
        for(offset in 0 until count step 2048) {
            val pcm=ShortArray(minOf(2048,count-offset)){i->val at=(offset+i)*2;((bytes[at].toInt() and 255) or (bytes[at+1].toInt() shl 8)).toShort()}
            if(!queue.offer(pcm))throw AudioFailure("QUEUE_BACKLOG","Translated-speech playback queue is full")
        }
    }
    fun checkHealthy(){error?.let{throw AudioFailure(it,"Translated-speech playback did not complete")}}
    fun finish() {
        finished.set(true)
        worker.join(25000);checkHealthy()
        if(worker.isAlive)throw AudioFailure("PLAYBACK_TIMEOUT","Translated speech did not finish playing in time")
        sink.playback(0,"chunkComplete",written.get(),rate,audible.get())
    }
    override fun close(){if(closed.compareAndSet(false,true)){runCatching{player.pause();player.flush()};queue.clear();worker.interrupt();try{joinUninterruptibly(worker,1000)}finally{if(ownsTrack)player.release()}}}
}
