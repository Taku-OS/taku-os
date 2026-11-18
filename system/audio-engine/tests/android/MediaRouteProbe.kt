// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio.tests

import android.app.Instrumentation
import android.os.Bundle
import org.agentos.audio.PlatformMediaCapture
import org.agentos.audio.PlatformCallCapture
import org.agentos.audio.CallDirection
import android.content.Intent
import android.content.ComponentName
import java.util.concurrent.atomic.AtomicReference
import org.json.*
import kotlin.math.*

/** Separate UID 1 kHz / 2 kHz fixtures. Real media route, no microphone or network. */
class MediaRouteProbe : Instrumentation() {
    private var call=false
    override fun onCreate(args: Bundle?){super.onCreate(args);call=args?.getString("call")=="true";start()}
    override fun onStart(){
        if(call){callRoute();return}
        val report=JSONObject().put("passed",false).put("microphoneOpened",false).put("audioUploaded",false)
        val results=JSONArray()
        try {
            val uid=targetContext.packageManager.getApplicationInfo("org.taku.callfixture.target",0).uid
            for(replace in listOf(false,true)) {
                PlatformMediaCapture(targetContext,uid,replace).use { route ->
                    route.start()
                    sendStatus(0,Bundle().apply{putString("phase",if(replace)"replace-active" else "companion-active")})
                    var n=0L;var r1=0.0;var i1=0.0;var r2=0.0;var i2=0.0
                    while(n<32000)for(sample in route.read()) {
                        val angle=2*PI*n/16000;r1+=sample*cos(1000*angle);i1+=sample*sin(1000*angle);r2+=sample*cos(2000*angle);i2+=sample*sin(2000*angle);n++
                    }
                    val wanted=2*hypot(r1,i1)/n;val other=2*hypot(r2,i2)/n
                    check(wanted>100 && other<wanted*.1)
                    results.put(JSONObject().put("replace",replace).put("capturedSamples",n).put("targetAmplitude",wanted).put("bystanderAmplitude",other))
                }
                Thread.sleep(300)
            }
            report.put("passed",true)
        }catch(e: Throwable){report.put("error",e.javaClass.simpleName)}
        report.put("results",results)
        finish(if(report.optBoolean("passed"))-1 else 0,Bundle().apply{putString("report",report.toString())})
    }
    private fun callRoute() {
        val report=JSONObject().put("passed",false).put("audioUploaded",false).put("cloudTranslationVerified",false)
        var route: PlatformCallCapture?=null
        try {
            val uid=PlatformCallCapture.uidFor(targetContext,"org.taku.callfixture.target")
            route=PlatformCallCapture(targetContext,uid,microphone=false)
            val lock=Any();var n=0L;var a=0.0;var b=0.0;var c=0.0;var d=0.0
            val failure=AtomicReference<String?>(null)
            route.start({direction,pcm->if(direction==CallDirection.INCOMING)synchronized(lock){
                for(value in pcm){val angle=2*PI*n/16000;a+=value*cos(1000*angle);b+=value*sin(1000*angle);c+=value*cos(2000*angle);d+=value*sin(2000*angle);n++}
            }},{failure.set(it)})
            for(pkg in listOf("org.taku.callfixture.target","org.taku.callfixture.bystander"))targetContext.sendBroadcast(
                Intent().setComponent(ComponentName(pkg,"org.taku.callfixture.CallFixtureReceiver")).putExtra("phase","reset-record"))
            val output=route.outgoingOutput;output.play()
            val frame=ShortArray(320){(sin(2*PI*3000*it/16000)*3000).toInt().toShort()}
            repeat(200){check(output.write(frame,0,frame.size)>0)}
            check(failure.get()==null)
            synchronized(lock){
                val target=2*hypot(a,b)/maxOf(n,1);val other=2*hypot(c,d)/maxOf(n,1)
                check(target>100 && other<target*.1)
                report.put("targetAmplitude",target).put("bystanderAmplitude",other).put("downlinkSamples",n)
            }
            route.close();route=null
            report.put("routeReleased",true).put("passed",true)
        }catch(e: Throwable){report.put("error",e.javaClass.simpleName).put("message",e.message?.take(240))}
        finally{route?.close()}
        finish(if(report.optBoolean("passed"))-1 else 0,Bundle().apply{putString("report",report.toString())})
    }

}
