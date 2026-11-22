// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

/** Pixel domain shared by image encoding, point selection and reference cropping. No Android state. */
public record LiveFrameRegion(int frameWidth,int frameHeight,int left,int top,int right,int bottom) {
    public LiveFrameRegion {
        if(frameWidth<128 || frameHeight<128 || frameWidth>16384 || frameHeight>16384
                || (long)frameWidth*frameHeight>16_777_216 || left<0 || top<0
                || right>frameWidth || bottom>frameHeight || right-left<128 || bottom-top<128)
            throw new IllegalArgumentException("Invalid live video region");
    }
    public int width(){return right-left;}
    public int height(){return bottom-top;}

    /** Derive only from verified window/content geometry; crop inward so no header/card pixels leak in. */
    public static LiveFrameRegion fromWindow(int windowLeft,int windowTop,int windowRight,int windowBottom,
                                            int contentTop,int contentBottom,int frameWidth,int frameHeight) {
        long width=(long)windowRight-windowLeft,height=(long)windowBottom-windowTop;
        if(width<=0 || height<=0 || width>16384 || height>16384
                || contentTop<windowTop || contentBottom>windowBottom || contentBottom<=contentTop
                || frameWidth<128 || frameHeight<128 || frameWidth>16384 || frameHeight>16384)return null;
        double sx=frameWidth/(double)width,sy=frameHeight/(double)height;
        if(!Double.isFinite(sx)||!Double.isFinite(sy)||sx<=0||sy<=0||Math.abs(sx-sy)/Math.max(sx,sy)>.01)return null;
        int top=(int)ceilDiv(((long)contentTop-windowTop)*frameHeight,height);
        int bottom=(int)(((long)contentBottom-windowTop)*frameHeight/height);
        try {return new LiveFrameRegion(frameWidth,frameHeight,0,top,frameWidth,bottom);}
        catch(IllegalArgumentException invalid){return null;}
    }

    /** Keep normalization rounding inside the video ROI; final JPEG pixel rounding is outward. */
    public LiveFrameTarget.Bounds toOriginal(LiveFrameTarget.Bounds roiBounds) {
        if(roiBounds==null)throw new IllegalArgumentException("Missing outfit bounds");
        int l=(int)ceilDiv((long)left*1000+(long)width()*roiBounds.left(),frameWidth);
        int t=(int)ceilDiv((long)top*1000+(long)height()*roiBounds.top(),frameHeight);
        int r=(int)(((long)left*1000+(long)width()*roiBounds.right())/frameWidth);
        int b=(int)(((long)top*1000+(long)height()*roiBounds.bottom())/frameHeight);
        return new LiveFrameTarget.Bounds(l,t,r,b);
    }
    private static long ceilDiv(long value,long divisor){return value/divisor+(value%divisor==0?0:1);}
}
