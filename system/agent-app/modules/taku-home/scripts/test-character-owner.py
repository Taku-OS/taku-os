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
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
MODULE = HERE.parent
SOURCE_ROOT = MODULE.parents[3]
PRODUCTION = MODULE / 'android/src/main/java/org/agentos/agent/home/assistant'
BUILD = SOURCE_ROOT / 'out/character-owner-host'
SOURCES = [PRODUCTION / name for name in ('CharacterTryOn.kt', 'CharacterTryOnPolicy.kt', 'VoiceGesture.kt')]
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
    for path, help_text in (
        (jdk / 'bin/javac', 'Set JAVA_HOME to JDK 17 or newer'),
        (jdk / 'bin/java', 'Set JAVA_HOME to JDK 17 or newer'),
        (kotlinc, 'Set TAKU_KOTLINC to the Kotlin JVM compiler'),
        (kotlin_lib / 'kotlin-stdlib.jar', 'Set KOTLIN_LIB to the Kotlin compiler library directory'),
        (coroutines, 'Set TAKU_TEST_COROUTINES_JAR to an existing coroutines JAR; this test never downloads dependencies'),
    ):
        if not path.is_file():
            raise SystemExit(f'{help_text}; missing: {path}')
    return jdk, kotlinc, kotlin_lib, coroutines

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
 val fallback=Collections.synchronizedList(mutableListOf<String>());val handoffs=AtomicInteger()
 val owner=CharacterTryOn(context,scope,{eligible},{unlocked},{state=it},{fallback.add(it)},{handoffs.incrementAndGet()})
 suspend fun <T> onOwner(block:()->T):T=withContext(dispatcher){block()}
 suspend fun reference():ByteArray=onOwner {
  val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
  owner.offer("gesture_1234","com.zhiliaoapp.musically",950,975,jpeg);jpeg
 }
 suspend fun intercept():Boolean=onOwner{owner.intercept("I wanna cosplay as him.")}
 suspend fun until(message:String,condition:()->Boolean){
  withTimeout(2500){while(!condition())delay(5)};expect(true,message)
 }
 suspend fun confirm(){onOwner{owner.confirm(state!!.requestId)}}
 suspend fun cancel(){onOwner{owner.cancel(state!!.requestId)}}
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
  expect(fallback.isEmpty()&&state==null,"no generic send or lingering confirmation")
 }
 case("cancel while checking prevents late fallback"){
  resolver.available=false;resolver.statusEntered=CountDownLatch(1);resolver.statusRelease=CountDownLatch(1)
  val jpeg=reference();expect(intercept(),"checking intercepted")
  until("status entered"){resolver.statusEntered!!.count==0L};cancel();resolver.statusRelease!!.countDown();delay(80)
  expect(state==null&&fallback.isEmpty(),"cancelled result has no fallback")
  expect(resolver.prepares.get()==0&&context.launched.isEmpty(),"cancelled status cannot prepare")
  expect(jpeg.all{it==0.toByte()},"checking cancel erased reference")
 }
 case("unavailable route falls back exactly once"){
  resolver.available=false;reference();expect(intercept(),"request owned during status")
  until("one fallback"){fallback.size==1};expect(fallback.single()=="I wanna cosplay as him.","original final preserved")
  expect(!intercept(),"consumed reference cannot intercept duplicate")
  delay(30);expect(fallback.size==1&&resolver.prepares.get()==0,"no duplicate owner fallback or prepare")
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
 println("RESULT cases=$cases assertions=$assertions")
}
'''


def main():
    jdk, kotlinc, kotlin_lib, coroutines = toolchain()
    BUILD.mkdir(parents=True, exist_ok=True)
    java = BUILD / 'contract-classes'
    classes = BUILD / 'classes'
    sources = BUILD / 'sources'
    for path in (java, classes, sources):
        if path.exists():
            shutil.rmtree(path)
        path.mkdir()
    production = [*SOURCES, CONTRACT]
    hashes = {path.relative_to(SOURCE_ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in production}
    copies = []
    for path in SOURCES:
        dest = sources / path.name
        shutil.copyfile(path, dest)
        copies.append(dest)
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
    compile_log = run([kotlinc, '-jvm-target', '17', '-cp', str(java) + ':' + str(coroutines), '-d', classes, *copies, harness])
    classpath = ':'.join(map(str, [classes, java, coroutines, kotlin_lib / 'kotlin-stdlib.jar']))
    output = run([jdk / 'bin/java', '-cp', classpath, 'org.agentos.agent.home.assistant.OwnerTestKt'])
    assert hashes == {path.relative_to(SOURCE_ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in production}, 'Production changed during test; repeat with stable source'
    assert 'RESULT cases=9 ' in output, output
    (BUILD / 'compile.log').write_text(compile_log)
    (BUILD / 'test.log').write_text(output)
    receipt = {'schema': 'taku.character-owner.host-qa/1', 'passed': True, 'sourceSha256': hashes,
               'cases': 9, 'assertions': 47, 'execution': 'Actual production owner and policy; Android/ContentResolver/Activity doubles',
               'androidRuntime': False, 'gestureOrAsrVerified': False, 'uiVerified': False, 'cameraOrCloudInvoked': False}
    (BUILD / 'report.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(output.strip())
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
