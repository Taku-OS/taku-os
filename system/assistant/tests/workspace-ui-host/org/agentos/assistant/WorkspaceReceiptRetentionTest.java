package org.agentos.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Real receipt client, in-memory/durable preference doubles, and concurrent timer callbacks. */
public final class WorkspaceReceiptRetentionTest {
    private static int checks;
    private static final long DAY = 24 * 60 * 60 * 1000L;
    private static void verify(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static String id(int number) { return String.format("00000000-0000-4000-8000-%012d", number); }
    private static JSONObject screen() throws Exception {
        return new JSONObject().put("threadId", id(1)).put("screenKey", "task-0123456789abcdef0123")
                .put("generation", id(2)).put("controlRevision", 3).put("controlOwner", "user");
    }
    private static JSONObject row(int number, String status, long at) throws Exception {
        JSONObject request = WorkspaceUi.request("returnAndResume", screen()).put("requestId", id(number)).put("controlRevision", 3);
        return new JSONObject().put("requestId", id(number)).put("action", "returnAndResume").put("request", request)
                .put("status", status).put("retainedAt", at).put("draftRevision", "").put("text", "");
    }
    private static String key(JSONObject row, boolean history) {
        String base = WorkspaceExecutionRequest.identity(row.optJSONObject("request")) + "." + row.optString("action");
        return history ? base + "." + row.optString("requestId") : base;
    }
    private static final class Preferences extends Context {
        final Map<String, String> values = new ConcurrentHashMap<>(), disk = new ConcurrentHashMap<>();
        int commits, lastRemoved;
        boolean failCommit;
        final SharedPreferences store = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> values.getOrDefault((String) args[0], (String) args[1]);
                    case "getAll" -> new HashMap<>(values);
                    case "edit" -> editor();
                    default -> throw new AssertionError("Unexpected preference operation: " + method.getName());
                });
        private SharedPreferences.Editor editor() {
            Map<String, String> writes = new HashMap<>(); Set<String> deletes = new HashSet<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "putString": deletes.remove((String) args[0]); writes.put((String) args[0], (String) args[1]); return proxy;
                            case "remove": writes.remove((String) args[0]); deletes.add((String) args[0]); return proxy;
                            case "commit":
                                commits++; lastRemoved = deletes.size(); deletes.forEach(values::remove); values.putAll(writes);
                                if (failCommit) return false;
                                deletes.forEach(disk::remove); disk.putAll(writes); return true;
                            default: throw new AssertionError("Unexpected editor operation: " + method.getName());
                        }
                    });
        }
        void seed(JSONObject row, boolean history) { put(key(row, history), row.toString()); }
        void put(String key, String value) { values.put(key, value); disk.put(key, value); }
        boolean has(JSONObject row, boolean history) { return values.containsKey(key(row, history)); }
        public SharedPreferences getSharedPreferences(String name, int mode) { return store; }
    }
    private static WorkspaceExecutionRequest client(Preferences preferences) throws Exception {
        return new WorkspaceExecutionRequest(preferences, screen(), "returnAndResume", result -> {},
                (request, callback) -> { throw new AssertionError("This check must not send RPCs"); });
    }
    private static void count() throws Exception {
        Preferences preferences = new Preferences(); long now = System.currentTimeMillis();
        for (int n = 1; n <= 200; n++) preferences.seed(row(n, "accepted", now), true);
        client(preferences);
        verify(preferences.values.size() == 120, "startup reclaims 200 historical receipts to 120 in one batch");
        verify(preferences.commits == 1 && preferences.lastRemoved == 80, "the whole batch uses one durable preference commit");
        verify(!preferences.has(row(80, "accepted", now), true) && preferences.has(row(81, "accepted", now), true), "oldest range is removed deterministically");
        for (int n = 201; n <= 279; n++) preferences.seed(row(n, "accepted", now + 1), true);
        client(preferences);
        verify(preferences.values.size() == 199 && preferences.commits == 1, "79 new receipts consume headroom without another deletion/write");
        preferences.seed(row(280, "accepted", now + 2), true); client(preferences);
        verify(preferences.values.size() == 120 && preferences.lastRemoved == 80, "next high-water crossing removes another full range");
    }
    private static void sweep(Preferences preferences, long now) throws Exception {
        Field field = WorkspaceExecutionRequest.class.getDeclaredField("RECEIPT_LOCK"); field.setAccessible(true);
        Method method = WorkspaceExecutionRequest.class.getDeclaredMethod("retain", SharedPreferences.class, String.class, JSONObject.class, long.class);
        method.setAccessible(true);
        synchronized (field.get(null)) { method.invoke(null, preferences.store, null, null, now); }
    }
    private static void maintain() throws Exception {
        Method method = WorkspaceExecutionRequest.class.getDeclaredMethod("maintain"); method.setAccessible(true); method.invoke(null);
    }
    private static void ageBytesAndRestart() throws Exception {
        long now = System.currentTimeMillis(); Preferences age = new Preferences();
        JSONObject oldest = row(1, "accepted", now - 7 * DAY), middle = row(2, "accepted", now - 6 * DAY), fresh = row(3, "accepted", now - 4 * DAY);
        age.seed(oldest, true); age.seed(middle, true); age.seed(fresh, true); client(age);
        verify(!age.has(oldest, true) && !age.has(middle, true) && age.has(fresh, true), "age trigger clears back to five days rather than deleting only the expired item");
        sweep(age, now + 4 * DAY);
        verify(age.values.isEmpty(), "time-only sweep works without another interaction");

        Preferences bytes = new Preferences(); JSONObject big = row(1, "accepted", now).put("text", "a".repeat(1_100_000));
        JSONObject smaller = row(2, "accepted", now + 1).put("text", "b".repeat(1_000_000));
        bytes.seed(big, true); bytes.seed(smaller, true); client(bytes);
        verify(!bytes.has(big, true) && bytes.has(smaller, true), "byte pressure reclaims the oldest range to below 1.2 MB");

        Preferences legacy = new Preferences(); JSONObject old = row(7, "accepted", now);
        old.remove("retainedAt"); old.put("receipt", new JSONObject().put("acceptedAt", now - 8 * DAY)); legacy.seed(old, true);
        JSONObject undated = row(8, "accepted", now); undated.remove("retainedAt"); legacy.seed(undated, true);
        legacy.put("unrelated", "not a receipt"); client(legacy);
        verify(!legacy.has(old, true) && new JSONObject(legacy.values.get(key(undated, true))).has("retainedAt"), "legacy timestamps are migrated and known-old receipts expire");
        Preferences restarted = new Preferences(); legacy.disk.forEach(restarted::put); client(restarted);
        sweep(restarted, now + 8 * DAY);
        verify(!restarted.has(undated, true) && restarted.values.get("unrelated").equals("not a receipt"), "migration survives restart and leaves unrelated preference data intact");

        Preferences periodic = new Preferences(); JSONObject current = row(9, "accepted", now - 8 * DAY);
        periodic.seed(current, false); client(periodic);
        verify(periodic.has(current, false), "startup preserves the receipt currently being restored");
        maintain(); verify(!periodic.has(current, false), "background maintenance later removes an obsolete completed current-slot receipt");
    }
    private static void protections() throws Exception {
        long now = System.currentTimeMillis(); Preferences preferences = new Preferences();
        for (int n = 1; n <= 220; n++) preferences.seed(row(n, n % 2 == 0 ? "pending" : "unknown", now - 30 * DAY), true);
        JSONObject dedup = row(500, "accepted", now - 30 * DAY).put("action", "addRequirement").put("draftRevision", "accepted-draft");
        dedup.getJSONObject("request").put("action", "addRequirement"); preferences.seed(dedup, false); preferences.seed(dedup, true);
        String draft = WorkspaceExecutionRequest.identity(dedup.getJSONObject("request")) + ".requirementDraft";
        preferences.put(draft, "already delivered requirement"); preferences.put(draft + ".revision", "accepted-draft");
        client(preferences); sweep(preferences, now + 30 * DAY);
        verify(preferences.has(row(2, "pending", now), true) && preferences.has(row(3, "unknown", now), true), "age and count pressure never discard unresolved outcomes");
        verify(preferences.has(dedup, false), "accepted draft's canonical dedup receipt survives even without a visible editor");
        verify(!preferences.has(dedup, true), "its unused resolved historical duplicate can still be reclaimed");
        verify(preferences.values.get(draft).equals("already delivered requirement"), "draft content is outside receipt retention");
        preferences.put(draft + ".revision", "new-draft"); sweep(preferences, now + 30 * DAY);
        verify(!preferences.has(dedup, false), "once the draft advances, the old dedup receipt becomes reclaimable");
    }
    private static void writesAndConcurrency() throws Exception {
        Preferences preferences = new Preferences(); long now = System.currentTimeMillis();
        for (int n = 1; n <= 199; n++) preferences.seed(row(n, "accepted", now - 1000), true);
        List<NativeClient.Callback> acknowledgements = new ArrayList<>();
        WorkspaceExecutionRequest request = new WorkspaceExecutionRequest(preferences, screen(), "returnAndResume", receipt -> {}, (input, callback) -> {
            try {
                if (input.getString("action").equals("list")) callback.ready(new JSONObject().put("screens", new JSONArray().put(screen())));
                else {
                    JSONObject durable = new JSONObject(preferences.disk.get(WorkspaceExecutionRequest.identity(input) + ".returnAndResume"));
                    verify(durable.getString("requestId").equals(input.getString("requestId")) && durable.getString("status").equals("pending"), "request is durably saved before transport dispatch");
                    acknowledgements.add(callback);
                }
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        request.start(); request.submit("");
        String first = request.receipt().getString("requestId");
        acknowledgements.get(0).ready(new JSONObject().put("requestId", first).put("status", "accepted"));
        verify(preferences.commits == 2 && preferences.lastRemoved == 80, "acknowledgement update and threshold eviction share one commit");

        CompletableFuture<Void> timer = new CompletableFuture<>();
        Thread cleaner = new Thread(() -> {
            try { for (int n = 0; n < 300; n++) maintain(); timer.complete(null); }
            catch (Exception error) { timer.completeExceptionally(error); }
        }, "receipt-retention-race");
        cleaner.setDaemon(true); cleaner.start();
        for (int n = 0; n < 1000; n++) {
            request.submit(""); String id = request.receipt().getString("requestId");
            acknowledgements.get(acknowledgements.size() - 1).ready(new JSONObject().put("requestId", id).put("status", "accepted"));
        }
        timer.get(10, TimeUnit.SECONDS); request.stop();
        verify(preferences.values.size() <= 200, "1000 interactions plus concurrent real maintenance callbacks stay below the high water mark");
        Preferences restarted = new Preferences(); preferences.disk.forEach(restarted::put);
        verify(client(restarted).receipt().getString("requestId").equals(request.receipt().getString("requestId")), "latest committed outcome survives cleanup and restart");

        Preferences failed = new Preferences(); failed.failCommit = true; int[] sent = {0};
        WorkspaceExecutionRequest notSent = new WorkspaceExecutionRequest(failed, screen(), "returnAndResume", receipt -> {}, (input, callback) -> {
            try {
                if (input.getString("action").equals("list")) callback.ready(new JSONObject().put("screens", new JSONArray().put(screen())));
                else sent[0]++;
            } catch (Exception error) { throw new AssertionError(error); }
        });
        notSent.start(); notSent.submit(""); verify(sent[0] == 0, "failed initial persistence never submits a new native operation"); notSent.stop();
        System.out.println("1000-operation simulation: " + preferences.values.size() + " retained entries; " + preferences.commits + " atomic commits");
    }
    public static void main(String[] args) throws Exception {
        count();
        if (args.length == 0) { ageBytesAndRestart(); protections(); writesAndConcurrency(); }
        System.out.println("WorkspaceReceiptRetentionTest: " + checks + " checks passed (fake transport and durable preference double)");
    }
}
