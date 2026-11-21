// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import org.json.JSONObject;

/** Single actor, memory-only Camera2 YUV source. No HAL bridge, files, network or model calls. */
final class CameraPreviewFrames {
    private final Object lock = new Object();
    private final Context context;
    private final Handler main;
    private final Runnable failed;
    private final HandlerThread thread;
    private final Handler actor;
    private final ImageReader reader;
    private final CameraFrameLease leases = new CameraFrameLease();
    private boolean closed, released, failureReported;
    private final ArrayList<Runnable> closedCallbacks = new ArrayList<>();
    private byte[] latestJpeg;
    private CameraFrameLease.Lease latestLease;
    private int latestWidth, latestHeight;
    private long latestSequence;

    CameraPreviewFrames(Context context, int width, int height, long camera, long stream,
            int clockwiseDegrees, Handler main, Runnable failed) {
        this.context = context.getApplicationContext(); this.main = main; this.failed = failed;
        reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
        thread = new HandlerThread("TakuCameraFrames");
        try {
            thread.start(); actor = new Handler(thread.getLooper());
            leases.open(camera, stream); leases.orientation(clockwiseDegrees);
            reader.setOnImageAvailableListener(this::imageAvailable, actor);
        } catch (RuntimeException | Error failure) { reader.close(); thread.quitSafely(); throw failure; }
    }

    Surface surface() { return reader.getSurface(); }

    void orientation(int clockwiseDegrees) {
        synchronized (lock) {
            if (closed) return;
            long generation = leases.orientation(clockwiseDegrees);
            if (latestLease != null && latestLease.generation != generation) clearLatest();
        }
    }

    /** Each caller receives its own JPEG bytes; no mutable plane or encoder buffer escapes. */
    CallTryOnBridge.Frame latest(long afterSequence) {
        synchronized (lock) {
            if (closed || latestJpeg == null || latestSequence <= afterSequence || !leases.current(latestLease) || !permitted()) return null;
            try {
                JSONObject metadata = new JSONObject().put("sourceSeq", latestSequence)
                        .put("camera", latestLease.camera).put("stream", latestLease.stream)
                        .put("generation", latestLease.generation).put("copiedNs", latestLease.copiedNs)
                        .put("width", latestWidth).put("height", latestHeight);
                return new CallTryOnBridge.Frame(latestJpeg.clone(), metadata);
            } catch (Exception invalid) { return null; }
            catch (OutOfMemoryError exhausted) { reportFailure(); return null; }
        }
    }

    /** Invalidates immediately, then joins actor work without blocking the UI thread. */
    void close(Runnable afterClosed) {
        synchronized (lock) {
            if (released) { if (afterClosed != null) main.post(afterClosed); return; }
            if (afterClosed != null) closedCallbacks.add(afterClosed);
            if (closed) return;
            closed = true; leases.close(); clearLatest();
        }
        try { reader.setOnImageAvailableListener(null, null); } catch (RuntimeException alreadyClosed) { }
        Runnable release = () -> {
            try { reader.close(); }
            finally {
                thread.quitSafely();
                ArrayList<Runnable> callbacks;
                synchronized (lock) { released = true; callbacks = new ArrayList<>(closedCallbacks); closedCallbacks.clear(); }
                for (Runnable callback : callbacks) main.post(callback);
            }
        };
        if (!actor.post(release)) release.run();
    }

    private boolean permitted() {
        return context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void imageAvailable(ImageReader source) {
        Image image = null;
        CameraFrameLease.Lease lease = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            if (!permitted()) { reportFailure(); return; }
            Rect crop = image.getCropRect();
            synchronized (lock) {
                if (closed || failureReported) return;
                long generation = leases.geometry(image.getWidth(), image.getHeight(), crop.left, crop.top, crop.right, crop.bottom);
                if (latestLease != null && latestLease.generation != generation) clearLatest();
                lease = leases.acquire(SystemClock.elapsedRealtimeNanos());
            }
            if (lease == null) return;
            Image.Plane[] planes = image.getPlanes();
            if (planes.length != 3 || image.getFormat() != ImageFormat.YUV_420_888) throw new IllegalArgumentException("yuv_format");
            YuvPlanePacking.Packed packed = YuvPlanePacking.pack(image.getWidth(), image.getHeight(),
                    crop.left, crop.top, crop.right, crop.bottom, plane(planes[0]), plane(planes[1]), plane(planes[2]));
            // Return the ImageReader slot before JPEG encoding; only the current packed frame is held.
            image.close(); image = null;
            if (!leases.current(lease)) return;
            YuvPlanePacking.Packed upright = YuvPlanePacking.orient(packed, lease.orientation, true);
            byte[] jpeg = encode(upright);
            synchronized (lock) {
                if (closed || failureReported || jpeg == null) return;
                if (!permitted()) { reportFailure(); return; }
                long sequence = leases.publish(lease);
                if (sequence == 0) return;
                latestJpeg = jpeg; latestLease = lease; latestSequence = sequence;
                latestWidth = upright.width; latestHeight = upright.height;
            }
        } catch (RuntimeException invalid) {
            // Malformed/closed buffers are dropped. Camera state callbacks handle actual device errors.
        } catch (OutOfMemoryError exhausted) {
            reportFailure();
        } finally {
            if (image != null) image.close();
            if (lease != null) leases.discard(lease);
        }
    }

    private static YuvPlanePacking.Plane plane(Image.Plane value) {
        return new YuvPlanePacking.Plane(value.getBuffer(), value.getRowStride(), value.getPixelStride());
    }

    private void reportFailure() {
        synchronized (lock) {
            if (closed || failureReported) return;
            failureReported = true; leases.close(); clearLatest();
        }
        main.post(failed);
    }

    private static byte[] encode(YuvPlanePacking.Packed source) {
        YuvImage image = new YuvImage(source.nv21, ImageFormat.NV21, source.width, source.height, null);
        Rect bounds = new Rect(0, 0, source.width, source.height);
        for (int quality : new int[]{82, 64, 46, 28}) {
            CappedJpeg output = new CappedJpeg();
            if (!image.compressToJpeg(bounds, quality, output) || output.overflow) continue;
            byte[] jpeg = output.toByteArray();
            if (jpeg.length >= 4 && jpeg.length <= CallTryOnBridge.MAX_JPEG &&
                    (jpeg[0] & 255) == 255 && (jpeg[1] & 255) == 216 &&
                    (jpeg[jpeg.length - 2] & 255) == 255 && (jpeg[jpeg.length - 1] & 255) == 217) return jpeg;
        }
        return null;
    }

    private void clearLatest() { latestJpeg = null; latestLease = null; latestSequence = 0; latestWidth = latestHeight = 0; }

    private static final class CappedJpeg extends ByteArrayOutputStream {
        boolean overflow;
        CappedJpeg() { super(64 * 1024); }
        @Override public synchronized void write(byte[] value, int offset, int length) {
            int available = Math.min(length, CallTryOnBridge.MAX_JPEG - count);
            if (available < length) overflow = true;
            // Native JPEG output can finish safely even when this quality exceeds the byte budget.
            super.write(value, offset, available);
        }
        @Override public synchronized void write(int value) {
            if (count == CallTryOnBridge.MAX_JPEG) { overflow = true; return; }
            super.write(value);
        }
    }
}
