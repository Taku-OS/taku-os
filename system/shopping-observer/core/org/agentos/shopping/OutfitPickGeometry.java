// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

/** Fit-center coordinates for a frozen reference. Letterbox and border taps never select clothing. */
public final class OutfitPickGeometry {
    public record Fit(double left, double top, double right, double bottom) {
        public Fit {
            if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(right)
                    || !Double.isFinite(bottom) || left < 0 || top < 0 || right <= left || bottom <= top)
                throw new IllegalArgumentException("Invalid outfit preview bounds");
        }
        public double width() { return right - left; }
        public double height() { return bottom - top; }
    }

    public record Point(int x, int y) {
        public Point {
            if (x < 0 || x > 1000 || y < 0 || y > 1000)
                throw new IllegalArgumentException("Invalid outfit point");
        }
    }

    private OutfitPickGeometry() {}

    public static Fit fit(int imageWidth, int imageHeight, int viewWidth, int viewHeight, int inset) {
        if (imageWidth <= 0 || imageHeight <= 0 || imageWidth > 16384 || imageHeight > 16384
                || viewWidth <= 0 || viewHeight <= 0 || inset < 0
                || (long)inset * 2 >= viewWidth || (long)inset * 2 >= viewHeight) return null;
        double availableWidth = viewWidth - (long)inset * 2;
        double availableHeight = viewHeight - (long)inset * 2;
        double scale = Math.min(availableWidth / imageWidth, availableHeight / imageHeight);
        double width = Math.min(availableWidth,imageWidth * scale);
        double height = Math.min(availableHeight,imageHeight * scale);
        double left = (viewWidth - width) / 2, top = (viewHeight - height) / 2;
        return new Fit(left, top, left + width, top + height);
    }

    /** Returns coordinates relative to the input bitmap, never the surrounding window. */
    public static Point pick(Fit fit, double x, double y) {
        if (fit == null || !Double.isFinite(x) || !Double.isFinite(y)
                || x <= fit.left || x >= fit.right || y <= fit.top || y >= fit.bottom) return null;
        int normalizedX = (int)Math.round((x - fit.left) * 1000 / fit.width());
        int normalizedY = (int)Math.round((y - fit.top) * 1000 / fit.height());
        // Rounding must not turn a near-border tap into an ambiguous outside-image point.
        if (normalizedX <= 0 || normalizedX >= 1000 || normalizedY <= 0 || normalizedY >= 1000) return null;
        return new Point(normalizedX, normalizedY);
    }
}
