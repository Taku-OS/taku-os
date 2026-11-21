// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

public final class ShoppingPreviewMotionTest {
    private static int checks;
    private static final ShoppingIslandGeometry.Box COMPACT = new ShoppingIslandGeometry.Box(464,100,152,92);
    private static final ShoppingIslandGeometry.Box PREVIEW = new ShoppingIslandGeometry.Box(360,100,360,434);
    public static void main(String[] args) {
        shoppingGrowsAroundPhysicalLens(); revealDoesNotScaleContent(); movedCardReturnsBeforeRolling(); anchoredCardRollsImmediately(); reverseStartsAtCurrentFrame();
        paperDoesNotFadeDuringVerticalRevealOrRoll(); morphKeepsCurrentOrigin(); epochRejectsStaleCompletion(); dimensionsAndAlphaRemainBounded();
        System.out.println("ShoppingPreviewMotionTest: " + checks + " checks passed; pure display geometry");
    }
    private static void shoppingGrowsAroundPhysicalLens() {
        var asymmetric=new ShoppingIslandGeometry.Box(403,28,462,116);
        var target=new ShoppingIslandGeometry.Box(296,44,486,733);
        var start=ShoppingPreviewMotion.resting(asymmetric,42,true);
        int previousWidth=0,previousHeight=0;
        for(int i=12;i<=88;i++) {
            var f=ShoppingPreviewMotion.islandOpening(start,asymmetric,target,539,44,84,58,i/100f);
            check(Math.abs(f.box().x()+f.box().width()/2-539)<=1,"opening remains symmetric about lens, independent of text lobe width");
            check(f.box().y()==44,"camera top stays fixed while opening downward");
            check(f.box().width()>=previousWidth&&f.box().height()>=previousHeight,"card grows continuously in both axes");
            check(f.offsetY()==0&&f.bodyAlpha()==1,"media is clipped in place and never translated or faded during reveal");
            previousWidth=f.box().width();previousHeight=f.box().height();
        }
        equal(ShoppingPreviewMotion.islandOpening(start,asymmetric,target,539,44,84,58,1).box(),target,"system reveal ends at preview geometry");
        var moved=new ShoppingIslandGeometry.Box(21,991,486,583);
        equal(ShoppingPreviewMotion.islandOpening(start,asymmetric,moved,539,44,84,58,1).box(),moved,"subsequent island restore preserves moved geometry");
        var closing=ShoppingPreviewMotion.resting(moved,58,false);
        equal(ShoppingPreviewMotion.islandClosing(closing,asymmetric,539,44,84,0).box(),moved,"close starts at actual retained frame");
        equal(ShoppingPreviewMotion.islandClosing(closing,asymmetric,539,44,84,1).box(),asymmetric,"close ends at physical camera island");
        var tail=ShoppingPreviewMotion.islandClosing(ShoppingPreviewMotion.resting(target,58,false),asymmetric,539,44,84,.96f);
        var reverse=ShoppingPreviewMotion.islandOpening(tail,asymmetric,target,539,44,84,58,0);
        equal(reverse.box(),tail.box(),"reopening a partially faded island keeps its exact initial frame");
        near(reverse.pillAlpha(),tail.pillAlpha(),0,"reverse does not flash a full-bright pill");
        check(ShoppingPreviewMotion.islandOpening(tail,asymmetric,target,539,44,84,58,.001f).pillAlpha()<=tail.pillAlpha(),"first positive reverse progress does not brighten the old pill");
    }
    private static ShoppingPreviewMotion.Frame compact() { return ShoppingPreviewMotion.resting(COMPACT,32,true); }
    private static ShoppingPreviewMotion.Frame open(float p) { return ShoppingPreviewMotion.opening(compact(),COMPACT,PREVIEW,16,24,p); }
    private static void revealDoesNotScaleContent() {
        equal(open(0).box(),COMPACT,"opening starts at retained compact bounds");
        var bar=open(.34f); check(bar.box().width()==PREVIEW.width() && bar.box().height()==16,"width grows before content height");
        check(bar.bodyAlpha()==0 && bar.ink()==1,"black bar precedes white reveal");
        var half=open(.55f);check(half.box().width()==PREVIEW.width(),"content width is fixed while revealing");
        near(half.offsetY()+PREVIEW.height(),half.box().height(),1,"bottom stays aligned: footer emerges first without font/video scale");
        check(half.offsetY()<0 && half.bodyAlpha()>0,"content translates behind root clip");
        equal(open(1).box(),PREVIEW,"opening ends at saved floating rectangle");
        check(open(1).offsetY()==0 && open(1).bodyAlpha()==1 && open(1).pillAlpha()==0,"resting content has no transform");
        for(float edge:new float[]{.12f,.34f,.88f}) near(open(edge-.00001f).box().height(),open(edge+.00001f).box().height(),1,"phase height has no jump");
    }
    private static void movedCardReturnsBeforeRolling() {
        var moved=new ShoppingIslandGeometry.Box(650,800,360,434);
        var from=ShoppingPreviewMotion.resting(moved,24,false);
        var centered=ShoppingPreviewMotion.closing(from,COMPACT,434,16,.42f);
        check(centered.box().width()==360 && centered.box().height()==434,"return-to-anchor preserves full card bounds");
        check(centered.box().y()==100 && centered.box().x()==360,"moved card returns to top camera anchor first");
        var roll=ShoppingPreviewMotion.closing(from,COMPACT,434,16,.65f);
        check(roll.box().width()==360 && roll.box().height()<434,"roll clips height without squeezing width");
        near(roll.offsetY()+434,roll.box().height(),1,"roll keeps footer aligned behind clip");
        equal(ShoppingPreviewMotion.closing(from,COMPACT,434,16,1).box(),COMPACT,"bar contracts to compact at end");
        equal(ShoppingPreviewMotion.closing(from,COMPACT,434,16,0).box(),moved,"collapse starts at actual dragged position");
        check(ShoppingPreviewMotion.requiresReturn(from,COMPACT),"only a card away from anchor reserves a return segment");
        check(ShoppingPreviewMotion.closingDurationMs(from,COMPACT)==880,"moved card retains return plus roll duration");
        var first=ShoppingPreviewMotion.closing(from,COMPACT,434,16,.001f);
        check(first.box().y()<moved.y()&&first.box().height()==moved.height(),"moved card starts moving immediately before rolling");
    }
    private static void anchoredCardRollsImmediately() {
        var from=ShoppingPreviewMotion.resting(PREVIEW,24,false);
        check(!ShoppingPreviewMotion.requiresReturn(from,COMPACT),"top card has no redundant return stage");
        check(ShoppingPreviewMotion.closingDurationMs(from,COMPACT)==510,"top card uses roll and narrow duration only");
        var first=ShoppingPreviewMotion.closing(from,COMPACT,434,16,.001f);
        check(first.box().height()<PREVIEW.height(),"tiny positive progress immediately rolls an anchored card");
        check(first.box().x()==PREVIEW.x()&&first.box().y()==PREVIEW.y(),"anchored roll does not invent a move");
        near(first.offsetY()+434,first.box().height(),1,"immediate roll preserves bottom-aligned content");
        var halfOpen=open(.55f);
        check(!ShoppingPreviewMotion.requiresReturn(halfOpen,COMPACT),"half-revealed anchored card does not reserve a return pause");
        var reversed=ShoppingPreviewMotion.closing(halfOpen,COMPACT,434,16,.001f);
        check(reversed.box().height()<halfOpen.box().height(),"reverse half-card starts shrinking at first positive progress");
    }
    private static void reverseStartsAtCurrentFrame() {
        var mid=open(.6f);
        var reverse=ShoppingPreviewMotion.closing(mid,COMPACT,434,16,0);
        equal(mid.box(),reverse.box(),"reverse close starts at current visual rectangle");
        near(mid.offsetY(),reverse.offsetY(),0,"reverse keeps current content translation");
        var closing=ShoppingPreviewMotion.closing(ShoppingPreviewMotion.resting(PREVIEW,24,false),COMPACT,434,16,.65f);
        var reopened=ShoppingPreviewMotion.opening(closing,COMPACT,PREVIEW,16,24,0);
        equal(closing.box(),reopened.box(),"reopen starts at interrupted clip rather than jumping to compact");
        near(closing.bodyAlpha(),reopened.bodyAlpha(),0,"reverse does not blink content");
        check(ShoppingPreviewMotion.opening(closing,COMPACT,PREVIEW,16,24,.1f).pillAlpha()==0,"reversing visible content does not flash compact mark");
    }
    private static void paperDoesNotFadeDuringVerticalRevealOrRoll() {
        for(int i=0;i<=100;i++) {
            float phase=i/100f;
            var reveal=open(.34f+.54f*phase);
            if(reveal.box().height()>16) opaquePaper(reveal,"revealing paper remains opaque and white");
            var roll=ShoppingPreviewMotion.closing(ShoppingPreviewMotion.resting(PREVIEW,24,false),COMPACT,434,16,(.38f/.58f)*phase);
            if(roll.box().height()>16) opaquePaper(roll,"rolling paper remains opaque and white");
            var moved=ShoppingPreviewMotion.closing(ShoppingPreviewMotion.resting(new ShoppingIslandGeometry.Box(650,800,360,434),24,false),COMPACT,434,16,.42f+.38f*phase);
            if(moved.box().height()>16) opaquePaper(moved,"moved card stays opaque after returning to anchor");
        }
        var interrupted=ShoppingPreviewMotion.closing(ShoppingPreviewMotion.resting(PREVIEW,24,false),COMPACT,434,16,.3f);
        opaquePaper(ShoppingPreviewMotion.opening(interrupted,COMPACT,PREVIEW,16,24,.1f),"reversing a paper card cannot reintroduce translucency");
        var blackBar=open(.34f);
        check(blackBar.bodyAlpha()==0&&blackBar.ink()==1,"black seam remains confined to bar stage");
        var compact=ShoppingPreviewMotion.closing(ShoppingPreviewMotion.resting(PREVIEW,24,false),COMPACT,434,16,1);
        check(compact.bodyAlpha()==0&&compact.ink()==1,"compact remains black with hidden paper body");
    }
    private static void opaquePaper(ShoppingPreviewMotion.Frame frame,String reason) {
        check(frame.bodyAlpha()==1&&frame.pillAlpha()==0&&frame.ink()==0,reason);
    }
    private static void morphKeepsCurrentOrigin() {
        var display=new ShoppingIslandGeometry.Display(1080,2424,0,173,0,64,497,44,582,129);
        var mini=ShoppingPreviewLayout.applicationOverlayLayout(display,2.625f,ShoppingIslandGeometry.Mode.PREVIEW,0,false,0,0);
        var fullscreen=ShoppingPreviewLayout.applicationOverlayLayout(display,2.625f,ShoppingIslandGeometry.Mode.FULLSCREEN,0,false,0,0);
        for(var start:new ShoppingIslandGeometry.Layout[]{mini,fullscreen}){
            var end=start==mini?fullscreen:mini;
            var before=ShoppingPreviewLayout.shoppingControls(start,2.625f);
            var after=ShoppingPreviewLayout.shoppingControls(end,2.625f);
            equal(ShoppingPreviewLayout.shoppingControls(ShoppingPreviewMotion.layout(start,end,0),2.625f),before,"controls do not jump when target mode changes");
            equal(ShoppingPreviewLayout.shoppingControls(ShoppingPreviewMotion.layout(start,end,1),2.625f),after,"controls settle at destination");
            for(int step=0;step<=100;step++){
                var body=ShoppingPreviewMotion.layout(start,end,step/100f);
                var controls=ShoppingPreviewLayout.shoppingControls(body,2.625f);
                check(controls.y()>=Math.min(before.y(),after.y())&&controls.y()<=Math.max(before.y(),after.y()),"control top interpolates between modes");
                check(body.window().width()-controls.right()==Math.round(12*2.625f),"right inset stays fixed through both transitions");
            }
        }
        var from=ShoppingPreviewMotion.resting(new ShoppingIslandGeometry.Box(650,800,360,434),24,false);
        var full=new ShoppingIslandGeometry.Box(0,0,1080,2320);
        equal(ShoppingPreviewMotion.morph(from,full,0,0).box(),from.box(),"fullscreen begins at current moved mini window");
        var mid=ShoppingPreviewMotion.morph(from,full,0,.5f);
        check(mid.box().x()>0 && mid.box().x()<650 && mid.box().width()>360 && mid.box().width()<1080,"fullscreen geometry passes intermediate bounds");
        equal(ShoppingPreviewMotion.morph(from,full,0,1).box(),full,"fullscreen ends at target");
        var returning=ShoppingPreviewMotion.resting(full,0,false);
        equal(ShoppingPreviewMotion.morph(returning,from.box(),24,1).box(),from.box(),"window returns to exact saved mini rectangle");
    }
    private static void epochRejectsStaleCompletion() {
        var e=new ShoppingPreviewMotion.Epoch();long opening=e.next();check(e.current(opening),"initial animation owns completion");
        long reverse=e.next();check(!e.current(opening)&&e.current(reverse),"reverse invalidates old completion");
        long close=e.next();check(!e.current(opening)&&!e.current(reverse)&&e.current(close),"privacy close fences all old callbacks");
    }
    private static void dimensionsAndAlphaRemainBounded() {
        for(int i=0;i<=100;i++){
            float p=i/100f;
            for(var f:new ShoppingPreviewMotion.Frame[]{open(p),ShoppingPreviewMotion.closing(ShoppingPreviewMotion.resting(PREVIEW,24,false),COMPACT,434,16,p)}){
                check(f.box().width()>0&&f.box().height()>0,"positive interpolated bounds");
                check(f.bodyAlpha()>=0&&f.bodyAlpha()<=1&&f.pillAlpha()>=0&&f.pillAlpha()<=1,"alpha remains bounded");
                check(f.radius()>=0&&Float.isFinite(f.offsetY()),"finite presentation values");
            }
        }
    }
    private static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);checks++;}
    private static void equal(Object a,Object b,String reason){check(a.equals(b),reason);}
    private static void near(float a,float b,float delta,String reason){check(Math.abs(a-b)<=delta,reason);}
}
