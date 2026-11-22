// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

/** Bounded local frame ranking, not a semantic garment detector or a model-confidence score. */
public final class LiveFrameQuality {
    public static final int SAMPLE_WIDTH=64,SAMPLE_HEIGHT=96;
    private LiveFrameQuality(){}

    /** Laplacian variance with exposure clipping penalties; comparable only for the same video geometry. */
    public static double score(int[] luminance,int width,int height) {
        if(luminance==null || width<8 || height<8 || width>96 || height>96
                || (long)width*height!=luminance.length)throw new IllegalArgumentException("Invalid quality sample");
        long sum=0;int clipped=0;
        for(int value:luminance){
            if(value<0||value>255)throw new IllegalArgumentException("Invalid luminance");
            sum+=value;if(value<=8||value>=247)clipped++;
        }
        double lapSum=0,lapSquares=0;int count=0;
        for(int y=1;y<height-1;y++)for(int x=1;x<width-1;x++){
            int p=y*width+x;
            int lap=4*luminance[p]-luminance[p-1]-luminance[p+1]-luminance[p-width]-luminance[p+width];
            lapSum+=lap;lapSquares+=(double)lap*lap;count++;
        }
        double variance=Math.max(0,lapSquares/count-Math.pow(lapSum/count,2));
        double mean=sum/(double)luminance.length;
        double exposure=Math.min(1,Math.min(mean,255-mean)/48);
        double unclipped=1-clipped/(double)luminance.length;
        return Math.log1p(variance)*exposure*unclipped*unclipped;
    }
}
