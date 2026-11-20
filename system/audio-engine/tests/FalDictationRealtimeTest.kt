// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private fun measuredLevels() {
    val levels=mutableListOf<Pair<Float,Int>>()
    val meter=PcmLevelMeter { level,frames -> levels.add(level to frames) }
    meter.accept(ShortArray(700),700)
    check(levels.isEmpty())
    meter.accept(ShortArray(900),900)
    check(levels.single()==(0f to 1600))
    meter.accept(ShortArray(1600){Short.MIN_VALUE},1600)
    check(levels.last()==(1f to 1600))
    meter.accept(ShortArray(1600){3276},1600)
    check(levels.last().first in .6665f.. .6668f && levels.last().second==1600)
    val offset=ShortArray(802){Short.MAX_VALUE}
    for(index in 1..800)offset[index]=0
    meter.accept(offset,800,1)
    meter.accept(ShortArray(800){Short.MIN_VALUE},800)
    check(levels.last().first in .9497f.. .9500f)
    meter.accept(ShortArray(71){3276},71)
    check(levels.size==4)
    meter.flush()
    check(levels.last().second==71 && levels.last().first in .6665f.. .6668f)
    meter.flush()
    check(levels.size==5)
}

private fun silentInputGate() {
    check(!WhisperSignalGate.hasSignal(ShortArray(32000)))
    check(!WhisperSignalGate.hasSignal(ShortArray(32000) { 2000 })) // DC is not speech.
    val noise = java.util.Random(7)
    check(!WhisperSignalGate.hasSignal(ShortArray(32000) { (noise.nextInt(257) - 128).toShort() }))
    val click = ShortArray(32000).apply { this[16000] = Short.MAX_VALUE }
    check(!WhisperSignalGate.hasSignal(click))
    val bursts = ShortArray(32000) { if (it % 1600 < 320) (if (it % 2 == 0) 1000 else -1000).toShort() else 0 }
    check(!WhisperSignalGate.hasSignal(bursts)) // Isolated clicks, not a sustained phrase.
    val speech = ShortArray(1600) { (600 * kotlin.math.sin(it * 2 * Math.PI * 220 / 16000)).toInt().toShort() }
    check(WhisperSignalGate.hasSignal(speech)) // Short sustained input still reaches FAL.
    val padded = ShortArray(32000)
    speech.copyInto(padded, 16000)
    check(WhisperSignalGate.hasSignal(padded))
    check(WhisperSignalGate.hasSignal(padded, 1600, 16000))
    check(!WhisperSignalGate.hasSignal(padded, 16000))
    check(!WhisperSignalGate.hasSignal(padded, 0, padded.size))
    check(runCatching { WhisperSignalGate.hasSignal(padded, 1, padded.size) }.isFailure)
}

private fun coalescedRanges() {
    val ranges=WhisperPcmRanges()
    ranges.publish(31999)
    val entered=CountDownLatch(1)
    val first=FutureTask { entered.countDown();ranges.next(0) }
    val worker=Thread(first,"fal-range-test")
    worker.start()
    try {
        check(entered.await(2,TimeUnit.SECONDS))
        check(runCatching{first.get(50,TimeUnit.MILLISECONDS)}.exceptionOrNull() is TimeoutException)
        ranges.publish(32000)
        val preview=first.get(2,TimeUnit.SECONDS)!!
        check(preview.offset==0 && preview.count==32000 && !preview.final)
        ranges.publish(36000)
        ranges.publish(64000)
        ranges.publish(96000)
        val next=ranges.next(preview.count)!!
        check(next.offset==32000 && next.count==64000 && !next.final)
        ranges.publish(100000)
        ranges.seal()
        val final=ranges.next(next.offset+next.count)!!
        check(final.offset==0 && final.count==100000 && final.final)
    } finally {ranges.cancel();worker.join(2000)}
    check(!worker.isAlive)

    val empty=WhisperPcmRanges()
    empty.seal()
    check(empty.next(0)==WhisperPcmRanges.Range(0,0,true))
}

private fun failedPreviewKeepsAudioWithoutSpinning() {
    val ranges=WhisperPcmRanges()
    ranges.publish(32000)
    val failed=ranges.next(0)!!
    val retry=FutureTask { ranges.next(0,failed.count) }
    val worker=Thread(retry);worker.start()
    try {
        check(runCatching { retry.get(50,TimeUnit.MILLISECONDS) }.exceptionOrNull() is TimeoutException)
        ranges.publish(64000)
        check(retry.get(2,TimeUnit.SECONDS)==WhisperPcmRanges.Range(0,64000,false))
        ranges.seal()
        check(ranges.next(0,64000)==WhisperPcmRanges.Range(0,64000,true))
    } finally { ranges.cancel();worker.join(2000) }
}

private fun canceledRangeWait() {
    val ranges=WhisperPcmRanges()
    val entered=CountDownLatch(1)
    val pending=FutureTask { entered.countDown();ranges.next(0) }
    val worker=Thread(pending,"fal-cancel-test")
    worker.start()
    try {
        check(entered.await(2,TimeUnit.SECONDS))
        ranges.cancel()
        check(pending.get(2,TimeUnit.SECONDS)==null)
        ranges.publish(32000)
        ranges.seal()
        check(ranges.next(0)==null)
    } finally {ranges.cancel();worker.join(2000)}
    check(!worker.isAlive)
}

private class FalTestDriver : AudioDriver {
    lateinit var sink: AudioDriver.Sink
    var finishes=0
    var closes=0
    override fun start(sink: AudioDriver.Sink){this.sink=sink}
    override fun finish(){finishes++}
    override fun close(){closes++}
}

private fun finalCorrectionAndLevels() {
    val driver=FalTestDriver()
    val events=mutableListOf<AudioEvent>()
    val runtime=AudioRuntime(clock={1234L},schedule={_,_ -> {}}){driver}
    val id=runtime.open("owner",AudioRequest(profileId="fal-whisper"),events::add)
    driver.sink.level(.4f,1600)
    check(events.none{it.type=="Level"})
    driver.sink.ready()
    driver.sink.text("first words",false)
    driver.sink.level(.4f,1600)
    driver.sink.text("first words and more",false)
    driver.sink.level(Float.NaN,1600)
    driver.sink.level(Float.POSITIVE_INFINITY,1600)
    driver.sink.level(.5f,0)
    driver.sink.level(2f,1600)
    val levels=events.filter{it.type=="Level"}
    check(levels.size==2 && levels[0].level==.4f && levels[1].level==1f)
    check(levels.all{it.segment==0L && it.frames==1600L && it.sampleRate==16000 && it.monotonicMs==1234L})
    runtime.finish("owner",id)
    driver.sink.level(.7f,1600)
    driver.sink.text("",false)
    driver.sink.text("",true)
    val texts=events.filter{it.type=="Text"}
    check(texts.map{it.text}==listOf("first words","first words and more",""))
    check(texts.all{it.segment==0L} && texts.map{it.final}==listOf(false,false,true))
    driver.sink.released();driver.sink.completed()
    driver.sink.level(.7f,1600)
    check(events.count{it.type=="Level"}==2)
    check(events.zipWithNext().all{(before,after)->after.sequence==before.sequence+1})
    check(events.last().reason=="completed" && events.last().completion=="complete" && events.last().restored)
    check(!runtime.resources("owner").busy)

    val speech=FalTestDriver()
    val speechEvents=mutableListOf<AudioEvent>()
    val speaking=AudioRuntime {speech}
    val speechId=speaking.open("owner",AudioRequest(purpose="SPEAK"),speechEvents::add)
    speech.sink.ready();speech.sink.level(.4f,1600);speech.sink.text("",true)
    check(speechEvents.none{it.type=="Level" || it.type=="Text"})
    speaking.close("owner",speechId);speech.sink.released()
}

private fun originalFalDeadline() {
    var now=1000L
    val alarms=mutableListOf<()->Unit>()
    val driver=FalTestDriver()
    val events=mutableListOf<AudioEvent>()
    val runtime=AudioRuntime(clock={now},schedule={delay,action ->
        check(delay==100000L)
        alarms.add(action)
        val cancel: ()->Unit = {}
        cancel
    }){driver}
    val id=runtime.open("owner",AudioRequest(profileId="fal-whisper"),events::add)
    driver.sink.ready();driver.sink.text("preview",false)
    runtime.finish("owner",id)
    check(runtime.drainDeadlineMonotonicMs("owner",id)==101000L)
    now=70000L
    runtime.finish("owner",id);driver.sink.inputEnded();driver.sink.text("later preview",false)
    check(driver.finishes==1 && alarms.size==1 && runtime.drainDeadlineMonotonicMs("owner",id)==101000L)
    now=101000L
    alarms.single().invoke()
    check(driver.closes==1 && runtime.resources("owner").busy)
    driver.sink.text("late final",true);driver.sink.completed()
    check(events.none{it.text=="late final"})
    check((runCatching{runtime.open("next",AudioRequest(profileId="fal-whisper"),{})}.exceptionOrNull() as AudioFailure).code=="RESOURCE_BUSY")
    driver.sink.released()
    check(!runtime.resources("owner").busy && events.last().reason=="PROCESSING_TIMEOUT")
    val next=runtime.open("next",AudioRequest(profileId="fal-whisper"),{})
    runtime.close("next",next);driver.sink.released()
}

fun main() {
    measuredLevels()
    silentInputGate()
    coalescedRanges()
    failedPreviewKeepsAudioWithoutSpinning()
    canceledRangeWait()
    finalCorrectionAndLevels()
    originalFalDeadline()
    println("PASS FalDictationRealtimeTest: measured levels, silence/DC/click gate, coalesced ranges, cancellation, empty final correction and original deadline")
}
