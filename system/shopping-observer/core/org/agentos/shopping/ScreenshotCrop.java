// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

/** Maps one verified screen-space image rectangle into a window screenshot. */
public final class ScreenshotCrop {
    public record Bounds(int left, int top, int right, int bottom) {
        public Bounds {
            if (left < 0 || top < 0 || right <= left || bottom <= top)
                throw new IllegalArgumentException("Invalid crop bounds");
        }
        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }

    private static final double SCALE_TOLERANCE = .01;
    private static final int MIN_IMAGE_PIXELS = 128;
    private ScreenshotCrop() {}

    /**
     * Returns null unless image is wholly within the captured window and both coordinate systems
     * agree on scale. The result is deliberately never clamped: a stale/mismatched rectangle
     * must fail rather than capture adjacent UI.
     */
    public static Bounds map(int windowLeft, int windowTop, int windowRight, int windowBottom,
                             int imageLeft, int imageTop, int imageRight, int imageBottom,
                             int shotWidth, int shotHeight) {
        long windowWidth = (long) windowRight - windowLeft;
        long windowHeight = (long) windowBottom - windowTop;
        if (windowWidth <= 0 || windowHeight <= 0 || shotWidth <= 0 || shotHeight <= 0
                || imageRight <= imageLeft || imageBottom <= imageTop
                || imageLeft < windowLeft || imageTop < windowTop
                || imageRight > windowRight || imageBottom > windowBottom) return null;
        double xScale = shotWidth / (double) windowWidth;
        double yScale = shotHeight / (double) windowHeight;
        if (!Double.isFinite(xScale) || !Double.isFinite(yScale)
                || Math.abs(xScale - yScale) / Math.max(xScale, yScale) > SCALE_TOLERANCE) return null;
        long left = floor((imageLeft - (long) windowLeft) * xScale);
        long top = floor((imageTop - (long) windowTop) * yScale);
        long right = ceil((imageRight - (long) windowLeft) * xScale);
        long bottom = ceil((imageBottom - (long) windowTop) * yScale);
        if (left < 0 || top < 0 || right > shotWidth || bottom > shotHeight
                || right <= left || bottom <= top
                || right - left < MIN_IMAGE_PIXELS || bottom - top < MIN_IMAGE_PIXELS) return null;
        return new Bounds((int) left, (int) top, (int) right, (int) bottom);
    }

    private static long floor(double value) {
        if (!Double.isFinite(value) || value < Long.MIN_VALUE || value > Long.MAX_VALUE) return Long.MIN_VALUE;
        return (long) Math.floor(value);
    }
    private static long ceil(double value) {
        if (!Double.isFinite(value) || value < Long.MIN_VALUE || value > Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) Math.ceil(value);
    }
}
