// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** View-only geometry for the video reference. Never changes a camera stream. */
final class ShoppingPreviewLayout {
    static final int TOOLBAR_DP = 44, HINT_DP = 26;
    private ShoppingPreviewLayout() {}

    /** The floating frame survives fullscreen/compact; those modes must never overwrite it. */
    static final class FloatingFrame {
        int width, x, y;
        boolean moved;
        void move(int nextX, int nextY) { x = nextX; y = nextY; moved = true; }
        void resize(int nextWidth) { width = Math.max(1, nextWidth); }
        ShoppingIslandGeometry.Layout layout(ShoppingIslandGeometry.Display d, float density, ShoppingIslandGeometry.Mode mode) {
            return layout(d, density, mode, false);
        }
        ShoppingIslandGeometry.Layout layout(ShoppingIslandGeometry.Display d, float density, ShoppingIslandGeometry.Mode mode, boolean character) {
            ShoppingIslandGeometry.Layout result = applicationOverlayLayout(d, density, mode, width, moved, x, y, character);
            if (mode == ShoppingIslandGeometry.Mode.PREVIEW) {
                x = result.window().x(); y = result.window().y();
                if (width > 0) width = result.window().width();
            }
            return result;
        }
    }

    static ShoppingIslandGeometry.Layout applicationOverlayLayout(ShoppingIslandGeometry.Display d, float density,
            ShoppingIslandGeometry.Mode mode, int requestedWidth, boolean moved, int previousX, int previousY) {
        return applicationOverlayLayout(d, density, mode, requestedWidth, moved, previousX, previousY, false);
    }

    static ShoppingIslandGeometry.Layout applicationOverlayLayout(ShoppingIslandGeometry.Display d, float density,
            ShoppingIslandGeometry.Mode mode, int requestedWidth, boolean moved, int previousX, int previousY, boolean character) {
        // Character and garment sessions share the same presentation. The effect
        // changes the pixels and labels, never the island or saved floating frame.
        if (mode == ShoppingIslandGeometry.Mode.COMPACT) return shoppingCompact(d, density);
        return shopping(d, density, mode, requestedWidth, moved, previousX, previousY);
    }

    /** Visual nodes share the physical lens; the system host owns their status-bar input region. */
    static ShoppingIslandGeometry.Layout shoppingCompact(ShoppingIslandGeometry.Display d, float density) {
        int sw=Math.max(1,d.width()),bottom=Math.max(1,d.height()-Math.max(0,d.insetBottom()));
        boolean hole=ShoppingIslandGeometry.trustedHole(d,density);
        int cx=hole?(d.cutoutLeft()+d.cutoutRight())/2:sw/2;
        int cy=hole?(d.cutoutTop()+d.cutoutBottom())/2:Math.max(0,d.insetTop())+dp(22,density);
        int rightLobe=dp(104,density);
        if(hole){
            int laneLeft=Math.max(Math.max(0,d.insetLeft()),dp(112,density));
            int laneRight=Math.min(sw-Math.max(0,d.insetRight()),sw-dp(128,density));
            int available=laneRight-(cx+dp(20,density));
            if(cx-dp(52,density)<laneLeft||available<dp(32,density)){
                hole=false;cx=sw/2;cy=Math.max(0,d.insetTop())+dp(22,density);
            }else rightLobe=Math.min(rightLobe,available);
        }
        int width=Math.min(sw,dp(72,density)+rightLobe),height=Math.min(bottom,dp(44,density));
        int x=clamp(hole?cx-dp(52,density):(sw-width)/2,0,sw-width);
        int y=clamp(cy-height/2,0,bottom-height),visual=Math.min(height,dp(32,density));
        return new ShoppingIslandGeometry.Layout(new ShoppingIslandGeometry.Box(x,y,width,height),
                new ShoppingIslandGeometry.Box(0,0,1,1),visual,(height-visual)/2,height,height,
                hole?cx-x-dp(12,density):-1,hole?cx-x+dp(12,density):-1,
                dp(32,density),rightLobe,hole?dp(24,density):0);
    }

    /** Floating controls overlay the picture; fullscreen controls have their own safe top row. */
    private static ShoppingIslandGeometry.Layout shopping(ShoppingIslandGeometry.Display d, float density,
            ShoppingIslandGeometry.Mode mode, int requestedWidth, boolean moved, int previousX, int previousY) {
        int sw = Math.max(1, d.width()), sh = Math.max(1, d.height());
        int left = clamp(d.insetLeft(), 0, sw - 1), right = clamp(sw - Math.max(0, d.insetRight()), left + 1, sw);
        int bottom = clamp(sh - Math.max(0, d.insetBottom()), 1, sh);
        int safeTop = Math.max(0, d.insetTop());
        if (ShoppingIslandGeometry.trustedHole(d, density)) safeTop = Math.max(safeTop, d.cutoutBottom());
        int top = clamp(safeTop, 0, bottom - 1);
        boolean full = mode == ShoppingIslandGeometry.Mode.FULLSCREEN;
        if (full) {
            int width = right - left, height = bottom;
            int pad = Math.min(dp(12, density), Math.max(0, (width - 1) / 2));
            int controlsTop = Math.min(height - 1, top + dp(8, density));
            int header = Math.min(height - 1, controlsTop + dp(TOOLBAR_DP, density) + dp(12, density));
            int videoWidth = Math.max(1, width - pad * 2);
            int availableHeight = Math.max(1, bottom - header);
            // A portrait result is fitted inside this viewport, never stretched or re-captured.
            int videoHeight = Math.min(availableHeight, Math.max(1, Math.round(videoWidth * 1.9f)));
            return new ShoppingIslandGeometry.Layout(new ShoppingIslandGeometry.Box(left, 0, width, height),
                    new ShoppingIslandGeometry.Box(pad, header, videoWidth, videoHeight), header, controlsTop,
                    videoHeight + header, videoHeight + header, -1, -1, dp(32, density), dp(106, density), 0);
        }
        int margin = Math.min(dp(8, density), Math.max(0, (right - left - 1) / 2));
        int availableWidth = Math.max(1, right - left - margin * 2);
        boolean hole=ShoppingIslandGeometry.trustedHole(d,density);
        int cameraX=hole?(d.cutoutLeft()+d.cutoutRight())/2:(left+right)/2;
        int cameraY=hole?(d.cutoutTop()+d.cutoutBottom())/2:top+dp(16,density);
        int anchorTop=clamp(hole?cameraY-dp(16,density):top+dp(4,density),0,bottom-1);
        // Initial reveal reserves the camera/status row. A dragged video keeps its clean media frame.
        int header=moved?0:Math.max(0,top+dp(8,density)-anchorTop);
        header=Math.min(header,Math.max(0,bottom-anchorTop-1));
        int availableHeight=Math.max(1,bottom-(moved?top:anchorTop)-Math.min(dp(8,density),Math.max(0,bottom-(moved?top:anchorTop)-1)));
        int maxWidth=Math.min(availableWidth,Math.max(1,Math.round(Math.max(1,availableHeight-header)/1.2f)));
        int width=clamp(requestedWidth>0?requestedWidth:Math.round((right-left)*.45f),Math.min(maxWidth,dp(144,density)),maxWidth);
        int videoHeight=Math.min(Math.max(1,availableHeight-header),Math.max(1,Math.round(width*1.2f)));
        int height=Math.min(availableHeight,header+videoHeight);
        int x=clamp(moved?previousX:cameraX-width/2,left+margin,Math.max(left+margin,right-margin-width));
        int minY=moved?top:anchorTop;
        int y=clamp(moved?previousY:anchorTop,minY,Math.max(minY,bottom-height));
        return new ShoppingIslandGeometry.Layout(new ShoppingIslandGeometry.Box(x,y,width,height),
                new ShoppingIslandGeometry.Box(0,header,width,Math.max(1,height-header)),header,Math.min(height-1,header+dp(12,density)),height,height,
                -1,-1,dp(32,density),dp(104,density),0);
    }

    /** Independent of a FIT_CENTER image's variable letterbox edges. */
    static ShoppingIslandGeometry.Box shoppingControls(ShoppingIslandGeometry.Layout layout, float density) {
        var window=layout.window();
        int target=Math.max(1,Math.min(dp(TOOLBAR_DP,density),Math.min(window.width()/2,window.height())));
        int edge=dp(12,density);
        int right=window.width();
        int x=Math.max(0,right-target*2-Math.min(edge,Math.max(0,right-target*2)));
        // This field is interpolated by ShoppingPreviewMotion.layout in both directions.
        int y=clamp(layout.headerContentTop(),0,Math.max(0,window.height()-target));
        return new ShoppingIslandGeometry.Box(x,y,Math.min(window.width(),target*2),target);
    }

    /** Same portrait content in both modes; the mini viewport only clips its center. */
    static ShoppingIslandGeometry.Box mediaBox(ShoppingIslandGeometry.Box viewport, boolean mini) {
        if (!mini) return new ShoppingIslandGeometry.Box(0, 0, viewport.width(), viewport.height());
        int height = Math.max(viewport.height(), Math.max(1, Math.round(viewport.width() * 4f / 3f)));
        return new ShoppingIslandGeometry.Box(0, (viewport.height() - height) / 2, viewport.width(), height);
    }

    static int scaledWidth(int startWidth, float factor) {
        if (!Float.isFinite(factor) || factor <= 0) return Math.max(1, startWidth);
        return Math.max(1, Math.round(Math.min(Integer.MAX_VALUE / 2f, Math.max(1, startWidth) * factor)));
    }
    private static int dp(int value, float density) { return Math.max(1, Math.round(value * Math.max(.1f, density))); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(Math.max(min, max), value)); }
}
