/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/** Visible camera geometry, distinct from DisplayCutout's padded layout exclusion rectangle. */
internal object IslandCameraGeometry {
    /** Camera centre in the host view's own pixels; [radius] is 0 without a centred punch hole. */
    class Camera(val x: Int, val y: Float, val radius: Float)

    /**
     * Where the island's camera circle sits inside [view]: the centred punch hole when the display
     * has one, else the middle of the view. DisplayCutout's top rectangle is a layout exclusion
     * (Pixel 8's 122 px box surrounds a 72.5 px hole), so the radius comes from the cutout path;
     * missing paths and notches report no radius and the capsule draws its design camera.
     */
    fun locate(view: View, maxDiameter: Float): Camera {
        val displayCutout = view.rootWindowInsets?.displayCutout
        val cutout = displayCutout?.boundingRectTop ?: Rect()
        val physical = !cutout.isEmpty && cutout.centerX() in view.width / 3..view.width * 2 / 3
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val camera = if (physical) bounds(displayCutout?.cutoutPath, cutout, maxDiameter) else null
        val x = if (camera != null) Math.round(camera.centerX()) - location[0]
            else if (physical) cutout.centerX() - location[0] else view.width / 2
        return Camera(x, camera?.let { it.centerY() - location[1] } ?: (view.height / 2f),
            camera?.let { maxOf(it.width(), it.height()) / 2f } ?: 0f)
    }

    fun bounds(cutoutPath: Path?, topExclusion: Rect, maxDiameter: Float): RectF? {
        if (cutoutPath == null || cutoutPath.isEmpty || topExclusion.isEmpty) return null
        // A display path can contain bottom/side cutouts as well. Only inspect the top one.
        val top = Path().apply { addRect(RectF(topExclusion), Path.Direction.CW) }
        val camera = Path(cutoutPath)
        if (!camera.op(top, Path.Op.INTERSECT)) return null
        val bounds = RectF()
        camera.computeBounds(bounds, true)
        if (bounds.isEmpty || bounds.width() > maxDiameter || bounds.height() > maxDiameter ||
            kotlin.math.abs(bounds.width() - bounds.height()) > 1f) return null
        // Edge-connected notches are not punch holes, even if their bounding box is square.
        if (bounds.top <= 0f) return null
        return bounds
    }
}
