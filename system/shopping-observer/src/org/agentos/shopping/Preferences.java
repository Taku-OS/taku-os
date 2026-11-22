// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.Set;

/** One validated settings path for the settings screen and the authenticated Agent API. */
final class Preferences {
    static final String ENABLED = "enabled", CLASSIFICATION = "classification", VISION = "vision";
    private static final Set<String> KEYS = Set.of(ENABLED, CLASSIFICATION, VISION);
    static SharedPreferences get(Context c) { return c.getSharedPreferences("shopping", Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return get(c).getBoolean(ENABLED, false); }
    static boolean vision(Context c) { return get(c).getBoolean(VISION, false); }
    static boolean classification(Context c) { return get(c).getBoolean(CLASSIFICATION, false); }
    static Bundle read(Context c) {
        Bundle result = new Bundle();
        result.putBoolean(ENABLED, enabled(c)); result.putBoolean(CLASSIFICATION, classification(c));
        result.putBoolean(VISION, vision(c)); return result;
    }
    static boolean configure(Context c, Bundle changes) {
        if (changes == null || changes.isEmpty()) throw new IllegalArgumentException("missing_settings");
        for (String key : changes.keySet())
            if (!KEYS.contains(key) || !(changes.get(key) instanceof Boolean))
                throw new IllegalArgumentException("invalid_setting");
        SharedPreferences.Editor edit = get(c).edit();
        for (String key : changes.keySet()) edit.putBoolean(key, changes.getBoolean(key));
        return edit.commit();
    }
    static void set(Context c, String key, boolean value) {
        Bundle changes = new Bundle(); changes.putBoolean(key, value);
        if (!configure(c, changes)) throw new IllegalStateException("settings_write_failed");
    }
    private Preferences() {}
}
