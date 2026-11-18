/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
import com.android.systemui.taku.island.IslandStore;

public final class IslandStoreTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void put(IslandStore<String> store, String key, long now, int priority, boolean alert) {
        store.put(key, store.begin(key), now, now, 3600, 120, priority, .5, alert, key);
    }
    public static void main(String[] args) {
        IslandStore<String> store = new IslandStore<>();
        long first = store.begin("a");
        long second = store.begin("a");
        check(!store.put("a", first, 0, 0, 10, 2, 5, .5, false, "old"), "late extraction rejected");
        check(store.put("a", second, 0, 0, 10, 2, 5, .5, false, "new"), "current extraction accepted");
        check(store.active(1000).size() == 1, "active before deadline");
        check(store.get("a").stale(2000), "stale boundary");
        check(store.put("a", store.begin("a"), 5000, 5000, 28800, 2, 5, .5, false, "update"), "update accepted");
        check(store.get("a").expiresAt == 10000, "update cannot extend initial deadline");
        check(store.active(10000).isEmpty(), "expires at boundary");
        long pending = store.begin("a");
        store.remove("a");
        check(!store.put("a", pending, 6000, 6000, 10, 2, 5, .5, false, "late"), "removed item not resurrected");
        put(store, "a", 20000, 5, false);
        check(store.active(20000).size() == 1, "reposted notification begins a new lifetime");
        check(store.dismiss("a"), "dismiss existing island");
        put(store, "a", 21000, 5, false);
        check(store.active(21000).isEmpty(), "publisher updates respect island dismissal");
        store.clear();
        put(store, "assistant", 100, 5, false);
        put(store, "call", 100, 0, false);
        put(store, "alarm", 100, 1, false);
        check(store.active(100).get(0).key.equals("call"), "call priority");
        put(store, "progress", 100, 7, true);
        check(store.active(100).get(0).key.equals("call"), "alerts cannot preempt critical calls");
        store.remove("call");
        check(store.active(100).get(0).key.equals("alarm"), "alerts cannot preempt alarms");
        store.remove("alarm");
        check(store.active(100).get(0).key.equals("progress"), "temporary non-critical alert preemption");
        put(store, "progress", 1000, 7, true);
        check(store.get("progress").alertUntil == 5100, "repeated alert payload cannot extend alert forever");
        check(store.active(5100).get(0).key.equals("assistant"), "alert returns to priority order");
        long old = store.begin("assistant");
        long latest = store.begin("assistant");
        check(!store.reject("assistant", old), "late invalid update cannot remove valid item");
        check(store.reject("assistant", latest), "current invalid update removes island only");
        check(store.get("assistant") == null, "rejected data absent");
        store.clear();
        for (int i = 0; i < IslandStore.MAX_ITEMS; i++) put(store, "item" + i, 0, 8, false);
        check(store.begin("overflow") == -1, "bounded store");
        store.remove("item0");
        check(store.begin("overflow") > 0, "removed item frees capacity");
        store.clear();
        put(store, "assistant", 100, 5, false);
        put(store, "media", 100, 6, false);
        put(store, "progress", 100, 7, false);
        java.util.function.Predicate<String> visible = key -> !key.equals("assistant");
        check(IslandStore.compactSelection(store.active(100), "assistant", false, visible).equals("media"),
                "hidden assistant yields compact presentation to media");
        check(IslandStore.compactSelection(store.active(100), "assistant", true, visible).equals("assistant"),
                "expanded assistant retains its card");
        check(IslandStore.compactSelection(store.active(100), "progress", false, visible).equals("progress"),
                "explicit visible selection is retained");
        check(IslandStore.compactSelection(store.active(100), "assistant", false, key -> true).equals("assistant"),
                "building assistant keeps visible capsule");
        store.remove("media");
        check(IslandStore.compactSelection(store.active(100), "media", false, visible).equals("progress"),
                "removed selection falls back to remaining visible activity");
        store.remove("progress");
        check(IslandStore.compactSelection(store.active(100), "assistant", false, visible).equals("assistant"),
                "hidden-only activity remains addressable");
        System.out.println("PASS: " + checks + " island store checks");
    }
}
