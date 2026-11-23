// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.io.ByteArrayOutputStream;

/** Cloud-reference geometry shared by Lucy shopping and character routes; never changes XMax/preview pixels. */
final class LucyCharacterReference {
    static final int SHORT_EDGE = 512;
    static final int MAX_LONG_EDGE = 1920;
    private static final int ALIGNMENT = 64;
    static final int MAX_JPEG_BYTES = 320 * 1024;
    private static final int[] JPEG_QUALITIES = {85, 75, 65, 55, 45, 35, 25, 15};

    private LucyCharacterReference() { }

    /** Aligned canvas only; contentBounds fits the complete source without changing its aspect ratio. */
    static int[] dimensions(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("invalid_image_dimensions");
        int shorter = Math.min(width, height), longer = Math.max(width, height);
        long scaledLonger = Math.round((double) longer * SHORT_EDGE / shorter);
        // Judge the actual rounded output. A legacy 512x1920 image becomes 273x1024
        // in preview, whose reconstructed long edge is 1920.47 and correctly rounds to 1920.
        if (scaledLonger > MAX_LONG_EDGE)
            throw new IllegalArgumentException("unsupported_character_reference_aspect_ratio");
        // Real Lucy sessions reject e.g. 512x578 at startup while 512x576 succeeds.
        // Use a bounded aligned canvas rather than passing arbitrary crop dimensions.
        int alignedLonger = (int) ((scaledLonger + ALIGNMENT / 2) / ALIGNMENT) * ALIGNMENT;
        return width <= height ? new int[]{SHORT_EDGE, alignedLonger}
                : new int[]{alignedLonger, SHORT_EDGE};
    }

    /** Full-source destination rectangle, centered with white padding instead of crop or stretch. */
    static int[] contentBounds(int width, int height, int canvasWidth, int canvasHeight) {
        if (width < 1 || height < 1 || canvasWidth < 1 || canvasHeight < 1)
            throw new IllegalArgumentException("invalid_image_dimensions");
        double scale = Math.min((double) canvasWidth / width, (double) canvasHeight / height);
        int contentWidth = Math.min(canvasWidth, Math.max(1, (int) Math.round(width * scale)));
        int contentHeight = Math.min(canvasHeight, Math.max(1, (int) Math.round(height * scale)));
        int left = (canvasWidth - contentWidth) / 2, top = (canvasHeight - contentHeight) / 2;
        return new int[]{left, top, left + contentWidth, top + contentHeight};
    }

    static int[] jpegQualities() { return JPEG_QUALITIES.clone(); }

    static boolean validJpegSize(long bytes) { return bytes > 0 && bytes <= MAX_JPEG_BYTES; }

    /** Never retains more than the allowed JPEG payload; an overflow must never be sent. */
    static final class JpegBuffer extends ByteArrayOutputStream {
        private boolean exceeded;
        JpegBuffer() { super(MAX_JPEG_BYTES); }
        @Override public synchronized void write(int value) {
            if (count == MAX_JPEG_BYTES) { exceeded = true; return; }
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || offset > bytes.length - length)
                throw new IndexOutOfBoundsException();
            int allowed = Math.min(length, MAX_JPEG_BYTES - count);
            super.write(bytes, offset, allowed);
            if (allowed != length) exceeded = true;
        }
        boolean exceeded() { return exceeded; }
    }
}
