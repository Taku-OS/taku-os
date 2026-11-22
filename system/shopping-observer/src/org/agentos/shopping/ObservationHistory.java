// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;

/** Bounded hashes and times only, so reconnecting the service does not reset cost/notification limits. */
final class ObservationHistory {
    private static final long ITEM_TTL = 30 * 60 * 1000L, INTERVAL = 60000;
    private static SharedPreferences store(Context c) { return c.getSharedPreferences("observation-history", Context.MODE_PRIVATE); }
    static synchronized boolean suppressed(Context c, LivePage page) {
        long now = System.currentTimeMillis(); SharedPreferences p = store(c);
        // Old per-item reminder history deliberately has no effect after an upgrade.
        return recent(p.getLong("published", 0), now, INTERVAL);
    }
    static synchronized void published(Context c, LivePage page) {
        store(c).edit().putLong("published", System.currentTimeMillis()).apply();
    }
    /** Commit before the paid call; any storage failure or prior attempt fails closed. */
    static synchronized boolean claimVision(Context c, LivePage page) {
        long now = System.currentTimeMillis(); SharedPreferences p = store(c); JSONObject rooms = read(p, "vision-rooms");
        if (recent(p.getLong("vision", 0), now, INTERVAL) || recent(rooms.optLong(page.hostKey()), now, ITEM_TTL)) return false;
        put(rooms, page.hostKey(), now);
        return p.edit().putLong("vision", now).putString("vision-rooms", rooms.toString()).commit();
    }
    private static boolean recent(long at, long now, long ttl) { return at > 0 && (now < at || now - at < ttl); }
    private static JSONObject read(SharedPreferences p, String key) {
        try { return new JSONObject(p.getString(key, "{}")); } catch (Exception invalid) { return new JSONObject(); }
    }
    private static void put(JSONObject values, String key, long now) {
        try {
            java.util.List<String> remove = new java.util.ArrayList<>();
            for (java.util.Iterator<String> i = values.keys(); i.hasNext();) { String k = i.next(); if (!recent(values.optLong(k), now, ITEM_TTL)) remove.add(k); }
            for (String k : remove) values.remove(k);
            values.put(key, now);
            while (values.length() > 128) {
                String oldest = null; long time = Long.MAX_VALUE;
                for (java.util.Iterator<String> i = values.keys(); i.hasNext();) { String k = i.next(); if (values.optLong(k) < time) { oldest = k; time = values.optLong(k); } }
                if (oldest == null) break; values.remove(oldest);
            }
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private ObservationHistory() {}
}
