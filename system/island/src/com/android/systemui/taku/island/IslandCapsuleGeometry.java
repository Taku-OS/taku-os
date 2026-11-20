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
