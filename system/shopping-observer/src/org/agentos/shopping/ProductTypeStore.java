// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import org.json.JSONObject;

/** Private durable cache/paid-call claim. No titles, host names, model output or credentials are stored. */
final class ProductTypeStore {
    private static final Object LOCK = new Object();

    static ProductTypeAnswer.Result cached(Context context, LivePage page) {
        synchronized (LOCK) {
            try { return read(store(context)).cached(key(page), System.currentTimeMillis()); }
            catch (Exception unavailable) { return null; }
        }
    }

    static long waitMillis(Context context, LivePage page) {
        synchronized (LOCK) {
            try { return read(store(context)).waitMillis(key(page), System.currentTimeMillis()); }
            catch (Exception unavailable) { return ProductTypeCachePolicy.ITEM_INTERVAL_MS; }
        }
    }

    static boolean claim(Context context, LivePage page) {
        synchronized (LOCK) {
            try {
                SharedPreferences preferences = store(context);
                ProductTypeCachePolicy state = read(preferences);
                return state.claim(key(page), System.currentTimeMillis()) && commit(preferences, state);
            } catch (Exception unavailable) { return false; }
        }
    }

    static void save(Context context, LivePage page, ProductTypeAnswer.Result result) {
        synchronized (LOCK) {
            try {
                SharedPreferences preferences = store(context);
                ProductTypeCachePolicy state = read(preferences);
                if (state.save(key(page), result, System.currentTimeMillis())) commit(preferences, state);
            } catch (Exception unavailable) { /* No cache is preferable to an unsupported or unsafe entry. */ }
        }
    }

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences("product-type-history", Context.MODE_PRIVATE);
    }
    private static String key(LivePage page) { return ProductTypeCachePolicy.key(page.contentKey()); }

    private static ProductTypeCachePolicy read(SharedPreferences preferences) throws Exception {
        JSONObject rawCache = new JSONObject(preferences.getString("cache", "{}"));
        JSONObject rawAttempts = new JSONObject(preferences.getString("attempts", "{}"));
        var cache = new LinkedHashMap<String, ProductTypeCachePolicy.Entry>();
        var attempts = new LinkedHashMap<String, Long>();
        for (var keys = rawCache.keys(); keys.hasNext();) {
            String key = keys.next(); JSONObject entry = rawCache.getJSONObject(key);
            cache.put(key, new ProductTypeCachePolicy.Entry(
                ProductTypeAnswer.Result.valueOf(entry.getString("label")), readTime(entry.get("at"))));
        }
        for (var keys = rawAttempts.keys(); keys.hasNext();) {
            String key = keys.next(); attempts.put(key, readTime(rawAttempts.get(key)));
        }
        return new ProductTypeCachePolicy(cache, attempts,
            preferences.getLong("last-attempt", -1), preferences.getLong("last-write", -1));
    }

    private static long readTime(Object value) {
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() < 0)
            throw new IllegalStateException("Invalid stored timestamp");
        return ((Number) value).longValue();
    }

    private static boolean commit(SharedPreferences preferences, ProductTypeCachePolicy state) throws Exception {
        JSONObject cache = new JSONObject(), attempts = new JSONObject();
        for (var entry : state.cachedEntries().entrySet()) cache.put(entry.getKey(), new JSONObject()
            .put("label", entry.getValue().label().name()).put("at", entry.getValue().at()));
        for (var entry : state.attemptEntries().entrySet()) attempts.put(entry.getKey(), entry.getValue());
        return preferences.edit().putString("cache", cache.toString()).putString("attempts", attempts.toString())
            .putLong("last-attempt", state.lastAttempt()).putLong("last-write", state.lastWrite()).commit();
    }

    private ProductTypeStore() {}
}
