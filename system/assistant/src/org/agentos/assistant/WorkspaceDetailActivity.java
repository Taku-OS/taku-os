package org.agentos.assistant;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Details for one exact broker lease. Viewing this page never acquires input control. */
public final class WorkspaceDetailActivity extends Activity {
    private WorkspaceUi w;
    private ScrollView scroll;
    private LinearLayout body;
    private JSONObject lease, screen;
    private JSONObject resumeRecord = new JSONObject();
    private WorkspaceExecutionRequest resumeRequest;
    private boolean resumed, loading, changing;
    private int stateVersion;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = () -> refresh(false);
    private final Runnable expireResumeNotice = () -> { if (alive()) render(null); };

    @Override public void onCreate(Bundle saved) {
        WorkspaceUi.applySystemTheme(this); super.onCreate(saved); w = new WorkspaceUi(this);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finish);
        try {
            String thread = getIntent().getStringExtra("threadId"), key = getIntent().getStringExtra("screenKey"), generation = getIntent().getStringExtra("generation");
            if (!AgentRepository.validId(thread) || key == null || !key.matches("task-[a-f0-9]{20}") || generation == null || !generation.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException();
            lease = new JSONObject().put("threadId", thread).put("screenKey", key).put("generation", generation);
        } catch (Exception invalid) { finish(); return; }
        resumeRequest = new WorkspaceExecutionRequest(this, lease, "returnAndResume", this::resumeReceipt);
        LinearLayout root = w.root(); scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = w.ui.column(); body.setPadding(w.ui.dp(20), w.ui.dp(8), w.ui.dp(20), w.ui.dp(28)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); render(null);
    }
    @Override protected void onResume() { super.onResume(); if (lease == null) return; resumed = true; ++stateVersion; loading = false; resumeRequest.start(); refresh(true); }
    @Override protected void onPause() { resumed = false; ++stateVersion; main.removeCallbacks(poll); main.removeCallbacks(expireResumeNotice); if (resumeRequest != null) resumeRequest.stop(); super.onPause(); }
    private boolean alive() { return resumed && !isFinishing() && !isDestroyed(); }
    private void schedule() { main.removeCallbacks(poll); if (alive()) main.postDelayed(poll, 2500); }
    private void refresh(boolean force) {
        if (!alive()) return;
        if (loading || changing) { schedule(); return; }
        loading = true; final int version = stateVersion;
        try {
            NativeClient.async(WorkspaceUi.request("list", null), new NativeClient.Callback() {
                public void ready(JSONObject result) {
                    if (version != stateVersion || !alive()) return;
                    loading = false; JSONObject found = null; JSONArray screens = result.optJSONArray("screens");
                    if (screens != null) for (int i = 0; i < screens.length(); i++) if (WorkspaceUi.same(lease, screens.optJSONObject(i))) { found = screens.optJSONObject(i); break; }
                    if (found == null) { screen = null; render(getString(R.string.workspace_error_changed)); }
                    else { boolean changed = screen == null || !screen.toString().equals(found.toString()); screen = found; if (force || changed) render(null); }
                    schedule();
                }
                public void failed(String message) {
                    if (version != stateVersion || !alive()) return;
                    loading = false; screen = null; render(getString(R.string.workspace_unavailable_copy)); schedule();
                }
            });
        } catch (Exception failure) { loading = false; screen = null; render(getString(R.string.workspace_unavailable_copy)); schedule(); }
    }
    private void render(String error) {
        int oldScroll = scroll.getScrollY(); body.removeAllViews();
        w.navigation(body, getString(R.string.workspace_title), this::finish);
        if (screen == null) {
            w.ui.add(body, w.ui.text(getString(error == null ? R.string.workspace_loading : R.string.workspace_status_unavailable), 24, true), 8);
            if (error != null) {
                w.ui.add(body, w.ui.text(error, 13, false), 12);
                compact(body, w.ui.button(getString(R.string.workspace_refresh), false, () -> refresh(true)), 18);
            }
            if (resumeUnknown()) compact(body, w.ui.button(getString(R.string.workspace_interaction_check_status), false, resumeRequest::refresh), 12);
            return;
        }
        w.ui.add(body, w.ui.text(w.appLabel(screen), 27, true), 4);
        w.ui.add(body, w.ui.text(w.taskLabel(screen), 13, false), 8);
        TextView label = w.ui.text(getString(R.string.workspace_detail_title), 12, false); label.setPadding(w.ui.dp(2), 0, 0, 0); w.ui.add(body, label, 24);
        LinearLayout facts = w.ui.card(); facts.setPadding(w.ui.dp(18), w.ui.dp(6), w.ui.dp(18), w.ui.dp(6));
        fact(facts, getString(R.string.workspace_detail_state), w.status(screen));
        fact(facts, getString(R.string.workspace_detail_task), w.taskLabel(screen));
        String owner = screen.optString("controlOwner");
        boolean cleanupFailed = "cleanup_failed".equals(screen.optString("status"));
        fact(facts, getString(R.string.workspace_detail_input), getString(cleanupFailed || "paused".equals(owner) ? R.string.workspace_input_paused : "user".equals(owner) ? R.string.workspace_input_you : R.string.workspace_input_agent));
        fact(facts, getString(R.string.workspace_detail_account), getString(R.string.workspace_detail_account_copy));
        w.ui.add(body, facts, 9);
        boolean detached = detached(screen), stopped = screen.optBoolean("stopping") || "stopping".equals(screen.optString("status"));
        boolean pending = resumeRequest.busy(), unknown = resumeUnknown();
        boolean pausedReturn = "paused".equals(owner);
        boolean controlReturn = "user".equals(owner) || (!pausedReturn && (pending || screen.optBoolean("resumeRequired")));
        if (!detached && !cleanupFailed) {
            LinearLayout actions = new LinearLayout(this); boolean stack = getResources().getConfiguration().fontScale > 1.3f;
            actions.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); actions.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            if (!"user".equals(owner)) {
                Button pause = w.ui.button(getString(pausedReturn ? resumeLabel() : R.string.workspace_pause), false, () -> { if (pausedReturn) resume(); else change("pause", false); });
                actionButton(actions, pause, false, stack); pause.setEnabled(!changing && !stopped && (!pausedReturn || !pending || unknown));
            }
            Button control = w.ui.button(getString(controlReturn ? resumeLabel() : R.string.workspace_take_control), true, () -> { if (controlReturn) resume(); else change("takeControl", true); });
            actionButton(actions, control, actions.getChildCount() > 0, stack); control.setEnabled(!changing && !stopped && (!pending || (controlReturn && unknown))); w.ui.add(body, actions, 18);
        }
        if (unknown && (detached || cleanupFailed || stopped))
            compact(body, w.ui.button(getString(R.string.workspace_interaction_check_status), false, resumeRequest::refresh), 12);
        int note = detached ? R.string.workspace_error_changed : changing || stopped ? R.string.workspace_action_pending : "user".equals(owner) ? R.string.workspace_user_control_copy : "paused".equals(owner) ? R.string.workspace_paused_copy : screen.optBoolean("resumeRequired") ? R.string.workspace_returned_copy : R.string.workspace_detail_hint;
        String message = resumeMessage();
        if (message.isEmpty() || detached || cleanupFailed || changing || stopped) message = getString(cleanupFailed ? R.string.workspace_cleanup_retry : note);
        TextView explanation = w.ui.text(message + (!screen.optBoolean("agentAllowed", true) && !detached && !cleanupFailed ? "\n\n" + getString(R.string.workspace_agent_blocked_copy) : ""), 12, false); explanation.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); w.ui.add(body, explanation, 16);
        LinearLayout links = w.ui.card(); links.setPadding(w.ui.dp(16), 0, w.ui.dp(16), 0);
        if (!detached && !cleanupFailed) link(links, R.string.workspace_view_live, () -> w.preview(screen));
        link(links, R.string.workspace_open_task, () -> w.openTask(lease));
        if (!cleanupFailed) link(links, R.string.workspace_close, this::confirmClose); w.ui.add(body, links, 22);
        scroll.post(() -> scroll.scrollTo(0, oldScroll));
    }
    private void fact(LinearLayout group, String key, String value) {
        if (group.getChildCount() > 0) { View line = new View(this); line.setBackgroundColor(w.theme.border); group.addView(line, new LinearLayout.LayoutParams(-1, w.ui.dp(1))); }
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.TOP); row.setPadding(0, w.ui.dp(12), 0, w.ui.dp(12));
        boolean stack = getResources().getConfiguration().fontScale > 1.3f; row.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        TextView name = w.ui.text(key, 13, false), content = w.ui.text(value, 13, true); content.setTextIsSelectable(true);
        if (stack) { row.addView(name); w.ui.add(row, content, 6); }
        else { row.addView(name, new LinearLayout.LayoutParams(0, -2, 0.4f)); content.setGravity(Gravity.END); LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 0.6f); size.leftMargin = w.ui.dp(16); row.addView(content, size); }
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    private void link(LinearLayout parent, int title, Runnable action) {
        if (parent.getChildCount() > 0) { View divider = new View(this); divider.setBackgroundColor(w.theme.border); parent.addView(divider, new LinearLayout.LayoutParams(-1, w.ui.dp(1))); }
        TextView link = w.ui.text(getString(title) + "  ›", 14, true); link.setTextColor(w.theme.accent); link.setGravity(Gravity.CENTER_VERTICAL); link.setMinHeight(w.ui.dp(52)); link.setPadding(0, w.ui.dp(12), 0, w.ui.dp(12));
        link.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(w.theme.border), null, null));
        link.setOnClickListener(v -> { if (!changing) action.run(); }); parent.addView(link, new LinearLayout.LayoutParams(-1, -2)); link.setEnabled(!changing);
    }
    private void actionButton(LinearLayout row, Button button, boolean spaced, boolean stack) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2); if (spaced) { if (stack) size.topMargin = w.ui.dp(10); else size.leftMargin = w.ui.dp(10); }
        row.addView(button, size);
    }
    private void compact(LinearLayout parent, View child, int top) { LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2); size.topMargin = w.ui.dp(top); parent.addView(child, size); }
    private boolean detached(JSONObject value) { String status = value.optString("status"); return status.equals("stale") || status.equals("disconnected") || status.equals("unavailable") || status.equals("rebind_required"); }
    private boolean resumeUnknown() { return "unknown".equals(resumeRecord.optString("status")) || resumeRecord.optBoolean("requiresReconciliation"); }
    private int resumeLabel() {
        return resumeUnknown() ? R.string.workspace_interaction_check_status
                : "failed".equals(resumeRecord.optString("status")) ? R.string.workspace_interaction_retry_continue : R.string.workspace_interaction_return_continue;
    }
    private String resumeMessage() {
        String status = resumeRecord.optString("status");
        if (resumeUnknown()) return getString(R.string.workspace_interaction_unknown);
        if (status.equals("pending")) return getString(R.string.workspace_interaction_resuming);
        if (status.equals("failed")) return WorkspaceRequirementView.error(this, resumeRecord.optString("error"));
        if (status.equals("accepted") && WorkspaceRequirementView.acceptedNoticeRemaining(resumeRecord, System.currentTimeMillis()) > 0)
            return getString(R.string.workspace_interaction_resume_accepted);
        return "";
    }
    private void resume() {
        if (changing || screen == null || !alive()) return;
        ++stateVersion; loading = false; main.removeCallbacks(poll);
        if (resumeUnknown()) resumeRequest.refresh(); else resumeRequest.submit("");
        render(null);
    }
    private void resumeReceipt(JSONObject record) {
        resumeRecord = record;
        if (!alive()) return;
        main.removeCallbacks(expireResumeNotice);
        if ("accepted".equals(record.optString("status"))) {
            long remaining = WorkspaceRequirementView.acceptedNoticeRemaining(record, System.currentTimeMillis());
            if (remaining > 0) main.postDelayed(expireResumeNotice, remaining);
        }
        // Receipts prove delivery, not current screen ownership; refresh from the broker.
        render(null); refresh(true);
    }
    private void confirmClose() {
        new AlertDialog.Builder(this).setTitle(R.string.workspace_close_title).setMessage(R.string.workspace_close_copy).setNegativeButton(R.string.workspace_cancel, null)
                .setPositiveButton(R.string.workspace_close, (dialog, which) -> change("release", false)).show();
    }
    private void change(String action, boolean previewAfter) {
        if (changing || screen == null || !alive()) return;
        changing = true; loading = false; final int version = ++stateVersion; main.removeCallbacks(poll); render(null);
        try {
            NativeClient.async(WorkspaceUi.request(action, lease), new NativeClient.Callback() {
                public void ready(JSONObject result) {
                    changing = false; if (isFinishing() || isDestroyed()) return;
                    if ("release".equals(action)) { finish(); return; }
                    if (version != stateVersion) { if (alive()) refresh(true); return; }
                    JSONObject updated = result.optJSONObject("screen");
                    if (updated != null && WorkspaceUi.same(lease, updated)) screen = updated;
                    if (alive()) {
                        render(null);
                        // Navigate only after the broker grants this exact lease to the user.
                        if (previewAfter && updated != null && WorkspaceUi.same(lease, updated) && "user".equals(updated.optString("controlOwner")) && updated.optLong("controlRevision", -1) >= 0 && !updated.optBoolean("stopping")) w.preview(updated);
                        else refresh(true);
                    }
                }
                public void failed(String message) {
                    changing = false; if (!alive()) return; Toast.makeText(WorkspaceDetailActivity.this, w.error(message), Toast.LENGTH_LONG).show(); refresh(true);
                }
            });
        } catch (Exception failure) { changing = false; if (alive()) { Toast.makeText(this, R.string.workspace_action_failed, Toast.LENGTH_LONG).show(); refresh(true); } }
    }
}
