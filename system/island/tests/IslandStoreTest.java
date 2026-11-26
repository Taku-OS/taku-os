/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
import com.android.systemui.taku.island.IslandStore;

public final class IslandStoreTest {
    private static final int ACTIVITY = IslandStore.TIER_ACTIVITY;
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void put(IslandStore<String> store, String key, long now, int tier, boolean alert) {
        store.put(key, store.begin(key), now, now, 3600, 120, tier, alert, key);
    }
    private static String first(IslandStore<String> store, long now) { return store.active(now).get(0).key; }
    public static void main(String[] args) {
        IslandStore<String> store = new IslandStore<>();
        long firstRevision = store.begin("a");
        long second = store.begin("a");
        check(!store.put("a", firstRevision, 0, 0, 10, 2, ACTIVITY, false, "old"), "late extraction rejected");
        check(store.put("a", second, 0, 0, 10, 2, ACTIVITY, false, "new"), "current extraction accepted");
        check(store.active(1000).size() == 1, "active before deadline");
        check(store.get("a").stale(2000), "stale boundary");
        check(store.put("a", store.begin("a"), 5000, 5000, 43200, 2, ACTIVITY, false, "update"), "update accepted");
        check(store.get("a").expiresAt == 10000, "update cannot extend initial deadline");
        check(store.active(10000).isEmpty(), "expires at boundary");
        long pending = store.begin("a");
        store.remove("a");
        check(!store.put("a", pending, 6000, 6000, 10, 2, ACTIVITY, false, "late"), "removed item not resurrected");
        put(store, "a", 20000, ACTIVITY, false);
        check(store.active(20000).size() == 1, "reposted notification begins a new lifetime");
        check(store.dismiss("a"), "dismiss existing island");
        put(store, "a", 21000, ACTIVITY, false);
        check(store.active(21000).isEmpty(), "publisher updates respect island dismissal");

        // Lifetime: default 1 h from first post, explicit up to 12 h, hard cap 12 h.
        store.clear();
        check(IslandStore.DEFAULT_LIFETIME_S == 3600 && IslandStore.MAX_LIFETIME_S == 43200, "lifetime constants");
        store.put("long", store.begin("long"), 0, 0, 43200, 0, ACTIVITY, false, "long");
        check(store.get("long").expiresAt == 43200_000L, "explicit 12 h lifetime accepted");
        boolean rejected = false;
        try { store.put("tooLong", store.begin("tooLong"), 0, 0, 43201, 0, ACTIVITY, false, "x"); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "lifetime above 12 h rejected");
        store.put("old", store.begin("old"), 50_000_000L, 0, 43200, 0, ACTIVITY, false, "old");
        check(store.active(50_000_000L).stream().noneMatch(e -> e.key.equals("old")), "restored first post keeps its 12 h cap");

        // Ordering: system tier, then a live alert, then the most recently triggered activity.
        store.clear();
        put(store, "older", 100, ACTIVITY, false);
        put(store, "newer", 200, ACTIVITY, false);
        check(first(store, 300).equals("newer"), "newest activity first");
        put(store, "older", 400, ACTIVITY, false);
        check(first(store, 500).equals("newer"), "routine update does not reorder");
        put(store, "recording", 50, IslandStore.TIER_RECORDING, false);
        put(store, "alarm", 60, IslandStore.TIER_ALARM, false);
        put(store, "call", 70, IslandStore.TIER_CALL, false);
        check(first(store, 500).equals("call"), "call outranks everything");
        put(store, "older", 600, ACTIVITY, true);
        check(first(store, 600).equals("call"), "alerts cannot preempt calls");
        store.remove("call");
        check(first(store, 600).equals("alarm"), "alerts cannot preempt alarms");
        store.remove("alarm");
        check(first(store, 600).equals("recording"), "alerts cannot hide recording");
        store.remove("recording");
        check(first(store, 600).equals("older"), "rising alert preempts");
        put(store, "older", 1000, ACTIVITY, true);
        check(store.get("older").alertUntil == 5600, "repeated alert payload cannot extend alert forever");
        check(first(store, 5600).equals("older"), "rising alert re-triggers recency after the alert window");
        check(store.get("older").triggeredAt == 600, "held alert does not re-trigger");
        long alertWindow = store.get("older").alertUntil;
        long contentRevision = store.get("older").revision;
        put(store, "older", 2000, ACTIVITY, false);
        check(store.get("older").revision != contentRevision && store.get("older").alertUntil == alertWindow,
                "quiet update inside the 5 s window changes content but not the consumed alert identity");
        put(store, "older", 2500, ACTIVITY, true);
        check(store.get("older").alertUntil == alertWindow,
                "cooldown-rejected flip inside the window cannot create a new alert identity");
        put(store, "older", 3000, ACTIVITY, true);
        check(store.get("older").alertUntil == alertWindow,
                "held true update inside the window keeps the consumed alert identity");
        put(store, "older", 6000, ACTIVITY, false);
        put(store, "older", 6100, ACTIVITY, true);
        check(store.get("older").triggeredAt == 600 && store.get("older").alertUntil == 5600,
                "a false-to-true flip inside the 30 s cooldown neither re-alerts nor reorders");
        put(store, "older", 20_000, ACTIVITY, false);
        put(store, "older", 600 + IslandStore.ALERT_COOLDOWN_MS, ACTIVITY, true);
        check(store.get("older").triggeredAt == 600 + IslandStore.ALERT_COOLDOWN_MS,
                "a false-to-true alert after the cooldown re-triggers");
        check(store.get("older").alertUntil != alertWindow,
                "accepted alert after cooldown has a new presentation identity");
        store.remove("older");
        put(store, "older", 600 + IslandStore.ALERT_COOLDOWN_MS + 1000, ACTIVITY, true);
        check(store.get("older").alertUntil == 0, "cancel and repost cannot skip the alert cooldown");
        store.remove("older");
        put(store, "older", 600, ACTIVITY, false);
        put(store, "stale", 7000, ACTIVITY, false);
        check(store.get("stale").stale(7000 + 120_000), "stale is tracked");
        check(first(store, 7000 + 120_000).equals("stale"), "stale does not change rank");

        long old = store.begin("newer");
        long latest = store.begin("newer");
        check(!store.reject("newer", old), "late invalid update cannot remove valid item");
        check(store.reject("newer", latest), "current invalid update removes island only");
        check(store.get("newer") == null, "rejected data absent");
        store.clear();
        for (int i = 0; i < IslandStore.MAX_ITEMS; i++) put(store, "item" + i, 0, ACTIVITY, false);
        check(store.begin("overflow") == -1, "bounded store");
        store.remove("item0");
        check(store.begin("overflow") > 0, "removed item frees capacity");

        // Heads-up admission at full capacity: a post the island turns away keeps its heads-up.
        store.clear();
        for (int i = 0; i < IslandStore.MAX_ITEMS; i++) put(store, "item" + i, 0, ACTIVITY, false);
        check(!store.admit("new"), "full store does not admit a new notification");
        check(store.begin("new") == -1 && store.get("new") == null, "a refused notification is not tracked");
        check(!store.admit("new"), "an update of the refused notification is still not admitted");
        // A tracked notification updates in place, however full the store is.
        check(store.admit("item5"), "tracked notification stays admitted when full");
        long tracked = store.begin("item5");
        check(tracked > 0 && store.put("item5", tracked, 10, 0, 3600, 0, ACTIVITY, true, "update"),
                "tracked notification updates at full capacity");
        check("update".equals(store.get("item5").data), "tracked update shown");
        check(store.keys().size() == IslandStore.MAX_ITEMS, "admission checks track nothing new");
        // Admission reserves the freed slot: whatever is posted in between cannot take it.
        store.remove("item0");
        check(store.admit("new"), "freed capacity admits the notification");
        check(!store.admit("rival") && store.begin("rival") == -1, "the admitted slot is held");
        long admitted = store.begin("new");
        check(admitted > 0 && store.put("new", admitted, 20, 20, 3600, 0, ACTIVITY, true, "new"),
                "admitted notification becomes an activity");
        // An admitted post that never becomes an activity gives its slot back on removal.
        store.remove("item1");
        check(store.admit("ghost") && !store.admit("rival"), "reservation without an activity holds a slot");
        check(store.get("ghost") == null && !store.keys().contains("ghost"), "a reservation is not an activity");
        store.remove("ghost");
        check(store.admit("rival"), "removing a reservation frees its slot");

        // Capsule selection: activities without capsule data never mask others.
        store.clear();
        put(store, "assistant", 300, ACTIVITY, false);
        put(store, "media", 200, ACTIVITY, false);
        put(store, "progress", 100, ACTIVITY, false);
        java.util.function.Predicate<String> visible = key -> !key.equals("assistant");
        check(IslandStore.compactSelection(store.active(300), "assistant", false, visible).equals("media"),
                "capsule-less activity yields the capsule to the next");
        check(IslandStore.compactSelection(store.active(300), "assistant", true, visible).equals("assistant"),
                "expanded activity retains its card");
        check(IslandStore.compactSelection(store.active(300), "progress", false, visible).equals("progress"),
                "explicit visible selection is retained");
        store.remove("media");
        check(IslandStore.compactSelection(store.active(300), "media", false, visible).equals("progress"),
                "removed selection falls back to remaining visible activity");
        store.remove("progress");
        check(IslandStore.compactSelection(store.active(300), "assistant", false, visible).equals("assistant"),
                "capsule-less activity remains addressable");
        System.out.println("PASS: " + checks + " island store checks");
    }
}
