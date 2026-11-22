// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Looper;
import java.io.IOException;

/** One immutable original frame supplies both model pixels and the final garment crop. Background only. */
final class LiveFrameImage implements AutoCloseable {
    private Bitmap original;
    private final LiveFrameWindow.Layout layout;
    private final LiveFrameRegion region;
    private LiveFrameImage(Bitmap original,LiveFrameWindow.Layout layout,LiveFrameRegion region){
        this.original=original;this.layout=layout;this.region=region;
    }
    synchronized int width(){if(original==null)throw new IllegalStateException("Closed live frame");return original.getWidth();}
    synchronized int height(){if(original==null)throw new IllegalStateException("Closed live frame");return original.getHeight();}
    static LiveFrameImage take(AccessibilityService.ScreenshotResult shot,LiveFrameWindow.Layout layout) throws IOException {
        if(shot==null)throw new IOException("invalid_live_frame");
        HardwareBuffer buffer=shot.getHardwareBuffer();Bitmap hardware=null,software=null;
        try {
            if(buffer==null||layout==null||buffer.getWidth()<128||buffer.getHeight()<128
                    ||(long)buffer.getWidth()*buffer.getHeight()>16_777_216)throw new IOException("invalid_live_frame");
            LiveFrameRegion region=LiveFrameRegion.fromWindow(layout.left(),layout.top(),layout.right(),layout.bottom(),
                layout.contentTop(),layout.contentBottom(),buffer.getWidth(),buffer.getHeight());
            if(region==null)throw new IOException("live_frame_geometry_changed");
            ColorSpace color=shot.getColorSpace();
            if(color!=null&&color.getModel()!=ColorSpace.Model.RGB)throw new IOException("invalid_color_space");
            hardware=Bitmap.wrapHardwareBuffer(buffer,color);if(hardware==null)throw new IOException("live_frame_unavailable");
            software=hardware.copy(Bitmap.Config.ARGB_8888,false);if(software==null)throw new IOException("live_frame_unavailable");
            LiveFrameImage result=new LiveFrameImage(software,layout,region);software=null;return result;
        } finally {if(software!=null)software.recycle();if(hardware!=null)hardware.recycle();if(buffer!=null)buffer.close();}
    }
    synchronized String analysisData() throws IOException {
        requireOpen();
        return ScreenshotCodec.dataUrl(ScreenshotCodec.encodePixels(original,regionRect(),960,220000));
    }
    /** Convert an analysis/selection ROI result before passing it to the original-frame crop API. */
    synchronized LiveFrameTarget toOriginal(LiveFrameTarget roiTarget) throws IOException {
        requireOpen();
        if(roiTarget==null)throw new IOException("invalid_live_target");
        try {return new LiveFrameTarget(region.toOriginal(roiTarget.bounds()),roiTarget.description());}
        catch(IllegalArgumentException invalid){throw new IOException("invalid_live_target");}
    }
    /** One bounded local ranking value. No model request, image log or source mutation. */
    synchronized double qualityScore() throws IOException {
        requireOpen();requireBackground();Bitmap sample=null;
        try {
            sample=copyRegion(LiveFrameQuality.SAMPLE_WIDTH,LiveFrameQuality.SAMPLE_HEIGHT);
            int[] pixels=new int[LiveFrameQuality.SAMPLE_WIDTH*LiveFrameQuality.SAMPLE_HEIGHT];
            sample.getPixels(pixels,0,LiveFrameQuality.SAMPLE_WIDTH,0,0,LiveFrameQuality.SAMPLE_WIDTH,LiveFrameQuality.SAMPLE_HEIGHT);
            for(int i=0;i<pixels.length;i++){
                int pixel=pixels[i];pixels[i]=(((pixel>>16)&255)*54+((pixel>>8)&255)*183+(pixel&255)*19)>>8;
            }
            return LiveFrameQuality.score(pixels,LiveFrameQuality.SAMPLE_WIDTH,LiveFrameQuality.SAMPLE_HEIGHT);
        } catch(OutOfMemoryError exhausted){throw new IOException("image_memory_limit");}
        catch(IllegalArgumentException|IllegalStateException invalid){throw new IOException("invalid_quality_sample");}
        finally {if(sample!=null)sample.recycle();}
    }
    /** An independent bounded ROI copy; the caller owns and must recycle the returned Bitmap. */
    synchronized Bitmap previewBitmap() throws IOException {
        requireOpen();requireBackground();
        float scale=Math.min(1f,960f/Math.max(region.width(),region.height()));
        try {return copyRegion(Math.max(1,Math.round(region.width()*scale)),Math.max(1,Math.round(region.height()*scale)));}
        catch(OutOfMemoryError exhausted){throw new IOException("image_memory_limit");}
        catch(IllegalArgumentException|IllegalStateException invalid){throw new IOException("live_frame_unavailable");}
    }
    synchronized byte[] crop(LiveFrameTarget target) throws IOException {
        requireOpen();
        if(target==null||!layout.allows(target.bounds()))throw new IOException("target_outside_live_video");
        ScreenshotCrop.Bounds bounds=target.bounds().pixels(original.getWidth(),original.getHeight());
        if(bounds==null)throw new IOException("garment_too_small");
        return ScreenshotCodec.encodePixels(original,new Rect(bounds.left(),bounds.top(),bounds.right(),bounds.bottom()),1920,ReferenceStore.MAX_BYTES);
    }
    private Rect regionRect(){return new Rect(region.left(),region.top(),region.right(),region.bottom());}
    private Bitmap copyRegion(int width,int height) {
        Bitmap result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.SRGB));
        try {
            Canvas canvas=new Canvas(result);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(original,regionRect(),new Rect(0,0,width,height),paint);return result;
        } catch(RuntimeException|Error failure){result.recycle();throw failure;}
    }
    private void requireOpen() throws IOException {if(original==null)throw new IOException("live_frame_closed");}
    private static void requireBackground() throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("background_thread_required");
    }
    @Override public synchronized void close(){if(original!=null){original.recycle();original=null;}}
}
