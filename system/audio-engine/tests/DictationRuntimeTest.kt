// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.agentos.audio

private class DictationTestDriver : AudioDriver {
    lateinit var sink: AudioDriver.Sink
    var finishes=0
    var closes=0
    override fun start(sink: AudioDriver.Sink){this.sink=sink;sink.ready()}
    override fun finish(){finishes++}
    override fun close(){closes++}
}

private class DictationClock {
    data class Alarm(val at: Long,val action: ()->Unit,var canceled: Boolean=false)
    var now=1000L
    val alarms=mutableListOf<Alarm>()
    fun schedule(delay: Long,action: ()->Unit): ()->Unit {
        val alarm=Alarm(now+delay,action)
        alarms.add(alarm)
        return {alarm.canceled=true}
    }
    fun advance(time: Long) {
        now=time
        alarms.filter{!it.canceled && it.at<=now}.forEach{it.canceled=true;it.action()}
    }
}

private fun scopedIdentityAndEvents() {
    val driver=DictationTestDriver()
    val clock=DictationClock()
    var creates=0
    val runtime=AudioRuntime(clock={clock.now},schedule=clock::schedule){creates++;driver}
    val cloud=AudioRequest(language="en-US",profileId="fal-whisper")
    val metadata=DictationSessionMetadata("assistant-entry",42,"zipformer","CLOUD_AUDIO","MODEL_MISSING",60000,100000)
    val events=mutableListOf<AudioEvent>()
    val first=runtime.open("desktop",cloud,events::add,"gesture",metadata)
    check(events.first().phase==AudioPhase.Preparing && events.first().dictation==metadata)
    check(events.all{it.profileId=="fal-whisper" && it.language=="en-US" && it.dictation==metadata})
    check(runtime.findRequest("desktop","gesture")==first && runtime.findRequest("other","gesture")==null)
    val changed=metadata.copy(settingsRevision=43,requestedProfileId="zipformer",processing="LOCAL",fallbackReason=null,maxCaptureMs=null,drainTimeoutMs=5000)
    check(runtime.open("desktop",AudioRequest(),events::add,"gesture",changed)==first)
    check(creates==1 && runtime.request("desktop",first)==cloud && runtime.dictationMetadata("desktop",first)==metadata)
    check((runCatching{runtime.open("desktop",cloud,events::add,"gesture")}.exceptionOrNull() as AudioFailure).code=="REQUEST_CONFLICT")
    check((runCatching{runtime.open("desktop",cloud,events::add,"gesture",metadata.copy(settingsScope="other"))}.exceptionOrNull() as AudioFailure).code=="REQUEST_CONFLICT")
    check((runCatching{runtime.dictationMetadata("other",first)}.exceptionOrNull() as AudioFailure).code=="PERMISSION_DENIED")
    runtime.close("desktop",first)
    driver.sink.released()
    check(runtime.open("desktop",AudioRequest(),events::add,"gesture",changed)==first)
    val second=runtime.open("desktop",AudioRequest(),events::add,"next-gesture",changed)
    check(runtime.request("desktop",second).profileId=="zipformer" && runtime.dictationMetadata("desktop",second)?.settingsRevision==43L)
    runtime.close("desktop",second);driver.sink.released()
}

private fun absoluteDeadline(automatic: Boolean,profile: String,timeout: Long) {
    val driver=DictationTestDriver()
    val clock=DictationClock()
    val events=mutableListOf<AudioEvent>()
    val runtime=AudioRuntime(clock={clock.now},schedule=clock::schedule){driver}
    val id=runtime.open("owner",AudioRequest(profileId=profile),events::add)
    driver.sink.text("unfinished preview",false)
    if(automatic)driver.sink.inputEnded()else runtime.finish("owner",id)
    check(runtime.status("owner",id).phase==AudioPhase.Draining)
    check(runtime.drainDeadlineMonotonicMs("owner",id)==1000+timeout)
    check(events.last().drainDeadlineMonotonicMs==1000+timeout)
    check(events.last().dictation?.drainTimeoutMs==timeout)
    if(automatic)check(events.any{it.type=="SourceEnded"})
    clock.advance(1000+timeout-1)
    runtime.finish("owner",id);driver.sink.inputEnded()
    check(driver.finishes==1 && clock.alarms.size==1)
    check(runtime.status("owner",id).phase==AudioPhase.Draining)
    clock.advance(1000+timeout)
    check(runtime.resources("owner").busy && runtime.status("owner",id).phase==AudioPhase.Stopping)
    check(driver.closes==1)
    driver.sink.text("late success",true);driver.sink.completed()
    check(events.none{it.text=="late success"})
    driver.sink.released()
    check(!runtime.resources("owner").busy)
    val closed=runtime.status("owner",id)
    check(closed.reason=="PROCESSING_TIMEOUT" && closed.completion=="incomplete" && closed.restored)
    driver.sink.released();driver.sink.completed();clock.alarms.single().action()
    check(events.count{it.type=="Closed"}==1)
}

private fun failureAndCleanupKeepOwnership() {
    val driver=DictationTestDriver()
    val clock=DictationClock()
    val events=mutableListOf<AudioEvent>()
    val runtime=AudioRuntime(clock={clock.now},schedule=clock::schedule){driver}
    val id=runtime.open("owner",AudioRequest(),events::add)
    check(events.first().dictation?.processing=="LOCAL_ONLY")
    driver.sink.text("partial text survives as preview",false)
    runtime.finish("owner",id)
    driver.sink.failed("NETWORK_FAILED")
    check(runtime.resources("owner").busy)
    check((runCatching{runtime.open("other",AudioRequest(),{})}.exceptionOrNull() as AudioFailure).code=="RESOURCE_BUSY")
    clock.advance(9000);driver.sink.completed()
    driver.sink.released()
    check(runtime.status("owner",id).reason=="NETWORK_FAILED")
    check(runtime.status("owner",id).completion=="incomplete")
    check(events.single{it.type=="Text"}.text=="partial text survives as preview")
    val second=runtime.open("owner",AudioRequest(),events::add)
    runtime.finish("owner",second)
    driver.sink.text("complete tail",true)
    driver.sink.released()
    check(runtime.resources("owner").busy)
    driver.sink.completed()
    check(runtime.status("owner",second).reason=="completed" && runtime.status("owner",second).completion=="complete")
    clock.advance(20000)
    check(runtime.status("owner",second).reason=="completed" && !runtime.resources("owner").busy)
    check(events.last().dictation?.drainTimeoutMs==5000L)
}

private fun completionCannotWinAfterDeadline() {
    val driver=DictationTestDriver()
    val clock=DictationClock()
    val runtime=AudioRuntime(clock={clock.now},schedule=clock::schedule){driver}
    val id=runtime.open("owner",AudioRequest(profileId="fal-whisper"),{})
    runtime.finish("owner",id)
    clock.now=101000
    driver.sink.text("too late",true)
    driver.sink.released();driver.sink.completed()
    check(runtime.status("owner",id).reason=="PROCESSING_TIMEOUT" && runtime.status("owner",id).completion=="incomplete")
    check(!runtime.resources("owner").busy)
}

private fun speechPreparationStillTimesOut() {
    val clock=DictationClock()
    lateinit var sink: AudioDriver.Sink
    val driver=object : AudioDriver {
        override fun start(value: AudioDriver.Sink){sink=value}
        override fun submit(index: Long,text: String){}
        override fun finish(){}
        override fun close(){}
    }
    val runtime=AudioRuntime(clock={clock.now},schedule=clock::schedule){driver}
    val id=runtime.open("reader",AudioRequest(purpose="SPEAK"),{})
    runtime.append("reader",id,0,"Waiting for the model")
    runtime.finish("reader",id)
    clock.advance(6000)
    check(runtime.status("reader",id).phase==AudioPhase.Stopping)
    sink.ready();sink.released()
    check(runtime.status("reader",id).reason=="PROCESSING_TIMEOUT" && !runtime.resources("reader").busy)
}

fun main() {
    scopedIdentityAndEvents()
    for(automatic in listOf(false,true)) {
        absoluteDeadline(automatic,"zipformer",5000)
        absoluteDeadline(automatic,"fal-whisper",100000)
    }
    failureAndCleanupKeepOwnership()
    completionCannotWinAfterDeadline()
    speechPreparationStillTimesOut()
    println("PASS DictationRuntimeTest: immutable scoped sessions, request intent conflicts, automatic and explicit absolute deadlines, failed partials and cleanup ownership")
}
