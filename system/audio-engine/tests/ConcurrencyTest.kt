// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #26 regressions, on the real AudioRuntime, RouteGate and FeedGate.
 * #2: stop while the driver is starting its route must not deadlock.
 * #3: a native resource is never released while a feeding thread is inside it.
 */

/** Media/call driver shape: worker starts the route under RouteGate, then reports ready. */
private class StartingDriver(private val inRouteStart: CountDownLatch,private val releaseRouteStart: CountDownLatch) : AudioDriver {
    val route=RouteGate()
    val routeStopped=AtomicInteger()
    var worker: Thread?=null
    override fun start(sink: AudioDriver.Sink) {
        worker=Thread({
            var started=false
            try {
                started=route.start({inRouteStart.countDown();awaitUninterruptibly(releaseRouteStart)},{routeStopped.incrementAndGet()})
                if(started)sink.ready()
            } finally { sink.released(true) }
        },"test-route-start").also{it.start()}
    }
    override fun finish() {}
    override fun close(){route.cancel{routeStopped.incrementAndGet()};worker?.interrupt()}
}

/** Platform route starts (AudioRecord.startRecording, policy registration) are not interruptible. */
private fun awaitUninterruptibly(latch: CountDownLatch) {
    var interrupted=false
    while(true) try { check(latch.await(10,TimeUnit.SECONDS));break } catch(_: InterruptedException){interrupted=true}
    if(interrupted)Thread.currentThread().interrupt()
}

private fun deadlocked(): String? {
    val ids=ManagementFactory.getThreadMXBean().findDeadlockedThreads() ?: return null
    return ManagementFactory.getThreadMXBean().getThreadInfo(ids,true,true).joinToString("\n")
}

private fun stopDuringRouteStart() {
    val inStart=CountDownLatch(1);val release=CountDownLatch(1)
    val drivers=mutableListOf<StartingDriver>()
    val runtime=AudioRuntime { StartingDriver(inStart,release).also{drivers.add(it)} }
    val events=java.util.Collections.synchronizedList(mutableListOf<AudioEvent>())
    val id=runtime.open("owner",AudioRequest(),{events.add(it)})
    val driver=drivers.single()
    check(inStart.await(5,TimeUnit.SECONDS)){"worker never entered the route start"}
    // Worker holds the route lock. Stop from another thread, holding the runtime monitor.
    val closed=CountDownLatch(1)
    val stopper=Thread({runtime.close("owner",id);closed.countDown()},"test-stop")
    stopper.start()
    // Before the fix, close() blocked on the route lock here while holding the runtime monitor.
    check(closed.await(5,TimeUnit.SECONDS)){"close blocked on the route lock\n${deadlocked()}"}
    release.countDown()
    driver.worker!!.join(5000);stopper.join(5000)
    check(!driver.worker!!.isAlive){"worker stuck\n${deadlocked()}"}
    check(deadlocked()==null){"deadlock: ${deadlocked()}"}
    // The route started after close() set the flag, so the worker stopped it itself and never became Running.
    check(driver.routeStopped.get()>=1){"route left running after cancel"}
    check(events.none{it.phase==AudioPhase.Running}){"canceled session reported Running"}
    check(events.last().phase==AudioPhase.Closed && events.last().reason=="canceled")
    check(!runtime.resources("owner").busy)
}

private fun stopBeforeAndAfterRouteStart() {
    // Canceled before start: the route is never started.
    val early=RouteGate();var ran=false
    early.cancel{};check(!early.start({ran=true},{}) && !ran)
    // Canceled after start: cancel() takes the free lock and stops the route.
    val late=RouteGate();var stops=0
    check(late.start({},{stops++}));late.cancel{stops++};check(stops==1)
}

private fun releaseWaitsForInFlightFeed() {
    val gate=FeedGate()
    val inside=CountDownLatch(1);val leave=CountDownLatch(1)
    val freed=AtomicBoolean();val usedAfterFree=AtomicBoolean()
    val feeder=Thread({gate.use{inside.countDown();leave.await();if(freed.get())usedAfterFree.set(true)}},"test-feeder")
    feeder.start();check(inside.await(5,TimeUnit.SECONDS))
    val releaser=Thread({check(gate.retire(5000){freed.set(true)})},"test-release")
    releaser.start()
    Thread.sleep(200)
    check(!freed.get()){"released while a feed call was in flight"}
    leave.countDown();feeder.join(5000);releaser.join(5000)
    check(freed.get() && !usedAfterFree.get())
    // After retirement new frames are refused instead of reaching the freed resource.
    var refused=false
    try{gate.use{}}catch(_: InterruptedException){refused=true}
    check(refused && gate.isRetired)
}

private fun releaseTimesOutWithoutFreeing() {
    val gate=FeedGate();val inside=CountDownLatch(1);val leave=CountDownLatch(1)
    val feeder=Thread({gate.use{inside.countDown();leave.await()}}).also{it.start()}
    check(inside.await(5,TimeUnit.SECONDS))
    var freed=false
    check(!gate.retire(100){freed=true}){"retire reported success with a call in flight"}
    check(!freed){"stuck caller: resource must be leaked, not freed"}
    leave.countDown();feeder.join(5000)
}

/** #1 (review): a cancel interrupting teardown must not cut the wait for a feeder short. */
private fun interruptedCleanupStillWaits() {
    val stop=CountDownLatch(1);val finished=AtomicBoolean()
    val feeder=Thread({awaitUninterruptibly(stop);Thread.sleep(100);finished.set(true)},"test-capture").also{it.start()}
    var waited=false;var flagRestored=false
    val cleanup=Thread({waited=joinUninterruptibly(feeder,5000);flagRestored=Thread.currentThread().isInterrupted},"test-cleanup")
    cleanup.start();Thread.sleep(100)
    cleanup.interrupt()            // what close() does to the driver worker
    Thread.sleep(50);check(cleanup.isAlive){"interrupt cut the join short"}
    stop.countDown();cleanup.join(5000)
    check(waited && finished.get()){"cleanup returned before the feeder ended"}
    check(flagRestored){"interrupt flag lost"}
}

private fun interruptedRetireStillWaits() {
    val gate=FeedGate();val inside=CountDownLatch(1);val leave=CountDownLatch(1);val freed=AtomicBoolean()
    val feeder=Thread({gate.use{inside.countDown();awaitUninterruptibly(leave)}}).also{it.start()}
    check(inside.await(5,TimeUnit.SECONDS))
    var result=false;var flagRestored=false
    val cleanup=Thread({result=gate.retire(5000){freed.set(true)};flagRestored=Thread.currentThread().isInterrupted}).also{it.start()}
    Thread.sleep(100);cleanup.interrupt();Thread.sleep(50)
    check(!freed.get()){"retire released after an interrupt while a feed call was in flight"}
    leave.countDown();cleanup.join(5000);feeder.join(5000)
    check(result && freed.get() && flagRestored)
}

/** #3 (review): validation and session open share the BYOK-dependent default media profile. */
private fun defaultMediaProfile() {
    check(AudioProfiles.defaultMedia(null,false)=="fal-kotoba")
    check(AudioProfiles.defaultMedia("grok",false)=="fal-grok")
    check(AudioProfiles.defaultMedia("whisper",false)=="fal-whisper")
    check(AudioProfiles.defaultMedia("whisper",true)=="fal-kotoba"){"calls cannot use captions-only Whisper"}
    check(AudioProfiles.defaultMedia("unknown",false)=="fal-kotoba")
}

private fun finishRacesAutomaticInputEnd() {
    val start=CountDownLatch(1)
    val finishes=AtomicInteger()
    lateinit var sink: AudioDriver.Sink
    val driver=object : AudioDriver {
        override fun start(value: AudioDriver.Sink){sink=value;value.ready()}
        override fun finish(){finishes.incrementAndGet()}
        override fun close(){}
    }
    val runtime=AudioRuntime{driver}
    val events=java.util.Collections.synchronizedList(mutableListOf<AudioEvent>())
    val id=runtime.open("owner",AudioRequest(profileId="fal-whisper"),{events.add(it)})
    val user=Thread({awaitUninterruptibly(start);repeat(100){runtime.finish("owner",id)}})
    val source=Thread({awaitUninterruptibly(start);repeat(100){sink.inputEnded()}})
    user.start();source.start();start.countDown()
    user.join(5000);source.join(5000)
    check(!user.isAlive && !source.isAlive && finishes.get()==1)
    check(events.count{it.phase==AudioPhase.Draining}==1)
    val deadline=runtime.drainDeadlineMonotonicMs("owner",id)
    runtime.finish("owner",id)
    check(deadline!=null && runtime.drainDeadlineMonotonicMs("owner",id)==deadline)
    runtime.close("owner",id)
    check(runtime.resources("owner").busy)
    sink.text("late result",true);sink.completed();sink.released()
    check(events.none{it.text=="late result"})
    check(events.count{it.type=="Closed"}==1 && events.last().reason=="canceled")
    check(!runtime.resources("owner").busy)
}

fun main() {
    repeat(20){stopDuringRouteStart()}
    stopBeforeAndAfterRouteStart()
    releaseWaitsForInFlightFeed()
    releaseTimesOutWithoutFreeing()
    interruptedCleanupStillWaits()
    interruptedRetireStillWaits()
    defaultMediaProfile()
    repeat(20){finishRacesAutomaticInputEnd()}
    println("PASS ConcurrencyTest: route start/stop without deadlock; native release waits for feeders, also when interrupted; shared default media profile")
}
