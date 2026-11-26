/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/** Visible camera geometry, distinct from DisplayCutout's padded layout exclusion rectangle. */
internal object IslandCameraGeometry {
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
