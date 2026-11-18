package org.agentos.assistant;

import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.*;

/** Runs production draft transitions against an in-memory store; no app prefs, account or device UI. */
public final class ComposerPreferencesTest {
    private static final String THREAD = "test-thread", OTHER = "other-thread";
    private static final String ATTACHMENT = "[{\"path\":\"/fixture/one\",\"name\":\"one.txt\"}]";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        unchangedAndNewerDrafts();
        queueFailureRecovery();
        preserveSeveralFailures();
        publicationAndRestart();
        completionAndCancellation();
        steerIsolationAndRestart();
        homeHandoffConsumption();
        pendingHomeSurvivesManualSteer();
        homePreservesDrafts();
        System.out.println("PASS ComposerPreferencesTest: " + assertions + " assertions; in-memory preferences only");
    }

    private static void unchangedAndNewerDrafts() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        ComposerDraft sent = edit(prefs, THREAD, "first", ATTACHMENT);
        edit(prefs, OTHER, "other thread", "[]");
        ComposerPreferences.acknowledge(prefs, sent);
        check(ComposerPreferences.draft(prefs, THREAD).isEmpty(), "unchanged accepted draft clears text and attachments");
        check(ComposerPreferences.draft(prefs, OTHER).text.equals("other thread"), "source acknowledgement cannot clear another thread");
        sent = edit(prefs, THREAD, "first", ATTACHMENT);
        edit(prefs, THREAD, "temporary", ATTACHMENT);
        edit(prefs, THREAD, "first", ATTACHMENT);
        ComposerPreferences.acknowledge(prefs, sent);
        check(ComposerPreferences.draft(prefs, THREAD).text.equals("first"), "retyped identical text has a new revision");
        sent = ComposerPreferences.draft(prefs, THREAD);
        edit(prefs, THREAD, "first", "[]");
        ComposerPreferences.acknowledge(prefs, sent);
        check(ComposerPreferences.draft(prefs, THREAD).text.equals("first"), "attachment edit protects the draft");
    }

    private static void queueFailureRecovery() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        ComposerDraft sent = edit(prefs, THREAD, "send this", ATTACHMENT);
        String token = ComposerPreferences.beginDelivery(prefs, sent);
        check(prefs.contains(ComposerPreferences.key(THREAD, "delivery")), "receipt exists before queue publication");
        ComposerPreferences.queued(prefs, THREAD, token, "100");
        ComposerPreferences.reconcile(prefs, THREAD, task("100", "queued", true), false);
        check(ComposerPreferences.draft(prefs, THREAD).isEmpty(), "published queue clears unchanged editor");
        check(prefs.contains(ComposerPreferences.key(THREAD, "delivery")), "queue does not discard recovery receipt");
        ComposerPreferences.reconcile(prefs, THREAD, task("100", "failed", true), false);
        check(ComposerPreferences.draft(prefs, THREAD).text.equals(sent.text), "upstream failure restores text");
        check(ComposerPreferences.draft(prefs, THREAD).attachments.equals(sent.attachments), "upstream failure restores attachment metadata");
        check(!prefs.contains(ComposerPreferences.key(THREAD, "delivery")), "failed active receipt is consumed");
        ComposerPreferences.reconcile(prefs, THREAD, task("100", "queued", true), false);
        check(ComposerPreferences.draft(prefs, THREAD).text.equals(sent.text), "late queued status cannot erase restored draft");
    }

    private static void preserveSeveralFailures() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        ComposerDraft first = edit(prefs, THREAD, "first failed", ATTACHMENT);
        String token = ComposerPreferences.beginDelivery(prefs, first);
        ComposerPreferences.queued(prefs, THREAD, token, "101");
        ComposerPreferences.reconcile(prefs, THREAD, task("101", "running", true), false);
        edit(prefs, THREAD, "new draft", "[]");
        ComposerPreferences.reconcile(prefs, THREAD, task("101", "failed", true).put("turnStarted", true), false);
        check(ComposerPreferences.draft(prefs, THREAD).text.equals("new draft"), "failure cannot overwrite a newer draft");
        check(ComposerPreferences.hasFailedDelivery(prefs, THREAD), "old failed content remains recoverable");
        check(ComposerPreferences.recoveryLabel(prefs, THREAD).equals("Restore failed message"), "started turn is not called unsent");
        check(!ComposerPreferences.restoreFailed(prefs, THREAD), "restore refuses to overwrite current draft");
        ComposerDraft second = ComposerPreferences.draft(prefs, THREAD);
        token = ComposerPreferences.beginDelivery(prefs, second);
        ComposerPreferences.queued(prefs, THREAD, token, "102");
        ComposerPreferences.reconcile(prefs, THREAD, task("102", "queued", true), false);
        edit(prefs, THREAD, "third draft", "[]");
        ComposerPreferences.reconcile(prefs, THREAD, task("102", "needs_user", true), false);
        check(ComposerPreferences.draft(prefs, THREAD).text.equals("third draft"), "second failure also preserves current draft");
        edit(prefs, THREAD, "", "[]");
        check(ComposerPreferences.restoreFailed(prefs, THREAD), "first failed message restores into empty editor");
        check(ComposerPreferences.draft(prefs, THREAD).text.equals("first failed"), "sending again retained the first failed message");
        check(ComposerPreferences.draft(prefs, THREAD).attachments.equals(first.attachments), "first failed attachments survive another send");
        check(ComposerPreferences.hasFailedDelivery(prefs, THREAD), "next failed message remains recoverable");
        edit(prefs, THREAD, "", "[]");
        check(ComposerPreferences.restoreFailed(prefs, THREAD), "second failed message restores");
        check(ComposerPreferences.draft(prefs, THREAD).text.equals("new draft"), "recovery order preserves each message");
        check(!ComposerPreferences.hasFailedDelivery(prefs, THREAD), "restored entries are consumed once");
    }

    private static void publicationAndRestart() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        ComposerDraft sent = edit(prefs, THREAD, "before ready", ATTACHMENT);
        String token = ComposerPreferences.beginDelivery(prefs, sent);
        ComposerPreferences.queued(prefs, THREAD, token, "103");
        ComposerPreferences.reconcile(prefs, THREAD, task("103", "queued", false), true);
        check(sent.matches(ComposerPreferences.draft(prefs, THREAD)), "persisting a receipt before ready must not clear input");
        ComposerPreferences.reconcile(prefs, THREAD, task("103", "queued", false), false);
        check(sent.matches(ComposerPreferences.draft(prefs, THREAD)), "missing ready after restart retains input");
        check(!prefs.contains(ComposerPreferences.key(THREAD, "delivery")), "unpublished attempt does not remain pending forever");
        token = ComposerPreferences.beginDelivery(prefs, sent);
        ComposerPreferences.queued(prefs, THREAD, token, "104");
        ComposerPreferences.deliveryFailed(prefs, THREAD, token);
        ComposerPreferences.reconcile(prefs, THREAD, task("104", "queued", false), false);
        check(sent.matches(ComposerPreferences.draft(prefs, THREAD)), "ready-write failure cannot be resurrected by stale queued state");
        token = ComposerPreferences.beginDelivery(prefs, sent);
        ComposerPreferences.reconcile(prefs, THREAD, null, false);
        check(sent.matches(ComposerPreferences.draft(prefs, THREAD)), "restart before task creation retains original input");
        check(!prefs.contains(ComposerPreferences.key(THREAD, "delivery")), "interrupted prequeue receipt is resolved");
        token = ComposerPreferences.beginDelivery(prefs, sent);
        ComposerPreferences.queued(prefs, THREAD, token, "105");
        // No ready callback is invoked: the process was recreated after publication.
        MemoryPreferences restarted = new MemoryPreferences(prefs);
        ComposerPreferences.reconcile(restarted, THREAD, task("105", "queued", true), false);
        check(ComposerPreferences.draft(restarted, THREAD).isEmpty(), "restart reconciles a published queue without callback");
        ComposerPreferences.reconcile(restarted, THREAD, task("105", "failed", true), false);
        check(ComposerPreferences.draft(restarted, THREAD).text.equals(sent.text), "restart still recovers later upstream failure");
    }

    private static void completionAndCancellation() throws Exception {
        for (String terminal : new String[]{"completed", "cancelled"}) {
            MemoryPreferences prefs = new MemoryPreferences();
            ComposerDraft sent = edit(prefs, THREAD, "submitted", ATTACHMENT);
            String token = ComposerPreferences.beginDelivery(prefs, sent);
            ComposerPreferences.queued(prefs, THREAD, token, "106");
            ComposerPreferences.reconcile(prefs, THREAD, task("106", terminal, true), false);
            check(ComposerPreferences.draft(prefs, THREAD).isEmpty(), terminal + " does not put submitted content back into editor");
            check(!prefs.contains(ComposerPreferences.key(THREAD, "delivery")), terminal + " consumes receipt");
            check(!ComposerPreferences.hasFailedDelivery(prefs, THREAD), terminal + " creates no resend entry");
            ComposerPreferences.reconcile(prefs, THREAD, task("106", "failed", true), false);
            check(ComposerPreferences.draft(prefs, THREAD).isEmpty(), "late status after " + terminal + " does not restore or resend");
        }
    }

    private static void steerIsolationAndRestart() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        ComposerDraft initial = edit(prefs, THREAD, "initial request", "[]");
        String delivery = ComposerPreferences.beginDelivery(prefs, initial);
        ComposerPreferences.queued(prefs, THREAD, delivery, "107");
        ComposerPreferences.reconcile(prefs, THREAD, task("107", "running", true), false);
        ComposerDraft sent = edit(prefs, THREAD, "append instruction", ATTACHMENT);
        String steer = ComposerPreferences.beginSteer(prefs, sent);
        check(ComposerPreferences.isSteering(prefs, THREAD), "in-flight steer is tracked across view recreation");
        check(!ComposerPreferences.reconcileSteer(prefs, THREAD), "live steer is not treated as interrupted");
        edit(prefs, THREAD, "newer input", "[]");
        edit(prefs, OTHER, "other editor", "[]");
        ComposerPreferences.finishSteer(prefs, THREAD, steer, true);
        check(ComposerPreferences.draft(prefs, THREAD).text.equals("newer input"), "late accepted steer preserves newer input");
        check(ComposerPreferences.draft(prefs, OTHER).text.equals("other editor"), "late steer cannot clear another conversation");
        check(prefs.contains(ComposerPreferences.key(THREAD, "delivery")), "steer does not overwrite original task receipt");
        check(!ComposerPreferences.isSteering(prefs, THREAD), "accepted steer clears pending state");
        sent = ComposerPreferences.draft(prefs, THREAD);
        steer = ComposerPreferences.beginSteer(prefs, sent);
        edit(prefs, THREAD, "even newer", "[]");
        ComposerPreferences.finishSteer(prefs, THREAD, steer, false);
        check(ComposerPreferences.draft(prefs, THREAD).text.equals("even newer"), "failed steer preserves newer input");
        check(ComposerPreferences.hasFailedDelivery(prefs, THREAD), "failed steer snapshot remains recoverable");
        sent = edit(prefs, THREAD, "interrupted steer", ATTACHMENT);
        steer = ComposerPreferences.beginSteer(prefs, sent);
        MemoryPreferences restarted = new MemoryPreferences(prefs);
        JSONObject interrupted = new JSONObject(restarted.getString(ComposerPreferences.key(THREAD, "steerDelivery"), "{}"));
        // A fresh process has no live token. Give the persisted fixture an unknown token to model that boundary.
        restarted.edit().putString(ComposerPreferences.key(THREAD, "steerDelivery"), interrupted.put("token", "previous-process").toString()).apply();
        edit(restarted, THREAD, "", "[]");
        check(ComposerPreferences.reconcileSteer(restarted, THREAD), "restart identifies an unconfirmed steer");
        check(ComposerPreferences.draft(restarted, THREAD).text.equals(sent.text), "unconfirmed steer content recovers without sending");
        check(!restarted.contains(ComposerPreferences.key(THREAD, "steerDelivery")), "interrupted steer receipt resolves once");
        check(!ComposerPreferences.reconcileSteer(restarted, THREAD), "repeated snapshot cannot repeat recovery");
        ComposerPreferences.finishSteer(prefs, THREAD, steer, true);
    }

    private static void homeHandoffConsumption() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putString("home-pending-id", "home-one").putString("home-pending-text", "from Home").apply();
        check(ComposerPreferences.applyHome(prefs, THREAD, "home-one", "from Home"), "Home content is applied before consumption");
        check(ComposerPreferences.consumeHome(prefs, THREAD, "home-one") == ComposerPreferences.HomeConsumption.CONSUMED, "Home handoff can be consumed before queue or steer");
        check(!prefs.contains("home-pending-id") && !prefs.contains("home-pending-text"), "consumption removes the automatic retry trigger");
        check(ComposerPreferences.draft(prefs, THREAD).text.equals("from Home"), "consumption leaves the durable draft recoverable if sending fails");
        prefs.edit().putString("home-pending-id", "home-two").putString("home-pending-text", "new Home request").apply();
        check(ComposerPreferences.consumeHome(prefs, THREAD, "home-one") == ComposerPreferences.HomeConsumption.CONSUMED, "a stale handoff does not block the current draft");
        check(prefs.getString("home-pending-id", "").equals("home-two"), "stale callback cannot consume a newer Home request");
        check(prefs.getString("home-pending-text", "").equals("new Home request"), "newer Home contents remain intact");
    }

    private static void pendingHomeSurvivesManualSteer() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putString("home-pending-id", "waiting-home").putString("home-pending-text", "waiting request B").apply();
        ComposerDraft manual = edit(prefs, THREAD, "manual instruction C", ATTACHMENT);
        check(ComposerPreferences.consumeHome(prefs, THREAD, "waiting-home") == ComposerPreferences.HomeConsumption.NOT_APPLIED,
                "a manual instruction must not consume a Home request waiting for the active task");
        String steer = ComposerPreferences.beginSteer(prefs, manual);
        ComposerPreferences.finishSteer(prefs, THREAD, steer, true);
        check(ComposerPreferences.draft(prefs, THREAD).isEmpty(), "manual instruction can still be accepted normally");
        check(prefs.getString("home-pending-id", "").equals("waiting-home"), "manual steer preserves the pending Home identity");
        check(prefs.getString("home-pending-text", "").equals("waiting request B"), "manual steer preserves pending Home contents");
        check(ComposerPreferences.applyHome(prefs, THREAD, "waiting-home", "waiting request B"), "waiting Home request applies when the active task is done");
        check(ComposerPreferences.consumeHome(prefs, OTHER, "waiting-home") == ComposerPreferences.HomeConsumption.NOT_APPLIED,
                "another thread cannot consume a Home request it has not applied");
        check(prefs.contains("home-pending-id"), "cross-thread consume leaves the Home request pending");
        check(ComposerPreferences.consumeHome(prefs, THREAD, "waiting-home") == ComposerPreferences.HomeConsumption.CONSUMED,
                "the intended thread can consume the applied Home request");
        check(ComposerPreferences.draft(prefs, THREAD).text.equals("waiting request B"), "the Home request is available for its own send");
        check(!prefs.contains("home-pending-id"), "the applied Home request is consumed exactly once");
    }

    private static void homePreservesDrafts() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        ComposerDraft original = edit(prefs, THREAD, "unfinished draft", ATTACHMENT);
        check(ComposerPreferences.applyHome(prefs, THREAD, "home-one", "Home request"), "Home can atomically stash the existing editor");
        check(ComposerPreferences.draft(prefs, THREAD).text.equals("Home request"), "Home text becomes the current draft");
        check(ComposerPreferences.draft(prefs, THREAD).attachments.equals("[]"), "Home does not inherit the existing attachments");
        check(ComposerPreferences.recoveryLabel(prefs, THREAD).equals("Restore saved draft"), "stashed drafts have an accurate recovery label");
        check(!ComposerPreferences.restoreFailed(prefs, THREAD), "stash restoration must not overwrite the Home request");
        check(ComposerPreferences.applyHome(prefs, THREAD, "home-one", "Home request"), "repeated Home callback succeeds without restashing");
        edit(prefs, THREAD, "", "[]");
        check(ComposerPreferences.restoreFailed(prefs, THREAD), "the pre-Home draft remains recoverable");
        check(ComposerPreferences.draft(prefs, THREAD).text.equals(original.text), "the original unsent text survives Home submission");
        check(ComposerPreferences.draft(prefs, THREAD).attachments.equals(original.attachments), "the original attachments survive Home submission");
        check(!ComposerPreferences.hasFailedDelivery(prefs, THREAD), "duplicate Home callbacks create only one stash");
        check(ComposerPreferences.applyHome(prefs, THREAD, "home-one", "Home request"), "stale callback is idempotent after stash restoration");
        check(ComposerPreferences.draft(prefs, THREAD).text.equals(original.text), "stale Home callback does not overwrite a restored draft");
        check(ComposerPreferences.applyHome(prefs, THREAD, "home-two", original.text), "identical text with attachments is also safely stashed");
        check(ComposerPreferences.hasFailedDelivery(prefs, THREAD), "same-text attachments remain recoverable");
        check(ComposerPreferences.draft(prefs, THREAD).attachments.equals("[]"), "same-text Home request still does not inherit attachments");

        MemoryPreferences empty = new MemoryPreferences();
        check(ComposerPreferences.applyHome(empty, THREAD, "empty-home", "new"), "empty editor accepts Home");
        check(!ComposerPreferences.hasFailedDelivery(empty, THREAD), "empty editor does not create a pointless stash");
        edit(empty, THREAD, "same", "[]");
        check(ComposerPreferences.applyHome(empty, THREAD, "same-home", "same"), "identical text without attachments needs no stash");
        check(!ComposerPreferences.hasFailedDelivery(empty, THREAD), "identical content does not create a duplicate recovery entry");

        MemoryPreferences failed = new MemoryPreferences();
        ComposerDraft attempt = edit(failed, THREAD, "failed message", ATTACHMENT);
        String token = ComposerPreferences.beginDelivery(failed, attempt);
        ComposerPreferences.queued(failed, THREAD, token, "108");
        ComposerPreferences.reconcile(failed, THREAD, task("108", "queued", true), false);
        ComposerPreferences.reconcile(failed, THREAD, task("108", "failed", true), false);
        check(ComposerPreferences.applyHome(failed, THREAD, "after-failure", "Home after failure"), "Home can follow failure recovery");
        edit(failed, THREAD, "", "[]");
        check(ComposerPreferences.restoreFailed(failed, THREAD), "a just-restored failed message is preserved before Home overwrite");
        check(ComposerPreferences.draft(failed, THREAD).text.equals(attempt.text)
                && ComposerPreferences.draft(failed, THREAD).attachments.equals(attempt.attachments), "restored failure retains both text and attachments");

        MemoryPreferences diskFailure = new MemoryPreferences();
        edit(diskFailure, THREAD, "must be saved first", ATTACHMENT);
        diskFailure.failNextCommit = true;
        check(!ComposerPreferences.applyHome(diskFailure, THREAD, "disk-home", "Home text"), "failed persistence does not authorize the UI to overwrite or send");
        check(ComposerPreferences.applyHome(diskFailure, THREAD, "disk-home", "Home text"), "the same handoff can retry persistence");
        check(new org.json.JSONArray(diskFailure.getString(ComposerPreferences.key(THREAD, "failedDeliveries"), "[]")).length() == 1,
                "retrying a failed disk write does not duplicate the stash");
        edit(diskFailure, THREAD, "", "[]");
        check(ComposerPreferences.restoreFailed(diskFailure, THREAD), "original draft survives a write failure and retry");
        check(ComposerPreferences.draft(diskFailure, THREAD).text.equals("must be saved first"), "write retry preserves the original text");
    }

    private static ComposerDraft edit(MemoryPreferences prefs, String thread, String text, String attachments) {
        ComposerPreferences.saveDraft(prefs, thread, text);
        prefs.edit().putString(ComposerPreferences.key(thread, "attachments"), attachments).apply();
        return ComposerPreferences.draft(prefs, thread);
    }
    private static JSONObject task(String id, String state, boolean published) throws Exception {
        return new JSONObject().put("id", id).put("state", state).put("published", published);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        boolean failNextCommit;
        MemoryPreferences() {}
        MemoryPreferences(MemoryPreferences other) { values.putAll(other.values); }
        public Map<String, ?> getAll() { return new HashMap<>(values); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> fallback) { return (Set<String>) values.getOrDefault(key, fallback); }
        public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
        public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
        public float getFloat(String key, float fallback) { return (Float) values.getOrDefault(key, fallback); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public boolean contains(String key) { return values.containsKey(key); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        public Editor edit() { return new Editor() {
            final Map<String, Object> edits = new HashMap<>();
            boolean clear;
            public Editor putString(String key, String value) { edits.put(key, value); return this; }
            public Editor putStringSet(String key, Set<String> value) { edits.put(key, value); return this; }
            public Editor putInt(String key, int value) { edits.put(key, value); return this; }
            public Editor putLong(String key, long value) { edits.put(key, value); return this; }
            public Editor putFloat(String key, float value) { edits.put(key, value); return this; }
            public Editor putBoolean(String key, boolean value) { edits.put(key, value); return this; }
            public Editor remove(String key) { edits.put(key, null); return this; }
            public Editor clear() { clear = true; return this; }
            public void apply() { commit(); }
            public boolean commit() {
                if (clear) values.clear();
                for (Map.Entry<String, Object> edit : edits.entrySet()) {
                    if (edit.getValue() == null) values.remove(edit.getKey());
                    else values.put(edit.getKey(), edit.getValue());
                }
                // Android updates its in-process map even when a synchronous disk write fails.
                boolean success = !failNextCommit;
                failNextCommit = false;
                return success;
            }
        }; }
    }
}
