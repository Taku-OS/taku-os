// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** Pure geometry for a TextureView, whose camera buffer already includes sensor orientation. */
final class TakuPreviewGeometry {
    private TakuPreviewGeometry() { }

    static float[] contain(int viewWidth, int viewHeight, int bufferWidth, int bufferHeight,
            int sensorOrientation, int displayDegrees, boolean mirrorHorizontally) {
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0 ||
                sensorOrientation < 0 || sensorOrientation >= 360 || sensorOrientation % 90 != 0 ||
                displayDegrees < 0 || displayDegrees >= 360 || displayDegrees % 90 != 0)
            throw new IllegalArgumentException("Invalid preview geometry");
        // TextureView has already applied the sensor orientation. Undo only its nonuniform stretch,
        // then compensate display rotation. Applying sensor rotation again turns an upright view sideways.
        float sourceWidth = sensorOrientation % 180 == 0 ? bufferWidth : bufferHeight;
        float sourceHeight = sensorOrientation % 180 == 0 ? bufferHeight : bufferWidth;
        int relative = (sensorOrientation - displayDegrees + 360) % 360;
        float orientedWidth = relative % 180 == 0 ? bufferWidth : bufferHeight;
        float orientedHeight = relative % 180 == 0 ? bufferHeight : bufferWidth;
        float scale = Math.min(viewWidth / orientedWidth, viewHeight / orientedHeight);
        float cosine = displayDegrees == 0 ? 1 : displayDegrees == 180 ? -1 : 0;
        float sine = displayDegrees == 90 ? -1 : displayDegrees == 270 ? 1 : 0;
        float mirror = mirrorHorizontally ? -1 : 1;
        float xx = mirror * scale * cosine * sourceWidth / viewWidth;
        float xy = -mirror * scale * sine * sourceHeight / viewHeight;
        float yx = scale * sine * sourceWidth / viewWidth;
        float yy = scale * cosine * sourceHeight / viewHeight;
        float centerX = viewWidth / 2f, centerY = viewHeight / 2f;
        return new float[]{xx, xy, centerX - xx * centerX - xy * centerY,
                yx, yy, centerY - yx * centerX - yy * centerY, 0, 0, 1};
    }
}
