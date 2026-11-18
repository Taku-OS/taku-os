/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Authoritative, main-thread-confined activity lifetime/revision store; no Android/renderer state. */
public final class IslandStore<T> {
    public static final long MAX_LIFETIME_MS = 8 * 60 * 60 * 1000L;
    public static final int MAX_ITEMS = 128;
    private final Map<String, Entry<T>> entries = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private long sequence;

    public static final class Entry<T> {
        public final String key;
        public final long firstSeen;
        public long revision;
        public long updatedAt;
        public long expiresAt = Long.MAX_VALUE;
        public long staleAt;
        public long alertUntil;
        public boolean dismissed;
        private boolean rejected;
        private boolean alerting;
        public int priority;
        public double relevance;
        public T data;

        private Entry(String key, long firstSeen) {
            this.key = key;
            this.firstSeen = firstSeen;
        }

        public boolean active(long now) { return !rejected && !dismissed && now < expiresAt; }
        public boolean stale(long now) { return now >= staleAt; }
    }

    /** Allocate before asynchronous extraction; late results cannot resurrect removed notifications. */
    public long begin(String key) {
        long revision = ++sequence;
        if (!revisions.containsKey(key) && revisions.size() >= MAX_ITEMS) return -1;
        revisions.put(key, revision);
        return revision;
    }

    public boolean isCurrent(String key, long revision) {
        return revision >= 0 && Long.valueOf(revision).equals(revisions.get(key));
    }

    public boolean put(String key, long revision, long now, long firstSeen, int lifetimeSeconds,
            int staleSeconds, int priority, double relevance, boolean alert, T data) {
        if (!isCurrent(key, revision)) return false;
        if (lifetimeSeconds < 1 || lifetimeSeconds > 28800 || staleSeconds < 0 || staleSeconds > 28800
                || priority < 0 || priority > 8 || !Double.isFinite(relevance)
                || relevance < 0 || relevance > 1 || data == null) {
            throw new IllegalArgumentException("invalid_activity");
        }
        Entry<T> entry = entries.get(key);
        if (entry == null) {
            entry = new Entry<>(key, Math.min(now, firstSeen));
            entries.put(key, entry);
        }
        entry.revision = revision;
        entry.updatedAt = now;
        // Updates cannot extend the initial requested lifetime or the hard 8 h ceiling.
        long expiry = entry.firstSeen + Math.min(MAX_LIFETIME_MS, lifetimeSeconds * 1000L);
        entry.expiresAt = Math.min(entry.expiresAt, expiry);
        entry.staleAt = staleSeconds == 0 ? Long.MAX_VALUE : now + staleSeconds * 1000L;
        entry.priority = priority;
        entry.relevance = relevance;
        if (alert && !entry.alerting) entry.alertUntil = Math.min(entry.expiresAt, now + 5000);
        entry.alerting = alert;
        entry.data = data;
        entry.rejected = false;
        return true;
    }

    /** Rejected revisions also remove previously valid UI for that key; the source notification stays. */
    public boolean reject(String key, long revision) {
        if (!Long.valueOf(revision).equals(revisions.get(key))) return false;
        Entry<T> entry = entries.get(key);
        if (entry != null) {
            entry.rejected = true;
            entry.data = null;
        }
        return true;
    }

    public void remove(String key) {
        revisions.remove(key);
        entries.remove(key);
    }

    public void clear() {
        entries.clear();
        revisions.clear();
    }

    public Entry<T> get(String key) {
        Entry<T> entry = entries.get(key);
        return entry == null || entry.rejected ? null : entry;
    }

    /** Dismisses only the island; ongoing notifications and their tasks are never cancelled here. */
    public boolean dismiss(String key) {
        Entry<T> entry = entries.get(key);
        if (entry == null) return false;
        entry.dismissed = true;
        return true;
    }

    public List<Entry<T>> active(long now) {
        List<Entry<T>> result = new ArrayList<>();
        for (Entry<T> entry : entries.values()) if (entry.active(now)) result.add(entry);
        result.sort(Comparator.<Entry<T>>comparingInt(entry -> entry.priority < 2 ? entry.priority : 2)
                .thenComparingInt(entry -> entry.alertUntil > now ? 0 : 1)
                .thenComparingInt(entry -> entry.priority)
                .thenComparingInt(entry -> entry.stale(now) ? 1 : 0)
                .thenComparing(Comparator.comparingDouble((Entry<T> entry) -> entry.relevance).reversed())
                .thenComparing(Comparator.comparingLong((Entry<T> entry) -> entry.updatedAt).reversed())
                .thenComparing(entry -> entry.key));
        return result;
    }

    /** An expanded card keeps its selection; a hidden compact entry must not mask other pills. */
    public static <T> String compactSelection(List<Entry<T>> activities, String selected,
            boolean expanded, Predicate<T> compactVisible) {
        if (expanded) return selected;
        for (Entry<T> entry : activities) {
            if (entry.key.equals(selected) && compactVisible.test(entry.data)) return selected;
        }
        for (Entry<T> entry : activities) {
            if (compactVisible.test(entry.data)) return entry.key;
        }
        // Keep the hidden activity addressable for explicit expansion when it is alone.
        return selected;
    }
}
