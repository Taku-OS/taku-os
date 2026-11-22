// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ProductTypeCachePolicyTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static String key(int n) { return ProductTypeCachePolicy.key("content-" + n); }
    private static ProductTypeCachePolicy empty() { return new ProductTypeCachePolicy(Map.of(), Map.of(), -1, -1); }

    public static void main(String[] args) {
        String a = key(1), b = key(2), c = key(3);
        var p = empty();
        check(p.cached(a, 0) == null && p.waitMillis(a, 0) == 0, "New item should be eligible");
        check(p.claim(a, 0), "Zero wall-clock timestamp is a real attempt");
        check(p.lastAttempt() == 0 && p.attemptEntries().get(a) == 0, "Claim was not recorded");
        check(!p.claim(a, 1) && !p.claim(b, 9999), "Repeated item/global claim was allowed");
        check(p.waitMillis(a, 9999) == 50001 && p.waitMillis(b, 9999) == 1, "Two interval remainders must use max");
        check(p.claim(b, 10000), "Global interval boundary must allow a different item");
        check(p.waitMillis(a, 10000) == 50000 && p.waitMillis(c, 10000) == 10000, "Item/global clocks interfered");
        check(p.waitMillis(a, 59999) == 1 && !p.claim(a, 59999), "Item boundary must be strict");
        check(p.claim(a, 60000), "Item interval never recovered");
        p = new ProductTypeCachePolicy(p.cachedEntries(), p.attemptEntries(), p.lastAttempt(), p.lastWrite());
        check(!p.claim(a, 60001) && p.waitMillis(a, 60001) == 59999, "Restored durable claim forgot paid attempt");

        p = empty(); p.claim(a, 100000);
        check(p.save(a, ProductTypeAnswer.Result.APPAREL, 100100), "Successful model output was not cached");
        long lastAttempt = p.lastAttempt(), lastWrite = p.lastWrite();
        check(p.cached(a, 100200) == ProductTypeAnswer.Result.APPAREL && p.waitMillis(a, 100200) == 0,
            "Paid-call cooldown blocked a cached decision");
        check(!p.claim(a, 100200) && p.lastAttempt() == lastAttempt && p.lastWrite() == lastWrite,
            "Cache hit made a paid-call claim or extended its timestamp");
        check(p.waitMillis(b, 100200) == 9800, "Saving cache must not replace global attempt time");
        check(p.cached(a, 1900099) == ProductTypeAnswer.Result.APPAREL && p.cached(a, 1900100) == null,
            "Cache TTL must be exactly thirty minutes");
        check(p.waitMillis(a, 1900100) == 0 && p.claim(a, 1900100), "Expired cache must allow a new paid attempt");
        for (var result : new ProductTypeAnswer.Result[]{ProductTypeAnswer.Result.APPAREL,
                ProductTypeAnswer.Result.NON_APPAREL, ProductTypeAnswer.Result.UNKNOWN}) {
            p = empty(); check(p.save(a, result, 10), "Supported label was rejected");
            check(p.cached(a, 11) == result, "Supported label did not survive cache lookup");
        }
        p = empty(); p.save(a, ProductTypeAnswer.Result.APPAREL, 100);
        for (var result : new ProductTypeAnswer.Result[]{null, ProductTypeAnswer.Result.UNAVAILABLE, ProductTypeAnswer.Result.TIMED_OUT}) {
            check(!p.save(a, result, 200) && p.cached(a, 200) == ProductTypeAnswer.Result.APPAREL && p.lastWrite() == 100,
                "Failure/unsupported label overwrote valid cache or stored a timestamp");
        }

        // A clock rollback cannot serve a future cache, save a result, or authorize a paid call.
        p = empty(); p.claim(a, 100000); p.save(a, ProductTypeAnswer.Result.UNKNOWN, 100100);
        check(p.cached(a, 100099) == null && p.waitMillis(a, 100099) == 60000, "Rollback reused future data or failed open");
        check(!p.claim(b, 99999) && !p.save(b, ProductTypeAnswer.Result.APPAREL, 100099), "Rollback wrote a new entry");
        check(p.cached(a, 100100) == ProductTypeAnswer.Result.UNKNOWN && p.waitMillis(a, 100100) == 0, "Clock recovery failed to restore valid cache");
        p = new ProductTypeCachePolicy(Map.of(), Map.of(a, Long.MAX_VALUE), -1, -1);
        check(p.waitMillis(b, 1) == 60000 && !p.claim(b, 1), "Future timestamp overflow bypassed cooldown");
        check(p.waitMillis(a, -1) == 60000 && !p.claim(a, -1) && !p.save(a, ProductTypeAnswer.Result.APPAREL, -1),
            "Negative time must fail closed");

        p = empty();
        for (int i = 0; i < 129; i++) check(p.save(key(i), ProductTypeAnswer.Result.UNKNOWN, i), "Cache capacity fill failed");
        check(p.cachedEntries().size() == 128 && p.cached(key(0), 129) == null
            && p.cached(key(128), 129) == ProductTypeAnswer.Result.UNKNOWN, "Cache must evict oldest entry at 128");
        check(p.save(key(1), ProductTypeAnswer.Result.APPAREL, 130), "Cache refresh failed");
        p.save(key(129), ProductTypeAnswer.Result.UNKNOWN, 131);
        check(p.cached(key(1), 132) == ProductTypeAnswer.Result.APPAREL && p.cached(key(2), 132) == null,
            "Updated cache entry was evicted instead of oldest remaining entry");
        var entries = new LinkedHashMap<String, ProductTypeCachePolicy.Entry>();
        var attempts = new LinkedHashMap<String, Long>();
        for (int i = 129; i >= 0; i--) {
            entries.put(key(i), new ProductTypeCachePolicy.Entry(ProductTypeAnswer.Result.UNKNOWN, i));
            attempts.put(key(i), (long)i);
        }
        p = new ProductTypeCachePolicy(entries, attempts, -1, -1);
        check(p.cachedEntries().size() == 128 && p.attemptEntries().size() == 128
            && !p.cachedEntries().containsKey(key(0)) && !p.attemptEntries().containsKey(key(0)),
            "Restored capacity must use time, not JSON iteration order");
        check(p.lastAttempt() == 129 && p.lastWrite() == 129, "Restored time frontier lost latest attempt");

        String raw = "private title / private host / product source";
        String hashed = ProductTypeCachePolicy.key(raw);
        check(hashed.matches("[0-9a-f]{64}") && hashed.equals(ProductTypeCachePolicy.key(raw)) && !hashed.contains("private"),
            "Persisted identifier must be a deterministic SHA-256 hash");
        p = empty(); p.claim(hashed, 10); p.save(hashed, ProductTypeAnswer.Result.APPAREL, 20);
        check(!p.cachedEntries().toString().contains(raw) && !p.attemptEntries().toString().contains(raw), "State retained raw text");
        check(!p.save(raw, ProductTypeAnswer.Result.APPAREL, 30) && !p.claim(raw, 100000) && p.waitMillis(raw, 100000) == 60000,
            "Raw identifiers entered persistence policy");
        entries = new LinkedHashMap<>(); entries.put(raw, new ProductTypeCachePolicy.Entry(ProductTypeAnswer.Result.APPAREL, 10));
        entries.put(a, new ProductTypeCachePolicy.Entry(ProductTypeAnswer.Result.TIMED_OUT, 10));
        attempts = new LinkedHashMap<>(); attempts.put(raw, 10L);
        p = new ProductTypeCachePolicy(entries, attempts, -1, -1);
        check(p.cachedEntries().isEmpty() && p.attemptEntries().isEmpty(), "Restore admitted raw IDs or unsupported labels");
        try { p.cachedEntries().put(a, new ProductTypeCachePolicy.Entry(ProductTypeAnswer.Result.APPAREL, 1));
            throw new AssertionError("Cache snapshot is mutable"); } catch (UnsupportedOperationException expected) { assertions++; }
        System.out.println(assertions + " shopping product type cache assertions passed");
    }
}
