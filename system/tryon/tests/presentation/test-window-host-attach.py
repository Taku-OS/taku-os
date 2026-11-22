#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise actual host admission/cleanup methods with deferred host/camera actors."""
from pathlib import Path
import argparse, os, re, subprocess, tempfile

MODULE = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--window', type=Path, default=MODULE/'app/src/org/taku/tryon/realtime/ShoppingPreviewWindow.java')
args = parser.parse_args()
source = args.window.read_text()

def method(name):
    match = re.search(r'^    (?:private )?(?:void|Bundle) '+name+r'\([^\n]*\)\s*\{', source, re.M)
    if match is None: raise AssertionError(name)
    end = source.index('{', match.start()) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[match.start():end]

assert 'host = ShoppingPreviewHost.create(context, root)' in source
assert 'windows.addView(root' not in source
assert 'windows.updateViewLayout(root' not in source
assert 'windows.removeViewImmediate(root' not in source
has_position = re.search(r'^    Bundle position\(', source, re.M) is not None
duplicate_ready = 'if (attached) return;' in method('show')

fixture = r'''package org.taku.tryon.realtime;
import java.util.*;import java.util.function.Consumer;
public final class ShoppingPreviewWindow {
    final Host host = new Host(); final Camera camera = new Camera();
    final Actions actions = new Actions(this); final Context context = new Context();
    final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
    final View root = new View(); final Handler main = new Handler();
    final Runnable refreshGeometry = () -> {}; final Object configuration = new Object();
    final ShoppingPreviewReveal firstReveal = new ShoppingPreviewReveal();
    final Resource decoder = new Resource(); Resource directRenderer = new Resource();
    boolean closed, closing, attached, attaching, directFrames=true, characterPreview;
    boolean automaticReveal, geometryRefreshPosted, canMove=true;
    String revealPhase="opening"; int geometryRefreshes, presentationUpdates, reveals, firstReveals;
    void scheduleGeometryRefresh(){geometryRefreshes++;}
    void updatePresentationState(){presentationUpdates++;}
    void reveal(){reveals++;} void revealFirstResult(){firstReveals++;}
    void cancelMediaHold(){} void cancelPresentation(){} void releaseOriginal(){}
    boolean canResizePresentation(){return canMove&&!closed&&!closing;}
''' + '\n'.join(method(name) for name in ['show', 'startAttachedCamera', 'updateLayout', 'close']) + (
    method('position') if has_position else ''
) + r'''
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] ignored){
        for(boolean direct:new boolean[]{false,true})for(boolean character:new boolean[]{false,true}){
            ShoppingPreviewWindow window=new ShoppingPreviewWindow();window.directFrames=direct;window.characterPreview=character;
            window.show();
            check(window.host.attaches==1&&window.attaching&&!window.attached,"show starts exactly one pending host attachment");
            check(window.camera.starts==0&&window.actions.ready==0,"pending attach cannot start a camera or acknowledge a window");
            window.show();check(window.host.attaches==1,"repeated show cannot attach the same camera tree twice");
            window.params.x=231;window.params.flags=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;window.params.alpha=0;
            window.updateLayout();
            check(window.host.updates==1&&window.host.x==231&&window.host.alpha==0&&window.host.flags==16,"pending host receives current geometry/capture visibility flags");
            window.host.ready();
            check(!window.attaching&&window.attached&&window.actions.ready==1&&window.camera.starts==1,"ready attaches and starts the existing camera tree once");
            check(window.firstReveals+window.reveals==1,"the existing reveal path executes only after host readiness");
            window.params.x=329;window.params.flags=0;window.params.alpha=1;window.updateLayout();
            check(window.host.updates==2&&window.host.x==329&&window.host.alpha==1&&window.host.flags==0,"attached updates use the same host and authoritative params");
            __DUPLICATE_READY_CHECK__
            __POSITION_CHECK__
            window.camera.holdClose=true;int[] completed={0};window.close(()->completed[0]++);
            check(window.closing&&!window.closed&&window.camera.disposes==0&&completed[0]==0,"close completion waits for camera onClosed");
            check(window.params.alpha==0&&(window.params.flags&16)!=0,"closing masks the view and removes input before cleanup");
            window.host.ready();check(window.camera.starts==1,"late ready cannot restart a closing camera");
            window.camera.complete();
            check(window.closed&&!window.attached&&window.camera.disposes==1&&window.host.releases==1&&completed[0]==1,"onClosed releases the same host once then reports cleanup");
            int updates=window.host.updates;window.updateLayout();window.show();
            check(window.host.updates==updates&&window.host.attaches==1,"closed window cannot update or reattach");
        }
        ShoppingPreviewWindow pending=new ShoppingPreviewWindow();pending.camera.holdClose=true;pending.show();pending.close(null);pending.host.ready();
        check(pending.camera.starts==0&&pending.actions.ready==0,"closing a pending bind fences both camera start and presentation acknowledgement");
        pending.camera.complete();check(pending.closed&&pending.host.releases==1,"pending bind releases its host when the unused camera finishes closing");
        pending.host.ready();check(pending.camera.starts==0&&pending.host.releases==1,"ready after close cannot resurrect the released tree");
        ShoppingPreviewWindow failed=new ShoppingPreviewWindow();failed.show();failed.host.fail();
        check(failed.actions.presentationFailures==1&&failed.actions.closes==0&&failed.closed&&failed.host.releases==1&&failed.camera.starts==0,"asynchronous attach failure ends the owning window without starting a camera");
        failed.host.ready();check(failed.camera.starts==0,"stale readiness after attachment failure is fenced");
        ShoppingPreviewWindow ownerClosed=new ShoppingPreviewWindow();ownerClosed.actions.closeOnReady=true;ownerClosed.show();ownerClosed.host.ready();
        check(ownerClosed.closed&&ownerClosed.camera.starts==0,"owner cancellation inside presentationReady cannot start a camera afterwards");
        ShoppingPreviewWindow revoked=new ShoppingPreviewWindow();revoked.host.throwUpdate=true;revoked.updateLayout();
        check(revoked.actions.presentationFailures==1,"host update rejection follows the existing failure path");
        revoked.closing=true;revoked.updateLayout();check(revoked.actions.presentationFailures==1,"cleanup update rejection cannot recursively fail the owner");
        System.out.println("Window host attach checks: "+checks+"; actual window methods with deferred actors, no Android camera/model");
    }
}
class Host {
    Runnable callback;Consumer<String> failure;int attaches,updates,releases,closes,x,flags;float alpha;boolean closed,throwUpdate;
    void attach(WindowManager.LayoutParams params,Runnable ready,Consumer<String> failed){attaches++;callback=ready;failure=failed;}
    void ready(){callback.run();}void fail(){failure.accept("fixture host disconnected");}
    void update(WindowManager.LayoutParams params){if(throwUpdate)throw new IllegalStateException("gone");updates++;x=params.x;flags=params.flags;alpha=params.alpha;}
    void close(){closes++;if(!closed){closed=true;releases++;}}
    String id(){return "system_island";}
}
class Camera {
    boolean holdClose;int starts,disposes;final List<Runnable> callbacks=new ArrayList<>();
    void start(){starts++;}void close(Runnable callback){if(callback!=null)callbacks.add(callback);if(!holdClose)complete();}
    void complete(){while(!callbacks.isEmpty())callbacks.remove(0).run();}void dispose(){disposes++;}
}
class Actions {
    final ShoppingPreviewWindow window;int ready,closes,failures;boolean closeOnReady;
    Actions(ShoppingPreviewWindow value){window=value;}
    int presentationFailures;void presentationFailed(String code){presentationFailures++;window.close(null);}
    void presentationReady(ShoppingPreviewWindow value){if(value!=window)throw new AssertionError("wrong tree");ready++;if(closeOnReady)window.close(null);}
    void close(){closes++;window.close(null);}void failed(String message){failures++;}
}
class Context {void unregisterComponentCallbacks(Object value){}}
class Resource {void close(){}void shutdown(){}}
class View {void setAlpha(float value){}}
class Handler {void removeCallbacks(Runnable value){}}
class WindowManager {static class LayoutParams {static final int FLAG_NOT_TOUCHABLE=16,FLAG_BLUR_BEHIND=4;int x,y,width,height,flags;float alpha=1;void setBlurBehindRadius(int value){}}}
class Build {static class VERSION {static final int SDK_INT=37;}}
class Bundle extends HashMap<String,Object>{void putInt(String key,int value){put(key,value);}void putBoolean(String key,boolean value){put(key,value);}void putString(String key,String value){put(key,value);}}
'''
fixture = fixture.replace('__DUPLICATE_READY_CHECK__', 'window.host.ready();check(window.camera.starts==1&&window.actions.ready==1,"duplicate ready does not restart an attached camera");' if duplicate_ready else '')
fixture = fixture.replace('__POSITION_CHECK__', 'check("system_island".equals(window.position().get("host")),"Agent position reads the actual selected host");' if has_position else '')

java_home = os.environ.get('JAVA_HOME')
javac = str(Path(java_home)/'bin/javac') if java_home else 'javac'
java = str(Path(java_home)/'bin/java') if java_home else 'java'
with tempfile.TemporaryDirectory(prefix='taku-window-host-') as temporary:
    target = Path(temporary)/'ShoppingPreviewWindow.java'
    target.write_text(fixture)
    subprocess.run([javac,'--release','17','-encoding','UTF-8','-d',temporary,str(target),str(args.window.parent/'ShoppingPreviewReveal.java')],check=True)
    subprocess.run([java,'-cp',temporary,'org.taku.tryon.realtime.ShoppingPreviewWindow'],check=True,timeout=15)
