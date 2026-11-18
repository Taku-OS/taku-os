package org.agentos.surfaces;

import android.content.Context;
import android.content.SharedPreferences;

/** Only the user's layout preference is durable; never stores content or caller identifiers. */
final class SurfacePositionPrefs {
    private final SharedPreferences preferences;
    SurfacePositionPrefs(Context context) { preferences=context.getSharedPreferences("surface-position-v1",Context.MODE_PRIVATE); }
    SurfacePosition.Anchor read() {
        if (!preferences.contains("center-x") || !preferences.contains("center-y")) return null;
        try {
            return new SurfacePosition.Anchor(preferences.getFloat("center-x",.5f),preferences.getFloat("center-y",.5f));
        } catch (ClassCastException | IllegalArgumentException invalid) {
            clear(); return null;
        }
    }
    void write(SurfacePosition.Anchor anchor) {
        preferences.edit().putFloat("center-x",anchor.x()).putFloat("center-y",anchor.y()).apply();
    }
    void clear() { preferences.edit().clear().apply(); }
}
