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
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;

/** Background-only, bounded in-memory encoding; this class never writes screenshot files. */
public final class ScreenshotCodec {
    private static final long MAX_SOURCE_PIXELS = 16_777_216L;
    private static final int MAX_DATA_URL_CHARS = 300000;
    private static final String JPEG_PREFIX = "data:image/jpeg;base64,";
    private ScreenshotCodec() {}

    /** Takes ownership of the screenshot HardwareBuffer and closes it on every return/error path. */
    public static byte[] encode(AccessibilityService.ScreenshotResult shot, int maxEdge, int maxBytes) throws IOException {
        return encode(shot, null, null, maxEdge, maxBytes);
    }

    /**
     * Takes ownership of the screenshot HardwareBuffer and encodes only the verified image rect.
     * Both rectangles are screen coordinates. Capture bounds describe the screenshot's domain
     * (the display for product references), so window surface/shadow padding is never inferred.
     */
    public static byte[] encodeProduct(AccessibilityService.ScreenshotResult shot, Rect windowBounds,
                                       Rect imageBounds, int maxEdge, int maxBytes) throws IOException {
        return encode(shot, windowBounds, imageBounds, maxEdge, maxBytes);
    }

    private static byte[] encode(AccessibilityService.ScreenshotResult shot, Rect windowBounds,
                                 Rect imageBounds, int maxEdge, int maxBytes) throws IOException {
        if (shot == null) throw new IOException("screenshot_unavailable");
        HardwareBuffer buffer = shot.getHardwareBuffer();
        Bitmap hardware = null, software = null, working = null;
        try {
            if (buffer == null) throw new IOException("screenshot_unavailable");
            if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("background_thread_required");
            interrupted();
            if (maxEdge < 64 || maxEdge > 4096 || maxBytes < 1024 || maxBytes > 3 * 1024 * 1024)
                throw new IOException("invalid_image_budget");
            long pixels = (long) buffer.getWidth() * buffer.getHeight();
            if (buffer.getWidth() <= 0 || buffer.getHeight() <= 0 || pixels > MAX_SOURCE_PIXELS)
                throw new IOException("screenshot_pixel_limit");
            ColorSpace sourceSpace = shot.getColorSpace();
            if (sourceSpace != null && sourceSpace.getModel() != ColorSpace.Model.RGB)
                throw new IOException("unsupported_color_space");
            hardware = Bitmap.wrapHardwareBuffer(buffer, sourceSpace);
            if (hardware == null) throw new IOException("screenshot_unavailable");
            software = hardware.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) throw new IOException("screenshot_unavailable");
            Rect source = new Rect(0, 0, software.getWidth(), software.getHeight());
            if (windowBounds != null || imageBounds != null) {
                if (windowBounds == null || imageBounds == null) throw new IOException("invalid_product_bounds");
                ScreenshotCrop.Bounds mapped = ScreenshotCrop.map(windowBounds.left, windowBounds.top,
                    windowBounds.right, windowBounds.bottom, imageBounds.left, imageBounds.top,
                    imageBounds.right, imageBounds.bottom, software.getWidth(), software.getHeight());
                if (mapped == null) throw new IOException("product_bounds_mismatch");
                source.set(mapped.left(), mapped.top(), mapped.right(), mapped.bottom());
            }
            float scale = Math.min(1f, (float) maxEdge / Math.max(source.width(), source.height()));
            int width = Math.max(1, Math.round(source.width() * scale));
            int height = Math.max(1, Math.round(source.height() * scale));
            working = srgb(software, source, width, height);
            software.recycle(); software = null;
            Bitmap owned = working; working = null;
            return compressOwned(owned, maxBytes);
        } catch (OutOfMemoryError exhausted) {
            throw new IOException("image_memory_limit");
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new IOException("screenshot_unavailable");
        } finally {
            if (working != null) working.recycle();
            if (software != null) software.recycle();
            if (hardware != null) hardware.recycle();
            if (buffer != null) buffer.close();
        }
    }

    /** Borrows immutable source pixels; only the encoded crop is retained by the caller. */
    static byte[] encodePixels(Bitmap source, Rect crop, int maxEdge, int maxBytes) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("background_thread_required");
        interrupted();
        if (source == null || crop == null || maxEdge < 64 || maxEdge > 4096
                || maxBytes < 1024 || maxBytes > 3*1024*1024) throw new IOException("invalid_image_budget");
        float scale=Math.min(1f,(float)maxEdge/Math.max(crop.width(),crop.height()));
        Bitmap owned=srgb(source,crop,Math.max(1,Math.round(crop.width()*scale)),Math.max(1,Math.round(crop.height()*scale)));
        return compressOwned(owned,maxBytes);
    }

    private static byte[] compressOwned(Bitmap working, int maxBytes) throws IOException {
        try {
            for (;;) {
                for (int quality : new int[]{88, 76, 64, 52, 40}) {
                    interrupted();
                    BudgetStream bytes = new BudgetStream(maxBytes);
                    boolean compressed;
                    try { compressed = working.compress(Bitmap.CompressFormat.JPEG, quality, bytes); }
                    catch (RuntimeException failed) {
                        if (!bytes.overflowed) throw failed;
                        compressed = false;
                    }
                    if (compressed && !bytes.overflowed && bytes.size() > 0) return bytes.toByteArray();
                }
                if (Math.max(working.getWidth(), working.getHeight()) <= 96) throw new IOException("image_budget_exceeded");
                int nextWidth = Math.max(1, Math.round(working.getWidth() * .80f));
                int nextHeight = Math.max(1, Math.round(working.getHeight() * .80f));
                Bitmap smaller = srgb(working, nextWidth, nextHeight);
                working.recycle(); working = smaller;
            }
        } finally {
            if (working != null) working.recycle();
        }
    }

    public static String dataUrl(byte[] jpeg) throws IOException {
        interrupted();
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216
                || (jpeg[jpeg.length - 2] & 255) != 255 || (jpeg[jpeg.length - 1] & 255) != 217)
            throw new IOException("invalid_jpeg");
        long length = JPEG_PREFIX.length() + 4L * ((jpeg.length + 2L) / 3L);
        if (length > MAX_DATA_URL_CHARS) throw new IOException("image_data_url_limit");
        return JPEG_PREFIX + Base64.encodeToString(jpeg, Base64.NO_WRAP);
    }

    private static Bitmap srgb(Bitmap input, int width, int height) {
        return srgb(input, new Rect(0, 0, input.getWidth(), input.getHeight()), width, height);
    }

    /** Crops directly into the bounded sRGB target; no full-size cropped bitmap is allocated. */
    private static Bitmap srgb(Bitmap input, Rect source, int width, int height) {
        if (input == null || source == null || source.left < 0 || source.top < 0
                || source.right > input.getWidth() || source.bottom > input.getHeight()
                || source.width() <= 0 || source.height() <= 0) throw new IllegalArgumentException();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false,
            ColorSpace.get(ColorSpace.Named.SRGB));
        try {
            Canvas canvas = new Canvas(result);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(input, source, new Rect(0, 0, width, height), paint);
            return result;
        } catch (RuntimeException | Error failure) { result.recycle(); throw failure; }
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("image_encoding_canceled");
    }

    private static final class BudgetStream extends OutputStream {
        private final ByteArrayOutputStream bytes;
        private final int limit;
        private boolean overflowed;
        BudgetStream(int limit) { this.limit = limit; bytes = new ByteArrayOutputStream(Math.min(limit, 65536)); }
        @Override public void write(int value) throws IOException {
            if (bytes.size() >= limit) { overflowed = true; throw new IOException("image_budget_exceeded"); }
            bytes.write(value);
        }
        @Override public void write(byte[] value, int offset, int count) throws IOException {
            if (count < 0 || count > limit - bytes.size()) { overflowed = true; throw new IOException("image_budget_exceeded"); }
            bytes.write(value, offset, count);
        }
        int size() { return bytes.size(); }
        byte[] toByteArray() { return bytes.toByteArray(); }
    }
}
