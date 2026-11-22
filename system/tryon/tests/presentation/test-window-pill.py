#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run actual window presentation methods with view doubles; no Android pixels/camera/model."""
from pathlib import Path
import argparse, os, re, subprocess, tempfile

MODULE = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--window', type=Path, default=MODULE/'app/src/org/taku/tryon/realtime/ShoppingPreviewWindow.java')
args = parser.parse_args()
source = args.window.read_text()

def method(name):
    match = re.search(r'^    (?:private )?(?:void|String) '+name+r'\([^\n]*\)\s*\{', source, re.M)
    if match is None: raise AssertionError(name)
    end = source.index('{', match.start()) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[match.start():end]

# The listeners still dispatch to the production presentation paths, without a separate card.
assert 'pill.setOnClickListener(view -> setCollapsed(false))' in source
assert 'pill.setVisibility(View.GONE); pill.setAlpha' not in source
assert 'nativeCompact' not in source

fixture = r'''package org.taku.tryon.realtime;
import java.util.function.Consumer;
public final class ShoppingPreviewWindow {
    final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
    final View root = new View(), content = new View(), pill = new View(), backplate = new View();
    final Chrome chrome = new Chrome(); final Ui ui = new Ui();
    final ShoppingPreviewLayout.FloatingFrame floatingFrame = new ShoppingPreviewLayout.FloatingFrame();
    final Host host = new Host();
    ShoppingIslandGeometry.Mode mode = ShoppingIslandGeometry.Mode.PREVIEW;
    ShoppingIslandGeometry.Layout currentLayout;
    ShoppingPreviewMotion.Frame motionFrame;
    boolean captureHidden, closing, closed, collapsed, expanded, movingPresentation, characterPreview;
    float visualRadius, backgroundFull; int updateCount;
    ShoppingPreviewWindow(){ layout(false); }
    ShoppingIslandGeometry.Display display(){return new ShoppingIslandGeometry.Display(1080,2424,0,130,0,80,0,0,0,0);}
    float density(){return 1;}
    int previewRadius(){return 24;}
    ShoppingIslandGeometry.Layout compactLayout(){return ShoppingPreviewLayout.applicationOverlayLayout(display(),density(),ShoppingIslandGeometry.Mode.COMPACT,0,false,0,0,characterPreview);}
    void layout(boolean initial){
        ShoppingIslandGeometry.Layout target = mode == ShoppingIslandGeometry.Mode.COMPACT ? compactLayout()
            : floatingFrame.layout(display(),density(),mode,characterPreview);
        collapsed = mode == ShoppingIslandGeometry.Mode.COMPACT;
        expanded = mode == ShoppingIslandGeometry.Mode.FULLSCREEN;
        if (!collapsed || currentLayout == null) currentLayout = target;
        applyMotion(ShoppingPreviewMotion.resting(target.window(),expanded?0:collapsed?16:previewRadius(),collapsed));
    }
    void updateLayout(){updateCount++;}
    void interruptAutomaticReveal(){} void releaseOriginal(){} void cancelPresentation(){}
    boolean animateAllowed(){return false;}
    ShoppingPreviewMotion.Frame currentFrame(){return motionFrame;}
    void applyBodyLayout(ShoppingIslandGeometry.Layout body,boolean full){currentLayout=body;}
    void animatePresentation(long duration,Consumer<Float> frame,Runnable done){frame.accept(0f);frame.accept(.5f);frame.accept(1f);done.run();}
    ShoppingPreviewMotion.Frame closingFrame(ShoppingPreviewMotion.Frame from,ShoppingIslandGeometry.Layout anchor,int height,float progress){return ShoppingPreviewMotion.closing(from,anchor.window(),height,32,progress);}
    ShoppingPreviewMotion.Frame openingFrame(ShoppingPreviewMotion.Frame from,ShoppingIslandGeometry.Layout anchor,ShoppingIslandGeometry.Layout target,float progress){return ShoppingPreviewMotion.opening(from,anchor.window(),target.window(),32,24,progress);}
''' + '\n'.join(method(name) for name in ['showPreparingPill', 'applyMotion', 'setCollapsed', 'expand']) + (
    method('presentationHost') if 'private String presentationHost()' in source else ''
) + r'''
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void interactive(ShoppingPreviewWindow window,boolean visible,String reason){
        check(window.params.alpha==(visible?1f:0f),reason+": compositor visibility");
        check(((window.params.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0)==visible,reason+": touch availability");
    }
    public static void main(String[] ignored){
        for(boolean character:new boolean[]{false,true}){
            ShoppingPreviewWindow window=new ShoppingPreviewWindow();window.characterPreview=character;
            window.params.flags=32;
            window.showPreparingPill();
            check(window.mode==ShoppingIslandGeometry.Mode.COMPACT,"Preparing uses the original compact pill");
            interactive(window,true,"Preparing");
            check(window.pill.visibility==View.VISIBLE&&window.pill.alpha==1,"Preparing pill is actually drawn");
            check(window.content.alpha==0,"Preparing does not reveal video content");
            check((window.params.flags&32)!=0,"presentation preserves unrelated window flags");
            window.expand();
            check(window.mode==ShoppingIslandGeometry.Mode.PREVIEW&&!window.collapsed&&!window.expanded,"existing expand returns the floating preview");
            interactive(window,true,"Expanded preview");
            check(window.pill.visibility==View.INVISIBLE&&window.pill.alpha==0&&window.content.alpha==1,"preview uses video rather than the compact pill");
            window.setCollapsed(true);
            check(window.mode==ShoppingIslandGeometry.Mode.COMPACT&&window.collapsed&&!window.expanded,"minimize retains compact state");
            interactive(window,true,"Minimized preview");
            check(window.pill.visibility==View.VISIBLE&&window.pill.alpha==1,"minimize retains the user-visible reopen target");
            window.setCollapsed(false);
            check(window.mode==ShoppingIslandGeometry.Mode.PREVIEW,"the same pill callback can reopen the preview");
            for(ShoppingIslandGeometry.Mode mode:ShoppingIslandGeometry.Mode.values()){
                for(boolean hidden:new boolean[]{false,true})for(boolean closing:new boolean[]{false,true}){
                    window.mode=mode;window.captureHidden=hidden;window.closing=closing;
                    ShoppingPreviewMotion.Frame frame=ShoppingPreviewMotion.resting(new ShoppingIslandGeometry.Box(10,20,300,400),16,mode==ShoppingIslandGeometry.Mode.COMPACT);
                    window.applyMotion(frame);
                    interactive(window,!hidden&&!closing,mode+" hidden="+hidden+" closing="+closing);
                    check(window.params.x==10&&window.params.y==20&&window.params.width==300&&window.params.height==400,"renderer still applies exact animation bounds");
                    check(window.pill.visibility==(frame.pillAlpha()>0?View.VISIBLE:View.INVISIBLE),"pill visibility follows actual animation opacity");
                }
            }
            window.captureHidden=false;window.closing=false;window.mode=ShoppingIslandGeometry.Mode.PREVIEW;
            ShoppingPreviewMotion.Frame transition=new ShoppingPreviewMotion.Frame(new ShoppingIslandGeometry.Box(0,0,300,400),-10,.4f,.6f,16,1);
            window.applyMotion(transition);
            check(window.pill.visibility==View.VISIBLE&&window.pill.alpha==.6f&&window.content.alpha==.4f,"transition preserves both original animation layers");
            window.closing=true;int before=window.updateCount;window.expand();
            check(window.updateCount==before,"closing windows reject expansion");
            window.closing=false;window.closed=true;window.expand();
            check(window.updateCount==before,"closed windows reject expansion");
        }
        System.out.println("Window compact pill checks: "+checks+"; actual presentation methods with view doubles, no camera/model");
    }
}
class Host {String id(){return "application_overlay";}}
class Ui {int dp(int value){return value;}}
class Chrome {Object motionBackplate(float full,float radius,float ink){return null;}}
class View {
    static final int VISIBLE=0,INVISIBLE=4,GONE=8;
    int visibility=VISIBLE;float alpha=1,translationX,translationY;Object params=new FrameLayout.LayoutParams();
    void invalidateOutline(){}void setScaleX(float value){}void setScaleY(float value){}
    void setTranslationX(float value){translationX=value;}void setTranslationY(float value){translationY=value;}
    void setAlpha(float value){alpha=value;}void setVisibility(int value){visibility=value;}
    Object getLayoutParams(){return params;}void setLayoutParams(Object value){params=value;}void setBackground(Object value){}
}
class WindowManager {static class LayoutParams {static final int FLAG_NOT_TOUCHABLE=16;int x,y,width,height,flags;float alpha=1;}}
class FrameLayout {static class LayoutParams {int width,height,gravity,leftMargin,topMargin;}}
class Gravity {static final int TOP=48,LEFT=3;}
'''

java_home = os.environ.get('JAVA_HOME')
javac = str(Path(java_home)/'bin/javac') if java_home else 'javac'
java = str(Path(java_home)/'bin/java') if java_home else 'java'
with tempfile.TemporaryDirectory(prefix='taku-window-pill-') as temporary:
    output = Path(temporary)
    target = output/'ShoppingPreviewWindow.java'
    target.write_text(fixture)
    sources = [str(target)] + [str(args.window.parent/(name+'.java')) for name in ['ShoppingIslandGeometry','ShoppingPreviewMotion','ShoppingPreviewLayout']]
    subprocess.run([javac,'--release','17','-encoding','UTF-8','-d',temporary,*sources],check=True)
    subprocess.run([java,'-cp',temporary,'org.taku.tryon.realtime.ShoppingPreviewWindow'],check=True,timeout=15)
