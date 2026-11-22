// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Wall-clock cache/paid-attempt policy. State contains only hashed identifiers, labels and times. */
public final class ProductTypeCachePolicy {
    public static final long CACHE_TTL_MS = 1800000, ITEM_INTERVAL_MS = 60000, GLOBAL_INTERVAL_MS = 10000;
    public static final int MAX_ENTRIES = 128;
    public record Entry(ProductTypeAnswer.Result label, long at) {}
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> attempts = new LinkedHashMap<>();
    private long lastAttempt, lastWrite;

    public ProductTypeCachePolicy(Map<String, Entry> cache, Map<String, Long> attempts,
                                  long lastAttempt, long lastWrite) {
        this.lastAttempt = Math.max(-1, lastAttempt);
        this.lastWrite = Math.max(this.lastAttempt, lastWrite);
        for (var item : cache.entrySet()) {
            Entry entry = item.getValue();
            if (validKey(item.getKey()) && entry != null && supported(entry.label()) && entry.at() >= 0) {
                this.cache.put(item.getKey(), entry);
                this.lastWrite = Math.max(this.lastWrite, entry.at());
            }
        }
        for (var item : attempts.entrySet()) {
            if (validKey(item.getKey()) && item.getValue() != null && item.getValue() >= 0) {
                this.attempts.put(item.getKey(), item.getValue());
                this.lastAttempt = Math.max(this.lastAttempt, item.getValue());
                this.lastWrite = Math.max(this.lastWrite, item.getValue());
            }
        }
        boundCache(); boundAttempts();
    }

    public ProductTypeAnswer.Result cached(String key, long now) {
        if (!validKey(key) || now < 0 || now < lastWrite) return null;
        Entry entry = cache.get(key);
        return entry != null && now >= entry.at() && now - entry.at() < CACHE_TTL_MS ? entry.label() : null;
    }

    /** Cached decisions bypass paid-call cooldown; clock rollback never authorizes a new call. */
    public long waitMillis(String key, long now) {
        if (!validKey(key) || now < 0) return ITEM_INTERVAL_MS;
        if (cached(key, now) != null) return 0;
        if (now < lastWrite) return ITEM_INTERVAL_MS;
        return Math.max(remaining(lastAttempt, now, GLOBAL_INTERVAL_MS),
            remaining(attempts.getOrDefault(key, -1L), now, ITEM_INTERVAL_MS));
    }

    /** Mutates this snapshot only; the adapter must commit it successfully before calling the model. */
    public boolean claim(String key, long now) {
        if (cached(key, now) != null || waitMillis(key, now) != 0) return false;
        prune(now);
        attempts.remove(key); attempts.put(key, now);
        lastAttempt = now; lastWrite = now;
        boundAttempts();
        return true;
    }

    public boolean save(String key, ProductTypeAnswer.Result label, long now) {
        if (!validKey(key) || !supported(label) || now < 0 || now < lastWrite) return false;
        prune(now);
        cache.remove(key); cache.put(key, new Entry(label, now));
        lastWrite = now;
        boundCache();
        return true;
    }

    public Map<String, Entry> cachedEntries() { return Map.copyOf(cache); }
    public Map<String, Long> attemptEntries() { return Map.copyOf(attempts); }
    public long lastAttempt() { return lastAttempt; }
    public long lastWrite() { return lastWrite; }

    public static String key(String contentKey) {
        if (contentKey == null || contentKey.isBlank()) throw new IllegalArgumentException("Missing content key");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(contentKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : hash) {
                out.append("0123456789abcdef".charAt((value & 255) >>> 4));
                out.append("0123456789abcdef".charAt(value & 15));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean validKey(String key) { return key != null && key.matches("[0-9a-f]{64}"); }
    private static boolean supported(ProductTypeAnswer.Result label) {
        return label == ProductTypeAnswer.Result.APPAREL || label == ProductTypeAnswer.Result.NON_APPAREL
            || label == ProductTypeAnswer.Result.UNKNOWN;
    }
    private static long remaining(long at, long now, long interval) {
        if (at < 0) return 0;
        if (now < at) return ITEM_INTERVAL_MS;
        return Math.max(0, interval - (now - at));
    }
    private void prune(long now) {
        cache.entrySet().removeIf(e -> now >= e.getValue().at() && now - e.getValue().at() >= CACHE_TTL_MS);
        attempts.entrySet().removeIf(e -> now >= e.getValue() && now - e.getValue() >= ITEM_INTERVAL_MS);
    }
    private void boundCache() {
        while (cache.size() > MAX_ENTRIES) {
            String oldest = null; long at = Long.MAX_VALUE;
            for (var item : cache.entrySet()) if (oldest == null || item.getValue().at() < at) { oldest = item.getKey(); at = item.getValue().at(); }
            cache.remove(oldest);
        }
    }
    private void boundAttempts() {
        while (attempts.size() > MAX_ENTRIES) {
            String oldest = null; long at = Long.MAX_VALUE;
            for (var item : attempts.entrySet()) if (oldest == null || item.getValue() < at) { oldest = item.getKey(); at = item.getValue(); }
            attempts.remove(oldest);
        }
    }
}
