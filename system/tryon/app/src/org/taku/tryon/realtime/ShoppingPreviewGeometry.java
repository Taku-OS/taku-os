// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** View-only size bounds. No camera stream dimensions or session clocks are changed. */
final class ShoppingPreviewGeometry {
    record Size(int width, int height) {}
    private ShoppingPreviewGeometry() {}

    static Size size(int screenWidth, int screenHeight, int requestedWidth, int minimumWidth,
                     int horizontalReserve, int verticalReserve, int controlsHeight) {
        int maxHeight = Math.max(1, screenHeight - Math.max(0, verticalReserve));
        int maxWidth = Math.max(1, screenWidth - Math.max(0, horizontalReserve));
        int videoBudget = Math.max(1, maxHeight - Math.max(0, controlsHeight));
        maxWidth = Math.min(maxWidth, Math.max(1, videoBudget * 3 / 4));
        int minWidth = Math.min(maxWidth, Math.max(1, minimumWidth));
        int width = Math.max(minWidth, Math.min(maxWidth, requestedWidth));
        int height = Math.min(maxHeight, width * 4 / 3 + Math.max(0, controlsHeight));
        return new Size(width, Math.max(1, height));
    }

    /** Horizontal and vertical drags both resize in the user's chosen direction. */
    static int resizedWidth(int startWidth, float deltaX, float deltaY) {
        float verticalWidth = deltaY * .75f;
        float delta = Math.abs(deltaX) >= Math.abs(verticalWidth) ? deltaX : verticalWidth;
        return Math.max(1, Math.round(startWidth + delta));
    }
}
