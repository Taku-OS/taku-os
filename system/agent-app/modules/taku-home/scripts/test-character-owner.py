#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Host regression for the actual CharacterTryOn owner and shared contract.

Run with Python 3, JDK 17+ (JAVA_HOME), and kotlinc (TAKU_KOTLINC or PATH).
KOTLIN_LIB/TAKU_KOTLIN_LIB optionally selects the compiler's library directory.
TAKU_TEST_COROUTINES_JAR optionally supplies an already cached coroutines JAR;
otherwise KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar is used. No downloads occur.
Android framework/provider/Activity doubles exercise ownership, not Android UI,
ASR, permissions, camera, cloud generation, or the physical navigation gesture.
All generated sources, classes, hashes and logs stay in ignored repository out/.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
MODULE = HERE.parent
SOURCE_ROOT = MODULE.parents[3]
PRODUCTION = MODULE / 'android/src/main/java/org/agentos/agent/home/assistant'
BUILD = SOURCE_ROOT / 'out/character-owner-host'
SOURCES = [PRODUCTION / name for name in ('CharacterTryOn.kt', 'CharacterTryOnPolicy.kt', 'CharacterTextInvocation.kt',
                                        'CharacterVoiceHandoff.kt', 'VoiceGesture.kt', 'VoiceInput.kt', 'VoiceDelivery.kt',
                                        'ThreadProjection.kt')]
ENGINE = PRODUCTION / 'EngineVoiceInput.kt'
SESSION = PRODUCTION / 'AssistantSession.kt'
CONTRACT = SOURCE_ROOT / 'system/tryon/contract/org/taku/cosplay/api/CosplayContract.java'


def toolchain():
    java_home = os.environ.get('JAVA_HOME')
    javac = shutil.which('javac')
    if not java_home and not javac:
        raise SystemExit('Set JAVA_HOME to JDK 17 or newer')
    jdk = Path(java_home).expanduser() if java_home else Path(javac).resolve().parent.parent
    selected = os.environ.get('TAKU_KOTLINC') or shutil.which('kotlinc')
    if not selected:
        local = SOURCE_ROOT / 'out/toolchains/kotlin-2.3.20/kotlinc/bin/kotlinc'
        selected = str(local) if local.is_file() else None
    if not selected:
        raise SystemExit('Set TAKU_KOTLINC to an existing Kotlin JVM compiler or put kotlinc on PATH')
    kotlinc = Path(shutil.which(selected) or selected).expanduser().resolve()
    library = os.environ.get('KOTLIN_LIB') or os.environ.get('TAKU_KOTLIN_LIB')
    kotlin_lib = Path(library).expanduser() if library else kotlinc.parent.parent / 'lib'
    selected_coroutines = os.environ.get('TAKU_TEST_COROUTINES_JAR')
    coroutines = Path(selected_coroutines).expanduser() if selected_coroutines else kotlin_lib / 'kotlinx-coroutines-core-jvm.jar'
    json_override = os.environ.get('TAKU_TEST_JSON_JAR')
    json_candidates = list((Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) /
                            'caches/modules-2/files-2.1/org.json/json/20250517').glob('*/*.jar'))
    json_jar = Path(json_override).expanduser() if json_override else (json_candidates[0] if json_candidates else Path('/missing-json.jar'))
    for path, help_text in (
        (jdk / 'bin/javac', 'Set JAVA_HOME to JDK 17 or newer'),
        (jdk / 'bin/java', 'Set JAVA_HOME to JDK 17 or newer'),
        (kotlinc, 'Set TAKU_KOTLINC to the Kotlin JVM compiler'),
        (kotlin_lib / 'kotlin-stdlib.jar', 'Set KOTLIN_LIB to the Kotlin compiler library directory'),
        (coroutines, 'Set TAKU_TEST_COROUTINES_JAR to an existing coroutines JAR; this test never downloads dependencies'),
        (json_jar, 'Set TAKU_TEST_JSON_JAR to the cached org.json JSON JAR; this test never downloads dependencies'),
    ):
        if not path.is_file():
            raise SystemExit(f'{help_text}; missing: {path}')
    return jdk, kotlinc, kotlin_lib, coroutines, json_jar


def session_fixture_source():
    """Run the production state/close/publish bodies without constructing Android's full session."""
    source = SESSION.read_text()
    state_start = source.index('enum class EntryMode')
    state_end = source.index('\n}\n', source.index('data class AssistantState(')) + len('\n}\n')

    def member(signature):
        start = source.index('  ' + signature)
        line_end = source.index('\n', start)
        if source[start:line_end].rstrip().endswith('}'):
            return source[start:line_end]
        # These methods' nested blocks are indented; their closing brace is at two spaces.
        end = source.index('\n  }', start) + len('\n  }')
        return source[start:end]

    handoff = re.search(r'handedOff = \{ ([^\n]+) \},', source)
    maximum = re.search(r'const val MAX_TEXT = ([0-9_]+)', source)
    assert handoff and maximum, 'Missing production handoff or text limit'
    return ('package org.agentos.agent.home.assistant\n'
            'import java.util.UUID\nimport kotlinx.coroutines.flow.MutableStateFlow\n'
            'import kotlinx.coroutines.flow.update\n' + source[state_start:state_end] + '\n' +
            'class AssistantSession {\n'
            ' val flow=MutableStateFlow(AssistantState())\n'
            ' internal lateinit var characterTryOn:CharacterTryOn\n'
            ' var liveCleanups=0;var voiceCleanups=0;var brokerStops=0;var draftRefreshes=0\n'
            ' val streamActive=true\n private val editor=Any()\n'
            ' private fun endLiveTalk(){liveCleanups++}\n'
            ' private fun revokeVoice(){voiceCleanups++;characterTryOn.clear()}\n'
            ' private fun refreshDraft(){draftRefreshes++}\n'
            ' fun stop(){brokerStops++}\n'
            ' private fun recoverFailure(editor:Any,code:String,text:String?):Unit=error("Unexpected stream recovery")\n'
            ' fun dismiss():Unit=error("Character handoff must not dismiss the conversation")\n'
            ' fun reset():Unit=error("Character handoff must not reset the conversation")\n'
            ' fun characterHandedOff(){' + handoff[1] + '}\n'
            ' fun replayTurn(turn:TurnView?){publish(turn)}\n'
            ' fun showTextEntry(){presentTextEntry()}\n' +
            member('fun closeEntry(') + '\n' +
            member('fun cancelCharacter(') + '\n' +
            member('private fun presentTextEntry(') + '\n' +
            member('private fun publish(turn:') + '\n' +
            ' companion object {const val MAX_TEXT=' + maximum[1] + '}\n}\n')

STUBS = {
'Os.kt': r'''package android.os
object SystemClock { @Volatile var now: Long = 1000; fun elapsedRealtime() = now }
class Bundle {
 private val values = linkedMapOf<String, Any>()
 fun putString(k:String,v:String){values[k]=v}; fun putLong(k:String,v:Long){values[k]=v}
 fun putBoolean(k:String,v:Boolean){values[k]=v}; fun putByteArray(k:String,v:ByteArray){values[k]=v}
 operator fun get(k:String):Any?=values[k]; fun getString(k:String):String?=values[k] as? String
 fun getLong(k:String):Long=(values[k] as? Long)?:0; fun getBoolean(k:String):Boolean=(values[k] as? Boolean)?:false
 fun keySet():Set<String> = values.keys; fun clear(){values.clear()}
}
''',
'Net.kt': r'''package android.net
data class Uri(val value:String){companion object{fun parse(value:String)=Uri(value)}}
''',
'Content.kt': r'''package android.content
import android.net.Uri
import android.os.Bundle
import android.content.pm.PackageManager
abstract class Context {
 abstract val packageManager:PackageManager; abstract val contentResolver:ContentResolver
 abstract fun startActivity(intent:Intent)
}
abstract class ContentResolver {abstract fun call(uri:Uri,method:String,arg:String?,extras:Bundle?):Bundle?}
data class ComponentName(val packageName:String,val className:String)
class Intent {
 var component:ComponentName?=null; var flags:Int=0; val extras=linkedMapOf<String,String>()
 fun setComponent(value:ComponentName):Intent{component=value;return this}
 fun addFlags(value:Int):Intent{flags=flags or value;return this}
 fun putExtra(key:String,value:String):Intent{extras[key]=value;return this}
 companion object {const val FLAG_ACTIVITY_NEW_TASK=0x10000000}
}
''',
'Packages.kt': r'''package android.content.pm
class Signature {fun toByteArray()=byteArrayOf(1,2,3)}
class SigningInfo {val apkContentsSigners=arrayOf(Signature())}
class ApplicationInfo(val packageName:String="org.taku.tryon",val uid:Int=10042)
class PackageInfo {val signingInfo:SigningInfo?=SigningInfo();val applicationInfo:ApplicationInfo?=ApplicationInfo()}
class ProviderInfo {val packageName="org.taku.tryon";val applicationInfo=ApplicationInfo()}
class PackageManager {
 fun getPackageInfo(name:String,flags:Int):PackageInfo=PackageInfo()
 fun checkSignatures(first:String,second:String):Int=SIGNATURE_MATCH
 fun resolveContentProvider(authority:String,flags:Int):ProviderInfo?=ProviderInfo()
 companion object {const val GET_SIGNING_CERTIFICATES=134217728;const val SIGNATURE_MATCH=0}
}
''',
'Bitmap.kt': r'''package android.graphics
import java.io.OutputStream
class Bitmap {
 val isRecycled:Boolean get()=error("No graphics execution in owner fixture")
 val width:Int get()=error("No graphics execution in owner fixture")
 val height:Int get()=error("No graphics execution in owner fixture")
 enum class Config {ARGB_8888}; enum class CompressFormat {JPEG}
 fun copy(config:Config,mutable:Boolean):Bitmap?=error("No graphics execution in owner fixture")
 fun compress(format:CompressFormat,quality:Int,output:OutputStream):Boolean=error("No graphics execution in owner fixture")
 fun recycle():Unit=error("No graphics execution in owner fixture")
 companion object {fun createScaledBitmap(source:Bitmap,width:Int,height:Int,filter:Boolean):Bitmap=error("No graphics execution in owner fixture")}
}
''',
}

HARNESS = r'''package org.agentos.agent.home.assistant
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import org.taku.cosplay.api.CosplayContract as C

private var assertions=0
private var cases=0
private fun expect(value:Boolean,message:String){check(value){message};assertions++}
private fun status(enabled:Boolean)=Bundle().apply {
 putString("schema",C.SCHEMA);putBoolean("ok",true);putBoolean("enabled",enabled);putBoolean("ready",enabled)
 putBoolean("keyConfigured",true);putBoolean("permissionsReady",true);putString("userMessage","Ready");putBoolean("voiceOnly",false)
}
private class Resolver:ContentResolver(){
 @Volatile var available=true;@Volatile var enabled=true;@Volatile var malformed=false
 @Volatile var statusEntered:CountDownLatch?=null;@Volatile var statusRelease:CountDownLatch?=null
 @Volatile var prepareEntered:CountDownLatch?=null;@Volatile var prepareRelease:CountDownLatch?=null
 @Volatile var payload:ByteArray?=null
 val statusCalls=AtomicInteger();val prepares=AtomicInteger();val cancellations=Collections.synchronizedList(mutableListOf<String>())
 override fun call(uri:Uri,method:String,arg:String?,extras:Bundle?):Bundle?=when(method){
  C.STATUS->{statusCalls.incrementAndGet();statusEntered?.countDown();statusRelease?.await(3,TimeUnit.SECONDS);if(available)status(enabled)else null}
  C.PREPARE->{
   val seq=prepares.incrementAndGet();payload=extras!!.get("jpeg") as ByteArray
   prepareEntered?.countDown();prepareRelease?.await(3,TimeUnit.SECONDS)
   Bundle().apply {putString("schema",C.SCHEMA);putBoolean("ok",true);putString("ticket",seq.toString(16).padStart(32,'0'))
    if(!malformed)putLong("expiresAtElapsedMs",SystemClock.elapsedRealtime()+C.TICKET_TTL_MS)
    putString("userMessage","Ready")}
  }
  C.CANCEL->{cancellations.add(extras!!.getString("ticket")!!);Bundle().apply{putBoolean("ok",true)}}
  else->error("Unexpected real operation $method")
 }
}
private class TestContext(val resolver:Resolver):Context(){
 override val packageManager=PackageManager();override val contentResolver:ContentResolver=resolver
 val launched=Collections.synchronizedList(mutableListOf<Intent>());@Volatile var launchFails=false
 override fun startActivity(intent:Intent){if(launchFails)throw IllegalStateException("Synthetic missing Activity");launched.add(intent)}
}
private class Fixture {
 val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
 val scope=CoroutineScope(SupervisorJob()+dispatcher)
 val resolver=Resolver();val context=TestContext(resolver)
 @Volatile var eligible=true;@Volatile var unlocked=true;@Volatile var state:CharacterTryOnState?=null
 @Volatile var textInput=false
 @Volatile var keepTalkingInput=false
 @Volatile var textFieldOpen=false
 val textPresentations=Collections.synchronizedList(mutableListOf<Boolean>())
 val textLease=CharacterTextInvocation()
 val voiceLease=CharacterVoiceHandoff()
 val session=AssistantSession()
 val handoffs=AtomicInteger()
 val owner=CharacterTryOn(context,scope,{id->when{ textInput->textLease.matches(id,SystemClock.elapsedRealtime());keepTalkingInput->voiceLease.eligible(id,SystemClock.elapsedRealtime());else->eligible}},{unlocked},{
  state=it
  if(textInput){if(textLease.closesTextInput(it?.phase))textFieldOpen=false;textPresentations.add(textFieldOpen)}
 },{session.characterHandedOff();handoffs.incrementAndGet()})
 init {session.characterTryOn=owner}
 suspend fun <T> onOwner(block:()->T):T=withContext(dispatcher){block()}
 suspend fun reference():ByteArray=onOwner {
  val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
  owner.offer("gesture_1234","com.zhiliaoapp.musically",950,975,jpeg);jpeg
 }
 suspend fun intercept():Boolean=onOwner{owner.intercept("I wanna cosplay as him.")}
 suspend fun textReference():ByteArray=onOwner {
  textInput=true;textFieldOpen=true
  check(textLease.reserve("system_text_1234",950,SystemClock.elapsedRealtime()))
  check(textLease.ready("system_text_1234",SystemClock.elapsedRealtime()))
  check(textLease.activate("system_text_1234",SystemClock.elapsedRealtime()))
  val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
  owner.offer("system_text_1234","com.zhiliaoapp.musically",950,975,jpeg);jpeg
 }
 suspend fun until(message:String,condition:()->Boolean){
  withTimeout(2500){while(!condition())delay(5)};expect(true,message)
 }
 suspend fun confirm(){onOwner{owner.confirm(state!!.requestId)}}
 suspend fun cancel(){onOwner{session.cancelCharacter(state!!.requestId)}}
 suspend fun dispose(){resolver.statusRelease?.countDown();resolver.prepareRelease?.countDown();onOwner{owner.clear()};scope.cancel();dispatcher.close()}
}
private suspend fun case(name:String,body:suspend Fixture.()->Unit){
 SystemClock.now=1000
 val f=Fixture();try{f.body();cases++;println("PASS $name")}finally{f.dispose()}
}
fun main()=runBlocking {
 case("successful ownership handoff"){
  val jpeg=reference();expect(intercept(),"final intercepted")
  until("confirming"){state?.phase==CharacterTryOnPhase.Confirming};confirm()
  until("handoff"){handoffs.get()==1};expect(context.launched.size==1,"one real Intent request")
  expect(context.launched.single().component?.className==C.ACTIVITY,"shared Activity contract")
  expect(resolver.cancellations.isEmpty(),"handed-off ticket retained")
  expect(jpeg.all{it==0.toByte()},"original reference erased")
  expect(resolver.payload!!.all{it==0.toByte()},"Binder copy erased after return")
  expect(state==null,"no lingering confirmation")
 }
 case("successful character handoff hides only the old assistant presentation"){
  val oldTurn=TurnView("old_turn","old question",false,false,"old reply","old reply",emptyList(),emptyList())
  val before=AssistantState(entry=EntryMode.Voice,island=IslandMode.Expanded,threadId="old_thread",
   activityId="old_activity",turn=oldTurn,draft="Keep my unsent draft",task="completed",taskId="old_task")
  onOwner{session.flow.value=before}
  reference();expect(intercept(),"fresh character final intercepted beside existing conversation")
  until("old-reply confirming"){state?.phase==CharacterTryOnPhase.Confirming};confirm()
  until("old-reply handoff"){handoffs.get()==1}
  expect(session.flow.value==before.copy(entry=EntryMode.None,island=IslandMode.Hidden),"success hides the card without losing thread, turn, draft or task")
  expect(session.liveCleanups==1&&session.voiceCleanups==1,"existing entry cleanups still run once")
  expect(session.brokerStops==0&&session.streamActive,"handoff does not stop a task or its stream")
  val later=oldTurn.copy(headline="Later old reply",reply="Later old reply")
  onOwner{session.replayTurn(later)}
  expect(session.flow.value.island==IslandMode.Hidden,"late ordinary turn update cannot cover the camera")
  expect(session.flow.value.turn==later&&session.flow.value.draft==before.draft,"hidden conversation continues receiving updates without losing the draft")
  onOwner{session.showTextEntry()}
  expect(session.flow.value.entry==EntryMode.Text&&session.flow.value.island==IslandMode.Expanded,"explicit text input can reopen the retained conversation")
  expect(session.flow.value.threadId==before.threadId&&session.flow.value.draft==before.draft&&session.brokerStops==0,"reopening preserves the same conversation and draft")
 }
 case("successful character handoff preserves a running ordinary task"){
  val oldTurn=TurnView("active_turn","old question",true,false,"Working",null,emptyList(),emptyList())
  val before=AssistantState(entry=EntryMode.Voice,island=IslandMode.Full,threadId="running_thread",
   turn=oldTurn,draft="Unsent notes",task="running",taskId="running_task",pending="Existing request")
  onOwner{session.flow.value=before}
  reference();intercept();until("running-task confirming"){state?.phase==CharacterTryOnPhase.Confirming};confirm()
  until("running-task handoff"){handoffs.get()==1}
  expect(session.flow.value==before.copy(entry=EntryMode.None,island=IslandMode.Hidden),"only the presentation changes while ordinary work stays active")
  expect(session.flow.value.working&&session.brokerStops==0&&session.streamActive,"running work is neither cancelled nor detached")
 }
 case("Not now preserves the original assistant island request"){
  val oldTurn=TurnView("old_turn","old question",false,false,"Old answer","Old answer",emptyList(),emptyList())
  val before=AssistantState(entry=EntryMode.Voice,island=IslandMode.Full,threadId="old_thread",turn=oldTurn,draft="Unsent draft")
  onOwner{session.flow.value=before}
  reference();intercept();until("cancel-with-history confirming"){state?.phase==CharacterTryOnPhase.Confirming};cancel()
  expect(session.flow.value==before.copy(entry=EntryMode.None),"ordinary close keeps the original full island and conversation")
  expect(handoffs.get()==0&&context.launched.isEmpty()&&session.brokerStops==0,"Not now never runs successful handoff or stops a task")
 }
 case("failed character launch does not apply successful handoff hiding"){
  val oldTurn=TurnView("old_turn","old question",false,false,"Old answer","Old answer",emptyList(),emptyList())
  val before=AssistantState(entry=EntryMode.Voice,island=IslandMode.Expanded,threadId="old_thread",turn=oldTurn,draft="Unsent draft")
  onOwner{session.flow.value=before};context.launchFails=true
  reference();intercept();until("failure-with-history confirming"){state?.phase==CharacterTryOnPhase.Confirming};confirm()
  until("failure-with-history card"){state?.phase==CharacterTryOnPhase.Failed}
  expect(session.flow.value==before&&handoffs.get()==0,"failure retains the previous presentation request until explicit Close")
  cancel()
  expect(session.flow.value==before.copy(entry=EntryMode.None)&&session.brokerStops==0,"closing the failure preserves the old reply and draft")
 }
 case("cancel while checking prevents late native failure"){
  resolver.available=false;resolver.statusEntered=CountDownLatch(1);resolver.statusRelease=CountDownLatch(1)
  val jpeg=reference();expect(intercept(),"checking intercepted")
  until("status entered"){resolver.statusEntered!!.count==0L};cancel();resolver.statusRelease!!.countDown();delay(80)
  expect(state==null,"cancelled status cannot create a late error card")
  expect(resolver.prepares.get()==0&&context.launched.isEmpty(),"cancelled status cannot prepare")
  expect(jpeg.all{it==0.toByte()},"checking cancel erased reference")
 }
 case("unavailable character route fails natively without submitting a chat"){
  resolver.available=false;val jpeg=reference();expect(intercept(),"request owned during status")
  until("native unavailable card"){state?.phase==CharacterTryOnPhase.Failed}
  expect(state?.message?.contains("unavailable")==true,"actionable native failure")
  expect(!intercept(),"consumed reference cannot intercept duplicate")
  expect(jpeg.all{it==0.toByte()}&&resolver.prepares.get()==0&&context.launched.isEmpty(),"failure erased image without model or Activity")
 }
 case("cancel while preparing reclaims late ticket"){
  resolver.prepareEntered=CountDownLatch(1);resolver.prepareRelease=CountDownLatch(1)
  val jpeg=reference();intercept();until("confirming"){state?.phase==CharacterTryOnPhase.Confirming};confirm()
  until("prepare entered"){resolver.prepareEntered!!.count==0L};cancel()
  expect(jpeg.all{it==0.toByte()},"cancel erased source before prepare completes")
  expect(resolver.payload!!.first()==0xff.toByte(),"in-flight Binder copy independent")
  resolver.prepareRelease!!.countDown();until("late ticket cancelled"){resolver.cancellations.size==1}
  expect(context.launched.isEmpty()&&handoffs.get()==0&&state==null,"late prepare cannot launch")
  expect(resolver.payload!!.all{it==0.toByte()},"late worker copy erased")
 }
 case("stale prepare cleanup preserves newer confirmation"){
  resolver.prepareEntered=CountDownLatch(1);resolver.prepareRelease=CountDownLatch(1)
  reference();intercept();until("first confirming"){state?.phase==CharacterTryOnPhase.Confirming};confirm()
  until("first prepare entered"){resolver.prepareEntered!!.count==0L};cancel()
  reference();intercept();until("new confirming"){state?.phase==CharacterTryOnPhase.Confirming}
  val newer=state!!.requestId;resolver.prepareRelease!!.countDown();until("old reclaimed"){resolver.cancellations.size==1}
  expect(state?.requestId==newer&&state?.phase==CharacterTryOnPhase.Confirming,"stale cleanup did not clear newer state")
  confirm();until("new handoff"){handoffs.get()==1}
  expect(context.launched.size==1&&resolver.prepares.get()==2,"only newer owner launched")
 }
 case("launch failure reclaims its ticket and presents failure"){
  context.launchFails=true;reference();intercept();until("confirming"){state?.phase==CharacterTryOnPhase.Confirming};confirm()
  until("failure and ticket cleanup"){state?.phase==CharacterTryOnPhase.Failed&&resolver.cancellations.size==1}
  expect(handoffs.get()==0&&context.launched.isEmpty(),"failed launch never handed off")
  expect(state?.message?.contains("could not open")==true,"visible actionable failure")
  cancel();expect(state==null,"failed state can close")
 }
 case("reference expiry during prepare reclaims ticket"){
  resolver.prepareEntered=CountDownLatch(1);resolver.prepareRelease=CountDownLatch(1)
  reference();intercept();until("confirming"){state?.phase==CharacterTryOnPhase.Confirming};confirm()
  until("prepare entered"){resolver.prepareEntered!!.count==0L};SystemClock.now=121000;resolver.prepareRelease!!.countDown()
  until("expired ticket cleanup"){resolver.cancellations.size==1&&state?.phase==CharacterTryOnPhase.Failed}
  expect(context.launched.isEmpty()&&handoffs.get()==0,"expired source did not launch")
 }
 case("malformed prepare reply reclaims valid returned capability"){
  resolver.malformed=true;reference();intercept();until("confirming"){state?.phase==CharacterTryOnPhase.Confirming};confirm()
  until("invalid reply cleanup"){state?.phase==CharacterTryOnPhase.Failed&&resolver.cancellations.size==1}
  expect(context.launched.isEmpty()&&handoffs.get()==0,"invalid reply not launched")
 }
 case("ordinary speech does not consume generic request"){
  val jpeg=reference();expect(!onOwner{owner.intercept("What is the weather today?")},"ordinary final returned to generic sender")
  expect(jpeg.all{it==0.toByte()}&&resolver.statusCalls.get()==0,"discard reference without provider request")
 }
 case("pointing final without a reference stays available to ordinary chat"){
  expect(!onOwner{owner.intercept("THIS OUTFIT OF HIS")},"recognized short phrase without frame returned to generic sender")
  expect(state==null&&resolver.statusCalls.get()==0&&resolver.prepares.get()==0&&context.launched.isEmpty(),"words alone cannot create confirmation or open camera")
 }
 case("fresh pointing final only confirms until the user chooses Try on"){
  resolver.statusEntered=CountDownLatch(1);resolver.statusRelease=CountDownLatch(1)
  val jpeg=reference();expect(onOwner{owner.intercept("THIS OUTFIT OF HIS")},"exact actual ASR final intercepted with its authorized frame")
  until("pointing checking"){resolver.statusEntered!!.count==0L}
  expect(state?.phase==CharacterTryOnPhase.Checking&&resolver.prepares.get()==0&&context.launched.isEmpty(),"checking cannot open model or camera")
  resolver.statusRelease!!.countDown();until("pointing confirming"){state?.phase==CharacterTryOnPhase.Confirming}
  expect(resolver.prepares.get()==0&&context.launched.isEmpty()&&handoffs.get()==0,"confirmation alone cannot open model, camera or chat Activity")
  expect(!onOwner{owner.intercept("THIS OUTFIT OF HIS")},"short phrase cannot consume its frame twice")
  cancel();expect(jpeg.all{it==0.toByte()},"Not now erases the pointing reference")
 }
 case("pointing aliases cannot acquire another app's reference"){
  val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
  onOwner{owner.offer("gesture_1234","com.android.chrome",950,975,jpeg)}
  expect(!onOwner{owner.intercept("this outfit of hers")},"foreign app's image cannot authorize the alias")
  expect(jpeg.all{it==0.toByte()}&&state==null&&resolver.statusCalls.get()==0,"foreign image erased without TryOn discovery")
 }
 case("typed system reference uses the same confirmation and one-use handoff"){
  resolver.statusEntered=CountDownLatch(1);resolver.statusRelease=CountDownLatch(1)
  val jpeg=textReference()
  expect(onOwner{owner.reference()?.source}=="com.zhiliaoapp.musically","real frame metadata available before send")
  expect(onOwner{val intercepted=owner.intercept("Turn me into this character");check(textLease.submit(SystemClock.elapsedRealtime()));intercepted},"typed command intercepted and owned")
  until("typed checking"){resolver.statusEntered!!.count==0L}
  expect(textFieldOpen,"availability checking keeps the typed field and keyboard")
  expect(onOwner{owner.reference()}==null,"consumed frame not advertised as ready")
  resolver.statusRelease!!.countDown()
  until("typed confirming"){state?.phase==CharacterTryOnPhase.Confirming}
  expect(!textFieldOpen,"actual confirmation replaces text input")
  expect(resolver.prepares.get()==0&&context.launched.isEmpty(),"typing alone cannot open camera")
  expect(!onOwner{owner.intercept("Turn me into this character")},"duplicate cannot consume reference twice")
  confirm();until("typed handoff"){handoffs.get()==1}
  expect(context.launched.size==1&&resolver.prepares.get()==1,"typed route uses one normal capability")
  expect(jpeg.all{it==0.toByte()},"typed source erased")
 }
 case("closed text entry rejects a late authorized image"){
  textInput=true
  onOwner{textLease.reserve("system_text_1234",950,1000);textLease.clear()}
  val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
  onOwner{owner.offer("system_text_1234","com.zhiliaoapp.musically",950,975,jpeg)}
  expect(jpeg.all{it==0.toByte()},"late image erased")
  expect(!onOwner{owner.intercept("Turn me into this character")},"closed entry cannot claim image")
  expect(resolver.statusCalls.get()==0&&state==null,"late image cannot create confirmation")
 }
 case("long press can explicitly transfer its reference to text"){
  val gesture=VoiceGesture()
  expect(gesture.prepare("gesture_1234","system",950,1000),"real system gesture prepared")
  gesture.activate("gesture_1234")
  val jpeg=reference()
  onOwner{check(textLease.transfer(gesture.id,950,1000));textInput=true;gesture.end(false)}
  expect(gesture.phase==VoiceGesture.Phase.Ended,"voice gesture ended")
  expect(onOwner{owner.intercept("Help me put on this outfit of his")},"transferred reference accepts typed command")
  until("transferred confirming"){state?.phase==CharacterTryOnPhase.Confirming}
  cancel();expect(jpeg.all{it==0.toByte()}&&resolver.prepares.get()==0,"cancel before camera erases transferred image")
 }
 case("desktop text cannot manufacture a system reference"){
  textInput=true
  val jpeg=reference()
  expect(jpeg.all{it==0.toByte()},"image without reserved text entry erased")
  expect(!onOwner{owner.intercept("Turn me into this character")},"ordinary desktop text stays generic")
  expect(resolver.statusCalls.get()==0,"no provider discovery without frame authority")
 }
 case("text editing cannot renew the invocation deadline"){
  val jpeg=textReference()
  onOwner{SystemClock.now=120950;check(textLease.activate("system_text_1234",SystemClock.now));SystemClock.now=120951}
  expect(!onOwner{owner.intercept("THIS OUTFIT OF HIS")},"pointing alias retains original deadline")
  expect(jpeg.all{it==0.toByte()}&&resolver.statusCalls.get()==0,"expired text reference erased without provider calls")
 }
 case("locked text entry cannot start confirmation"){
  val jpeg=textReference();unlocked=false
  expect(!onOwner{owner.intercept("Turn me into this character")},"locked entry not intercepted")
  expect(jpeg.all{it==0.toByte()}&&resolver.statusCalls.get()==0,"lock clears reference at admission")
 }
 case("unavailable text route shows a native error instead of a generic conversation"){
  resolver.available=false;resolver.statusEntered=CountDownLatch(1);resolver.statusRelease=CountDownLatch(1);textReference()
  expect(onOwner{val intercepted=owner.intercept("Turn me into this character");check(textLease.submit(SystemClock.elapsedRealtime()));intercepted},"typed request owned during status")
  until("unavailable typed checking"){resolver.statusEntered!!.count==0L}
  expect(textFieldOpen,"unavailable route still has its original field while checking")
  resolver.statusRelease!!.countDown()
  until("typed native failure"){state?.phase==CharacterTryOnPhase.Failed}
  expect(!textFieldOpen&&textPresentations.first()&&textPresentations.last()==false,"native error replaces checking input without reopening it")
  expect(!onOwner{owner.intercept("Turn me into this character")},"failure cannot re-intercept consumed frame")
  expect(resolver.prepares.get()==0&&context.launched.isEmpty(),"typed failure cannot launch a model or chat Activity")
 }
 case("disabled route has an explicit native Close state"){
  resolver.enabled=false;val jpeg=reference();intercept()
  until("disabled native failure"){state?.phase==CharacterTryOnPhase.Failed}
  expect(state?.message?.contains("turned off")==true,"disabled state is explicit")
  expect(jpeg.all{it==0.toByte()}&&resolver.prepares.get()==0&&context.launched.isEmpty(),"disabled state cannot start anything")
  cancel();expect(state==null,"native failure can close")
 }
 case("first Keep Talking final claims the same authorized reference"){
  val g=VoiceGesture();g.prepare("gesture_1234","system",950,1000);g.activate(g.id)
  val jpeg=reference();val ref=onOwner{owner.reference()}!!
  val delivery=VoiceDeliveryMachine(16000);delivery.begin(42,VoiceDestination.AwaitGesture)
  onOwner{check(voiceLease.begin(g.id,ref.expiresAtElapsedMs,1000));keepTalkingInput=true;check(g.keepTalking());check(voiceLease.bind(g.id,delivery.handoff()!!,1000))}
  val final=VoiceState(operationId=42,target=VoiceTarget.Hold,phase=VoicePhase.Closed,outcome=VoiceOutcome.Success("帮我换成这个角色"))
  val effect=delivery.settle(final,"") as VoiceEffect.Live
  expect(onOwner{voiceLease.classify(g.id,effect.operationId,effect.text,1000)}==CharacterVoiceHandoff.Decision.Character,"first final is character, not Live")
  expect(onOwner{owner.intercept(effect.text)},"same owner claims complete Chinese request")
  onOwner{g.end(false);delivery.newGeneration()}
  until("Keep Talking confirming"){state?.phase==CharacterTryOnPhase.Confirming}
  expect(g.phase==VoiceGesture.Phase.Ended&&onOwner{voiceLease.claimed(g.id,1000)},"only claimed lease survives the ended voice gesture")
  expect(context.launched.isEmpty()&&resolver.prepares.get()==0,"final alone only confirms; no model or chat Activity")
  expect(delivery.settle(final,"")==VoiceEffect.None,"same final cannot also reach generic delivery")
  expect(onOwner{voiceLease.classify(g.id,42,effect.text,1001)}==CharacterVoiceHandoff.Decision.Ignore,"late duplicate cannot become Live")
  cancel();expect(jpeg.all{it==0.toByte()},"confirmation cancel erases preserved reference")
 }
 case("ordinary Keep Talking final releases the reference to the Live path"){
  val jpeg=reference();val ref=onOwner{owner.reference()}!!
  val delivery=VoiceDeliveryMachine(16000);delivery.begin(43,VoiceDestination.AwaitGesture)
  onOwner{check(voiceLease.begin(ref.id,ref.expiresAtElapsedMs,1000));keepTalkingInput=true;check(voiceLease.bind(ref.id,delivery.handoff()!!,1000))}
  val effect=delivery.settle(VoiceState(operationId=43,target=VoiceTarget.Hold,phase=VoicePhase.Closed,outcome=VoiceOutcome.Success("What is this video about?")),"") as VoiceEffect.Live
  expect(onOwner{voiceLease.classify(ref.id,effect.operationId,effect.text,1000)}==CharacterVoiceHandoff.Decision.Live,"ordinary sentence retains normal Live routing")
  onOwner{owner.clear()}
  expect(jpeg.all{it==0.toByte()}&&state==null&&resolver.statusCalls.get()==0,"normal Live selection does not inspect TryOn or retain its image")
 }
 case("first Keep Talking pointing final shares the same confirmation owner"){
  val jpeg=reference();val ref=onOwner{owner.reference()}!!
  val delivery=VoiceDeliveryMachine(16000);delivery.begin(46,VoiceDestination.AwaitGesture)
  onOwner{check(voiceLease.begin(ref.id,ref.expiresAtElapsedMs,1000));keepTalkingInput=true;check(voiceLease.bind(ref.id,delivery.handoff()!!,1000))}
  val final=VoiceState(operationId=46,target=VoiceTarget.Hold,phase=VoicePhase.Closed,outcome=VoiceOutcome.Success("this outfit of hers"))
  val effect=delivery.settle(final,"") as VoiceEffect.Live
  expect(onOwner{voiceLease.classify(ref.id,47,effect.text,1000)}==CharacterVoiceHandoff.Decision.Ignore,"wrong recording cannot use the alias")
  expect(onOwner{voiceLease.classify(ref.id,effect.operationId,effect.text,1000)}==CharacterVoiceHandoff.Decision.Character,"same bound first final selects confirmation, not Live")
  expect(onOwner{owner.intercept(effect.text)},"shared owner accepts the second exact pointing alias")
  until("pointing Keep Talking confirming"){state?.phase==CharacterTryOnPhase.Confirming}
  expect(context.launched.isEmpty()&&resolver.prepares.get()==0,"pointing Live handoff cannot launch without Try on")
  expect(delivery.settle(final,"")==VoiceEffect.None,"pointing final delivered only once")
  cancel();expect(jpeg.all{it==0.toByte()},"pointing handoff cancelled without camera")
 }
 case("cancelled Keep Talking cannot revive its image on a late final"){
  val jpeg=reference();val ref=onOwner{owner.reference()}!!
  onOwner{voiceLease.begin(ref.id,ref.expiresAtElapsedMs,1000);voiceLease.bind(ref.id,44,1000);keepTalkingInput=true;voiceLease.clear();owner.clear()}
  expect(onOwner{voiceLease.classify(ref.id,44,"THIS OUTFIT OF HIS",1001)}==CharacterVoiceHandoff.Decision.Ignore,"cancelled pointing final ignored")
  expect(!onOwner{owner.intercept("THIS OUTFIT OF HIS")},"late pointing final cannot reclaim cleared owner")
  expect(jpeg.all{it==0.toByte()}&&resolver.statusCalls.get()==0&&context.launched.isEmpty(),"cancelled final cannot launch any path")
 }
 case("leaving a claimed voice confirmation erases its attempt except during recreation"){
  val jpeg=reference();val ref=onOwner{owner.reference()}!!
  onOwner{voiceLease.begin(ref.id,ref.expiresAtElapsedMs,1000);voiceLease.bind(ref.id,45,1000);keepTalkingInput=true}
  expect(onOwner{voiceLease.classify(ref.id,45,"帮我换成这个角色",1000)}==CharacterVoiceHandoff.Decision.Character,"voice reference claimed")
  expect(onOwner{owner.intercept("帮我换成这个角色")},"claimed final intercepted")
  until("surface confirming"){state?.phase==CharacterTryOnPhase.Confirming}
  expect(!onOwner{voiceLease.cancelOnSurfaceStop(ref.id,true,1001)},"recreation retains native confirmation")
  expect(!onOwner{voiceLease.cancelOnSurfaceStop("old_gesture_1234",false,1001)},"another surface cannot cancel this invocation")
  expect(jpeg.first()==0xff.toByte(),"retained confirmation still owns its image")
  expect(onOwner{voiceLease.cancelOnSurfaceStop(ref.id,false,1001)},"leaving the owning overlay revokes it")
  onOwner{voiceLease.clear();owner.clear()}
  expect(jpeg.all{it==0.toByte()}&&state==null&&resolver.prepares.get()==0,"surface loss erases the attempt before model start")
 }
 println("RESULT cases=$cases assertions=$assertions")
}
'''


def main():
    jdk, kotlinc, kotlin_lib, coroutines, json_jar = toolchain()
    BUILD.mkdir(parents=True, exist_ok=True)
    java = BUILD / 'contract-classes'
    classes = BUILD / 'classes'
    sources = BUILD / 'sources'
    for path in (java, classes, sources):
        if path.exists():
            shutil.rmtree(path)
        path.mkdir()
    production = [*SOURCES, CONTRACT, ENGINE, SESSION]
    hashes = {path.relative_to(SOURCE_ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in production}
    copies = []
    for path in SOURCES:
        dest = sources / path.name
        shutil.copyfile(path, dest)
        copies.append(dest)
    transcript = sources / 'DictationTranscript.kt'
    transcript.write_text('package org.agentos.agent.home.assistant\nclass DictationTranscript {' +
                          ENGINE.read_text().split('class DictationTranscript {', 1)[1])
    copies.append(transcript)
    session = sources / 'SessionCloseFixture.kt'
    session.write_text(session_fixture_source())
    copies.append(session)
    for name, source in STUBS.items():
        path = sources / name
        path.write_text(source)
        copies.append(path)
    harness = sources / 'OwnerTest.kt'
    harness.write_text(HARNESS)
    env = {**os.environ, 'JAVA_HOME': str(jdk)}
    def run(parts):
        result = subprocess.run([str(p) for p in parts], cwd=HERE, env=env, capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise RuntimeError((result.stdout + result.stderr)[-14000:])
        return result.stdout + result.stderr
    run([jdk / 'bin/javac', '--release', '17', '-d', java, CONTRACT])
    compile_log = run([kotlinc, '-jvm-target', '17', '-cp', ':'.join(map(str, [java, coroutines, json_jar])), '-d', classes, *copies, harness])
    classpath = ':'.join(map(str, [classes, java, coroutines, json_jar, kotlin_lib / 'kotlin-stdlib.jar']))
    output = run([jdk / 'bin/java', '-cp', classpath, 'org.agentos.agent.home.assistant.OwnerTestKt'])
    assert hashes == {path.relative_to(SOURCE_ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in production}, 'Production changed during test; repeat with stable source'
    result = re.search(r'RESULT cases=(\d+) assertions=(\d+)', output)
    assert result and int(result[1]) == 29, output
    (BUILD / 'compile.log').write_text(compile_log)
    (BUILD / 'test.log').write_text(output)
    receipt = {'schema': 'taku.character-owner.host-qa/1', 'passed': True, 'sourceSha256': hashes,
               'cases': int(result[1]), 'assertions': int(result[2]), 'execution': 'Actual production owner, policy, leases, final VoiceDelivery, state and extracted session handoff/close/turn-publication/text-presentation methods; Android/ContentResolver/Activity and entry-cleanup doubles',
               'androidRuntime': False, 'gestureOrAsrVerified': False, 'uiVerified': False, 'cameraOrCloudInvoked': False}
    (BUILD / 'report.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(output.strip())
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
