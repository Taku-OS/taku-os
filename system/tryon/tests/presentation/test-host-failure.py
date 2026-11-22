#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Actual deferred Window failure -> Service terminal state/card; no Android/model."""
from pathlib import Path
import os, re, subprocess, tempfile

MODULE = Path(__file__).resolve().parents[2]
SRC = MODULE / 'app/src/org/taku/tryon/realtime'
service = (SRC / 'CallTryOnService.java').read_text()
window = (SRC / 'ShoppingPreviewWindow.java').read_text()

def block(source, pattern):
    match = re.search(pattern, source, re.M)
    assert match, pattern
    at = source.index('{', match.start()) + 1
    depth = 1
    while depth:
        depth += (source[at] == '{') - (source[at] == '}')
        at += 1
    return source[match.start():at]

def method(source, name):
    return block(source, r'^    (?:private )?void ' + name + r'\([^\n]*\)\s*\{')

fixture = r'''package org.taku.tryon.realtime;
import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;import java.util.function.Consumer;
public final class CallTryOnService extends Context {
    static final Admission ADMISSION=new Admission();
    static final int STOP_FOREGROUND_REMOVE=1;
    final Handler main=new Handler();final Resource frames=new Resource();final Owner owner=new Owner();
    final Journal journal=new Journal();final Runnable tick=()->{};
    final Request request=new Request();final AtomicBoolean terminating=new AtomicBoolean();
    ShoppingPreviewWindow window;LocalShoppingFrameBridge bridge;Object transport;Resource reference;
    boolean released,destroyed,resultShown;long began,revision,admissionGeneration,inputs,outputs;
    String state="starting",detail="",code="none";int publications,stops,foregroundStops,videoStarts;
    void publish(){publications++;}void stopSelf(){stops++;}void stopForeground(int value){foregroundStops++;}
    void startVideo(){videoStarts++;}
''' + '\n'.join(method(service, n) for n in ['showCamera', 'presentationFailed', 'finish', 'releaseIfClosed']) + r'''
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static CallTryOnService fresh(){TryOnResultWindow.shows=0;TryOnResultWindow.last=null;return new CallTryOnService();}
    public static void main(String[] ignored){
        String[] failures={"system_island_timeout","system_island_unavailable","system_island_disconnected",
            "system_island_binding_died","system_island_bind_failed","system_island_layout_failed",
            "system_island_error","system_island_invalid_display","system_island_missing_surface",
            "system_island_attach_failed","system_island_connection_lost","preview_host_failed"};
        for(String failure:failures){
            CallTryOnService s=fresh();s.showCamera();ShoppingPreviewWindow w=s.window;
            check(w.attaching&&!w.attached&&w.camera.starts==0,"deferred bind cannot start a camera");
            w.host.fail(failure);
            check(s.terminating.get()&&"failed".equals(s.state)&&failure.equals(s.code),"host failure reaches actual Service failed state and exact fixed code: "+failure);
            check(!"user_stop".equals(s.code)&&s.videoStarts==0,"host failure never masquerades as user stop or starts a model");
            check(s.journal.finishes==1&&failure.equals(s.journal.failure)&&s.owner.closeCalls==1,"actual finish records the exact diagnostic failure once");
            check(w.closing&&!w.closed&&TryOnResultWindow.shows==0&&!s.released,"failure card and admission wait for camera onClosed");
            w.host.fail(failure);w.host.ready();
            check(s.journal.finishes==1&&w.camera.starts==0&&TryOnResultWindow.shows==0,"late failed/READY callbacks cannot restart or duplicate termination");
            w.camera.complete();
            check(w.closed&&w.host.releases==1&&s.owner.cameraClosed&&s.released&&s.stops==1,"exact camera-close callback releases host and admission");
            check(TryOnResultWindow.shows==1&&TryOnResultWindow.last==s&&s.foregroundStops==1,"existing terminal failure card publishes once after close");
            check(s.detail.equals("The try-on window could not open. Camera is off. Please try again."),"product text is fixed and contains no raw host response");
            w.host.fail("system_island_error");w.camera.complete();
            check(TryOnResultWindow.shows==1&&s.journal.finishes==1,"closed owner cannot create another failure card");
        }
        for(String unknown:new String[]{null,"external_host_arbitrary_response https://private.example/secret"}){
            CallTryOnService s=fresh();s.showCamera();s.window.host.fail(unknown);
            check("preview_host_failed".equals(s.code)&&"preview_host_failed".equals(s.journal.failure),"unknown host data is normalized before state/diagnostic publication");
            check(!s.detail.contains("private.example"),"raw factory response never reaches product text");s.window.camera.complete();
        }
        CallTryOnService s=fresh();s.showCamera();s.window.actions.close();s.window.host.fail("system_island_timeout");s.window.camera.complete();
        check("stopped".equals(s.state)&&"user_stop".equals(s.code)&&TryOnResultWindow.shows==0,"real user close remains a normal stop with no failure card");
        s=fresh();s.showCamera();s.window.actions.failed("camera detail");s.window.camera.complete();
        check("failed".equals(s.state)&&"camera_stopped".equals(s.code)&&TryOnResultWindow.shows==1,"existing camera-failure mapping and card remain intact");
        System.out.println("Host failure actual window/service/card checks: "+checks);
    }
}
final class ShoppingPreviewWindow {
''' + block(window, r'^    interface Actions\s*\{') + r'''
    final Host host=new Host();final Camera camera=new Camera();final Resource decoder=new Resource();Resource directRenderer=new Resource();
    final Params params=new Params();final Root root=new Root();final Handler main=new Handler();
    final ShoppingPreviewReveal firstReveal=new ShoppingPreviewReveal();final Runnable refreshGeometry=()->{};
    final Object configuration=new Object();final Context context;final Actions actions;
    boolean closed,closing,attached,attaching,directFrames,characterPreview,automaticReveal,geometryRefreshPosted;
    ShoppingPreviewWindow(Context c,Actions a,boolean direct){context=c;actions=a;directFrames=direct;}
    void setCharacterModel(String label){characterPreview=true;}void scheduleGeometryRefresh(){}
    void cancelMediaHold(){}void cancelPresentation(){}void releaseOriginal(){}void updateLayout(){}
    void reveal(){}void revealFirstResult(){}
''' + '\n'.join(method(window, n) for n in ['show', 'startAttachedCamera', 'close']) + r'''
}
class Host {
    Runnable ready;Consumer<String> failed;boolean closed;int releases;
    void attach(Params p,Runnable r,Consumer<String> f){ready=r;failed=f;}
    void fail(String code){failed.accept(code);}void ready(){ready.run();}
    void close(){if(!closed){closed=true;releases++;}}
}
class Camera {int starts;Runnable pending;void start(){starts++;}void close(Runnable r){pending=r;}void dispose(){}void complete(){Runnable r=pending;pending=null;if(r!=null)r.run();}}
class Owner {boolean closing,cameraClosed;int closeCalls;void cameraOpening(){cameraClosed=false;}void close(){closeCalls++;closing=true;}void cameraClosed(){cameraClosed=true;}boolean closed(){return closing&&cameraClosed;}}
class Journal {int finishes;String failure;void finish(String phase,String state,String code,long input,long output,int recovery){finishes++;failure=CallTryOnFailures.safeCode(code);}void restored(boolean restored){if(!restored)throw new AssertionError();}}
class TryOnResultWindow {static int shows;static CallTryOnService last;static void dismissCurrent(){}static void show(Context c,String detail){CallTryOnService s=(CallTryOnService)c;if(!s.owner.cameraClosed)throw new AssertionError("card before camera closed");shows++;last=s;}}
class LocalShoppingFrameBridge {void stop(){}}
class Request {String mode="outfit";final Config configuration=new Config();}class Config {String cosplayModelLabel(){return "fixture";}}
class TryOnController {static final String CHARACTER="character";}
class Context {void unregisterComponentCallbacks(Object o){}}
class Resource {void close(){}void shutdown(){}void shutdownNow(){}}
class Handler {void postDelayed(Runnable r,long delay){}void removeCallbacks(Runnable r){}}
class Admission {void release(long generation){}}
class Params {float alpha=1;int flags;void setBlurBehindRadius(int radius){}}
class Root {void setAlpha(float value){}}
class WindowManager {static class LayoutParams {static final int FLAG_BLUR_BEHIND=4,FLAG_NOT_TOUCHABLE=16;}}
class Build {static class VERSION {static final int SDK_INT=37;}}
class SystemClock {static long elapsedRealtime(){return 1000;}}
'''

home = os.environ.get('JAVA_HOME')
javac = str(Path(home)/'bin/javac') if home else 'javac'
java = str(Path(home)/'bin/java') if home else 'java'
with tempfile.TemporaryDirectory(prefix='taku-host-failure-') as temporary:
    source = Path(temporary)/'CallTryOnService.java'
    source.write_text(fixture)
    subprocess.run([javac,'--release','17','-encoding','UTF-8','-d',temporary,str(source),
                    str(SRC/'ShoppingPreviewReveal.java'),str(SRC/'CallTryOnFailures.java')],check=True)
    subprocess.run([java,'-cp',temporary,'org.taku.tryon.realtime.CallTryOnService'],check=True,timeout=15)
