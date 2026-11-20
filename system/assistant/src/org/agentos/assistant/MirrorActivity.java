package org.agentos.assistant;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** Read-only by default. Managed input is mediated by the broker's exact screen lease. */
public final class MirrorActivity extends Activity {
    private boolean returnedFromWorkspace, suppressWorkspaceReturn;

    @Override public void finish() {
        if (!suppressWorkspaceReturn && !returnedFromWorkspace) {
            returnedFromWorkspace = true;
            if (returnsHome()) AgentFrontendLauncher.returnToWorkspaceHome(this);
            else returnToConversation();
        }
        super.finish();
    }

    private boolean returnsHome() {
        return getIntent() != null && getIntent().getBooleanExtra(AgentFrontendLauncher.RETURN_WORKSPACE_HOME, false);
    }

    private void returnToConversation() {
        Intent source = getIntent();
        boolean returnAgent = source != null && source.getBooleanExtra(AgentFrontendLauncher.RETURN_AGENT_FRONTEND, false);
        String thread = source == null ? null : source.getStringExtra(AgentFrontendLauncher.RETURN_CONVERSATION_ID);
        String leaseThread = lease == null ? null : lease.optString("threadId");
        // A marked return is tied to the admitted workspace, never an arbitrary conversation extra.
        boolean exactReturn = AgentFrontendLauncher.matchesWorkspaceReturn(returnAgent, leaseThread, thread);
        if (returnAgent && !exactReturn || thread == null) thread = leaseThread;
        if (AgentRepository.validId(thread)) {
            if (!AgentFrontendLauncher.returnFromWorkspace(this, exactReturn, leaseThread, thread)) {
                Intent fallback = new Intent(this, PanelActivity.class).putExtra("conversationId", thread);
                if (returnAgent) fallback.putExtra(AgentFrontendRoute.NATIVE_CONTROLS_ONLY, true);
                startActivity(fallback);
            }
        }
    }
    private WorkspaceUi w;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private ImageView preview;
    private TextView state, details, note;
    private Button control, stop;
    private ImageView appIcon;
    private ImageButton minimize, edit, backInApp, activityToggle;
    private View statusDot;
    private WorkspaceRequirementView requirement;
    private WorkspaceExecutionRequest resumeRequest;
    private AgentRepository activityRepository;
    private LinearLayout activityRows, stepRows;
    private FrameLayout stepsFrame;
    private View activityContent;
    private boolean activityOpen, activityLoading, editingRequirement;
    private boolean resumeUnknown, resumeFailed;
    private JSONObject currentScreen;
    private String currentPackage = "";
    private final Runnable activityPoll = this::loadActivity;
    private final Runnable expireResumeNotice = () -> showNote("");
    private String activitySignature = "";
    private ConversationExpansion expansion;
    private volatile boolean resumed, userControl, connected, inputPending;
    private volatile LocalSocket frames;
    private volatile int watchGeneration;
    private volatile long ownerCheckedAt;
    private volatile long controlGeneration;
    private volatile long controlRevision = -1;
    private long lastObservedControlRevision = -1;
    private JSONObject lease;
    private volatile int width = 720, height = 1280;
    private float downX, downY;
    private long downAt;
    private long downControlRevision, downControlGeneration;
    private boolean tracking, changing;

    @Override public void onCreate(Bundle saved) {
        WorkspaceUi.applySystemTheme(this); super.onCreate(saved); w = new WorkspaceUi(this);
        WorkspaceFloatService.hide(this);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finish);
        try {
            String thread = getIntent().getStringExtra("threadId"), key = getIntent().getStringExtra("screenKey"), generation = getIntent().getStringExtra("generation");
            if (thread != null || key != null || generation != null) {
                if (!AgentRepository.validId(thread) || key == null || !key.matches("task-[a-f0-9]{20}") || generation == null || !generation.matches("[a-f0-9-]{36}")) throw new IOException("INVALID_SCREEN_LEASE");
                lease = new JSONObject().put("threadId", thread).put("screenKey", key).put("generation", generation);
            }
        } catch (Exception invalid) { finish(); return; }
        buildLayout();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try {
            String thread = intent.getStringExtra("threadId"), key = intent.getStringExtra("screenKey"), generation = intent.getStringExtra("generation");
            if (!AgentRepository.validId(thread) || key == null || !key.matches("task-[a-f0-9]{20}")
                    || generation == null || !generation.matches("[a-f0-9-]{36}")) throw new IOException("INVALID_SCREEN_LEASE");
            JSONObject next = new JSONObject().put("threadId", thread).put("screenKey", key).put("generation", generation);
            WorkspaceFloatService.hide(this);
            if (WorkspaceUi.same(lease, next)) {
                setIntent(intent);
                minimize.setContentDescription(getString(exitLabel()));
                if (requirement != null) requirement.start();
            } else {
                // A frame reader and its input gate are immutable for one lease. A different
                // intent gets a fresh Activity instead of rebinding old callbacks or controls.
                Intent fresh = new Intent(this, MirrorActivity.class);
                if (intent.getExtras() != null) fresh.putExtras(intent.getExtras());
                startActivity(fresh);
                suppressWorkspaceReturn = true;
                finish();
            }
        } catch (Exception invalid) { showNote(getString(R.string.workspace_interaction_changed)); }
    }

    private int exitLabel() {
        return returnsHome() ? R.string.workspace_back : R.string.workspace_interaction_minimize;
    }

    private void buildLayout() {
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(color(R.color.workspace_viewer_background));
        getWindow().setNavigationBarColor(color(R.color.workspace_viewer_background));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.workspace_viewer_background));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (requirement != null) requirement.setAvailableHeight(b - t - root.getPaddingTop() - root.getPaddingBottom());
        });
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            boolean resized = root.getPaddingBottom() != Math.max(bars.bottom, keyboard);
            root.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard));
            if (requirement != null) requirement.setAvailableHeight(root.getHeight() - root.getPaddingTop() - root.getPaddingBottom());
            if (resized && keyboard > bars.bottom) root.post(() -> {
                View focused = root.findFocus();
                if (focused instanceof EditText) {
                    android.graphics.Rect bounds = new android.graphics.Rect(); focused.getDrawingRect(bounds);
                    focused.requestRectangleOnScreen(bounds, true);
                }
            });
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(8), dp(8), dp(8));
        appIcon = new ImageView(this); appIcon.setImageResource(R.drawable.ic_tako_mark);
        appIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(appIcon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout labels = column();
        details = text(getString(R.string.workspace_interaction_title), ChatDesign.Role.CONTEXT);
        details.setMaxLines(2); details.setEllipsize(android.text.TextUtils.TruncateAt.END);
        state = text(getString(R.string.workspace_connecting), ChatDesign.Role.META_MEDIUM);
        state.setMaxLines(2); state.setTextColor(color(R.color.workspace_viewer_secondary));
        LinearLayout stateRow = new LinearLayout(this); stateRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = dot(R.color.workspace_viewer_secondary);
        LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(dp(8), dp(8)); dotSize.setMarginEnd(dp(6));
        stateRow.addView(statusDot, dotSize); stateRow.addView(state, new LinearLayout.LayoutParams(-1, -2));
        labels.addView(details); labels.addView(stateRow);
        LinearLayout.LayoutParams names = new LinearLayout.LayoutParams(0, -2, 1); names.setMarginStart(dp(8));
        header.addView(labels, names);
        edit = icon(R.drawable.ic_taku_edit, R.string.workspace_interaction_edit, () -> setEditingRequirement(!editingRequirement));
        edit.setEnabled(lease != null); header.addView(edit);
        minimize = icon(R.drawable.ic_taku_fullscreen_exit, exitLabel(), () -> {
            if (returnsHome()) { finish(); return; }
            boolean requested = lease != null && WorkspaceFloatService.show(this, lease, () -> {
                if (!isFinishing() && !isDestroyed()) { suppressWorkspaceReturn = true; finish(); }
            });
            if (!requested) showNote(getString(R.string.workspace_interaction_float_unavailable));
        });
        minimize.setEnabled(lease != null); header.addView(minimize);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(surface(R.color.workspace_viewer_control, 16)); frame.setClipToOutline(true);
        preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription(getString(R.string.workspace_preview_description));
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        preview.setOnTouchListener((view, event) -> gesture(event));
        backInApp = icon(R.drawable.ic_taku_arrow_left, R.string.workspace_screen_back, () -> {
            try { input(new JSONObject().put("type", "key").put("keyCode", 4)); }
            catch (Exception error) { showNote(getString(R.string.workspace_input_failed)); }
        });
        backInApp.setBackground(surface(R.color.workspace_viewer_background, 6)); backInApp.setVisibility(View.GONE);
        FrameLayout.LayoutParams backSize = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.START);
        backSize.setMargins(dp(8), dp(8), dp(8), dp(8)); frame.addView(backInApp, backSize);
        LinearLayout footer = column(); footer.setPadding(dp(16), dp(8), dp(16), dp(12));
        // PRD focus-view mapping, not a general DS dark theme. Real pixels retain their
        // aspect ratio; constrained height uses one scroll owner rather than smaller type.
        WorkspaceFocusScrollView content = new WorkspaceFocusScrollView(this, frame, footer);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        stepsFrame = new FrameLayout(this); stepsFrame.setMinimumHeight(dp(48)); stepsFrame.setVisibility(View.GONE);
        stepRows = column(); stepRows.setPadding(0, 0, dp(48), 0);
        stepsFrame.addView(stepRows, new FrameLayout.LayoutParams(-1, -2));
        activityToggle = icon(R.drawable.ic_taku_chevron_down, R.string.chat_expand_activity, () -> {
            activityOpen = !activityOpen;
            if (expansion != null) expansion.toggle("workspace/details");
            activityContent.setVisibility(activityOpen ? View.VISIBLE : View.GONE);
            activityToggle.setRotation(activityOpen ? 0 : -90);
            activityToggle.setContentDescription(getString(activityOpen ? R.string.chat_collapse_activity : R.string.chat_expand_activity));
        });
        activityToggle.setRotation(-90);
        stepsFrame.addView(activityToggle, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL));
        footer.addView(stepsFrame, new LinearLayout.LayoutParams(-1, -2));
        activityRows = column(); activityContent = activityRows;
        activityContent.setVisibility(View.GONE); footer.addView(activityContent, new LinearLayout.LayoutParams(-1, -2));
        note = text("", ChatDesign.Role.META); note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        note.setVisibility(View.GONE); footer.addView(note);
        if (lease != null) {
            activityRepository = new AgentRepository(this);
            expansion = new ConversationExpansion(getSharedPreferences("agent-settings", 0), lease.optString("threadId"));
            activityOpen = expansion.contains("workspace/details");
            activityToggle.setRotation(activityOpen ? 0 : -90);
            activityToggle.setContentDescription(getString(activityOpen ? R.string.chat_collapse_activity : R.string.chat_expand_activity));
            resumeRequest = new WorkspaceExecutionRequest(this, lease, "returnAndResume", this::resumeReceipt);
            requirement = new WorkspaceRequirementView(this, lease); requirement.setVisibility(View.GONE);
            requirement.setOnAttentionRequired(() -> setEditingRequirement(true, false));
            LinearLayout.LayoutParams requireSize = new LinearLayout.LayoutParams(-1, -2); requireSize.topMargin = dp(8); requireSize.bottomMargin = dp(8);
            footer.addView(requirement, requireSize);
            LinearLayout actions = new LinearLayout(this);
            actions.setBaselineAligned(false);
            boolean stack = getResources().getConfiguration().fontScale > 1.3f;
            actions.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            control = button(getString(R.string.workspace_interaction_take_over), R.drawable.ic_taku_hand, () -> {
                if (resumeUnknown) resumeRequest.refresh();
                else if (returnNeeded()) resumeRequest.submit(""); else change("takeControl");
            });
            stop = button(getString(R.string.workspace_interaction_stop), R.drawable.ic_taku_stop, () -> change("pause"));
            actions.addView(control, new LinearLayout.LayoutParams(stack ? -1 : 0, stack ? -2 : -1, stack ? 0 : 1));
            LinearLayout.LayoutParams stopSize = new LinearLayout.LayoutParams(stack ? -1 : 0, stack ? -2 : -1, stack ? 0 : 1);
            if (stack) stopSize.topMargin = dp(8); else stopSize.setMarginStart(dp(8));
            actions.addView(stop, stopSize);
            LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(-1, -2); actionSize.topMargin = dp(12);
            footer.addView(actions, actionSize); controls(false);
        }
    }
    private void setEditingRequirement(boolean value) {
        setEditingRequirement(value, value);
    }
    private void setEditingRequirement(boolean value, boolean focusInput) {
        if (requirement == null) return;
        editingRequirement = value;
        requirement.setVisibility(value ? View.VISIBLE : View.GONE);
        edit.setImageResource(value ? R.drawable.ic_taku_check : R.drawable.ic_taku_edit);
        edit.setContentDescription(getString(value ? R.string.workspace_interaction_done_editing : R.string.workspace_interaction_edit));
        if (value) { if (focusInput) { requirement.start(); requirement.focusInput(); } }
        else {
            View focused = requirement.findFocus(); if (focused != null) focused.clearFocus();
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(requirement.getWindowToken(), 0);
        }
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(int resource) { return getColor(resource); }
    private TextView text(String value, ChatDesign.Role role) {
        TextView view = new TextView(this); view.setText(value); view.setTextColor(color(R.color.workspace_viewer_foreground));
        view.setIncludeFontPadding(false); ChatDesign.text(view, role); return view;
    }
    private ImageButton icon(int resource, int label, Runnable action) {
        ImageButton view = new ImageButton(this); view.setImageResource(resource); view.setColorFilter(color(R.color.workspace_viewer_foreground));
        view.setContentDescription(getString(label)); view.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        view.setPadding(dp(14), dp(14), dp(14), dp(14)); view.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        view.setOnClickListener(v -> action.run()); return view;
    }
    private android.graphics.drawable.GradientDrawable surface(int colorResource, int radius) {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(color(colorResource)); background.setCornerRadius(dp(radius)); return background;
    }
    private View dot(int colorResource) {
        View view = new View(this); android.graphics.drawable.GradientDrawable shape = surface(colorResource, 999);
        view.setBackground(shape); view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return view;
    }
    private void buttonIcon(Button view, int icon) {
        if (Integer.valueOf(icon).equals(view.getTag())) return;
        view.setTag(icon);
        android.graphics.drawable.Drawable drawable = ChatDesign.icon(this, icon, color(R.color.workspace_viewer_foreground));
        if (drawable != null) drawable.setBounds(0, 0, dp(20), dp(20));
        view.setCompoundDrawablesRelative(drawable, null, null, null); view.setCompoundDrawablePadding(dp(4));
    }
    private Button button(String label, int icon, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false);
        view.setStateListAnimator(null);
        view.setTextColor(color(R.color.workspace_viewer_foreground)); ChatDesign.text(view, ChatDesign.Role.BODY);
        view.setBackground(surface(R.color.workspace_viewer_control, 6)); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48)); view.setMinWidth(0);
        buttonIcon(view, icon);
        view.setPadding(dp(16), dp(12), dp(16), dp(12)); view.setOnClickListener(v -> action.run()); return view;
    }
    private void showNote(String text) { main.removeCallbacks(expireResumeNotice); note.setText(text); ChatDesign.text(note, ChatDesign.Role.META); note.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE); }
    private boolean returnNeeded() {
        return currentScreen != null && ("user".equals(currentScreen.optString("controlOwner"))
                || "paused".equals(currentScreen.optString("controlOwner")) || currentScreen.optBoolean("resumeRequired"));
    }
    private void resumeReceipt(JSONObject record) {
        String status = record.optString("status");
        resumeUnknown = status.equals("unknown") || record.optBoolean("requiresReconciliation");
        resumeFailed = status.equals("failed") && !record.optBoolean("requiresReconciliation");
        if (WorkspaceExecutionRequest.unresolved(record)) {
            ++controlGeneration; userControl = false; controlRevision = -1; cancelGesture();
        }
        if (status.equals("pending")) showNote(getString(R.string.workspace_interaction_resuming));
        else if (status.equals("unknown") || record.optBoolean("requiresReconciliation")) showNote(getString(R.string.workspace_interaction_unknown));
        else if (status.equals("failed")) showNote(WorkspaceRequirementView.error(this, record.optString("error")));
        else if (status.equals("accepted")) {
            long remaining = WorkspaceRequirementView.acceptedNoticeRemaining(record, System.currentTimeMillis());
            showNote(remaining > 0 ? getString(R.string.workspace_interaction_resume_accepted) : "");
            if (remaining > 0) main.postDelayed(expireResumeNotice, remaining);
            // A durable acknowledgement is historical, never the current owner projection.
            refreshOwnerCatalog();
        }
        controls(connected);
    }
    private void refreshOwnerCatalog() {
        if (!resumed || lease == null) return;
        final int token = watchGeneration;
        try {
            NativeClient.async(WorkspaceUi.request("list", null), new NativeClient.Callback() {
                public void ready(JSONObject result) {
                    if (!resumed || token != watchGeneration || isFinishing() || isDestroyed()) return;
                    JSONArray screens = result.optJSONArray("screens");
                    if (screens == null) return;
                    for (int i = 0; i < screens.length(); i++) {
                        JSONObject screen = screens.optJSONObject(i);
                        if (WorkspaceUi.same(lease, screen)) { owner(screen, token); return; }
                    }
                }
                public void failed(String ignored) { /* Existing frame watcher keeps the state unavailable until a fresh catalog succeeds. */ }
            });
        } catch (Exception ignored) { /* Do not turn an old acknowledgement into owner state. */ }
    }
    private void loadActivity() {
        if (!resumed || activityRepository == null || activityLoading) return;
        activityLoading = true; final int token = watchGeneration;
        activityRepository.snapshot(lease.optString("threadId"), new AgentRepository.Callback<JSONObject>() {
            public void ready(JSONObject snapshot) {
                activityLoading = false; if (!resumed || token != watchGeneration) return;
                try {
                    JSONObject thread = snapshot.optJSONObject("thread");
                    if (thread != null) {
                        java.util.List<AgentConversation.Entry> entries = new java.util.ArrayList<>();
                        // Read the native thread across turns. A new turn without a tool
                        // does not erase the last real app steps from the previous turn.
                        for (AgentConversation.Entry entry : AgentConversation.read(thread).entries)
                            if (entry.kind == AgentConversation.Kind.TOOL && !entry.type.equals("contextCompaction")
                                    && !entry.type.equals("enteredReviewMode") && !entry.type.equals("exitedReviewMode")) entries.add(entry);
                        if (entries.size() > 4) {
                            java.util.Set<String> chosen = new java.util.HashSet<>();
                            for (int i = entries.size() - 1; i >= 0 && chosen.size() < 4; i--)
                                if (stepWorking(entries.get(i).item)) chosen.add(entries.get(i).key());
                            for (int i = entries.size() - 1; i >= 0 && chosen.size() < 4; i--) chosen.add(entries.get(i).key());
                            java.util.List<AgentConversation.Entry> visible = new java.util.ArrayList<>();
                            for (AgentConversation.Entry entry : entries) if (chosen.contains(entry.key())) visible.add(entry);
                            entries = visible;
                        }
                        bindActivity(entries, snapshot.optJSONObject("native"));
                    }
                } catch (Exception ignored) { /* No fabricated progress when native history is unavailable. */ }
                main.postDelayed(activityPoll, 2000);
            }
            public void failed(String message) { activityLoading = false; if (resumed && token == watchGeneration) main.postDelayed(activityPoll, 2000); }
        });
    }
    private void bindActivity(java.util.List<AgentConversation.Entry> entries, JSONObject nativeState) {
        JSONObject outputs = nativeState == null ? null : nativeState.optJSONObject("outputs");
        StringBuilder signature = new StringBuilder();
        for (AgentConversation.Entry entry : entries) signature.append(entry.key()).append(entry.item.toString())
                .append(outputs == null ? "" : outputs.optString(entry.id));
        if (signature.toString().equals(activitySignature)) return;
        activitySignature = signature.toString();
        stepsFrame.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        activityContent.setVisibility(activityOpen && !entries.isEmpty() ? View.VISIBLE : View.GONE);
        stepRows.removeAllViews(); activityRows.removeAllViews();
        NativeToolPresentation.Labels labels = NativeActivityView.labels(this);
        for (int i = 0; i < entries.size(); i++) {
            AgentConversation.Entry entry = entries.get(i);
            String title = NativeToolPresentation.title(entry.item, labels);
            if (entry.type.equals("commandExecution")) title = getString(R.string.workspace_interaction_task_step);
            // Unmapped tool identifiers belong in opt-in details, never in the step title.
            if (title.equals(entry.item.optString("tool")) || title.contains("__") || title.contains("functions."))
                title = getString(R.string.workspace_interaction_app_step);
            String stepStatus = NativeToolPresentation.status(entry.item, labels);
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            View marker = dot(stepColor(entry.item));
            LinearLayout.LayoutParams markerSize = new LinearLayout.LayoutParams(dp(8), dp(8)); markerSize.setMarginEnd(dp(6));
            row.addView(marker, markerSize);
            TextView label = text(title, ChatDesign.Role.META); label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setTextColor(color(stepWorking(entry.item) ? R.color.workspace_viewer_foreground : R.color.workspace_viewer_secondary));
            row.addView(label, new LinearLayout.LayoutParams(-1, -2));
            row.setContentDescription(title + (stepStatus.isEmpty() ? "" : ". " + stepStatus));
            stepRows.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (i < entries.size() - 1) { View divider = new View(this); divider.setBackgroundColor(color(R.color.workspace_viewer_border)); stepRows.addView(divider, new LinearLayout.LayoutParams(-1, dp(1))); }
            TextView detailTitle = text(title + (stepStatus.isEmpty() ? "" : " · " + stepStatus), ChatDesign.Role.META_MEDIUM);
            detailTitle.setPadding(0, dp(8), 0, dp(4)); activityRows.addView(detailTitle);
            String value = NativeToolPresentation.details(entry.item, outputs == null ? "" : outputs.optString(entry.id), labels);
            if (!value.isEmpty()) {
                TextView detail = text(value, ChatDesign.Role.META);
                detail.setTextColor(color(R.color.workspace_viewer_secondary)); detail.setTextIsSelectable(true);
                detail.setPadding(0, 0, 0, dp(8)); activityRows.addView(detail);
            }
        }
    }
    private boolean stepWorking(JSONObject item) {
        return item.optString("status").equals("inProgress") || item.optString("status").equals("in_progress");
    }
    private int stepColor(JSONObject item) {
        if (Boolean.FALSE.equals(item.opt("success")) || item.optString("status").equals("failed") || item.optString("status").equals("errored")
                || item.optString("type").equals("commandExecution") && item.has("exitCode") && !item.isNull("exitCode") && item.optInt("exitCode") != 0)
            return R.color.taku_status_error;
        if (stepWorking(item)) return R.color.taku_status_attention;
        if (item.optString("status").equals("completed")) return R.color.taku_status_success;
        return R.color.taku_status_waiting;
    }
    @Override protected void onResume() { super.onResume(); if (w == null || isFinishing()) return; resumed = true; int token = ++watchGeneration; if (resumeRequest != null) resumeRequest.start(); if (requirement != null) requirement.start(); loadActivity(); new Thread(() -> watch(token), "workspace-preview").start(); }
    @Override protected void onPause() {
        resumed = false; userControl = false; connected = false; controlRevision = -1; cancelGesture(); ++watchGeneration; ++controlGeneration;
        try { if (frames != null) frames.close(); } catch (Exception ignored) {}
        main.removeCallbacks(activityPoll); main.removeCallbacks(expireResumeNotice); activityLoading = false; if (resumeRequest != null) resumeRequest.stop(); if (requirement != null) requirement.stop();
        super.onPause();
    }
    @Override protected void onDestroy() { commands.shutdownNow(); if (activityRepository != null) activityRepository.close(); super.onDestroy(); }

    private static final class Channel implements Closeable {
        final LocalSocket socket = new LocalSocket();
        final PrintWriter request;
        final DataInputStream response;
        Channel(String name) throws Exception {
            try {
                socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)); socket.setSoTimeout(8000);
                int uid = socket.getPeerCredentials().getUid(); if (uid != 0 && uid != 1000 && uid != 2000) throw new IOException("SCREEN_HOST_IDENTITY");
            } catch (Exception failure) { try { socket.close(); } catch (Exception ignored) {} throw failure; }
            request = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            response = new DataInputStream(socket.getInputStream());
        }
        byte[] call(String value) throws Exception {
            request.println(value); int size = response.readInt(); if (size < 0 || size > 4_000_000) throw new IOException("INVALID_FRAME_SIZE");
            byte[] result = new byte[size]; response.readFully(result); return result;
        }
        public void close() throws IOException { socket.close(); }
    }
    private JSONObject verifyLease() throws Exception {
        if (lease == null) return null;
        JSONArray entries = NativeClient.exchange(WorkspaceUi.request("list", null)).getJSONArray("screens");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject current = entries.getJSONObject(i);
            if (WorkspaceUi.same(lease, current)) {
                String status = current.optString("status");
                if (status.equals("unavailable") || status.equals("rebind_required")) throw new IOException("SCREEN_REBIND_REQUIRED");
                return current;
            }
        }
        throw new IOException("SCREEN_GENERATION_CHANGED");
    }
    private void validateHost(Channel channel, JSONObject meta, JSONObject binding) throws Exception {
        int uid = channel.socket.getPeerCredentials().getUid(), pid = channel.socket.getPeerCredentials().getPid();
        if (meta.getInt("uid") != uid || meta.getInt("pid") != pid || meta.getInt("displayId") <= 0) throw new IOException("SCREEN_HOST_IDENTITY");
        if (binding != null) {
            String key = lease.getString("screenKey"), generation = lease.getString("generation");
            if (!key.equals(meta.getString("screenKey")) || !generation.equals(meta.getString("generation"))
                    || binding.getInt("displayId") != meta.getInt("displayId")
                    || !("taku-mirror-" + key).equals(meta.getString("mirrorSocket"))
                    || !("Taku-Independent-Screen-" + key).equals(meta.getString("name"))) throw new IOException("SCREEN_GENERATION_CHANGED");
        } else if (!"default".equals(meta.optString("screenKey")) || !"taku-mirror".equals(meta.optString("mirrorSocket"))) throw new IOException("SCREEN_HOST_IDENTITY");
        int x = meta.getInt("width"), y = meta.getInt("height"); if (x < 1 || y < 1 || x > 4096 || y > 4096) throw new IOException("INVALID_SCREEN_SIZE");
        width = x; height = y;
    }
    private void watch(int token) {
        while (resumed && token == watchGeneration) {
            try {
                JSONObject binding = verifyLease();
                if (!resumed || token != watchGeneration) return;
                String socket = lease == null ? "taku-mirror" : "taku-mirror-" + lease.getString("screenKey");
                try (Channel channel = new Channel(socket)) {
                    if (!resumed || token != watchGeneration) return;
                    frames = channel.socket;
                    validateHost(channel, new JSONObject(new String(channel.call("status"), StandardCharsets.UTF_8)), binding);
                    owner(binding, token); long checked = SystemClock.elapsedRealtime();
                    while (resumed && token == watchGeneration) {
                        if (SystemClock.elapsedRealtime() - checked > 1500) {
                            binding = verifyLease(); validateHost(channel, new JSONObject(new String(channel.call("status"), StandardCharsets.UTF_8)), binding); owner(binding, token); checked = SystemClock.elapsedRealtime();
                        }
                        byte[] jpeg = channel.call("frame");
                        if (jpeg.length > 0) {
                            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                            if (bitmap != null) main.post(() -> {
                                if (resumed && token == watchGeneration) { connected = true; preview.setImageBitmap(bitmap); if (currentScreen != null) bindHeaderStatus(currentScreen); else state.setText(R.string.workspace_view_only); controls(true); }
                                else bitmap.recycle();
                            });
                        } else main.post(() -> { if (resumed && token == watchGeneration) state.setText(R.string.workspace_waiting_frame); });
                        Thread.sleep(200);
                    }
                }
            } catch (Exception error) {
                if (token == watchGeneration) { connected = false; userControl = false; controlRevision = -1; }
                main.post(() -> { if (resumed && token == watchGeneration) { preview.setImageDrawable(null); state.setText(R.string.workspace_connection_lost); controls(false); } });
                try { Thread.sleep(1200); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            } finally { if (token == watchGeneration) frames = null; }
        }
    }
    private void owner(JSONObject current, int token) {
        Runnable apply = () -> {
            if (!resumed || token != watchGeneration) return;
            long revision = current == null ? -1 : current.optLong("controlRevision", -1);
            // A list request started before a handoff may arrive after its receipt.
            if (changing || revision < lastObservedControlRevision) return;
            boolean owned = current != null && "user".equals(current.optString("controlOwner")) && revision >= 0
                    && (resumeRequest == null || !resumeRequest.busy());
            if (revision != controlRevision || owned != userControl) { ++controlGeneration; cancelGesture(); }
            lastObservedControlRevision = revision; controlRevision = revision;
            userControl = owned; ownerCheckedAt = SystemClock.elapsedRealtime();
            currentScreen = current;
            details.setText(current == null ? getString(R.string.workspace_interaction_title) : getString(R.string.workspace_interaction_app, w.appLabel(current)));
            ChatDesign.text(details, ChatDesign.Role.CONTEXT);
            if (current != null && !current.optString("packageName").equals(currentPackage)) {
                currentPackage = current.optString("packageName");
                try { appIcon.setImageDrawable(getPackageManager().getApplicationIcon(currentPackage)); }
                catch (Exception missing) { appIcon.setImageResource(R.drawable.ic_tako_mark); }
            }
            if (current != null) bindHeaderStatus(current);
            if (current != null) {
                control.setText(controlLabel());
                ChatDesign.text(control, ChatDesign.Role.BODY);
            }
            controls(connected);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run(); else main.post(apply);
    }
    private String fullStatus(JSONObject screen) {
        if (screen.optString("controlOwner").equals("user")) return w.status(screen);
        if (screen.optBoolean("resumeRequired") && !screen.optBoolean("canResume"))
            return getString(R.string.workspace_interaction_paused);
        return w.status(screen);
    }
    private void bindHeaderStatus(JSONObject screen) {
        state.setText(fullStatus(screen)); ChatDesign.text(state, ChatDesign.Role.META_MEDIUM);
        String status = screen.optString("status"), owner = screen.optString("controlOwner");
        // Neutral status uses the PRD viewer foreground mapping; the DS waiting
        // token is intended for a light surface and disappears on this dark viewer.
        int colorResource = R.color.workspace_viewer_secondary;
        if (status.equals("unavailable") || status.equals("failed") || status.equals("disconnected")) colorResource = R.color.taku_status_error;
        else if (!owner.equals("paused") && !owner.equals("user") && !screen.optBoolean("resumeRequired")
                && (!screen.optString("activeTurnId").isEmpty() || status.equals("running") || status.equals("working") || screen.optBoolean("waitingForUser")))
            colorResource = R.color.taku_status_attention;
        statusDot.setBackground(surface(colorResource, 999));
    }
    private void controls(boolean available) {
        if (control == null) return;
        boolean pending = resumeRequest != null && resumeRequest.busy();
        control.setText(controlLabel());
        ChatDesign.text(control, ChatDesign.Role.BODY);
        buttonIcon(control, resumeUnknown ? R.drawable.ic_taku_refresh : returnNeeded() ? R.drawable.ic_taku_play : R.drawable.ic_taku_hand);
        control.setEnabled(available && !changing && (!pending || resumeUnknown));
        // Delivery uncertainty must never take away the user's interrupt. Stop has its
        // own exact-lease freshness gate and leaves the resume receipt unresolved.
        stop.setEnabled(!changing && freshStopLease());
        backInApp.setVisibility(userControl ? View.VISIBLE : View.GONE); backInApp.setEnabled(available && userControl && !changing && !pending);
    }
    private boolean freshStopLease() {
        return lease != null && WorkspaceUi.same(lease, currentScreen)
                && currentScreen.optLong("controlRevision", -1) >= 0
                && ownerCheckedAt > 0 && SystemClock.elapsedRealtime() - ownerCheckedAt <= 3000;
    }
    private int controlLabel() {
        return resumeUnknown ? R.string.workspace_interaction_check_status
                : returnNeeded() ? resumeFailed ? R.string.workspace_interaction_retry_continue
                : R.string.workspace_interaction_return_continue : R.string.workspace_interaction_take_over;
    }
    private void change(String action) {
        if (lease == null || changing) return;
        // Recheck at tap time: a displayed button can outlive the latest owner poll.
        if (action.equals("pause") && !freshStopLease()) { controls(connected); return; }
        changing = true; ++controlGeneration; controlRevision = -1; cancelGesture(); userControl = false; controls(false); state.setText(R.string.workspace_action_pending);
        final int token = watchGeneration;
        try {
            NativeClient.async(WorkspaceUi.request(action, lease), new NativeClient.Callback() {
                public void ready(JSONObject result) {
                    changing = false; if (!resumed || token != watchGeneration || isFinishing() || isDestroyed()) return;
                    JSONObject screen = result.optJSONObject("screen"); if (screen != null && WorkspaceUi.same(lease, screen)) owner(screen, token);
                    if (action.equals("pause")) {
                        if (WorkspaceUi.same(lease, screen) && "paused".equals(screen.optString("controlOwner"))
                                && screen.optString("activeTurnId").isEmpty() && !screen.optBoolean("stopping")) finish();
                        else { showNote(getString(R.string.workspace_interaction_stop_unconfirmed)); controls(false); }
                        return;
                    }
                    showNote(""); controls(connected);
                }
                public void failed(String message) { changing = false; if (resumed && token == watchGeneration && !isFinishing() && !isDestroyed()) { userControl = false; cancelGesture(); showNote(w.error(message)); controls(false); } }
            });
        } catch (Exception error) { changing = false; state.setText(R.string.workspace_action_failed); }
    }
    private boolean gesture(MotionEvent event) {
        if (lease == null || !connected || !userControl || changing || SystemClock.elapsedRealtime() - ownerCheckedAt > 3000) { cancelGesture(); return false; }
        float scale = Math.min((float) preview.getWidth() / width, (float) preview.getHeight() / height);
        if (scale <= 0) return false;
        float left = (preview.getWidth() - width * scale) / 2, top = (preview.getHeight() - height * scale) / 2;
        float x = (event.getX() - left) / scale, y = (event.getY() - top) / scale;
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || x < 0 || y < 0 || x >= width || y >= height) { cancelGesture(); return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { preview.getParent().requestDisallowInterceptTouchEvent(true); tracking = true; downX = x; downY = y; downAt = event.getEventTime(); downControlRevision = controlRevision; downControlGeneration = controlGeneration; return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_UP && tracking) {
            cancelGesture();
            if (downControlRevision != controlRevision || downControlGeneration != controlGeneration) return true;
            long duration = event.getEventTime() - downAt; float distance = (float) Math.hypot(x - downX, y - downY);
            try {
                JSONObject command = new JSONObject();
                if (distance < 18 && duration < 500) command.put("type", "tap").put("x", Math.round(x)).put("y", Math.round(y));
                else command.put("type", "swipe").put("fromX", Math.round(downX)).put("fromY", Math.round(downY)).put("toX", Math.round(x)).put("toY", Math.round(y)).put("durationMs", Math.max(150, Math.min(1200, duration)));
                input(command, downControlRevision, downControlGeneration); preview.performClick();
            } catch (Exception ignored) { state.setText(R.string.workspace_input_failed); }
        }
        return true;
    }
    private void cancelGesture() {
        tracking = false;
        if (preview != null && preview.getParent() != null) preview.getParent().requestDisallowInterceptTouchEvent(false);
    }
    private void input(JSONObject input) throws Exception {
        input(input, controlRevision, controlGeneration);
    }
    private void input(JSONObject input, long revision, long controlToken) throws Exception {
        if (!resumed || lease == null || !userControl || revision < 0 || revision != controlRevision || controlToken != controlGeneration || !connected || changing || inputPending) return;
        JSONObject request = WorkspaceUi.request("input", lease).put("controlRevision", revision).put("input", input); final int token = watchGeneration;
        inputPending = true;
        state.setText(R.string.workspace_input_pending);
        commands.execute(() -> {
            try {
                if (!resumed || token != watchGeneration || controlToken != controlGeneration || revision != controlRevision || !userControl) return;
                NativeClient.exchange(request);
            } catch (Exception error) { main.post(() -> { if (resumed && token == watchGeneration && controlToken == controlGeneration && revision == controlRevision) { userControl = false; cancelGesture(); state.setText(R.string.workspace_input_failed); controls(false); } }); }
            finally { inputPending = false; }
        });
    }
}
