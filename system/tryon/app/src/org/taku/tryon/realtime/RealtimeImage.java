// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;


/** A bounded reference held only in memory; no content grant or pixels are persisted. */
final class RealtimeImage implements AutoCloseable {
    private static final int MAX_INPUT = 8 * 1024 * 1024;
    // Leaves room for Base64 expansion below the controller's 500 KiB data URI limit.
    private static final int MAX_JPEG = 350 * 1024;
    final Bitmap preview;
    final String dataUri;

    static final class Failure extends IOException {
        Failure(String message) { super(message); }
    }

    private RealtimeImage(Bitmap preview, String dataUri) {
        this.preview = preview;
        this.dataUri = dataUri;
    }

    /** A validated, in-memory assistant capture; never opens a URI or changes caller byte ownership. */
    static RealtimeImage fromJpeg(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 4 || bytes.length > (3 * 1024 * 1024)
                || !"image/jpeg".equals(format(bytes)))
            throw new Failure("The captured look is unavailable. Return to TikTok and ask Taku again.");
        if (Thread.currentThread().isInterrupted()) throw new Failure("Look preparation was cancelled.");
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (!"image/jpeg".equals(bounds.outMimeType) || bounds.outWidth < 1 || bounds.outHeight < 1
                || Math.max(bounds.outWidth, bounds.outHeight) > 1920)
            throw new Failure("The captured look is invalid. Return to TikTok and ask Taku again.");
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new Failure("Could not read the captured look. Please ask Taku again.");
            Matrix transform = orientation(bytes);
            if (!transform.isIdentity()) {
                Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
                if (oriented != bitmap) { bitmap.recycle(); bitmap = oriented; }
            }
            return fromBitmap(bitmap);
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            if (failure instanceof IOException) throw (IOException) failure;
            throw new Failure("Could not prepare the captured look. Return to TikTok and ask Taku again.");
        } finally { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
    }

    static RealtimeImage read(Context context, Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme()))
            throw new Failure("请选择系统文件选择器中的图片");
        try(InputStream input=context.getContentResolver().openInputStream(uri)) {return read(input);}
    }
    static RealtimeImage read(InputStream input)throws IOException {
        byte[] bytes;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new Failure("reference_unavailable");
            byte[] buffer = new byte[16384];
            int count, total = 0;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new Failure("reference_cancelled");
                total += count;
                if (total > MAX_INPUT) throw new Failure("reference_too_large");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        return decode(bytes);
    }

    static RealtimeImage decode(byte[] bytes) throws IOException {
        if(bytes==null||bytes.length>MAX_INPUT)throw new Failure("invalid_reference");
        String actual = format(bytes);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (actual == null || !actual.equals(bounds.outMimeType) || bounds.outWidth < 1
                || bounds.outHeight < 1 || (long) bounds.outWidth * bounds.outHeight > 32000000L)
            throw new Failure("请选择有效的 JPG、PNG 或 WebP 图片（最多 3200 万像素）");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024)
            options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new Failure("无法解码这张图片，请重新选择");
        try {
            Matrix transform = orientation(bytes);
            if (!transform.isIdentity()) {
                Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
                if (oriented != bitmap) { bitmap.recycle(); bitmap = oriented; }
            }
            return fromBitmap(bitmap);
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            if (failure instanceof IOException) throw (IOException) failure;
            throw new Failure("无法准备这张图片，请换一张较小的图片");
        } finally { bitmap.recycle(); }
    }

    /** Copies an already oriented/cropped image. The caller retains ownership of the input. */
    static RealtimeImage fromBitmap(Bitmap input) throws IOException {
        if (input == null || input.isRecycled()) throw new Failure("图片预览已失效，请重新分享");
        Bitmap bitmap = null;
        try {
            if (Thread.currentThread().isInterrupted()) throw new Failure("图片准备已取消");
            int[] size = outputDimensions(input.getWidth(), input.getHeight());
            // JPEG has no alpha; flatten transparent garment references onto white.
            bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(input, null, new Rect(0, 0, size[0], size[1]), new Paint(Paint.FILTER_BITMAP_FLAG));
            byte[] jpeg;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new Failure("图片准备已取消");
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
                        throw new Failure("无法准备这张图片，请重新选择");
                    jpeg = output.toByteArray();
                }
                if (jpeg.length <= MAX_JPEG) break;
                int width = Math.max(1, bitmap.getWidth() * 3 / 4);
                int height = Math.max(1, bitmap.getHeight() * 3 / 4);
                if (width < 64 || height < 64) throw new Failure("这张图片压缩后仍然过大，请换一张");
                Bitmap smaller = Bitmap.createScaledBitmap(bitmap, width, height, true);
                if (smaller != bitmap) { bitmap.recycle(); bitmap = smaller; }
            }
            return new RealtimeImage(bitmap, "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP));
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            if (bitmap != null) bitmap.recycle();
            if (failure instanceof IOException) throw (IOException) failure;
            throw new Failure("无法准备这张图片，请换一张较小的图片");
        }
    }

    static int[] outputDimensions(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("invalid_image_dimensions");
        double scale = Math.min(1d, 1024d / Math.max(width, height));
        return new int[]{Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }

    /** Shared Lucy cloud reference; preserves the complete preview and the XMax/legacy data URI. */
    String lucyCharacterDataUri() throws IOException {
        if (preview == null || preview.isRecycled())
            throw new Failure("The character reference is no longer available. Please choose it again.");
        Bitmap normalized = null;
        try {
            if (Thread.currentThread().isInterrupted())
                throw new Failure("Character reference preparation was cancelled.");
            int[] size;
            try { size = LucyCharacterReference.dimensions(preview.getWidth(), preview.getHeight()); }
            catch (IllegalArgumentException unsupported) {
                throw new Failure("This character reference is too wide or tall for Lucy. Use an image with an aspect ratio no greater than 3.75:1.");
            }
            normalized = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(normalized);
            canvas.drawColor(Color.WHITE);
            int[] content = LucyCharacterReference.contentBounds(preview.getWidth(), preview.getHeight(), size[0], size[1]);
            canvas.drawBitmap(preview, null, new Rect(content[0], content[1], content[2], content[3]),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            for (int quality : LucyCharacterReference.jpegQualities()) {
                if (Thread.currentThread().isInterrupted())
                    throw new Failure("Character reference preparation was cancelled.");
                try (LucyCharacterReference.JpegBuffer output = new LucyCharacterReference.JpegBuffer()) {
                    if (!normalized.compress(Bitmap.CompressFormat.JPEG, quality, output))
                        throw new Failure("Could not encode this character reference for Lucy. Please choose another image.");
                    if (Thread.currentThread().isInterrupted())
                        throw new Failure("Character reference preparation was cancelled.");
                    if (output.exceeded()) continue;
                    if (!LucyCharacterReference.validJpegSize(output.size()))
                        throw new Failure("Could not encode this character reference for Lucy. Please choose another image.");
                    return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
                }
            }
            throw new Failure("This character reference is too complex to send to Lucy. Please choose a simpler image.");
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            if (failure instanceof IOException) throw (IOException) failure;
            throw new Failure("Could not prepare this character reference for Lucy. Please choose another image.");
        } finally {
            if (normalized != null && normalized != preview && !normalized.isRecycled()) normalized.recycle();
        }
    }

    private static String format(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255)
            return "image/jpeg";
        if (bytes.length >= 8 && (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10)
            return "image/png";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P')
            return "image/webp";
        return null;
    }

    private static Matrix orientation(byte[] bytes) {
        Matrix transform = new Matrix();
        try {
            int value = new ExifInterface(new ByteArrayInputStream(bytes)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (value) {
                case 2: transform.setScale(-1, 1); break;
                case 3: transform.setRotate(180); break;
                case 4: transform.setRotate(180); transform.postScale(-1, 1); break;
                case 5: transform.setRotate(90); transform.postScale(-1, 1); break;
                case 6: transform.setRotate(90); break;
                case 7: transform.setRotate(-90); transform.postScale(-1, 1); break;
                case 8: transform.setRotate(-90); break;
                default: break;
            }
        } catch (IOException | RuntimeException ignored) { /* Valid images need not contain EXIF. */ }
        return transform;
    }

    @Override public void close() { if (!preview.isRecycled()) preview.recycle(); }
}
