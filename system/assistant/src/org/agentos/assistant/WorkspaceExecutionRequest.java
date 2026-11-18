package org.agentos.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Durable UI receipt for one exact lease/action. Unknown delivery is queried, never replayed. */
final class WorkspaceExecutionRequest {
    // Multiple surfaces share this preference file. Keep the read/claim/write
    // atomic across instances; no transport or listener runs while holding it.
    private static final Object RECEIPT_LOCK = new Object();
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static SharedPreferences retainedPreferences;
    private static final ScheduledExecutorService retention = Executors.newSingleThreadScheduledExecutor(run -> {
        Thread thread = new Thread(run, "workspace-receipt-retention"); thread.setDaemon(true); return thread;
    });
    static {
        retention.scheduleWithFixedDelay(WorkspaceExecutionRequest::maintain, 1, 1, TimeUnit.HOURS);
    }

    private static void maintain() {
        synchronized (RECEIPT_LOCK) {
            if (retainedPreferences != null) retain(retainedPreferences, null, null, System.currentTimeMillis());
        }
    }
    interface Listener { void changed(JSONObject receipt); }
    interface Transport { void send(JSONObject request, NativeClient.Callback callback); }
    private final SharedPreferences preferences;
    private final JSONObject lease;
    private final String action, key;
    private final Listener listener;
    private final Transport transport;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;
    private boolean active, fetching;
    private int generation;

    WorkspaceExecutionRequest(Context context, JSONObject lease, String action, Listener listener) {
        this(context, lease, action, listener, NativeClient::async);
    }
    WorkspaceExecutionRequest(Context context, JSONObject lease, String action, Listener listener, Transport transport) {
        this.lease = copy(lease); this.action = action; this.listener = listener;
        this.transport = transport;
        preferences = context.getSharedPreferences("workspace-interactions", Context.MODE_PRIVATE);
        key = identity(lease) + "." + action;
        synchronized (RECEIPT_LOCK) {
            retainedPreferences = preferences;
            retain(preferences, key, null, System.currentTimeMillis());
        }
    }

    /** Under RECEIPT_LOCK: stage writes and the entire eviction batch in one durable commit. */
    private static boolean retain(SharedPreferences preferences, String keepKey, JSONObject write, long now) {
        Map<String, Object> values = new HashMap<>(preferences.getAll());
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = write != null;
        if (write != null) {
            String value = write.toString();
            values.put(keepKey, value); values.put(keepKey + "." + write.optString("requestId"), value);
            editor.putString(keepKey, value).putString(keepKey + "." + write.optString("requestId"), value);
        }
        Map<String, Long> times = new HashMap<>(), sizes = new HashMap<>();
        long bytes = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!(entry.getValue() instanceof String value)) continue;
            try {
                JSONObject record = new JSONObject(value), request = record.optJSONObject("request");
                String action = record.optString("action"), id = record.optString("requestId");
                if (request == null || id.isEmpty() || !(action.equals("returnAndResume") || action.equals("addRequirement"))) continue;
                String base = identity(request) + "." + action;
                if (!entry.getKey().equals(base) && !entry.getKey().equals(base + "." + id)) continue;
                if (!record.has("retainedAt")) {
                    JSONObject receipt = record.optJSONObject("receipt");
                    record.put("retainedAt", receipt == null ? now : receipt.optLong("acceptedAt", receipt.optLong("updatedAt", now)));
                    value = record.toString(); editor.putString(entry.getKey(), value); changed = true;
                }
                // A hidden requirement editor may still hold the accepted draft after its callback stopped.
                boolean dedup = entry.getKey().equals(base) && action.equals("addRequirement")
                        && record.optString("status").equals("accepted") && !record.optString("draftRevision").isEmpty()
                        && record.optString("draftRevision").equals(values.get(identity(request) + ".requirementDraft.revision"));
                if (entry.getKey().equals(keepKey) || unresolved(record) || dedup) continue;
                long size = 64L + entry.getKey().getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length;
                times.put(entry.getKey(), record.optLong("retainedAt", now)); sizes.put(entry.getKey(), size); bytes += size;
            } catch (JSONException invalid) { /* Unrecognized data is not safe to evict. */ }
        }
        List<String> eligible = new ArrayList<>(times.keySet());
        eligible.sort(Comparator.<String>comparingLong(times::get).thenComparing(key -> key));
        if (!eligible.isEmpty() && (eligible.size() >= 200 || bytes >= 2_000_000 || now - times.get(eligible.get(0)) >= 7 * DAY_MS)) {
            int remaining = eligible.size();
            for (String key : eligible) {
                if (remaining <= 120 && bytes <= 1_200_000 && now - times.get(key) < 5 * DAY_MS) break;
                editor.remove(key); changed = true; remaining--; bytes -= sizes.get(key);
            }
        }
        return !changed || editor.commit();
    }

    static String identity(JSONObject lease) {
        return lease.optString("threadId") + "." + lease.optString("screenKey") + "." + lease.optString("generation");
    }
    JSONObject receipt() {
        try { return new JSONObject(preferences.getString(key, "{}")); }
        catch (Exception invalid) { return new JSONObject(); }
    }
    static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
    }
    void start() { active = true; ++generation; listener.changed(receipt()); if (unresolved(receipt())) refresh(); }
    void stop() { active = false; ++generation; main.removeCallbacks(poll); }
    static boolean unresolved(JSONObject receipt) {
        String status = receipt.optString("status");
        return status.equals("pending") || status.equals("unknown") || receipt.optBoolean("requiresReconciliation");
    }
    boolean busy() { return fetching || unresolved(receipt()); }

    void submit(String text) { submit(text, ""); }
    void submit(String text, String draftRevision) {
        final String before;
        final JSONObject previous;
        synchronized (RECEIPT_LOCK) {
            before = preferences.getString(key, "{}");
            previous = receipt();
        }
        if (unresolved(previous)) { refresh(); return; }
        if (acceptedDraft(previous, draftRevision)) { listener.changed(previous); return; }
        if (fetching) return;
        fetching = true;
        final int token = generation;
        try {
            transport.send(WorkspaceUi.request("list", null), new NativeClient.Callback() {
                public void ready(JSONObject result) {
                    fetching = false;
                    if (!active || token != generation) return;
                    if (!before.equals(preferences.getString(key, "{}"))) { refresh(); return; }
                    JSONObject screen = null; JSONArray screens = result.optJSONArray("screens");
                    if (screens != null) for (int i = 0; i < screens.length(); i++)
                        if (WorkspaceUi.same(lease, screens.optJSONObject(i))) { screen = screens.optJSONObject(i); break; }
                    if (screen == null || screen.optLong("controlRevision", -1) < 0) {
                        transientFailure("SCREEN_GENERATION_CHANGED"); return;
                    }
                    String owner = screen.optString("controlOwner");
                    if (action.equals("addRequirement") && (owner.equals("user") || owner.equals("paused")
                            || owner.equals("stopping") || screen.optBoolean("resumeRequired"))) {
                        transientFailure("RETURN_AND_CONTINUE_FIRST"); return;
                    }
                    try {
                        JSONObject record = claim(screen, text, draftRevision, before);
                        // Another surface already claimed this attempt, even if its
                        // native acknowledgement arrived before this catalog read.
                        if (record == null) { refresh(); return; }
                        listener.changed(record);
                        dispatch(record.getJSONObject("request"), record.getString("requestId"));
                    } catch (java.io.IOException blocked) { transientFailure(blocked.getMessage()); }
                    catch (Exception invalid) { transientFailure("INVALID_REQUEST"); }
                }
                public void failed(String message) { fetching = false; if (active && token == generation) transientFailure(message); }
            });
        } catch (Exception invalid) { fetching = false; transientFailure("INVALID_REQUEST"); }
    }

    private boolean acceptedDraft(JSONObject record, String draftRevision) {
        return action.equals("addRequirement") && draftRevision != null && !draftRevision.isEmpty()
                && draftRevision.equals(record.optString("draftRevision"))
                && record.optString("status").equals("accepted");
    }

    private JSONObject claim(JSONObject screen, String text, String draftRevision, String before) throws Exception {
        synchronized (RECEIPT_LOCK) {
            JSONObject latest = receipt();
            if (!before.equals(preferences.getString(key, "{}")) || unresolved(latest)
                    || acceptedDraft(latest, draftRevision)) return null;
            if (action.equals("addRequirement")) {
                String draftKey = identity(lease) + ".requirementDraft";
                if (draftRevision == null || draftRevision.isEmpty()
                        || !draftRevision.equals(preferences.getString(draftKey + ".revision", ""))
                        || text == null || !text.equals(preferences.getString(draftKey, "").trim()))
                    throw new java.io.IOException("WORKSPACE_DRAFT_SUPERSEDED");
            }
            JSONObject request = WorkspaceUi.request(action, lease)
                    .put("controlRevision", screen.getLong("controlRevision"))
                    .put("requestId", UUID.randomUUID().toString());
            if (action.equals("addRequirement")) request.put("text", text);
            JSONObject record = new JSONObject().put("requestId", request.getString("requestId"))
                    .put("action", action).put("status", "pending").put("text", text == null ? "" : text)
                    .put("request", request).put("draftRevision", draftRevision).put("retainedAt", System.currentTimeMillis());
            if (!retain(preferences, key, record, System.currentTimeMillis())) throw new java.io.IOException("RECEIPT_NOT_SAVED");
            return record;
        }
    }

    private void dispatch(JSONObject request, String requestId) {
        fetching = true;
        transport.send(request, new NativeClient.Callback() {
            public void ready(JSONObject result) { fetching = false; reconcile(requestId, result); }
            public void failed(String message) {
                fetching = false;
                // The write could have reached the broker. Persist uncertainty and query the same ID.
                try { reconcile(requestId, new JSONObject().put("requestId", requestId)
                        .put("status", "unknown").put("requiresReconciliation", true).put("error", message)); }
                catch (Exception impossible) { throw new IllegalStateException(impossible); }
            }
        });
    }
    void refresh() {
        if (!active || fetching) return;
        JSONObject record = receipt(); String id = record.optString("requestId");
        if (id.isEmpty() || !unresolved(record)) { listener.changed(record); return; }
        fetching = true;
        try {
            transport.send(WorkspaceUi.request("executionStatus", lease).put("requestId", id), new NativeClient.Callback() {
                public void ready(JSONObject result) { fetching = false; reconcile(id, result); }
                public void failed(String message) {
                    fetching = false;
                    if (active) {
                        JSONObject latest;
                        synchronized (RECEIPT_LOCK) {
                            latest = receipt();
                            if (!id.equals(latest.optString("requestId")) || !unresolved(latest)) return;
                            try { latest.put("status", "unknown").put("requiresReconciliation", true).put("error", message); }
                            catch (Exception ignored) {}
                            retain(preferences, key, latest, System.currentTimeMillis());
                        }
                        listener.changed(latest);
                    }
                }
            });
        } catch (Exception invalid) { fetching = false; transientFailure("INVALID_REQUEST"); }
    }
    private void reconcile(String id, JSONObject result) {
        JSONObject record;
        synchronized (RECEIPT_LOCK) {
            record = receipt();
            if (!id.equals(record.optString("requestId")) || !id.equals(result.optString("requestId"))) return;
            boolean wasUnresolved = unresolved(record);
            // A delayed read must not regress a receipt already accepted by the native runtime.
            if (!record.optString("status").equals("accepted")) try {
                record.put("receipt", result).put("status", result.optString("status", "unknown"))
                        .put("requiresReconciliation", result.optBoolean("requiresReconciliation"));
                if (result.has("error")) record.put("error", result.opt("error")); else record.remove("error");
                if (wasUnresolved && !unresolved(record)) record.put("retainedAt", System.currentTimeMillis());
                retain(preferences, key, record, System.currentTimeMillis());
            } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        if (!active) return;
        listener.changed(record);
        main.removeCallbacks(poll);
        if (record.optString("status").equals("pending")) main.postDelayed(poll, 750);
        // Unknown is visible and has an explicit Check status action; it is not replayed.
    }
    private void transientFailure(String error) {
        if (!active) return;
        try { listener.changed(new JSONObject().put("action", action).put("status", "failed").put("error", error)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
