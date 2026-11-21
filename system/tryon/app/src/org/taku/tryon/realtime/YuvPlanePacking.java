// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.nio.ByteBuffer;

/** Bounded, stride-aware YUV_420_888 packing and geometry, independent of Android. */
final class YuvPlanePacking {
    private YuvPlanePacking() { }

    static final class Plane {
        final ByteBuffer buffer;
        final int rowStride, pixelStride, start;
        Plane(ByteBuffer buffer, int rowStride, int pixelStride) {
            if (buffer == null || rowStride < 1 || pixelStride < 1) throw new IllegalArgumentException("plane");
            this.buffer = buffer.duplicate(); this.start = buffer.position();
            this.rowStride = rowStride; this.pixelStride = pixelStride;
        }
        int at(int x, int y) {
            long index = (long) start + (long) y * rowStride + (long) x * pixelStride;
            if (x < 0 || y < 0 || index < start || index >= buffer.limit()) throw new IllegalArgumentException("short_plane");
            return buffer.get((int) index) & 255;
        }
    }

    static final class Packed {
        final int width, height;
        final byte[] nv21;
        Packed(int width, int height, byte[] nv21) {
            if (width < 2 || height < 2 || (width & 1) != 0 || (height & 1) != 0 ||
                    (long) width * height * 3 / 2 != nv21.length) throw new IllegalArgumentException("nv21");
            this.width = width; this.height = height; this.nv21 = nv21;
        }
    }

    /** Crop coordinates refer to the full image. An odd trailing row/column is removed for NV21. */
    static Packed pack(int imageWidth, int imageHeight, int left, int top, int right, int bottom,
            Plane y, Plane u, Plane v) {
        if (imageWidth < 2 || imageHeight < 2 || imageWidth > 1920 || imageHeight > 1920 ||
                left < 0 || top < 0 || right > imageWidth || bottom > imageHeight ||
                right <= left || bottom <= top || y == null || u == null || v == null)
            throw new IllegalArgumentException("crop");
        int width = (right - left) & ~1, height = (bottom - top) & ~1;
        if (width < 2 || height < 2) throw new IllegalArgumentException("small_crop");
        byte[] data = new byte[width * height * 3 / 2];
        int offset = 0;
        for (int row = 0; row < height; row++)
            for (int column = 0; column < width; column++) data[offset++] = (byte) y.at(left + column, top + row);
        for (int row = 0; row < height / 2; row++) {
            for (int column = 0; column < width / 2; column++) {
                int x = (left + column * 2) / 2, yy = (top + row * 2) / 2;
                data[offset++] = (byte) v.at(x, yy);
                data[offset++] = (byte) u.at(x, yy);
            }
        }
        return new Packed(width, height, data);
    }

    /** Rotate clockwise, then mirror horizontally in the upright coordinate system. */
    static Packed orient(Packed source, int clockwiseDegrees, boolean mirrorHorizontally) {
        if (source == null || clockwiseDegrees < 0 || clockwiseDegrees >= 360 || clockwiseDegrees % 90 != 0)
            throw new IllegalArgumentException("orientation");
        boolean turn = clockwiseDegrees % 180 != 0;
        int width = turn ? source.height : source.width, height = turn ? source.width : source.height;
        byte[] output = new byte[source.nv21.length];
        transform(source.nv21, 0, source.width, source.height, output, 0, width, height,
                1, clockwiseDegrees, mirrorHorizontally);
        transform(source.nv21, source.width * source.height, source.width / 2, source.height / 2,
                output, width * height, width / 2, height / 2, 2, clockwiseDegrees, mirrorHorizontally);
        return new Packed(width, height, output);
    }

    private static void transform(byte[] source, int sourceStart, int sourceWidth, int sourceHeight,
            byte[] output, int outputStart, int outputWidth, int outputHeight,
            int bytesPerPixel, int degrees, boolean mirror) {
        int offset = outputStart;
        for (int row = 0; row < outputHeight; row++) {
            for (int column = 0; column < outputWidth; column++) {
                int xx = mirror ? outputWidth - 1 - column : column;
                int x, y;
                if (degrees == 90) { x = row; y = sourceHeight - 1 - xx; }
                else if (degrees == 180) { x = sourceWidth - 1 - xx; y = sourceHeight - 1 - row; }
                else if (degrees == 270) { x = sourceWidth - 1 - row; y = xx; }
                else { x = xx; y = row; }
                int sourceOffset = sourceStart + (y * sourceWidth + x) * bytesPerPixel;
                for (int i = 0; i < bytesPerPixel; i++) output[offset++] = source[sourceOffset + i];
            }
        }
    }
}
