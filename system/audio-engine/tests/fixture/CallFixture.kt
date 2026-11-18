// SPDX-License-Identifier: Apache-2.0
package org.taku.callfixture

import android.app.Activity
import android.media.*
import android.os.Bundle
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/** Disposable synthetic call owner. No network, storage, contacts or real call. */
class CallFixture : Activity() {
    private val alive = AtomicBoolean(true)
    private lateinit var audio: AudioManager
    private var playback: AudioTrack? = null
    private var recorder: AudioRecord? = null
    private val workers = mutableListOf<Thread>()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text="Synthetic call fixture — no network"; textSize=24f })
        audio=getSystemService(AudioManager::class.java)
        audio.mode=AudioManager.MODE_IN_COMMUNICATION
        val output=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(6400).build()
        playback=output;output.play()
        val input=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(6400).build()
        recorder=input;input.startRecording()
        workers += Thread({
            val tone=ShortArray(320) { (sin(2*PI*1000*it/16000)*2000).toInt().toShort() }
            while(alive.get() && output.write(tone,0,tone.size)>0) { }
        }, "fixture-playback").also { it.start() }
        workers += Thread({
            val discarded=ShortArray(320)
            while(alive.get() && input.read(discarded,0,discarded.size)>0) { }
        }, "fixture-recording").also { it.start() }
    }
    override fun onDestroy() {
        alive.set(false)
        runCatching { recorder?.stop() };runCatching { playback?.pause();playback?.flush() }
        workers.forEach { runCatching { it.join(1000) } }
        recorder?.release();playback?.release();audio.mode=AudioManager.MODE_NORMAL
        super.onDestroy()
    }
}
