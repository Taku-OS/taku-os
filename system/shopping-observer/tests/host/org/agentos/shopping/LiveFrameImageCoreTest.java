// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import java.util.Arrays;

public final class LiveFrameImageCoreTest {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static void invalid(Runnable action,String message){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,message);}
    public static void main(String[] args){regions();quality();System.out.println(assertions+" live frame image core assertions passed");}
    private static void regions(){
        LiveFrameRegion region=LiveFrameRegion.fromWindow(0,0,1080,2424,273,2070,1080,2424);
        check(region!=null && region.left()==0 && region.top()==273 && region.right()==1080 && region.bottom()==2070,
            "AI/preview domain excludes actual header and native product bar");
        LiveFrameTarget.Bounds garment=new LiveFrameTarget.Bounds(250,200,750,700);
        check(region.toOriginal(garment).equals(new LiveFrameTarget.Bounds(250,261,750,631)),
            "ROI normalized coordinates map to the original screenshot rather than being reused in the wrong space");
        check(region.equals(LiveFrameRegion.fromWindow(10,20,1090,2444,293,2090,1080,2424)),
            "window screen offset does not shift the screenshot-local video domain");
        LiveFrameTarget.Bounds full=region.toOriginal(new LiveFrameTarget.Bounds(0,0,1000,1000));
        check(full.withinVideo(273,2070,2424),"full ROI normalization stays inside trusted video geometry");
        ScreenshotCrop.Bounds pixels=full.pixels(1080,2424);
        check(pixels!=null && pixels.top()>=273 && pixels.bottom()<=2070 && pixels.left()==0 && pixels.right()==1080,
            "final outward JPEG rounding cannot reintroduce excluded UI");
        check(pixels.top()-273<=3 && 2070-pixels.bottom()<=3,"1000-unit mapping loses only bounded quantization pixels");
        LiveFrameRegion half=LiveFrameRegion.fromWindow(0,0,1080,2424,273,2070,540,1212);
        check(half!=null && half.top()==137 && half.bottom()==1035,"fractional screen ROI crop rounds inward at screenshot scale");
        check(LiveFrameRegion.fromWindow(0,0,1080,2424,273,2070,540,1216)!=null,
            "small accepted X/Y scale difference still uses each axis independently");
        check(LiveFrameRegion.fromWindow(0,0,1080,2424,273,2070,540,1300)==null,
            "materially non-uniform screenshot scale fails closed");
        for(int[] invalid:new int[][]{
                {0,0,1080,2424,-1,2070,1080,2424},{0,0,1080,2424,273,2425,1080,2424},
                {0,0,1080,2424,2070,273,1080,2424},{0,0,1080,2424,273,300,1080,2424},
                {0,0,0,2424,273,2070,1080,2424},{0,0,1080,0,273,2070,1080,2424},
                {0,0,1080,2424,273,2070,0,2424},{0,0,1080,2424,273,2070,1080,127},
                {Integer.MIN_VALUE,0,Integer.MAX_VALUE,2424,273,2070,1080,2424},
                {0,0,16384,16384,0,16384,16384,16384}}){
            check(LiveFrameRegion.fromWindow(invalid[0],invalid[1],invalid[2],invalid[3],invalid[4],invalid[5],invalid[6],invalid[7])==null,
                "invalid/stale or excessive ROI cannot capture neighboring UI");
        }
        invalid(()->new LiveFrameRegion(1080,2424,0,-1,1080,2000),"negative screenshot ROI rejected");
        invalid(()->new LiveFrameRegion(1080,2424,0,273,1081,2070),"oversized screenshot ROI rejected");
        invalid(()->region.toOriginal(null),"missing garment bounds rejected");
        LiveFrameRegion narrow=new LiveFrameRegion(16384,1024,0,0,128,128);
        invalid(()->narrow.toOriginal(new LiveFrameTarget.Bounds(1,1,2,2)),"normalization collapse cannot be padded into another garment");
        LiveFrameRegion shorter=new LiveFrameRegion(1080,2424,0,300,1080,1900);
        LiveFrameTarget.Bounds mapped=shorter.toOriginal(new LiveFrameTarget.Bounds(250,100,750,200));
        check(mapped.bottom()-mapped.top()<100 && mapped.pixels(1080,2424)!=null,
            "valid 128px-plus garments are not rejected merely because original-frame normalized span shrank below 100");
        for(int frameHeight:new int[]{1280,1920,2424,3200})for(int videoTop:new int[]{0,200,300}){
            int videoBottom=frameHeight-200;
            LiveFrameRegion actual=new LiveFrameRegion(1080,frameHeight,0,videoTop,1080,videoBottom);
            for(LiveFrameTarget.Bounds b:new LiveFrameTarget.Bounds[]{new LiveFrameTarget.Bounds(0,0,1000,1000),
                    new LiveFrameTarget.Bounds(100,100,900,900),new LiveFrameTarget.Bounds(250,0,750,600),
                    new LiveFrameTarget.Bounds(250,300,750,1000)}){
                LiveFrameTarget.Bounds original=actual.toOriginal(b);ScreenshotCrop.Bounds crop=original.pixels(1080,frameHeight);
                check(original.withinVideo(videoTop,videoBottom,frameHeight),"mapped target is inside source-domain video");
                check(crop!=null && crop.top()>=videoTop && crop.bottom()<=videoBottom,"pixel crop stays in trusted region after normalization round-trip");
            }
        }
    }
    private static void quality(){
        int width=LiveFrameQuality.SAMPLE_WIDTH,height=LiveFrameQuality.SAMPLE_HEIGHT;
        int[] sharp=new int[width*height];for(int y=0;y<height;y++)for(int x=0;x<width;x++)sharp[y*width+x]=x%8<4?64:192;
        int[] blurred=blur(blur(sharp,width,height),width,height);
        double clearScore=LiveFrameQuality.score(sharp,width,height),blurScore=LiveFrameQuality.score(blurred,width,height);
        check(Double.isFinite(clearScore)&&clearScore>blurScore&&blurScore>0,"same-scene crisp fabric edges rank above motion/defocus blur");
        int[] brighter=sharp.clone();for(int i=0;i<brighter.length;i++)brighter[i]+=20;
        check(Math.abs(clearScore-LiveFrameQuality.score(brighter,width,height))<.000001,
            "safe brightness shift alone does not beat a sharper frame");
        int[] over=new int[sharp.length],under=new int[sharp.length];
        for(int i=0;i<over.length;i++){over[i]=sharp[i]<128?248:255;under[i]=sharp[i]<128?0:7;}
        check(clearScore>LiveFrameQuality.score(over,width,height),"clipped white detail does not win as a sharp frame");
        check(clearScore>LiveFrameQuality.score(under,width,height),"clipped black detail does not win as a sharp frame");
        for(int value:new int[]{0,127,255}){int[] flat=new int[sharp.length];Arrays.fill(flat,value);check(LiveFrameQuality.score(flat,width,height)==0,"uniform frame has finite zero evidence of sharp detail");}
        int[] before=sharp.clone();LiveFrameQuality.score(sharp,width,height);check(Arrays.equals(before,sharp),"ranking never modifies retained sample pixels");
        invalid(()->LiveFrameQuality.score(null,width,height),"missing samples rejected");
        invalid(()->LiveFrameQuality.score(new int[0],width,height),"empty samples rejected");
        invalid(()->LiveFrameQuality.score(sharp,0,height),"zero sample dimension rejected");
        invalid(()->LiveFrameQuality.score(sharp,Integer.MAX_VALUE,Integer.MAX_VALUE),"unbounded sample dimensions rejected before multiplication");
        invalid(()->LiveFrameQuality.score(new int[97*96],97,96),"quality work remains bounded");
        int[] bad=sharp.clone();bad[8]=-1;invalid(()->LiveFrameQuality.score(bad,width,height),"negative luminance rejected");
        bad[8]=256;invalid(()->LiveFrameQuality.score(bad,width,height),"out-of-range luminance rejected");
        check(LiveFrameQuality.score(new int[8*8],8,8)==0,"small valid sample retains defined behavior");
    }
    private static int[] blur(int[] input,int width,int height){
        int[] result=input.clone();
        for(int y=1;y<height-1;y++)for(int x=1;x<width-1;x++){
            int sum=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)sum+=input[(y+dy)*width+x+dx];
            result[y*width+x]=sum/9;
        }
        return result;
    }
}
