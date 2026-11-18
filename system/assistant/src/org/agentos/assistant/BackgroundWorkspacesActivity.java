package org.agentos.assistant;

import android.app.*;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** System settings entry for the broker's real screen leases and automation policy. */
public final class BackgroundWorkspacesActivity extends Activity {
    private WorkspaceUi w;
    private LinearLayout body;
    private ScrollView scroll;
    private JSONObject snapshot;
    private boolean resumed, loading, mutation, choosingApps;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = () -> { if (resumed) refresh(false); };

    @Override public void onCreate(Bundle saved) {
        WorkspaceUi.applySystemTheme(this); super.onCreate(saved); w = new WorkspaceUi(this);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finish);
        LinearLayout root = w.root();
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = w.ui.column(); body.setPadding(w.ui.dp(20), w.ui.dp(4), w.ui.dp(20), w.ui.dp(28)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        header(); w.ui.add(body, w.ui.text(getString(R.string.workspace_loading), 14, false), 24);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; refresh(true); }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(poll); super.onPause(); }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private void schedule() { main.removeCallbacks(poll); if (resumed) main.postDelayed(poll, 4000); }
    private void refresh(boolean force) {
        if (loading || mutation || choosingApps) { schedule(); return; }
        loading = true;
        try {
            NativeClient.async(WorkspaceUi.request("list", null), new NativeClient.Callback() {
                public void ready(JSONObject result) {
                    loading = false; if (!alive()) return;
                    boolean changed = snapshot == null || !snapshot.toString().equals(result.toString()); snapshot = result;
                    if (force || changed) render(); schedule();
                }
                public void failed(String message) {
                    loading = false; if (!alive()) return; snapshot = null; unavailable(); schedule();
                }
            });
        } catch (Exception error) { loading = false; unavailable(); schedule(); }
    }
    private void unavailable() {
        body.removeAllViews(); header(); LinearLayout card = w.ui.card();
        w.ui.add(card, w.ui.text(getString(R.string.workspace_unavailable_title), 18, true), 0);
        w.ui.add(card, w.ui.text(getString(R.string.workspace_unavailable_copy), 14, false), 10);
        w.ui.add(card, w.ui.button(getString(R.string.workspace_refresh), false, () -> refresh(true)), 16); w.ui.add(body, card, 8);
    }
    private void header() {
        w.navigation(body, getString(R.string.workspace_breadcrumb), this::finish);
        w.ui.add(body, w.ui.text(getString(R.string.workspace_title), 27, true), 8);
        w.ui.add(body, w.ui.text(getString(R.string.workspace_summary), 13, false), 8);
    }
    private void render() {
        if (snapshot == null) return;
        int oldScroll = scroll.getScrollY(); body.removeAllViews(); header();
        JSONArray screens = snapshot.optJSONArray("screens"); if (screens == null) screens = new JSONArray();
        JSONObject policy = snapshot.optJSONObject("policy"); if (policy == null) policy = new JSONObject();
        if (screens.length() == 0) {
            LinearLayout empty = w.ui.card(); w.ui.add(empty, w.ui.text(getString(R.string.workspace_empty_title), 16, true), 0);
            w.ui.add(empty, w.ui.text(getString(R.string.workspace_empty_copy), 13, false), 8);
            w.ui.section(body, getString(R.string.workspace_active), empty);
        } else {
            taskSummary(screens);
            LinearLayout workspaces = w.ui.listGroup();
            for (int n = 0; n < screens.length(); n++) { JSONObject screen = screens.optJSONObject(n); if (screen != null) screenRow(workspaces, screen); }
            w.ui.section(body, getString(R.string.workspace_active), workspaces);
        }
        LinearLayout preferences = w.ui.listGroup();
        LinearLayout allowRow = preferenceRow(preferences, getString(R.string.workspace_allow), getString(R.string.workspace_allow_copy));
        Switch allow = new Switch(this); allow.setShowText(false); allow.setMinHeight(w.ui.dp(48));
        allow.setContentDescription(getString(R.string.workspace_allow));
        android.content.res.ColorStateList tint = new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{w.theme.accent, w.theme.muted});
        allow.setThumbTintList(tint); allow.setTrackTintList(tint); allow.setChecked(policy.optBoolean("allowBackground", true));
        allow.setOnCheckedChangeListener((button, value) -> configure("allowBackground", value));
        LinearLayout.LayoutParams toggle = new LinearLayout.LayoutParams(-2, -2); toggle.leftMargin = w.ui.dp(12); allowRow.addView(allow, toggle);
        // The broker owns the range (WorkspacePolicy); an older broker without limits leaves it read-only.
        final int selected = policy.optInt("maxActiveScreens");
        JSONObject limits = snapshot.optJSONObject("limits");
        final JSONObject range = limits == null ? null : limits.optJSONObject("maxActiveScreens");
        LinearLayout limitRow = preferenceRow(preferences, getString(R.string.workspace_limit), getString(R.string.workspace_limit_copy));
        TextView limit = w.ui.text(Integer.toString(selected), 15, true); limit.setTextColor(w.theme.accent);
        limit.setGravity(Gravity.CENTER); limit.setMinWidth(w.ui.dp(32)); limit.setMinHeight(w.ui.dp(40));
        limit.setBackground(w.theme.rounded(this, w.theme.soft, 10, false));
        LinearLayout.LayoutParams limitSize = new LinearLayout.LayoutParams(-2, -2); limitSize.leftMargin = w.ui.dp(12); limitRow.addView(limit, limitSize);
        if (range != null) {
            chevron(limitRow);
            limitRow.setOnClickListener(v -> chooseLimit(range.optInt("min", 1), range.optInt("max", 1), selected));
        }
        LinearLayout appsRow = preferenceRow(preferences, getString(R.string.workspace_allowed_apps), getString(R.string.workspace_allowed_apps_copy));
        chevron(appsRow); appsRow.setOnClickListener(v -> chooseApps());
        w.ui.section(body, getString(R.string.workspace_preferences), preferences);
        TextView note = w.ui.text(getString(policy.optBoolean("allowBackground", true) ? R.string.workspace_shared_note : R.string.workspace_background_off_copy), 12, false);
        note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); w.ui.add(body, note, 16);
        setEnabled(body, !mutation); scroll.post(() -> scroll.scrollTo(0, oldScroll));
    }
    private void taskSummary(JSONArray screens) {
        Set<String> threads = new HashSet<>(); JSONObject onlyTask = null;
        for (int n = 0; n < screens.length(); n++) {
            JSONObject screen = screens.optJSONObject(n); if (screen == null) continue;
            String thread = screen.optString("threadId");
            if (AgentRepository.validId(thread)) { threads.add(thread); onlyTask = screen; }
        }
        int taskCount = threads.size();
        LinearLayout summary = w.ui.card();
        String title = taskCount == 1 && onlyTask != null ? w.taskLabel(onlyTask)
                : getResources().getQuantityString(R.plurals.workspace_tasks, taskCount, taskCount);
        w.ui.add(summary, w.ui.text(title, 16, true), 0);
        if (taskCount != 1) w.ui.add(summary, w.ui.text(getString(R.string.workspace_tasks_copy), 13, false), 5);
        LinearLayout count = new LinearLayout(this); count.setGravity(Gravity.CENTER_VERTICAL);
        ImageView layers = new ImageView(this); layers.setImageResource(R.drawable.ic_home_widgets); layers.setColorFilter(w.theme.accent);
        layers.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams mark = new LinearLayout.LayoutParams(w.ui.dp(17), w.ui.dp(17)); mark.rightMargin = w.ui.dp(8); count.addView(layers, mark);
        String workspaces = getResources().getQuantityString(R.plurals.workspace_spaces, screens.length(), screens.length());
        String tasks = getResources().getQuantityString(R.plurals.workspace_tasks, taskCount, taskCount);
        TextView countText = w.ui.text(getString(R.string.workspace_task_counts, workspaces, tasks), 13, false); countText.setTextColor(w.theme.accent);
        count.addView(countText, new LinearLayout.LayoutParams(0, -2, 1)); w.ui.add(summary, count, 14);
        w.ui.section(body, getString(taskCount == 1 ? R.string.workspace_current_task : R.string.workspace_current_tasks), summary);
    }
    private void screenRow(LinearLayout group, JSONObject screen) {
        w.ui.divider(group, 68);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(w.ui.dp(16), w.ui.dp(16), w.ui.dp(16), w.ui.dp(16));
        row.setMinimumHeight(w.ui.dp(76)); row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(w.theme.border), null, null));
        ImageView icon = new ImageView(this); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.setBackground(w.theme.rounded(this, w.theme.soft, 12, false)); icon.setClipToOutline(true);
        try { icon.setImageDrawable(getPackageManager().getApplicationIcon(screen.getString("packageName"))); }
        catch (Exception ignored) { icon.setImageResource(R.drawable.ic_home_apps); icon.setColorFilter(w.theme.accent); icon.setPadding(w.ui.dp(8), w.ui.dp(8), w.ui.dp(8), w.ui.dp(8)); }
        LinearLayout.LayoutParams mark = new LinearLayout.LayoutParams(w.ui.dp(40), w.ui.dp(40)); mark.rightMargin = w.ui.dp(12); row.addView(icon, mark);
        LinearLayout labels = w.ui.column(); w.ui.add(labels, w.ui.text(w.appLabel(screen), 16, true), 0);
        w.ui.add(labels, w.ui.text(w.status(screen), 12, false), 3); row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        chevron(row); row.setOnClickListener(v -> w.detail(screen)); group.addView(row);
    }
    private LinearLayout preferenceRow(LinearLayout group, String title, String subtitle) {
        w.ui.divider(group, 16);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(w.ui.dp(16), w.ui.dp(16), w.ui.dp(16), w.ui.dp(16));
        row.setMinimumHeight(w.ui.dp(72)); row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(w.theme.border), null, null));
        LinearLayout copy = w.ui.column(); w.ui.add(copy, w.ui.text(title, 15, true), 0); w.ui.add(copy, w.ui.text(subtitle, 12, false), 3);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); group.addView(row); return row;
    }
    private void chevron(LinearLayout row) {
        ImageView arrow = new ImageView(this); arrow.setImageResource(R.drawable.ic_drawer_chevron_down); arrow.setRotation(-90); arrow.setColorFilter(w.theme.muted);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(w.ui.dp(16), w.ui.dp(16)); size.leftMargin = w.ui.dp(12); row.addView(arrow, size);
    }
    private void chooseLimit(int min, int max, int selected) {
        String[] values = new String[Math.max(0, max - min + 1)];
        for (int i = 0; i < values.length; i++) values[i] = getString(R.string.workspace_limit_value, min + i);
        new AlertDialog.Builder(this).setTitle(R.string.workspace_limit).setSingleChoiceItems(values, selected - min, (dialog, index) -> {
            dialog.dismiss();
            configure("maxActiveScreens", min + index);
        }).setNegativeButton(R.string.workspace_cancel, null).show();
    }
    private void configure(String key, Object value) {
        try { mutate(WorkspaceUi.request("configure", null).put("patch", new JSONObject().put(key, value)), false); }
        catch (Exception error) { toast(R.string.workspace_action_failed); }
    }
    private void mutate(JSONObject request, boolean returned) {
        if (mutation) return; mutation = true; main.removeCallbacks(poll); setEnabled(body, false);
        NativeClient.async(request, new NativeClient.Callback() {
            public void ready(JSONObject result) { mutation = false; if (!alive()) return; if (returned || result.optBoolean("resumeRequired")) toast(R.string.workspace_returned_copy); refresh(true); }
            public void failed(String message) { mutation = false; if (!alive()) return; Toast.makeText(BackgroundWorkspacesActivity.this, w.error(message), Toast.LENGTH_LONG).show(); refresh(true); }
        });
    }
    private void chooseApps() {
        if (choosingApps || snapshot == null) return; choosingApps = true;
        toast(R.string.workspace_apps_loading);
        JSONObject policy = snapshot.optJSONObject("policy"); Set<String> blocked = new TreeSet<>();
        JSONArray existing = policy == null ? null : policy.optJSONArray("blockedPackages");
        if (existing != null) for (int i = 0; i < existing.length(); i++) blocked.add(existing.optString(i));
        NativeClient.io.execute(() -> {
            try {
                List<ResolveInfo> matches = getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
                Map<String, String> installed = new TreeMap<>();
                for (ResolveInfo info : matches) if (info.activityInfo != null && info.activityInfo.applicationInfo != null)
                    installed.putIfAbsent(info.activityInfo.packageName, info.loadLabel(getPackageManager()).toString());
                List<String> packages = new ArrayList<>(installed.keySet()); packages.sort((a, b) -> {
                    int byName = installed.get(a).compareToIgnoreCase(installed.get(b)); return byName == 0 ? a.compareTo(b) : byName;
                });
                main.post(() -> showApps(packages, installed, blocked));
            } catch (Exception error) { main.post(() -> { choosingApps = false; if (alive()) toast(R.string.workspace_apps_failed); schedule(); }); }
        });
    }
    private void showApps(List<String> packages, Map<String, String> names, Set<String> blocked) {
        if (!alive() || !resumed) { choosingApps = false; return; }
        if (packages.isEmpty()) { choosingApps = false; toast(R.string.workspace_apps_empty); schedule(); return; }
        String[] labels = new String[packages.size()]; boolean[] enabled = new boolean[packages.size()];
        for (int i = 0; i < packages.size(); i++) { String pkg = packages.get(i); labels[i] = names.get(pkg); enabled[i] = !blocked.contains(pkg); }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.workspace_allowed_apps)
                .setMultiChoiceItems(labels, enabled, (d, index, allowed) -> { if (allowed) blocked.remove(packages.get(index)); else blocked.add(packages.get(index)); })
                .setNegativeButton(R.string.workspace_cancel, null).setPositiveButton(R.string.workspace_save, (d, n) -> {
                    choosingApps = false; JSONArray list = new JSONArray(); for (String pkg : blocked) list.put(pkg); configure("blockedPackages", list);
                }).create();
        dialog.setOnDismissListener(d -> { choosingApps = false; schedule(); }); dialog.show();
    }
    private void toast(int message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled); if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) setEnabled(group.getChildAt(i), enabled);
    }
}
