package org.agentos.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import org.json.*;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test APK only. Deterministic transport and private test preferences; no runtime/device actions. */
public final class WorkspaceExecutionTestActivity extends Activity {
    private int checks;
    private final List<WorkspaceExecutionRequest> clients = new ArrayList<>();
    private final JSONObject lease = makeLease();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Throwable failure = null;
        try {
            getSharedPreferences("workspace-interactions", MODE_PRIVATE).edit().clear().commit();
            duplicateAndUnknown();
            lifecycleAndLateRead();
            rejectedRetryAndRevision();
            scopeAndOwnership();
            sharedRequirementViews();
            competingSubmissions();
            supersededDrafts();
            staleRequirementView();
        } catch (Throwable error) { failure = error; }
        finally { for (WorkspaceExecutionRequest client : clients) client.stop(); }
        try (FileOutputStream out = openFileOutput("workspace-execution-tests.json", MODE_PRIVATE)) {
            JSONObject result = new JSONObject().put("passed", failure == null).put("assertions", checks)
                    .put("fixtureOnly", true).put("realRuntimeInvoked", false);
            if (failure != null) result.put("error", failure.toString());
            out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception reportFailure) { throw new IllegalStateException(reportFailure); }
    }

    private void duplicateAndUnknown() throws Exception {
        FakeTransport fake = new FakeTransport(); List<JSONObject> seen = new ArrayList<>();
        WorkspaceExecutionRequest client = client(lease, "addRequirement", fake, seen);
        saveDraft(lease, "Only stores with returns", "draft-1");
        client.start(); client.submit("Only stores with returns", "draft-1"); client.submit("Only stores with returns", "draft-1");
        check(fake.calls.size() == 1 && fake.calls.get(0).action().equals("list"), "double tap performs one fresh catalog read");
        fake.calls.get(0).callback.ready(catalog(lease, "agent", 8));
        check(fake.calls.size() == 2 && fake.calls.get(1).action().equals("addRequirement"), "one mutation after fresh lease validation");
        Call write = fake.calls.get(1); String id = write.request.getString("requestId");
        check(write.request.getLong("controlRevision") == 8, "mutation binds fresh control revision");
        check(client.receipt().getString("requestId").equals(id)
                && client.receipt().getJSONObject("request").getString("text").equals("Only stores with returns"),
                "exact mutation is durable before transport callback");
        check(client.receipt().getString("draftRevision").equals("draft-1"), "acceptance is tied to the submitted draft revision");
        client.submit("Only stores with returns", "draft-1");
        check(fake.calls.size() == 2, "pending repeated tap creates no second write");
        write.callback.failed("simulated connection loss");
        check(client.receipt().getString("status").equals("unknown"), "ambiguous delivery persists unknown");
        check(client.receipt().getString("requestId").equals(id), "unknown retains original operation id");
        client.stop();
        FakeTransport reopened = new FakeTransport();
        WorkspaceExecutionRequest restored = client(lease, "addRequirement", reopened, new ArrayList<>());
        restored.start();
        check(reopened.calls.size() == 1 && reopened.calls.get(0).action().equals("executionStatus"), "reopening unknown only queries receipt");
        JSONObject query = reopened.calls.get(0).request;
        check(query.getString("requestId").equals(id) && !query.has("text") && !query.has("controlRevision"), "status query cannot replay action payload");
        reopened.calls.get(0).callback.ready(receipt(id, "addRequirement", "accepted"));
        check(restored.receipt().getString("status").equals("accepted"), "server acceptance resolves persisted uncertainty");
        write.callback.ready(receipt(id, "addRequirement", "pending"));
        check(restored.receipt().getString("status").equals("accepted"), "late mutation callback cannot regress accepted receipt");
        restored.stop();
    }

    private void lifecycleAndLateRead() throws Exception {
        JSONObject second = copy(lease).put("screenKey", "task-bbbbbbbbbbbbbbbbbbbb");
        FakeTransport fake = new FakeTransport(); List<JSONObject> updates = new ArrayList<>();
        WorkspaceExecutionRequest before = client(second, "returnAndResume", fake, updates);
        before.start(); before.submit(""); fake.calls.get(0).callback.ready(catalog(second, "user", 12));
        Call write = fake.calls.get(1); String id = write.request.getString("requestId");
        int painted = updates.size(); before.stop();
        write.callback.ready(receipt(id, "returnAndResume", "pending"));
        check(updates.size() == painted && before.receipt().getString("status").equals("pending"), "detached callback persists receipt without painting old view");
        FakeTransport a = new FakeTransport(), b = new FakeTransport();
        WorkspaceExecutionRequest firstReader = client(second, "returnAndResume", a, new ArrayList<>());
        WorkspaceExecutionRequest secondReader = client(second, "returnAndResume", b, new ArrayList<>());
        firstReader.start(); secondReader.start();
        check(a.calls.get(0).action().equals("executionStatus") && b.calls.get(0).action().equals("executionStatus"), "both restored views are read-only reconciliation clients");
        b.calls.get(0).callback.ready(receipt(id, "returnAndResume", "accepted"));
        a.calls.get(0).callback.failed("late read failure");
        check(secondReader.receipt().getString("status").equals("accepted"), "late status error cannot overwrite accepted receipt");
        firstReader.stop(); secondReader.stop();
    }

    private void rejectedRetryAndRevision() throws Exception {
        JSONObject third = copy(lease).put("screenKey", "task-cccccccccccccccccccc");
        FakeTransport fake = new FakeTransport();
        WorkspaceExecutionRequest client = client(third, "returnAndResume", fake, new ArrayList<>());
        client.start(); client.submit(""); fake.calls.get(0).callback.ready(catalog(third, "paused", 20));
        Call first = fake.calls.get(1); String oldId = first.request.getString("requestId");
        first.callback.ready(receipt(oldId, "returnAndResume", "failed").put("requiresReconciliation", false));
        check(fake.calls.size() == 2, "confirmed failure does not automatically retry");
        client.submit(""); // Models the explicit Retry control, never an automatic callback.
        check(fake.calls.get(2).action().equals("list"), "explicit retry refreshes lease before creating an attempt");
        fake.calls.get(2).callback.ready(catalog(third, "paused", 23));
        Call retry = fake.calls.get(3);
        check(!retry.request.getString("requestId").equals(oldId) && retry.request.getLong("controlRevision") == 23,
                "explicit retry is a new action with the latest revision");
        String oldKey = WorkspaceExecutionRequest.identity(third) + ".returnAndResume." + oldId;
        check(new JSONObject(getSharedPreferences("workspace-interactions", MODE_PRIVATE).getString(oldKey, "{}")).getString("status").equals("failed"),
                "prior failed receipt remains available by operation id");
        retry.callback.ready(receipt(retry.request.getString("requestId"), "returnAndResume", "accepted"));
        client.stop();
    }

    private void scopeAndOwnership() throws Exception {
        JSONObject fourth = copy(lease).put("screenKey", "task-dddddddddddddddddddd");
        FakeTransport fake = new FakeTransport(); List<JSONObject> updates = new ArrayList<>();
        WorkspaceExecutionRequest client = client(fourth, "addRequirement", fake, updates);
        saveDraft(fourth, "Leave it on this page", "draft-a");
        client.start(); client.submit("Leave it on this page", "draft-a");
        fake.calls.get(0).callback.ready(catalog(fourth, "user", 4));
        check(fake.calls.size() == 1 && last(updates).optString("error").equals("RETURN_AND_CONTINUE_FIRST"),
                "user-owned screen rejects requirement before mutation and does not silently resume");
        client.submit("Leave it on this page", "draft-a");
        JSONObject stale = copy(fourth).put("generation", "22222222-2222-2222-2222-222222222222");
        fake.calls.get(1).callback.ready(catalog(stale, "agent", 5));
        check(fake.calls.size() == 2 && last(updates).optString("error").equals("SCREEN_GENERATION_CHANGED"),
                "different generation cannot receive requirement");
        check(client.receipt().optString("requestId").isEmpty(), "failed preflight creates no claimed execution receipt");
        client.stop();
        FakeTransport clean = new FakeTransport();
        WorkspaceExecutionRequest isolated = client(stale, "addRequirement", clean, new ArrayList<>());
        isolated.start(); check(clean.calls.isEmpty(), "new lease generation does not inherit old operation");
        isolated.stop();
    }

    private void sharedRequirementViews() throws Exception {
        JSONObject scope = copy(lease).put("screenKey", "task-eeeeeeeeeeeeeeeeeeee");
        FakeTransport fullTransport = new FakeTransport(), floatingTransport = new FakeTransport();
        WorkspaceRequirementView full = new WorkspaceRequirementView(this, scope, fullTransport);
        WorkspaceRequirementView floating = new WorkspaceRequirementView(this, scope, floatingTransport);
        WorkspaceRequirementView other = null;
        try {
            // Both production View instances exist before the floating editor writes. This is
            // the NEW_TASK reuse case that constructing a fresh View cannot catch.
            EditText fullInput = edit(full), floatingInput = edit(floating);
            String draft = "Keep this as a preview. Do not start a timer.";
            full.start(); full.stop();
            floating.start(); floatingInput.setText(draft);
            button(floating).performClick();
            check(floatingTransport.calls.size() == 1 && floatingTransport.calls.get(0).action().equals("list"),
                    "real requirement View validates current screen on Send");
            floatingTransport.calls.get(0).callback.ready(catalog(scope, "paused", 7));
            check(floatingInput.getText().toString().equals(draft), "paused preflight preserves real floating editor text");
            floating.stop(); full.start();
            check(fullInput.getText().toString().equals(draft), "existing full-screen View reloads floating draft on resume");
            check(fullTransport.calls.isEmpty(), "draft restoration starts no execution");
            fullInput.requestFocus(); fullInput.setSelection(5, 12);
            android.text.Editable original = fullInput.getText(); full.start();
            check(fullInput.getText() == original && fullInput.getSelectionStart() == 5 && fullInput.getSelectionEnd() == 12,
                    "unchanged start preserves Editable identity and current selection");
            String changed = "只保留预览。Please do not start the timer.";
            full.stop(); floating.start(); floatingInput.setText(changed); floating.stop(); full.start();
            check(fullInput.getText().toString().equals(changed), "reused full-screen View picks up the latest mixed-language draft");
            button(full).performClick();
            fullTransport.calls.get(0).callback.ready(catalog(scope, "agent", 8));
            Call write = fullTransport.calls.get(1);
            check(write.request.getString("text").equals(changed), "real View sends latest shared draft rather than its constructor snapshot");
            write.callback.ready(receipt(write.request.getString("requestId"), "addRequirement", "accepted"));
            check(fullInput.length() == 0, "accepted matching draft revision clears only that submitted draft");
            check(visibleText(full, getString(R.string.workspace_interaction_requirement_accepted)),
                    "fresh native acceptance briefly confirms the real View submission");
            String recordKey = WorkspaceExecutionRequest.identity(scope) + ".addRequirement";
            android.content.SharedPreferences prefs = getSharedPreferences("workspace-interactions", MODE_PRIVATE);
            JSONObject oldAcceptance = new JSONObject(prefs.getString(recordKey, "{}"));
            oldAcceptance.getJSONObject("receipt").put("acceptedAt", System.currentTimeMillis() - 7000);
            prefs.edit().putString(recordKey, oldAcceptance.toString()).commit();
            full.stop(); full.start();
            check(!visibleText(full, getString(R.string.workspace_interaction_requirement_accepted))
                            && new JSONObject(prefs.getString(recordKey, "{}")).optString("status").equals("accepted"),
                    "reopened old acceptance hides its notice while preserving the durable receipt");
            fullInput.setText(changed); fullInput.setSelection(3); full.stop(); full.start();
            check(fullInput.getText().toString().equals(changed) && fullInput.getSelectionStart() == 3,
                    "old accepted receipt cannot clear a newly typed identical draft");
            full.stop(); floating.start();
            check(floatingInput.getText().toString().equals(changed), "round trip preserves a new identical draft across both real Views");
            JSONObject newGeneration = copy(scope).put("generation", "33333333-3333-3333-3333-333333333333");
            other = new WorkspaceRequirementView(this, newGeneration, new FakeTransport()); other.start();
            check(edit(other).length() == 0, "different lease generation cannot inherit a shared requirement draft");
        } finally { full.stop(); floating.stop(); if (other != null) other.stop(); }
    }

    private void competingSubmissions() throws Exception {
        for (String action : new String[]{"addRequirement", "returnAndResume"}) {
            for (boolean reverse : new boolean[]{false, true}) for (boolean acknowledged : new boolean[]{false, true}) {
                JSONObject scope = copy(lease).put("generation", UUID.randomUUID().toString());
                String text = action.equals("addRequirement") ? "Only stores with returns" : "";
                String revision = action.equals("addRequirement") ? UUID.randomUUID().toString() : "";
                if (action.equals("addRequirement")) saveDraft(scope, text, revision);
                FakeTransport a = new FakeTransport(), b = new FakeTransport();
                WorkspaceExecutionRequest first = client(scope, action, a, new ArrayList<>());
                WorkspaceExecutionRequest second = client(scope, action, b, new ArrayList<>());
                first.start(); second.start(); first.submit(text, revision); second.submit(text, revision);
                check(a.calls.size() == 1 && b.calls.size() == 1, "two surfaces may validate before either claims delivery");
                FakeTransport winner = reverse ? b : a, loser = reverse ? a : b;
                JSONObject current = catalog(scope, action.equals("addRequirement") ? "agent" : "paused", 31);
                winner.calls.get(0).callback.ready(current);
                Call write = winner.calls.get(1); String id = write.request.getString("requestId");
                if (acknowledged) write.callback.ready(receipt(id, action, "accepted"));
                loser.calls.get(0).callback.ready(current);
                check(mutations(a) + mutations(b) == 1, "normal/reversed preflight order cannot duplicate a pending or accepted delivery");
                check(first.receipt().getString("requestId").equals(id) && second.receipt().getString("requestId").equals(id),
                        "both surfaces retain the winning operation identity");
                if (acknowledged) {
                    check(first.receipt().getString("status").equals("accepted"), "late catalog cannot replace an accepted receipt with pending");
                    if (action.equals("addRequirement")) {
                        int calls = loser.calls.size();
                        (reverse ? first : second).submit(text, revision);
                        check(loser.calls.size() == calls, "already accepted draft cannot be resubmitted by the stale second surface");
                    }
                } else {
                    check(loser.calls.size() == 2 && loser.calls.get(1).action().equals("executionStatus")
                                    && loser.calls.get(1).request.getString("requestId").equals(id),
                            "losing surface only queries the original pending operation");
                }
                first.stop(); second.stop();
            }
        }
    }

    private void supersededDrafts() throws Exception {
        for (boolean newerFirst : new boolean[]{false, true}) {
            JSONObject scope = copy(lease).put("generation", UUID.randomUUID().toString());
            FakeTransport oldTransport = new FakeTransport(), newTransport = new FakeTransport();
            List<JSONObject> oldUpdates = new ArrayList<>();
            WorkspaceExecutionRequest old = client(scope, "addRequirement", oldTransport, oldUpdates);
            WorkspaceExecutionRequest newer = client(scope, "addRequirement", newTransport, new ArrayList<>());
            old.start(); newer.start();
            saveDraft(scope, "Old draft", "revision-old"); old.submit("Old draft", "revision-old");
            saveDraft(scope, "Current draft", "revision-new"); newer.submit("Current draft", "revision-new");
            JSONObject current = catalog(scope, "agent", 41);
            if (!newerFirst) {
                oldTransport.calls.get(0).callback.ready(current);
                check(mutations(oldTransport) == 0 && old.receipt().optString("requestId").isEmpty(),
                        "changed draft aborts old preflight before claiming an operation");
            }
            newTransport.calls.get(0).callback.ready(current);
            Call write = newTransport.calls.get(1);
            write.callback.ready(receipt(write.request.getString("requestId"), "addRequirement", "accepted"));
            if (newerFirst) oldTransport.calls.get(0).callback.ready(current);
            check(mutations(oldTransport) == 0 && mutations(newTransport) == 1
                            && write.request.getString("text").equals("Current draft"),
                    "only the current draft is sent in either callback order");
            check(newer.receipt().getString("status").equals("accepted")
                            && newer.receipt().getString("draftRevision").equals("revision-new"),
                    "old draft callback preserves the newer accepted receipt");
            old.stop(); newer.stop();
        }
        JSONObject scope = copy(lease).put("generation", UUID.randomUUID().toString());
        FakeTransport fake = new FakeTransport();
        WorkspaceExecutionRequest request = client(scope, "addRequirement", fake, new ArrayList<>());
        request.start(); saveDraft(scope, "Discard this", "before-clear"); request.submit("Discard this", "before-clear");
        saveDraft(scope, "", "after-clear"); fake.calls.get(0).callback.ready(catalog(scope, "agent", 51));
        check(mutations(fake) == 0 && request.receipt().optString("requestId").isEmpty(), "cleared draft cannot be sent by a late catalog callback");
        saveDraft(scope, "New explicit request", "after-edit"); request.submit("New explicit request", "after-edit");
        fake.calls.get(1).callback.ready(catalog(scope, "agent", 52));
        check(mutations(fake) == 1 && fake.calls.get(2).request.getString("text").equals("New explicit request"),
                "a later explicit current draft still submits normally");
        request.stop();
    }

    private void staleRequirementView() throws Exception {
        JSONObject scope = copy(lease).put("generation", UUID.randomUUID().toString());
        FakeTransport fullTransport = new FakeTransport(), floatTransport = new FakeTransport();
        WorkspaceRequirementView full = new WorkspaceRequirementView(this, scope, fullTransport);
        WorkspaceRequirementView floating = new WorkspaceRequirementView(this, scope, floatTransport);
        try {
            full.start(); floating.start(); edit(full).setText("Old screen draft"); edit(floating).setText("New floating draft");
            button(full).performClick(); fullTransport.calls.get(0).callback.ready(catalog(scope, "agent", 61));
            check(mutations(fullTransport) == 0, "stale real View cannot pair old text with another View's current draft revision");
            String draftKey = WorkspaceExecutionRequest.identity(scope) + ".requirementDraft";
            check(getSharedPreferences("workspace-interactions", MODE_PRIVATE).getString(draftKey, "").equals("New floating draft"),
                    "rejected stale View preserves the latest shared draft");
            button(floating).performClick(); floatTransport.calls.get(0).callback.ready(catalog(scope, "agent", 62));
            check(mutations(floatTransport) == 1 && floatTransport.calls.get(1).request.getString("text").equals("New floating draft"),
                    "current real View remains able to submit after stale View is rejected");
        } finally { full.stop(); floating.stop(); }
    }

    private void saveDraft(JSONObject scope, String text, String revision) {
        String key = WorkspaceExecutionRequest.identity(scope) + ".requirementDraft";
        if (!getSharedPreferences("workspace-interactions", MODE_PRIVATE).edit()
                .putString(key, text).putString(key + ".revision", revision).commit()) throw new AssertionError("save draft");
    }
    private int mutations(FakeTransport transport) {
        int result = 0;
        for (Call call : transport.calls) if (call.action().equals("addRequirement") || call.action().equals("returnAndResume")) result++;
        return result;
    }

    private EditText edit(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText result = edit(((ViewGroup) view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private ImageButton button(View view) {
        if (view instanceof ImageButton) return (ImageButton) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            ImageButton result = button(((ViewGroup) view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private boolean visibleText(View view, String value) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof android.widget.TextView && value.contentEquals(((android.widget.TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (visibleText(((ViewGroup) view).getChildAt(i), value)) return true;
        return false;
    }

    private WorkspaceExecutionRequest client(JSONObject lease, String action, FakeTransport transport, List<JSONObject> seen) {
        WorkspaceExecutionRequest client = new WorkspaceExecutionRequest(this, lease, action,
                record -> seen.add(copy(record)), transport);
        clients.add(client); return client;
    }
    private JSONObject last(List<JSONObject> values) { return values.get(values.size() - 1); }
    private void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static JSONObject makeLease() {
        try { return new JSONObject().put("threadId", "11111111-1111-1111-1111-111111111111")
                .put("screenKey", "task-aaaaaaaaaaaaaaaaaaaa").put("generation", "11111111-1111-1111-1111-111111111111"); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static JSONObject copy(JSONObject object) { return WorkspaceExecutionRequest.copy(object); }
    private JSONObject catalog(JSONObject target, String owner, long revision) throws JSONException {
        return new JSONObject().put("screens", new JSONArray().put(copy(target).put("controlOwner", owner).put("controlRevision", revision)));
    }
    private JSONObject receipt(String id, String action, String status) throws JSONException {
        JSONObject result = new JSONObject().put("requestId", id).put("action", action).put("status", status);
        if (status.equals("accepted")) result.put("acceptedAt", System.currentTimeMillis());
        return result;
    }
    private static final class Call {
        final JSONObject request; final NativeClient.Callback callback;
        Call(JSONObject request, NativeClient.Callback callback) { this.request = copy(request); this.callback = callback; }
        String action() { return request.optString("action"); }
    }
    private static final class FakeTransport implements WorkspaceExecutionRequest.Transport {
        final List<Call> calls = new ArrayList<>();
        public void send(JSONObject request, NativeClient.Callback callback) { calls.add(new Call(request, callback)); }
    }
}
