// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** Display-only keyframes. Content is translated behind a clip, never scaled. */
final class ShoppingPreviewMotion {
    static final long OPEN_MS = 920, CLOSE_MS = 880, ROLL_CLOSE_MS = 510, MORPH_MS = 360;
    record Frame(ShoppingIslandGeometry.Box box, float offsetY, float bodyAlpha, float pillAlpha,
                 float radius, float ink) {}
    private ShoppingPreviewMotion() {}
    static Frame resting(ShoppingIslandGeometry.Box box, float radius, boolean compact) {
        return new Frame(box, 0, compact ? 0 : 1, compact ? 1 : 0, radius, compact ? 1 : 0);
    }
    static Frame opening(Frame from, ShoppingIslandGeometry.Box compact, ShoppingIslandGeometry.Box target,
                         int barHeight, float radius, float progress) {
        float p = clamp(progress);
        ShoppingIslandGeometry.Box anchorCard = atAnchor(target, compact);
        ShoppingIslandGeometry.Box bar = bar(anchorCard, barHeight);
        Frame barFrame = new Frame(bar, bar.height() - target.height(), 0, 0, bar.height()/2f, 1);
        Frame revealed = new Frame(anchorCard, 0, 1, 0, radius, 0);
        Frame end = resting(target, radius, false);
        if (from.bodyAlpha() > .001f) return p < .88f ? paper(mix(from, revealed, p/.88f),barHeight) : mix(revealed, end, (p-.88f)/.12f);
        if (from.pillAlpha() < .99f) {
            if (p < .28f) return mix(from, barFrame, p/.28f);
            if (p < .88f) return paper(mix(barFrame, revealed, (p-.28f)/.60f),barHeight);
            return mix(revealed,end,(p-.88f)/.12f);
        }
        if (p < .12f) return mix(from, new Frame(from.box(), from.offsetY(), from.bodyAlpha(), 1, from.radius(), from.ink()), p/.12f);
        if (p < .34f) return mix(new Frame(from.box(), from.offsetY(), from.bodyAlpha(), 1, from.radius(), from.ink()), barFrame, (p-.12f)/.22f);
        if (p < .88f) return paper(mix(barFrame, revealed, (p-.34f)/.54f),barHeight);
        return mix(revealed, end, (p-.88f)/.12f);
    }
    /** Camera-anchored shopping reveal: card width grows symmetrically and its bottom opens downward. */
    static Frame islandOpening(Frame from, ShoppingIslandGeometry.Box compact, ShoppingIslandGeometry.Box target,
                               int cameraX, int anchorTop, int nodeHeight, float radius, float progress) {
        float p=clamp(progress);
        if(p==0)return from;
        if(from.bodyAlpha()>.001f) return morph(from,target,radius,p);
        // Fade the asymmetric text lobes while a centered camera node becomes the card.
        float q=ease(clamp((p-.12f)/.76f));
        int width=value(Math.min(target.width(),nodeHeight),target.width(),q);
        int height=value(nodeHeight,target.height(),q);
        int x=cameraX-width/2, y=anchorTop;
        if(p>=.88f) return morph(resting(new ShoppingIslandGeometry.Box(cameraX-target.width()/2,anchorTop,target.width(),target.height()),radius,false),target,radius,(p-.88f)/.12f);
        // The invisible host may change bounds immediately; the capsule itself remains at its physical position.
        int left=Math.min(x,compact.x()),right=Math.max(x+width,compact.right());
        if(p<.12f) return new Frame(new ShoppingIslandGeometry.Box(left,Math.min(y,compact.y()),right-left,
                Math.max(height,compact.bottom()-Math.min(y,compact.y()))),0,0,from.pillAlpha()*(1-p/.12f),radius,1);
        return new Frame(new ShoppingIslandGeometry.Box(x,y,width,height),0,1,0,radius,0);
    }

    static Frame islandClosing(Frame from,ShoppingIslandGeometry.Box compact,int cameraX,int anchorTop,int nodeHeight,float progress) {
        float p=clamp(progress);
        var card=from.box();var anchor=new ShoppingIslandGeometry.Box(cameraX-card.width()/2,anchorTop,card.width(),card.height());
        boolean moved=Math.abs(card.x()-anchor.x())>1||Math.abs(card.y()-anchor.y())>1;
        float returnEnd=moved?.4f:0;
        if(moved&&p<returnEnd)return morph(from,anchor,from.radius(),p/returnEnd);
        float q=clamp((p-returnEnd)/(1-returnEnd));
        if(q<.88f){
            float t=ease(q/.88f);int w=value(card.width(),nodeHeight,t),h=value(card.height(),nodeHeight,t);
            return new Frame(new ShoppingIslandGeometry.Box(cameraX-w/2,anchorTop,w,h),0,1,0,from.radius(),0);
        }
        return new Frame(compact,0,0,(q-.88f)/.12f,nodeHeight/2f,1);
    }

    static Frame closing(Frame from, ShoppingIslandGeometry.Box compact, int bodyHeight,
                         int barHeight, float progress) {
        float p=clamp(progress);
        ShoppingIslandGeometry.Box anchorCard=atAnchor(from.box(),compact);
        Frame centered=new Frame(anchorCard,from.offsetY(),from.bodyAlpha(),from.pillAlpha(),from.radius(),from.ink());
        ShoppingIslandGeometry.Box bar=bar(anchorCard,barHeight);
        Frame rolled=new Frame(bar,bar.height()-bodyHeight,0,0,bar.height()/2f,1);
        Frame end=resting(compact,Math.min(compact.width(),compact.height())/2f,true);
        boolean returning=requiresReturn(from,compact);
        if(returning&&p<.42f)return mix(from,centered,p/.42f);
        float rollProgress=returning?(p-.42f)/.58f:p;
        Frame rollStart=returning?centered:from;
        float rollEnd=.38f/.58f;
        if(rollProgress<rollEnd)return paper(mix(rollStart,rolled,rollProgress/rollEnd),barHeight);
        return mix(rolled,end,(rollProgress-rollEnd)/(1-rollEnd));
    }
    static long closingDurationMs(Frame from,ShoppingIslandGeometry.Box compact) {
        return requiresReturn(from,compact)?CLOSE_MS:ROLL_CLOSE_MS;
    }
    static boolean requiresReturn(Frame from,ShoppingIslandGeometry.Box compact) {
        ShoppingIslandGeometry.Box anchor=atAnchor(from.box(),compact);
        // Independent pixel rounding of a morph's width and x can differ by one
        // pixel at the same visual center; that must not manufacture a move pause.
        return Math.abs(from.box().x()-anchor.x())>1||Math.abs(from.box().y()-anchor.y())>1;
    }
    static Frame morph(Frame from, ShoppingIslandGeometry.Box target, float radius, float progress) {
        return mix(from,resting(target,radius,false),clamp(progress));
    }
    static ShoppingIslandGeometry.Layout layout(ShoppingIslandGeometry.Layout from, ShoppingIslandGeometry.Layout to, float progress) {
        float p=ease(clamp(progress));
        return new ShoppingIslandGeometry.Layout(box(from.window(),to.window(),p),box(from.video(),to.video(),p),
                value(from.headerHeight(),to.headerHeight(),p),value(from.headerContentTop(),to.headerContentTop(),p),
                value(from.footerTop(),to.footerTop(),p),value(from.gripTop(),to.gripTop(),p),
                to.holeLeft(),to.holeRight(),to.leftLobe(),to.rightLobe(),to.holeWidth());
    }
    static Frame mix(Frame a,Frame b,float progress) {
        float p=ease(clamp(progress));
        return new Frame(box(a.box(),b.box(),p),number(a.offsetY(),b.offsetY(),p),number(a.bodyAlpha(),b.bodyAlpha(),p),
                number(a.pillAlpha(),b.pillAlpha(),p),number(a.radius(),b.radius(),p),number(a.ink(),b.ink(),p));
    }
    /** A paper card is revealed only by geometry; fading its whole body makes a grey flash. */
    private static Frame paper(Frame frame,int barHeight) {
        return frame.box().height()>Math.max(1,barHeight)
                ?new Frame(frame.box(),frame.offsetY(),1,0,frame.radius(),0):frame;
    }
    private static ShoppingIslandGeometry.Box atAnchor(ShoppingIslandGeometry.Box card,ShoppingIslandGeometry.Box compact) {
        return new ShoppingIslandGeometry.Box(compact.x()+(compact.width()-card.width())/2,compact.y(),card.width(),card.height());
    }
    private static ShoppingIslandGeometry.Box bar(ShoppingIslandGeometry.Box card,int height) {
        return new ShoppingIslandGeometry.Box(card.x(),card.y(),card.width(),Math.max(1,height));
    }
    private static ShoppingIslandGeometry.Box box(ShoppingIslandGeometry.Box a,ShoppingIslandGeometry.Box b,float p) {
        return new ShoppingIslandGeometry.Box(value(a.x(),b.x(),p),value(a.y(),b.y(),p),Math.max(1,value(a.width(),b.width(),p)),Math.max(1,value(a.height(),b.height(),p)));
    }
    private static int value(int a,int b,float p){return Math.round(number(a,b,p));}
    private static float number(float a,float b,float p){return a+(b-a)*p;}
    private static float clamp(float p){return Float.isFinite(p)?Math.max(0,Math.min(1,p)):0;}
    private static float ease(float p){float q=1-p;return 1-q*q*q;}

    /** Generation fence for cancellation/reversal and delayed animator completion. */
    static final class Epoch {
        private long value;
        long next(){return ++value;}
        boolean current(long candidate){return candidate==value;}
    }
}
