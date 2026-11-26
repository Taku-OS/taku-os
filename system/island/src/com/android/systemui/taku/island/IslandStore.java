/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Authoritative, main-thread-confined activity lifetime/revision store; no Android/renderer state.
 * Order: system tier (call, alarm, recording/cast) first, then a live alert, then the most recently
 * triggered activity. Routine updates never reorder; only a first post or a rising alert triggers.
 */
public final class IslandStore<T> {
    public static final int MAX_LIFETIME_S = 12 * 60 * 60;
    public static final long MAX_LIFETIME_MS = MAX_LIFETIME_S * 1000L;
    public static final int DEFAULT_LIFETIME_S = 60 * 60;
    public static final int TIER_CALL = 0;
    public static final int TIER_ALARM = 1;
    public static final int TIER_RECORDING = 2;
    public static final int TIER_ACTIVITY = 3;
    public static final int MAX_ITEMS = 128;
    /** A rising alert re-presents an activity at most this often; faster false→true flips stay quiet. */
    public static final long ALERT_COOLDOWN_MS = 30_000L;
    /** Last presented alert per key; kept across removal so cancel-and-repost cannot skip the cooldown. */
    private final Map<String, Long> lastAlerts = new HashMap<>();
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
        /** First post, or the latest false-to-true alert; the recency key for ordinary activities. */
        public long triggeredAt;
        public boolean dismissed;
        private boolean rejected;
        private boolean alerting;
        public int tier;
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

    /**
     * Claims a slot for {@code key} without starting a revision: true when the key is already
     * tracked or capacity remained. The heads-up decision for a brand-new post precedes its
     * {@link #begin}, so it reserves here; a post the full store turns away keeps Android's
     * heads-up. The slot is released by {@link #remove} like any other.
     */
    public boolean admit(String key) {
        if (revisions.containsKey(key)) return true;
        if (revisions.size() >= MAX_ITEMS) return false;
        revisions.put(key, ++sequence);
        return true;
    }

    public boolean isCurrent(String key, long revision) {
        return revision >= 0 && Long.valueOf(revision).equals(revisions.get(key));
    }

    public boolean put(String key, long revision, long now, long firstSeen, int lifetimeSeconds,
            int staleSeconds, int tier, boolean alert, T data) {
        if (!isCurrent(key, revision)) return false;
        if (lifetimeSeconds < 1 || lifetimeSeconds > MAX_LIFETIME_S || staleSeconds < 0
                || staleSeconds > MAX_LIFETIME_S || tier < TIER_CALL || tier > TIER_ACTIVITY || data == null) {
            throw new IllegalArgumentException("invalid_activity");
        }
        Entry<T> entry = entries.get(key);
        if (entry == null) {
            entry = new Entry<>(key, Math.min(now, firstSeen));
            entry.triggeredAt = entry.firstSeen;
            entries.put(key, entry);
        }
        entry.revision = revision;
        entry.updatedAt = now;
        // Updates cannot extend the initial requested lifetime or the hard 12 h ceiling.
        long expiry = entry.firstSeen + Math.min(MAX_LIFETIME_MS, lifetimeSeconds * 1000L);
        entry.expiresAt = Math.min(entry.expiresAt, expiry);
        entry.staleAt = staleSeconds == 0 ? Long.MAX_VALUE : now + staleSeconds * 1000L;
        entry.tier = tier;
        if (alert && !entry.alerting) {
            Long last = lastAlerts.get(key);
            if (last == null || now - last >= ALERT_COOLDOWN_MS) {
                entry.alertUntil = Math.min(entry.expiresAt, now + 5000);
                entry.triggeredAt = now;
                if (lastAlerts.size() >= 2 * MAX_ITEMS) lastAlerts.values().removeIf(at -> now - at >= ALERT_COOLDOWN_MS);
                lastAlerts.put(key, now);
            }
        }
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

    /** Every key with extracted state, including dismissed and rejected entries. */
    public java.util.Set<String> keys() { return new java.util.HashSet<>(entries.keySet()); }

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
        result.sort(Comparator.<Entry<T>>comparingInt(entry -> entry.tier)
                .thenComparingInt(entry -> entry.alertUntil > now ? 0 : 1)
                .thenComparing(Comparator.comparingLong((Entry<T> entry) -> entry.triggeredAt).reversed())
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
