package org.agentos.assistant;

import android.content.SharedPreferences;
import org.json.*;
import java.io.IOException;
import java.util.UUID;

/** Composer overrides belong to one conversation; settings-page selections remain defaults. */
final class ComposerPreferences {
    private static final java.util.Set<String> pendingSteers = new java.util.HashSet<>();
    static String key(String thread, String field) { return "composer." + thread + "." + field; }
    static String get(SharedPreferences prefs, String thread, String field, String fallback) {
        return prefs.getString(key(thread, field), prefs.getString(field, fallback));
    }
    static void set(SharedPreferences prefs, String thread, String field, String value) {
        prefs.edit().putString(key(thread, field), value).apply();
    }
    static JSONArray attachments(SharedPreferences prefs, String thread) {
        try { return new JSONArray(prefs.getString(key(thread, "attachments"), "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }
    static synchronized void saveDraft(SharedPreferences prefs, String thread, String text) {
        if (thread.isEmpty() || text.equals(prefs.getString("draft-" + thread, ""))) return;
        prefs.edit().putString("draft-" + thread, text)
                .putString(key(thread, "draftRevision"), UUID.randomUUID().toString()).apply();
    }
    static synchronized ComposerDraft draft(SharedPreferences prefs, String thread) {
        return new ComposerDraft(thread, prefs.getString(key(thread, "draftRevision"), ""),
                prefs.getString("draft-" + thread, ""), attachments(prefs, thread).toString());
    }
    /** Queue one durable Home request for the Agent; it is consumed exactly once when sent. */
    static synchronized boolean prepareHome(SharedPreferences prefs, String text, String thread,
            String requestId, boolean forceNew) throws Exception {
        if (text == null || text.trim().isEmpty() || text.length() > 16000)
            throw new IllegalArgumentException("Enter a request of 1 to 16000 characters");
        if (thread != null && !thread.isEmpty() && !thread.matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("Invalid original conversation identifier");
        String id = requestId == null || requestId.isEmpty() ? UUID.randomUUID().toString() : requestId;
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid request identifier");
        JSONArray receipts;
        try { receipts = new JSONArray(prefs.getString("home-handoff-receipts", "[]")); }
        catch (JSONException invalid) { receipts = new JSONArray(); }
        for (int i = 0; i < receipts.length(); i++) if (id.equals(receipts.optString(i))) return false;
        String pending = prefs.getString("home-pending-id", "");
        if (!pending.isEmpty()) {
            if (id.equals(pending)) return true;
            throw new IllegalStateException("A request is waiting to be sent. Return to Agent to handle it first");
        }
        if (!prefs.edit().putString("home-pending-id", id)
                .putString("home-pending-text", text.trim())
                .putString("home-pending-thread", thread == null ? "" : thread)
                .putBoolean("home-pending-new-thread", forceNew).commit())
            throw new IllegalStateException("Request was not saved. Retry");
        return true;
    }
    enum HomeConsumption { NOT_APPLIED, CONSUMED, FAILED }
    static synchronized HomeConsumption consumeHome(SharedPreferences prefs, String thread, String id) {
        if (id.isEmpty() || !id.equals(prefs.getString(key(thread, "homeApplied"), "")))
            return HomeConsumption.NOT_APPLIED;
        if (!id.equals(prefs.getString("home-pending-id", ""))) return HomeConsumption.CONSUMED;
        String target=prefs.getString("home-pending-thread","");
        if(!target.isEmpty()&&!target.equals(thread))return HomeConsumption.NOT_APPLIED;
        JSONArray delivered;
        try{delivered=new JSONArray(prefs.getString("home-handoff-receipts","[]"));}catch(JSONException invalid){delivered=new JSONArray();}
        while(delivered.length()>=40)delivered.remove(0);
        delivered.put(id);
        // The text was copied into the immutable draft before this one-shot handoff is consumed.
        return prefs.edit().remove("home-pending-id").remove("home-pending-text")
                .remove("home-pending-thread").remove("home-pending-new-thread")
                .putString("home-handoff-receipts",delivered.toString()).commit()
                ? HomeConsumption.CONSUMED : HomeConsumption.FAILED;
    }
    static synchronized boolean applyHome(SharedPreferences prefs, String thread, String id, String text) {
        String target=prefs.getString("home-pending-thread","");
        if(!target.isEmpty()&&!target.equals(thread))return false;
        String applied = key(thread, "homeApplied");
        if (id.equals(prefs.getString(applied, "")))
            return prefs.edit().putString(applied, id).commit(); // Retry a previous disk-write failure without duplicating the stash.
        ComposerDraft current = draft(prefs, thread);
        SharedPreferences.Editor editor = prefs.edit();
        if (!current.isEmpty() && (!current.text.equals(text) || !current.attachments.equals("[]"))) {
            try {
                JSONArray saved = failures(prefs, thread);
                saved.put(newReceipt(current).put("status", "saved-draft"));
                editor.putString(key(thread, "failedDeliveries"), saved.toString());
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        // Preserve the old editor and install Home's text in one durable write.
        return editor.putString("draft-" + thread, text)
                .putString(key(thread, "draftRevision"), UUID.randomUUID().toString())
                .remove(key(thread, "attachments")).putString(applied, id).commit();
    }
    private static JSONObject receipt(SharedPreferences prefs, String thread) {
        return receipt(prefs, thread, "delivery");
    }
    private static JSONObject receipt(SharedPreferences prefs, String thread, String field) {
        try { return new JSONObject(prefs.getString(key(thread, field), "{}")); }
        catch (JSONException ignored) { return new JSONObject(); }
    }
    private static ComposerDraft sentDraft(String thread, JSONObject receipt) {
        return new ComposerDraft(thread, receipt.optString("revision"), receipt.optString("text"),
                receipt.optJSONArray("attachments") == null ? "[]" : receipt.optJSONArray("attachments").toString());
    }
    private static JSONObject newReceipt(ComposerDraft draft) throws JSONException {
        String token = UUID.randomUUID().toString();
        return new JSONObject().put("token", token).put("revision", draft.revision)
                .put("text", draft.text).put("attachments", new JSONArray(draft.attachments))
                .put("status", "sending").put("taskId", "");
    }
    static synchronized String beginDelivery(SharedPreferences prefs, ComposerDraft draft) throws Exception {
        if (receipt(prefs, draft.thread).has("token")) throw new IOException("The previous message is unconfirmed");
        JSONObject receipt = newReceipt(draft);
        if (!prefs.edit().putString(key(draft.thread, "delivery"), receipt.toString()).commit())
            throw new IOException("Could not save pending content");
        return receipt.getString("token");
    }
    static synchronized void queued(SharedPreferences prefs, String thread, String token, String task) throws Exception {
        JSONObject receipt = receipt(prefs, thread);
        if (!token.equals(receipt.optString("token"))) throw new IOException("Send record changed");
        receipt.put("taskId", task).put("status", "queued");
        // Persist the recovery receipt and task pointer before TaskRequests publishes ready.
        if (!prefs.edit().putString(key(thread, "delivery"), receipt.toString())
                .putString("task-" + thread, task).commit()) throw new IOException("Could not save send record");
    }
    static synchronized void deliveryFailed(SharedPreferences prefs, String thread, String token) {
        JSONObject receipt = receipt(prefs, thread);
        if (!token.equals(receipt.optString("token"))) return;
        try { receipt.put("status", "failed"); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        prefs.edit().putString(key(thread, "delivery"), receipt.toString()).commit();
        preserveFailed(prefs, thread, "delivery", receipt);
    }
    static synchronized void acknowledge(SharedPreferences prefs, ComposerDraft sent) {
        if (!sent.matches(draft(prefs, sent.thread))) return;
        prefs.edit().putString("draft-" + sent.thread, "")
                .putString(key(sent.thread, "draftRevision"), UUID.randomUUID().toString())
                .remove(key(sent.thread, "attachments")).apply();
    }
    /** Reconcile a durable send receipt with the existing TaskBroker status; never resubmit. */
    static synchronized void reconcile(SharedPreferences prefs, String thread, JSONObject task, boolean submitting) {
        JSONObject receipt = receipt(prefs, thread);
        if (!receipt.has("token")) return;
        String state = receipt.optString("status"), taskId = receipt.optString("taskId");
        if (!state.equals("failed") && task != null && !taskId.isEmpty() && taskId.equals(task.optString("id"))) {
            String actual = task.optString("state");
            if (actual.equals("completed") || actual.equals("cancelled")) {
                acknowledge(prefs, sentDraft(thread, receipt));
                prefs.edit().remove(key(thread, "delivery")).apply();
                return;
            }
            if (!task.optBoolean("published")) {
                if (submitting) return; // A receipt alone is not acceptance by the broker.
                state = "failed";
            }
            else if (actual.equals("failed") || actual.equals("needs_user")) state = "failed";
            else if (actual.equals("queued") || actual.equals("running")) state = "queued";
            try { receipt.put("turnStarted", task.optBoolean("turnStarted")); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        } else if (state.equals("sending") && !submitting) state = "failed";
        try { receipt.put("status", state); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        prefs.edit().putString(key(thread, "delivery"), receipt.toString()).apply();
        if (state.equals("queued") && task != null && taskId.equals(task.optString("id"))
                && task.optBoolean("published")) acknowledge(prefs, sentDraft(thread, receipt));
        else if (state.equals("failed")) preserveFailed(prefs, thread, "delivery", receipt);
    }
    static synchronized String beginSteer(SharedPreferences prefs, ComposerDraft draft) throws Exception {
        reconcileSteer(prefs, draft.thread);
        if (receipt(prefs, draft.thread, "steerDelivery").has("token")) throw new IOException("Follow-up instruction is unconfirmed");
        JSONObject receipt = newReceipt(draft).put("turnStarted", true);
        if (!prefs.edit().putString(key(draft.thread, "steerDelivery"), receipt.toString()).commit())
            throw new IOException("Could not save follow-up instruction");
        String token = receipt.getString("token");
        pendingSteers.add(token);
        return token;
    }
    static synchronized boolean isSteering(SharedPreferences prefs, String thread) {
        return pendingSteers.contains(receipt(prefs, thread, "steerDelivery").optString("token"));
    }
    static synchronized void finishSteer(SharedPreferences prefs, String thread, String token, boolean accepted) {
        pendingSteers.remove(token);
        JSONObject receipt = receipt(prefs, thread, "steerDelivery");
        if (!token.equals(receipt.optString("token"))) return;
        if (accepted) {
            acknowledge(prefs, sentDraft(thread, receipt));
            prefs.edit().remove(key(thread, "steerDelivery")).apply();
        } else preserveFailed(prefs, thread, "steerDelivery", receipt);
    }
    static synchronized boolean reconcileSteer(SharedPreferences prefs, String thread) {
        JSONObject receipt = receipt(prefs, thread, "steerDelivery");
        if (receipt.has("token") && !isSteering(prefs, thread)) {
            preserveFailed(prefs, thread, "steerDelivery", receipt);
            return true;
        }
        return false;
    }
    private static JSONArray failures(SharedPreferences prefs, String thread) {
        try { return new JSONArray(prefs.getString(key(thread, "failedDeliveries"), "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }
    private static void restore(SharedPreferences.Editor editor, ComposerDraft sent) {
        editor.putString("draft-" + sent.thread, sent.text)
                .putString(key(sent.thread, "draftRevision"), UUID.randomUUID().toString())
                .putString(key(sent.thread, "attachments"), sent.attachments);
    }
    private static void preserveFailed(SharedPreferences prefs, String thread, String field, JSONObject receipt) {
        ComposerDraft sent = sentDraft(thread, receipt), current = draft(prefs, thread);
        SharedPreferences.Editor editor = prefs.edit().remove(key(thread, field));
        if (sent.matches(current)) { /* The original draft was never cleared. */ }
        else if (sent.canRestoreInto(current)) restore(editor, sent);
        else {
            JSONArray failures = failures(prefs, thread);
            failures.put(receipt);
            editor.putString(key(thread, "failedDeliveries"), failures.toString());
        }
        editor.apply();
    }
    static synchronized boolean hasFailedDelivery(SharedPreferences prefs, String thread) {
        return failures(prefs, thread).length() > 0;
    }
    static synchronized String recoveryLabel(SharedPreferences prefs, String thread) {
        JSONObject first = failures(prefs, thread).optJSONObject(0);
        if (first != null && first.optString("status").equals("saved-draft")) return "Restore saved draft";
        if (first != null && first.optString("status").equals("sending")) return "Restore unconfirmed message";
        return first != null && first.optBoolean("turnStarted") ? "Restore failed message" : "Restore unsent content";
    }
    static synchronized boolean restoreFailed(SharedPreferences prefs, String thread) {
        JSONArray failures = failures(prefs, thread);
        JSONObject receipt = failures.optJSONObject(0);
        if (receipt == null) return false;
        ComposerDraft sent = sentDraft(thread, receipt), current = draft(prefs, thread);
        if (!sent.canRestoreInto(current)) return false;
        SharedPreferences.Editor editor = prefs.edit();
        restore(editor, sent);
        failures.remove(0);
        editor.putString(key(thread, "failedDeliveries"), failures.toString()).apply();
        return true;
    }
    static void apply(SharedPreferences prefs, String thread, JSONObject request) throws JSONException {
        for (String name : new String[]{"model", "effort", "multiAgentMode"}) {
            String value = get(prefs, thread, name, "");
            if (!value.isEmpty()) request.put(name, value);
        }
        request.put("permissionMode", get(prefs, thread, "permissionMode", "full"));
        String tier = get(prefs, thread, "serviceTier", "");
        request.put("serviceTier", tier.isEmpty() ? JSONObject.NULL : tier);
        request.put("attachments", attachments(prefs, thread));
    }
}
