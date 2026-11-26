/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island;

/** Pixel geometry shared by the camera-anchored capsule and the centered native cutout slot. */
public final class IslandCapsuleGeometry {
    private IslandCapsuleGeometry() {}

    public static int maximumWidth(int contentLeft, int contentRight, int cameraX,
            int cameraLeftWidth, int clockWidth, int endWidth, int collapsibleIconsWidth, int gap) {
        if (contentRight <= contentLeft || cameraLeftWidth <= 0) return 0;
        // StatusIconContainer collapses into the space remaining after cutout allocation.
        // Charging its current width here makes the capsule compete with its own reservation.
        int fixedEnd = Math.max(0, endWidth - Math.max(0, collapsibleIconsWidth));
        int side = Math.max(Math.max(0, clockWidth), fixedEnd) + Math.max(0, gap);
        int center = contentLeft + (contentRight - contentLeft) / 2;
        int radius = Math.min(center - contentLeft, contentRight - center) - side;
        int left = cameraX - cameraLeftWidth;
        if (radius <= 0 || left < center - radius || cameraX > center + radius) return 0;
        return Math.max(0, center + radius - left);
    }

    /** Handle length of the authored MetaballShape1 curves as a fraction of the circle radius. */
    static final float HANDLE = 0.5776f;

    /**
     * Upper half of the metaball neck joining a circle of radius {@code r1} at x=0 to a circle of
     * radius {@code r2} centred {@code distance} to its right (both on y=0, y grows downward). The
     * neck starts and ends {@code inset} inside each circle and leaves tangent to it, with handles
     * of {@link #HANDLE}·r: for r1=16, r2=12, distance=36, inset=4 this reproduces Figma's
     * MetaballShape1 (16.07 × 21.33). Any camera size keeps the same smooth tangency instead of
     * the 24 dp camera's fixed outline. Returns {x0, y0, c1x, c1y, c2x, c2y, x1, y1}; mirror the
     * y values for the lower half.
     */
    public static float[] neck(float r1, float r2, float distance, float inset) {
        float cos1 = (r1 - inset) / r1, sin1 = (float) Math.sqrt(1 - cos1 * cos1);
        float cos2 = (r2 - inset) / r2, sin2 = (float) Math.sqrt(1 - cos2 * cos2);
        float x0 = r1 * cos1, y0 = -r1 * sin1;
        float x1 = distance - r2 * cos2, y1 = -r2 * sin2;
        float a = HANDLE * r1, b = HANDLE * r2;
        // A very large camera would pull its handle past the centre line and pinch the neck shut.
        float waist = -0.35f * Math.min(-y0, -y1);
        return new float[] {
            x0, y0,
            x0 + a * sin1, Math.min(y0 + a * cos1, waist),
            x1 - b * sin2, Math.min(y1 + b * cos2, waist),
            x1, y1,
        };
    }

    public static int reservationWidth(int contentLeft, int contentRight, int cameraX,
            int cameraLeftWidth, int capsuleWidth) {
        if (capsuleWidth <= 0) return 0;
        int center = contentLeft + (contentRight - contentLeft) / 2;
        int left = cameraX - cameraLeftWidth;
        // PhoneStatusBarView's cutout slot is centered between equally weighted sides.
        // The capsule grows to the camera's right, so reserving only its width undercounts it.
        return 2 * Math.max(center - left, left + capsuleWidth - center);
    }
}
