// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio.tests

import android.app.Instrumentation
import android.os.Bundle
import org.agentos.audio.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the review's teardown and config boundaries on ART; no cloud request. */
class ReviewRegressionProbe : Instrumentation() {
    private var options = Bundle()
    override fun onCreate(args: Bundle?) { super.onCreate(args); options = args ?: Bundle(); start() }
    private fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) }
    override fun onStart() {
        val report = JSONObject().put("passed", false).put("cloudTranslationVerified", false)
        try {
            when (options.getString("mode", "cleanup")) {
                "config" -> {
                    val snapshot = EngineConfig.control(JSONObject().put("op", "read"))
                    val fal = snapshot.getJSONObject("fal")
                    check((if (fal.isNull("error")) "" else fal.getString("error")) == options.getString("error", "")) { fal.toString() }
                    options.getString("model")?.let { check(fal.getString("model") == it) }
                    options.getString("configured")?.let { check(fal.getBoolean("configured") == it.toBoolean()) }
                    report.put("fal", fal).put("configReadable", true)
                    options.getString("credentialError")?.let { expected ->
                        var caught: AudioFailure? = null
                        try { EngineConfig.control(JSONObject().put("op", "falCredential")) }
                        catch (failure: AudioFailure) { caught = failure }
                        check(caught?.code == expected) { "Expected credential error $expected" }
                        report.put("credentialError", caught.code).put("credentialMessage", caught.message)
                    }
                }
                "playbackCleanup" -> {
                    val entered = CountDownLatch(1); val release = CountDownLatch(1)
                    val sink = object : AudioDriver.Sink {
                        override fun ready() {}
                        override fun text(text: String, final: Boolean) {}
                        override fun completed() {}
                        override fun failed(code: String) { error(code) }
                        override fun released(restored: Boolean) {}
                        override fun playback(index: Long, state: String, frames: Long, rate: Int, nonSilent: Boolean) {
                            if (state != "audiblePcm") return
                            entered.countDown()
                            var interrupted = false
                            while (true) try { await(release); break } catch (_: InterruptedException) { interrupted = true }
                            if (interrupted) Thread.currentThread().interrupt()
                        }
                    }
                    val playback = StreamingPlayback(sink)
                    val playerField = StreamingPlayback::class.java.getDeclaredField("player").apply { isAccessible = true }
                    val track = playerField.get(playback) as android.media.AudioTrack
                    val workerField = StreamingPlayback::class.java.getDeclaredField("worker").apply { isAccessible = true }
                    val worker = workerField.get(playback) as Thread
                    val restored = AtomicBoolean()
                    try {
                        playback.accept(ByteArray(1920) { if (it % 2 == 0) 0 else 8 })
                        await(entered)
                        val cleanup = Thread { playback.close(); restored.set(Thread.currentThread().isInterrupted) }
                        cleanup.start(); Thread.sleep(60); cleanup.interrupt(); Thread.sleep(60)
                        check(cleanup.isAlive && track.state == android.media.AudioTrack.STATE_INITIALIZED)
                        release.countDown(); check(joinUninterruptibly(cleanup, 3000))
                        check(!worker.isAlive && restored.get() && track.state == android.media.AudioTrack.STATE_UNINITIALIZED)
                    } finally { release.countDown(); playback.close() }
                    report.put("realAudioTrack", true).put("releaseWaitedForWorker", true).put("interruptFlagRestored", true)
                }
                "captureFailure" -> {
                    val expected = options.getString("expected", "QUEUE_BACKLOG")
                    val uid = PlatformCallCapture.uidFor(targetContext, "org.taku.callfixture.target")
                    val failed = CountDownLatch(1)
                    val reason = java.util.concurrent.atomic.AtomicReference<String?>(null)
                    PlatformCallCapture(targetContext, uid, microphone = false).use { route ->
                        route.start({ _, _ ->
                            when (expected) {
                                "QUEUE_BACKLOG" -> throw AudioFailure("QUEUE_BACKLOG", "Synthetic model backpressure")
                                "CAPTURE_FAILED" -> throw java.io.IOException("Synthetic consumer failure")
                            }
                        }, { code -> reason.compareAndSet(null, code); failed.countDown() })
                        sendStatus(0, Bundle().apply { putString("phase", "route-active") })
                        check(failed.await(10, TimeUnit.SECONDS)) { "Expected capture failure did not arrive" }
                        check(reason.get() == expected) { "Expected $expected, got ${reason.get()}" }
                        check(route.stopInputAndJoin())
                    }
                    report.put("captureReason", reason.get()).put("routeReleased", true)
                        .put("scope", "Real Android dynamic call playback route; synthetic source and injected model errors")
                }
                "cleanup" -> {
                    repeat(20) {
                        val entered = CountDownLatch(1); val release = CountDownLatch(1)
                        val gate = RouteGate(); val stopped = AtomicBoolean()
                        val worker = Thread { gate.start({ entered.countDown(); await(release) }, { stopped.set(true) }) }
                        worker.start(); await(entered)
                        gate.cancel { stopped.set(true) }; release.countDown()
                        check(joinUninterruptibly(worker, 5000) && stopped.get())
                        val feed = FeedGate(); val feeding = CountDownLatch(1); val leave = CountDownLatch(1)
                        val freed = AtomicBoolean(); val restored = AtomicBoolean(); val cleaning = CountDownLatch(1)
                        val feeder = Thread { feed.use { feeding.countDown(); await(leave); check(!freed.get()) } }
                        feeder.start(); await(feeding)
                        val cleanup = Thread {
                            cleaning.countDown()
                            check(feed.retire(5000) { freed.set(true) })
                            restored.set(Thread.currentThread().isInterrupted)
                        }
                        cleanup.start(); await(cleaning); cleanup.interrupt()
                        Thread.sleep(20); check(!freed.get()); leave.countDown()
                        check(joinUninterruptibly(cleanup, 5000) && joinUninterruptibly(feeder, 5000))
                        check(freed.get() && restored.get())
                    }
                    report.put("routeCancelIterations", 20).put("interruptedRetireIterations", 20)
                        .put("scope", "Production RouteGate/FeedGate on ART with controlled worker barriers; not a live phone call")
                }
                else -> error("Unknown mode")
            }
            report.put("passed", true)
        } catch (error: Throwable) { report.put("error", error.javaClass.simpleName).put("message", error.message?.take(240)) }
        finish(if (report.optBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
    }
}
