package org.agentos.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.*;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.function.Consumer;

/** Inline requirement for the same native session, shared by full-screen and floating previews. */
public final class WorkspaceRequirementView extends LinearLayout {
    private final EditText input;
    private final ImageButton send;
    private final GradientDrawable sendSurface;
    private final TextView status;
    private final SharedPreferences preferences;
    private final String draftKey;
    private final WorkspaceExecutionRequest request;
    private JSONObject last = new JSONObject();
    private Consumer<Boolean> editingChanged;
    private Runnable attentionRequired;
    private boolean active, updating;
    private boolean inputChinese;
    private String appliedDraftRevision;
    private final Runnable expireAcceptedNotice = this::expireAcceptedNotice;
    private void expireAcceptedNotice() {
        if (active && last.optString("status").equals("accepted")) {
            status.setText(""); status.setVisibility(GONE);
        }
    }

    public WorkspaceRequirementView(Context context, JSONObject lease) {
        this(context, lease, NativeClient::async);
    }
    WorkspaceRequirementView(Context context, JSONObject lease, WorkspaceExecutionRequest.Transport transport) {
        super(context); setOrientation(VERTICAL);
        preferences = context.getSharedPreferences("workspace-interactions", Context.MODE_PRIVATE);
        draftKey = WorkspaceExecutionRequest.identity(lease) + ".requirementDraft";
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.BOTTOM);
        row.setMinimumHeight(dp(48)); row.setPadding(dp(12), 0, 0, 0);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(color(R.color.taku_surface_card)); surface.setCornerRadius(dp(6));
        row.setBackground(surface);
        input = new EditText(context); input.setTextColor(color(R.color.taku_text_primary));
        input.setHintTextColor(color(R.color.taku_text_secondary)); input.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        input.setHint(R.string.workspace_interaction_requirement_hint);
        input.setContentDescription(context.getString(R.string.workspace_interaction_requirement_hint));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(1); input.setMinimumHeight(dp(48)); input.setMaxHeight(dp(144));
        input.setPadding(0, dp(12), 0, dp(12));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        input.setText(preferences.getString(draftKey, "")); ChatDesign.text(input, ChatDesign.Role.BODY);
        inputChinese = TakuText.isChinese(input.getText());
        appliedDraftRevision = preferences.getString(draftKey + ".revision", "");
        row.addView(input, new LayoutParams(0, -2, 1));
        send = new ImageButton(context); send.setImageResource(R.drawable.ic_taku_send);
        send.setColorFilter(color(R.color.taku_text_inverse)); send.setPadding(dp(14), dp(14), dp(14), dp(14));
        sendSurface = new GradientDrawable(); sendSurface.setColor(color(R.color.taku_action_neutral)); sendSurface.setCornerRadius(dp(6));
        send.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.taku_border)), sendSurface, null));
        send.setContentDescription(context.getString(R.string.workspace_interaction_send));
        LayoutParams sendSize = new LayoutParams(dp(48), dp(48)); sendSize.setMarginStart(dp(4));
        row.addView(send, sendSize); addView(row);
        status = new TextView(context); status.setTextColor(color(R.color.workspace_viewer_secondary));
        status.setIncludeFontPadding(false); status.setPadding(dp(4), dp(8), dp(4), 0);
        status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        ChatDesign.text(status, ChatDesign.Role.META); status.setVisibility(GONE); addView(status);
        request = new WorkspaceExecutionRequest(context, lease, "addRequirement", this::receipt, transport);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable value) {
                boolean chinese = TakuText.isChinese(value);
                if (chinese != inputChinese) {
                    inputChinese = chinese;
                    // Typography changes in place: do not replace the Editable or selection.
                    ChatDesign.text(input, ChatDesign.Role.BODY);
                }
                if (!updating) {
                    appliedDraftRevision = java.util.UUID.randomUUID().toString();
                    preferences.edit().putString(draftKey, value.toString())
                            .putString(draftKey + ".revision", appliedDraftRevision).apply();
                }
                updateControls();
            }
        });
        input.setOnFocusChangeListener((view, focused) -> { if (editingChanged != null) editingChanged.accept(focused); });
        send.setOnClickListener(view -> {
            if (WorkspaceExecutionRequest.unresolved(last)) { request.refresh(); return; }
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            // Repeated taps cannot generate a new operation while its receipt is unresolved.
            String revision = preferences.getString(draftKey + ".revision", "");
            if (revision.isEmpty()) {
                revision = java.util.UUID.randomUUID().toString();
                if (!preferences.edit().putString(draftKey + ".revision", revision).commit()) return;
            }
            request.submit(text, revision);
        });
        updateControls();
    }
    public void setOnEditingChanged(Consumer<Boolean> listener) { editingChanged = listener; }
    public void setOnAttentionRequired(Runnable listener) {
        attentionRequired = listener;
        if (listener != null && needsAttention()) listener.run();
    }
    public boolean needsAttention() {
        JSONObject saved = request.receipt();
        return WorkspaceExecutionRequest.unresolved(last) || WorkspaceExecutionRequest.unresolved(saved)
                || requiresAttention(last) || requiresAttention(saved);
    }
    private boolean requiresAttention(JSONObject record) {
        return record.optString("status").equals("unknown") || record.optBoolean("requiresReconciliation")
                || record.optString("status").equals("failed");
    }
    public void focusInput() {
        if (!input.isEnabled()) return;
        input.requestFocus();
        input.post(() -> {
            if (!isAttachedToWindow() || !isShown() || !input.hasFocus()) return;
            android.graphics.Rect caret = new android.graphics.Rect(); input.getDrawingRect(caret);
            input.requestRectangleOnScreen(caret, true);
            ((android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }
    /** Pass the allocated host height after its keyboard/safe-area insets, in pixels. */
    public void setAvailableHeight(int heightPx) {
        int cap = dp(heightPx > 0 && heightPx < dp(500) ? 96 : 144);
        if (input.getMaxHeight() != cap) { input.setMaxHeight(cap); requestLayout(); }
    }
    public void stop() { active = false; removeCallbacks(expireAcceptedNotice); request.stop(); if (editingChanged != null) editingChanged.accept(false); }
    public void start() {
        restoreLatestDraft();
        if (active) return;
        active = true;
        request.start();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); start(); }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }

    private void restoreLatestDraft() {
        String revision = preferences.getString(draftKey + ".revision", "");
        String saved = preferences.getString(draftKey, "");
        if (revision.equals(appliedDraftRevision) && saved.equals(input.getText().toString())) return;
        appliedDraftRevision = revision;
        // Full-screen can survive behind the floating surface. Refresh only a changed draft;
        // never replace the live Editable or its selection for an unchanged lifecycle callback.
        if (!saved.equals(input.getText().toString())) {
            int start = input.getSelectionStart(), end = input.getSelectionEnd();
            updating = true;
            input.setText(saved);
            ChatDesign.text(input, ChatDesign.Role.BODY);
            if (start >= 0 && end >= 0) input.setSelection(Math.min(start, saved.length()), Math.min(end, saved.length()));
            updating = false;
        }
        updateControls();
    }

    private void receipt(JSONObject record) {
        removeCallbacks(expireAcceptedNotice);
        last = record;
        String state = record.optString("status");
        if (state.equals("accepted")) {
            String sent = record.optString("text");
            if (!record.optString("draftRevision").isEmpty()
                    && record.optString("draftRevision").equals(preferences.getString(draftKey + ".revision", ""))
                    && input.getText().toString().trim().equals(sent)) {
                updating = true; input.setText(""); updating = false;
                appliedDraftRevision = java.util.UUID.randomUUID().toString();
                preferences.edit().putString(draftKey, "")
                        .putString(draftKey + ".revision", appliedDraftRevision).apply();
                input.clearFocus();
                ((android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
            long remaining = acceptedNoticeRemaining(record, System.currentTimeMillis());
            if (remaining > 0) {
                status.setText(R.string.workspace_interaction_requirement_accepted);
                postDelayed(expireAcceptedNotice, remaining);
            } else status.setText("");
        } else if (state.equals("pending")) status.setText(R.string.workspace_interaction_sending);
        else if (state.equals("unknown") || record.optBoolean("requiresReconciliation")) status.setText(R.string.workspace_interaction_unknown);
        else if (state.equals("failed")) status.setText(error(getContext(), record.optString("error")));
        else status.setText("");
        ChatDesign.text(status, ChatDesign.Role.META);
        status.setVisibility(status.length() == 0 ? GONE : VISIBLE);
        updateControls();
        if (active && attentionRequired != null && requiresAttention(record)) attentionRequired.run();
    }
    static long acceptedNoticeRemaining(JSONObject record, long now) {
        JSONObject receipt = record.optJSONObject("receipt");
        long acceptedAt = receipt == null ? 0 : receipt.optLong("acceptedAt", 0);
        long age = now - acceptedAt;
        return record.optString("status").equals("accepted") && acceptedAt > 0 && age >= 0 && age < 6000 ? 6000 - age : 0;
    }
    private void updateControls() {
        if (request == null) return;
        boolean pending = last.optString("status").equals("pending");
        boolean unknown = last.optString("status").equals("unknown") || last.optBoolean("requiresReconciliation");
        input.setEnabled(!pending && !unknown);
        send.setEnabled(unknown || !request.busy() && !input.getText().toString().trim().isEmpty());
        sendSurface.setColor(color(send.isEnabled() ? R.color.taku_action_neutral : R.color.taku_surface_disabled));
        send.setColorFilter(color(send.isEnabled() ? R.color.taku_text_inverse : R.color.taku_text_disabled));
        boolean retry = last.optString("status").equals("failed") && !last.optBoolean("requiresReconciliation")
                && last.optString("text").equals(input.getText().toString().trim());
        send.setContentDescription(getContext().getString(unknown ? R.string.workspace_interaction_check_status
                : retry ? R.string.workspace_interaction_retry_requirement : R.string.workspace_interaction_send));
        send.setTooltipText(send.getContentDescription());
        send.setImageResource(unknown ? R.drawable.ic_taku_refresh : R.drawable.ic_taku_send);
    }
    static String error(Context context, String error) {
        int resource = error.contains("DRAFT_SUPERSEDED")
                ? R.string.workspace_interaction_draft_changed
                : error.contains("RETURN_AND_CONTINUE_FIRST") || error.contains("USER_CONTROL") || error.contains("PAUSED")
                ? R.string.workspace_interaction_return_first
                : error.contains("WAITING_FOR_USER") || error.contains("REQUEST_PENDING")
                ? R.string.workspace_interaction_answer_first
                : error.contains("GENERATION") || error.contains("REVISION") || error.contains("CHANGED")
                ? R.string.workspace_interaction_changed
                : R.string.workspace_interaction_failed;
        return context.getString(resource);
    }
    private int color(int resource) { return ChatDesign.color(getContext(), resource); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
