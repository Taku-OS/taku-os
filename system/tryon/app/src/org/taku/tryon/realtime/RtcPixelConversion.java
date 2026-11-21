// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.nio.ByteBuffer;

/** CPU BT.601 limited-range conversion; explicit stride and odd-dimension handling. */
final class RtcPixelConversion {
    private RtcPixelConversion() {}
    private static int clip(int value) { return Math.max(0, Math.min(255, value)); }
    static void argbToI420(int[] pixels, int width, int height, ByteBuffer yPlane, int yStride,
            ByteBuffer uPlane, int uStride, ByteBuffer vPlane, int vStride) {
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int p = pixels[y * width + x], r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
            yPlane.put(y * yStride + x, (byte) clip(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
        }
        for (int y = 0; y < height; y += 2) for (int x = 0; x < width; x += 2) {
            int r = 0, g = 0, b = 0, count = 0;
            for (int j = y; j < Math.min(y + 2, height); j++) for (int i = x; i < Math.min(x + 2, width); i++) {
                int p = pixels[j * width + i]; r += p >> 16 & 255; g += p >> 8 & 255; b += p & 255; count++;
            }
            r /= count; g /= count; b /= count;
            uPlane.put((y / 2) * uStride + x / 2, (byte) clip(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
            vPlane.put((y / 2) * vStride + x / 2, (byte) clip(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
        }
    }
    static int[] i420ToArgb(int width, int height, ByteBuffer yPlane, int yStride,
            ByteBuffer uPlane, int uStride, ByteBuffer vPlane, int vStride) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int luma = Math.max(0, (yPlane.get(y * yStride + x) & 255) - 16);
            int u = (uPlane.get((y / 2) * uStride + x / 2) & 255) - 128;
            int v = (vPlane.get((y / 2) * vStride + x / 2) & 255) - 128;
            int r = clip((298 * luma + 409 * v + 128) >> 8);
            int g = clip((298 * luma - 100 * u - 208 * v + 128) >> 8);
            int b = clip((298 * luma + 516 * u + 128) >> 8);
            pixels[y * width + x] = 0xff000000 | r << 16 | g << 8 | b;
        }
        return pixels;
    }
}
