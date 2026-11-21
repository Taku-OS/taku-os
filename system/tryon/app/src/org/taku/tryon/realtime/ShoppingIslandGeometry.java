// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** Screen-pixel presentation geometry only. cutout coordinates are a verified physical path, not safeInsets. */
final class ShoppingIslandGeometry {
    enum Mode { COMPACT, PREVIEW, FULLSCREEN }
    record Box(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
    }
    record Display(int width, int height, int insetLeft, int insetTop, int insetRight, int insetBottom,
                   int cutoutLeft, int cutoutTop, int cutoutRight, int cutoutBottom) {}
    record Layout(Box window, Box video, int headerHeight, int headerContentTop, int footerTop, int gripTop,
                  int holeLeft, int holeRight, int leftLobe, int rightLobe, int holeWidth) {}
    private ShoppingIslandGeometry() {}

    static boolean trustedHole(Display d, float density) {
        int width=d.cutoutRight-d.cutoutLeft,height=d.cutoutBottom-d.cutoutTop;
        int center=(d.cutoutLeft+d.cutoutRight)/2;
        return width>0&&height>0&&d.cutoutTop>=0&&d.cutoutTop<=Math.max(dp(4,density),d.insetTop)
                && height<=dp(48,density)&&height<=width*1.25f&&width<=Math.max(1,d.width/3)
                && center>=d.width/4&&center<=d.width*3/4;
    }
    /** Ordinary application overlays cannot receive touches over SystemUI's status-bar window. */
    static Layout applicationOverlayLayout(Display display, float density, Mode mode, int requestedWidth,
                                          boolean moved, int previousX, int previousY) {
        return layout(display,density,mode,requestedWidth,moved,previousX,previousY,false);
    }
    /** Physical-anchor geometry only; a real SystemUI host is required to make that status row interactive. */
    static Layout layout(Display display, float density, Mode mode, int requestedWidth,
                         boolean moved, int previousX, int previousY) {
        return layout(display,density,mode,requestedWidth,moved,previousX,previousY,true);
    }
    private static Layout layout(Display display, float density, Mode mode, int requestedWidth,
                                 boolean moved, int previousX, int previousY, boolean statusBarHost) {
        int sw=Math.max(1,display.width),sh=Math.max(1,display.height);
        int left=bound(display.insetLeft,0,sw-1),right=bound(sw-Math.max(0,display.insetRight),left+1,sw);
        int bottom=bound(sh-Math.max(0,display.insetBottom),1,sh),topSafe=Math.max(0,display.insetTop);
        boolean hole=statusBarHost&&trustedHole(display,density);
        int cameraX=hole?(display.cutoutLeft+display.cutoutRight)/2:(left+right)/2;
        int cameraY=hole?(display.cutoutTop+display.cutoutBottom)/2:topSafe+dp(8,density)+dp(22,density);
        // Safe-cutout height often covers the entire status bar. It must never enlarge the visual island.
        int visualHeight=Math.min(dp(32,density),bottom),touchHeight=Math.min(bottom,Math.max(visualHeight,dp(44,density)));
        int lobe=dp(32,density),label=dp(66,density),join=dp(8,density);
        int cameraWidth=hole?Math.max(dp(24,density),display.cutoutRight-display.cutoutLeft+dp(8,density)):0;
        int width=lobe+join+cameraWidth+join+label;
        int anchorX=hole?cameraX-lobe-join-cameraWidth/2:(left+right-width)/2;
        if(hole) {
            int laneLeft=Math.max(left,dp(112,density)),laneRight=Math.min(right,sw-dp(112,density));
            int labelAvailable=laneRight-(anchorX+lobe+join+cameraWidth+join);
            // Do not slide the island across the real hole or cover OS clock/notification/system icons.
            if(anchorX<laneLeft||labelAvailable<dp(32,density))hole=false;
            else label=Math.min(label,labelAvailable);
        }
        if(!hole) {
            cameraWidth=0;join=0;label=Math.max(label,dp(106,density));width=lobe+label;anchorX=(left+right-width)/2;
        } else width=lobe+join+cameraWidth+join+label;
        width=Math.min(width,right-left);anchorX=bound(anchorX,left,Math.max(left,right-width));
        int anchorY=hole?bound(cameraY-touchHeight/2,0,Math.max(0,bottom-touchHeight))
                :bound(topSafe+dp(8,density),0,Math.max(0,bottom-touchHeight));
        if(mode==Mode.COMPACT) {
            Box box=new Box(anchorX,anchorY,Math.max(1,width),Math.max(1,touchHeight));
            return new Layout(box,new Box(0,0,1,1),visualHeight,0,touchHeight,touchHeight,
                    hole?bound(cameraX-cameraWidth/2-box.x,0,box.width):-1,
                    hole?bound(cameraX+cameraWidth-cameraWidth/2-box.x,0,box.width):-1,lobe,label,cameraWidth);
        }
        int margin=mode==Mode.FULLSCREEN?0:Math.min(dp(8,density),Math.max(0,(right-left-1)/2));
        int available=Math.max(1,right-left-2*margin);
        width=mode==Mode.FULLSCREEN?right-left:Math.min(available,
                Math.max(Math.min(dp(260,density),available),requestedWidth>0?requestedWidth:dp(396,density)));
        int x=mode==Mode.FULLSCREEN?left:bound(cameraX-width/2,left+margin,Math.max(left+margin,right-margin-width));
        int y=mode==Mode.FULLSCREEN?0:hole?Math.max(0,cameraY-visualHeight/2):topSafe+dp(8,density);
        y=bound(y,0,Math.max(0,bottom-1));
        if(mode==Mode.PREVIEW&&moved) {
            x=bound(previousX,left,Math.max(left,right-width));y=bound(previousY,0,Math.max(0,bottom-dp(160,density)));
        }
        // All readable header content is below both the status-bar row and the physical hole.
        // Its top cannot be inferred by subtracting text height from a combined safe-area height.
        int contentTop=Math.max(dp(8,density),Math.max(topSafe, trustedHole(display,density)?display.cutoutBottom:0)+dp(8,density)-y);
        int header=contentTop+dp(48,density),padding=dp(16,density),gap=dp(8,density),footer=dp(48,density),grip=dp(24,density);
        int availableHeight=Math.max(1,bottom-y-(mode==Mode.FULLSCREEN?0:dp(8,density)));
        int chrome=header+gap*2+footer+grip;
        int videoWidth=Math.max(1,width-padding*2),videoHeight=Math.max(1,videoWidth*4/3);
        if(videoHeight>Math.max(1,availableHeight-chrome)) {
            videoHeight=Math.max(1,availableHeight-chrome);videoWidth=Math.max(1,Math.min(videoWidth,videoHeight*3/4));
        }
        int height=mode==Mode.FULLSCREEN?availableHeight:Math.min(availableHeight,chrome+videoHeight);
        int videoTop=mode==Mode.FULLSCREEN?header+gap+Math.max(0,(height-chrome-videoHeight)/2):header+gap;
        int footerTop=Math.max(0,height-footer-grip);
        Box box=new Box(x,y,Math.max(1,width),Math.max(1,height));
        Box video=new Box(Math.max(0,(width-videoWidth)/2),Math.min(videoTop,Math.max(0,height-1)),
                Math.min(width,videoWidth),Math.min(videoHeight,Math.max(1,footerTop-Math.min(videoTop,Math.max(0,height-1)))));
        return new Layout(box,video,Math.min(header,height),Math.min(contentTop,Math.max(0,height-1)),footerTop,
                Math.max(0,height-grip),-1,-1,lobe,label,cameraWidth);
    }
    private static int dp(int value,float density) { return Math.max(1,Math.round(value*Math.max(.1f,density))); }
    private static int bound(int value,int min,int max) { return Math.max(min,Math.min(Math.max(min,max),value)); }
}
