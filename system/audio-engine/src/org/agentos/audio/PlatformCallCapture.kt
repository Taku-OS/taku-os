// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Android 16 dynamic policies, confined to one app UID; no global call interception helper. */
enum class CallDirection { INCOMING, OUTGOING }

class PlatformCallCapture(private val context: Context, val targetUid: Int, private val microphone: Boolean = true) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = 320
        private const val CALL_PERMISSION = "android.permission.CALL_AUDIO_INTERCEPTION"
        private const val ROUTING_PERMISSION = "android.permission.MODIFY_AUDIO_ROUTING"
        private const val RULE_USAGE = 1
        private const val RULE_SOURCE = 2
        private const val RULE_UID = 4
        private const val ROLE_PLAYERS = 0
        private const val ROLE_INJECTOR = 1

        fun uidFor(context: Context, packageName: String): Int {
            val app = context.packageManager.getApplicationInfo(packageName, 0)
            require(app.uid >= Process.FIRST_APPLICATION_UID && app.uid != Process.myUid()) { "Only call apps with an independent UID are supported" }
            val packages = context.packageManager.getPackagesForUid(app.uid) ?: emptyArray()
            require(packages.size == 1) { "This app shares a UID; audio isolation cannot currently be guaranteed" }
            return app.uid
        }

        fun capabilities(context: Context): Map<String, Any> = linkedMapOf(
            "callInterceptionPermission" to (context.checkSelfPermission(CALL_PERMISSION) == PackageManager.PERMISSION_GRANTED),
            "routingPermission" to (context.checkSelfPermission(ROUTING_PERMISSION) == PackageManager.PERMISSION_GRANTED),
            "microphonePermission" to (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            "audioMode" to context.getSystemService(AudioManager::class.java).mode,
            "dynamicPolicyAvailable" to runCatching { Class.forName("android.media.audiopolicy.AudioPolicy") }.isSuccess,
        )

        fun requireActiveTarget(context: Context, uid: Int, requireMicrophone: Boolean = true) {
            val manager = context.getSystemService(AudioManager::class.java)
            check(manager.mode in setOf(AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_COMMUNICATION_REDIRECT)) { "Start a voice or video call in the target app first" }
            fun clientUid(config: Any) = config.javaClass.getMethod("getClientUid").invoke(config) as Int
            val playing = manager.activePlaybackConfigurations.any { config ->
                clientUid(config) == uid && config.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION &&
                    config.javaClass.getMethod("isActive").invoke(config) == true
            }
            check(playing) { "No accessible call playback track is available for the selected app" }
            if (requireMicrophone) {
                check(!manager.isMicrophoneMute) { "The system microphone is muted. Unmute it first" }
                check(manager.activeRecordingConfigurations.any { config ->
                    clientUid(config) == uid && config.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                }) { "No accessible call recording path is available for the selected app. Check call and mute status" }
            }
        }
    }

    private val alive = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var policy: Any? = null
    private var downlink: AudioRecord? = null
    private var mic: AudioRecord? = null
    private var uplink: AudioTrack? = null
    private var listening: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private val workers = mutableListOf<Thread>()
    private val capturing = AtomicBoolean()
    val incomingOutput: AudioTrack get() = checkNotNull(listening)
    val outgoingOutput: AudioTrack get() = checkNotNull(uplink)
    val incomingSamples = AtomicLong()
    val outgoingSamples = AtomicLong()
    private var failure: (String) -> Unit = {}
    private val main = Handler(Looper.getMainLooper())
    private var targetMissingSince = 0L
    private val monitorTarget = object : Runnable {
        override fun run() {
            if (!alive.get() || !microphone) return
            try {
                val active = audio.activeRecordingConfigurations.any { config ->
                    config.javaClass.getMethod("getClientUid").invoke(config) == targetUid &&
                        config.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                }
                val now = SystemClock.elapsedRealtime()
                if (active) targetMissingSince = 0L
                else if (targetMissingSince == 0L) targetMissingSince = now
                else if (now - targetMissingSince > 1500) { failure("CALL_ENDED"); return }
                if (audio.isMicrophoneMute) { failure("CAPTURE_SILENCED"); return }
            } catch (_: Exception) { failure("CALL_STATE_UNAVAILABLE"); return }
            if (alive.get()) main.postDelayed(this, 500)
        }
    }
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (alive.get() && configs.any { it.isClientSilenced }) failure("CAPTURE_SILENCED")
        }
    }
    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        if (alive.get() && mode != AudioManager.MODE_IN_COMMUNICATION && mode != AudioManager.MODE_COMMUNICATION_REDIRECT) {
            failure("CALL_ENDED")
        }
    }

    private fun type(name: String) = Class.forName(name)
    private fun invoke(receiver: Any, name: String, vararg args: Pair<Class<*>, Any>): Any? = try {
        receiver.javaClass.getMethod(name, *args.map { it.first }.toTypedArray()).invoke(receiver, *args.map { it.second }.toTypedArray())
    } catch (error: InvocationTargetException) { throw (error.targetException as? Exception ?: error) }
    private fun intArg(value: Int) = Int::class.javaPrimitiveType!! to value

    private fun mix(input: Boolean): Any {
        val rule = type("android.media.audiopolicy.AudioMixingRule\$Builder").getConstructor().newInstance()
        val attributes = AudioAttributes.Builder()
        if (input) {
            invoke(attributes, "setInternalCapturePreset", intArg(MediaRecorder.AudioSource.VOICE_COMMUNICATION))
        } else attributes.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        invoke(attributes, "setForCallRedirection")
        invoke(rule, "addMixRule", intArg(if (input) RULE_SOURCE else RULE_USAGE), Any::class.java to attributes.build())
        invoke(rule, "addMixRule", intArg(RULE_UID), Any::class.java to targetUid)
        invoke(rule, "setTargetMixRole", intArg(if (input) ROLE_INJECTOR else ROLE_PLAYERS))
        val builtRule = invoke(rule, "build")!!
        val builder = type("android.media.audiopolicy.AudioMix\$Builder").getConstructor(type("android.media.audiopolicy.AudioMixingRule")).newInstance(builtRule)
        invoke(builder, "setFormat", AudioFormat::class.java to format(false))
        invoke(builder, "setRouteFlags", intArg(2)) // ROUTE_FLAG_LOOP_BACK: reroute, rather than copy and render.
        return invoke(builder, "build")!!
    }

    private fun format(input: Boolean) = AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(if (input) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_OUT_MONO).build()

    // A stop during native construction waits for construction before releasing everything.
    @Synchronized
    /** [onFailure] receives an engine failure code (CALL_ENDED, CAPTURE_SILENCED, CAPTURE_FAILED, or a model AudioFailure code). */
    fun start(onPcm: (CallDirection, ShortArray) -> Unit, onFailure: (String) -> Unit) {
        check(!closed.get() && alive.compareAndSet(false, true)) { "Audio route has already started or been released" }
        failure = onFailure
        try {
            check(context.checkSelfPermission(CALL_PERMISSION) == PackageManager.PERMISSION_GRANTED) { "The system has not granted call-audio access" }
            requireActiveTarget(context, targetUid, microphone)
            val downMix = mix(false)
            val upMix = mix(true)
            val builder = type("android.media.audiopolicy.AudioPolicy\$Builder").getConstructor(Context::class.java).newInstance(context)
            val mixClass = type("android.media.audiopolicy.AudioMix")
            invoke(builder, "addMix", mixClass to downMix)
            invoke(builder, "addMix", mixClass to upMix)
            policy = invoke(builder, "build")!!
            val policyClass = type("android.media.audiopolicy.AudioPolicy")
            val registered = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass).invoke(audio, policy) as Int
            check(registered == 0) { "The system refused to register the target app's audio route" }
            downlink = invoke(policy!!, "createAudioRecordSink", mixClass to downMix) as AudioRecord
            uplink = invoke(policy!!, "createAudioTrackSource", mixClass to upMix) as AudioTrack
            check(downlink!!.state == AudioRecord.STATE_INITIALIZED && uplink!!.state == AudioTrack.STATE_INITIALIZED) { "The device could not initialize the call audio path" }
            if (microphone) {
                check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Allow microphone access first" }
                listening = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(format(false)).setBufferSizeInBytes(6400).build()
                // A second private VOICE_COMMUNICATION client loses to the call owner even
                // with CAPTURE_AUDIO_OUTPUT. ASR is a regular capture client; the protected
                // capture/bypass permission permits it alongside the private call client.
                mic = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setPrivacySensitive(false)
                    .setAudioFormat(format(true)).setBufferSizeInBytes(6400).build()
                check(mic!!.state == AudioRecord.STATE_INITIALIZED) { "Device microphone unavailable" }
                if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(mic!!.audioSessionId)?.also { it.enabled = true }
            }
            audio.addOnModeChangedListener(context.mainExecutor, modeListener)
            downlink!!.registerAudioRecordingCallback(context.mainExecutor, recordingCallback)
            mic?.registerAudioRecordingCallback(context.mainExecutor, recordingCallback)
            capturing.set(true)
            downlink!!.startRecording()
            mic?.startRecording()
            capture(downlink!!, CallDirection.INCOMING, onPcm)
            mic?.let { capture(it, CallDirection.OUTGOING, onPcm) }
            if (microphone) main.postDelayed(monitorTarget, 1000)
        } catch (error: Exception) { close(); throw error }
    }

    private fun launch(name: String, action: () -> Unit) {
        val worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try { action() } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                // Keep the real cause: a model failure in onPcm (e.g. QUEUE_BACKLOG) is not a call end.
                if (alive.get()) failure((error as? AudioFailure)?.code ?: "CAPTURE_FAILED")
            }
        }, "taku-call-$name")
        synchronized(workers) { workers.add(worker) }; worker.start()
    }

    private fun capture(record: AudioRecord, direction: CallDirection, onPcm: (CallDirection, ShortArray) -> Unit) = launch(direction.name.lowercase()) {
        while (alive.get() && capturing.get()) {
            val frame = ShortArray(FRAME_SAMPLES)
            val count = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
            if(!capturing.get())break
            check(count > 0) { "AudioRecord read failed" }
            if (direction == CallDirection.INCOMING) incomingSamples.addAndGet(count.toLong()) else outgoingSamples.addAndGet(count.toLong())
            if (alive.get() && capturing.get()) onPcm(direction, if (count == frame.size) frame else frame.copyOf(count))
        }
    }

    fun stopInput() {
        capturing.set(false)
        listOf(downlink,mic).forEach { runCatching { it?.stop() } }
    }

    /**
     * Stops capture and waits until the capture threads have exited, so no [start] PCM callback
     * is running or will run afterwards. Returns false if a thread is still alive at the
     * deadline. Blocking: call from a worker thread, never the main or Binder thread.
     */
    fun stopInputAndJoin(timeoutMs: Long = 3000): Boolean {
        stopInput()
        val threads = synchronized(workers) { workers.toList() }.filter { it != Thread.currentThread() }
        // Runs in teardown, where close() may interrupt this thread: never let that skip the wait.
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        for (thread in threads) joinUninterruptibly(thread, maxOf(1, deadline - SystemClock.elapsedRealtime()))
        if (threads.any { it.isAlive }) { threads.forEach { it.interrupt() }; threads.forEach { joinUninterruptibly(it, 500) } }
        return threads.none { it.isAlive }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        alive.set(false)
        stopInput()
        main.removeCallbacks(monitorTarget)
        runCatching { audio.removeOnModeChangedListener(modeListener) }
        workers.forEach { it.interrupt() }
        listOf(downlink, mic).forEach { record -> runCatching { record?.unregisterAudioRecordingCallback(recordingCallback) }; runCatching { record?.stop() }; runCatching { record?.release() } }
        listOf(uplink, listening).forEach { track -> runCatching { track?.pause(); track?.flush(); track?.release() } }
        runCatching { echo?.release() }
        val restored = policy?.let { value -> runCatching { audio.javaClass.getMethod("unregisterAudioPolicy", type("android.media.audiopolicy.AudioPolicy")).invoke(audio, value) } }
        policy = null
        synchronized(workers) { workers.toList() }.forEach { joinUninterruptibly(it, 500) }
        check(restored?.isFailure != true) { "Audio route release could not be confirmed. Check the original call" }
    }
}
