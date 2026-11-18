package org.agentos.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.text.TextUtils;
import android.widget.*;
import org.json.*;

/** Compact inspection of actual managed context. Full content and receipts expand in place. */
final class AgentHarnessView extends LinearLayout {
    private final AgentSettingsUi ui;
    private final AgentRepository repository;
    private final String thread;
    private final LinearLayout body;
    private final TextView status;
    private boolean busy;

    AgentHarnessView(Context context, DrawerTheme theme, AgentRepository repository, String thread) {
        super(context);
        setOrientation(VERTICAL);
        ui = new AgentSettingsUi(context, theme);
        this.repository = repository;
        this.thread = thread;
        status = ui.text("Reading actual context…", 12, false);
        status.setContentDescription("Harness status");
        addView(status);
        body = ui.column();
        addView(body);
        load(false);
    }

    void options(View anchor) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 0, 0, "Reload Harness");
        menu.getMenu().add(0, 1, 1, "Append current environment update");
        menu.setOnMenuItemClickListener(item -> { load(item.getItemId() == 1); return true; });
        menu.show();
    }

    private void load(boolean append) {
        if (busy) return;
        busy = true;
        status.setVisibility(VISIBLE);
        status.setText(append ? "Appending context update…" : "Reading context version…");
        JSONObject params = new JSONObject();
        try {
            params.put("threadId", thread)
                    .put("responseStyle", repository.preferences().getString("responseStyle", "concise"))
                    .put("includeScreenshot", repository.preferences().getBoolean("includeScreenshot", false));
        } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
        repository.rpc(append ? "harness/refresh" : "harness/read", params,
                new AgentRepository.Callback<JSONObject>() {
                    public void ready(JSONObject result) { busy = false; render(result); }
                    public void failed(String message) { busy = false; status.setText(message); }
                });
    }

    private LinearLayout group() {
        LinearLayout group = ui.column();
        group.setBackground(ui.theme.rounded(getContext(), ui.theme.raised, 14, false));
        return group;
    }

    private void section(String label, LinearLayout group, int margin) {
        ui.add(body, ui.text(label, 12, false), margin);
        ui.add(body, group, 6);
    }

    private void row(LinearLayout group, String title, String subtitle, String details, String accessibility) {
        if (group.getChildCount() > 0) {
            View divider = new View(getContext());
            divider.setBackgroundColor(ui.theme.border);
            LayoutParams line = new LayoutParams(-1, ui.dp(1));
            line.leftMargin = ui.dp(12); line.rightMargin = ui.dp(12);
            group.addView(divider, line);
        }
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(ui.dp(subtitle.isEmpty() ? 44 : 52));
        header.setPadding(ui.dp(12), ui.dp(7), ui.dp(10), ui.dp(7));
        header.setContentDescription(accessibility);
        header.setBackground(new RippleDrawable(ColorStateList.valueOf(ui.theme.border), null, null));
        LinearLayout labels = ui.column();
        TextView heading = ui.text(title, 14, true);
        heading.setMaxLines(1); heading.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(heading);
        if (!subtitle.isEmpty()) {
            TextView summary = ui.text(subtitle, 11, false);
            summary.setMaxLines(1); summary.setEllipsize(TextUtils.TruncateAt.END);
            ui.add(labels, summary, 3);
        }
        header.addView(labels, new LayoutParams(0, -2, 1));
        ImageView disclosure = new ImageView(getContext());
        disclosure.setImageResource(R.drawable.ic_drawer_chevron_down);
        disclosure.setColorFilter(ui.theme.muted);
        disclosure.setRotation(-90);
        disclosure.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(disclosure, new LayoutParams(ui.dp(18), ui.dp(18)));
        TextView content = ui.text(details, 11, false);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(12));
        content.setVisibility(GONE);
        header.setOnClickListener(view -> {
            boolean expand = content.getVisibility() == GONE;
            content.setVisibility(expand ? VISIBLE : GONE);
            disclosure.setRotation(expand ? 0 : -90);
        });
        group.addView(header);
        group.addView(content);
    }

    private void render(JSONObject data) {
        body.removeAllViews();
        JSONObject state = data.optJSONObject("state");
        String warning = data.optBoolean("notYetInjected") ? "No dynamic context has been sent in this conversation" : "";
        if (state != null && state.has("pending")) warning = "An injection receipt is unconfirmed; further sending is paused.";
        if (data.optBoolean("auditIncomplete")) warning += " Some receipts were not saved; native history remains authoritative for execution.";
        status.setText(warning.trim());
        status.setVisibility(warning.isEmpty() ? GONE : VISIBLE);

        TextView heading = ui.text("Harness · Current conversation", 14, true);
        heading.setTypeface(null, Typeface.BOLD);
        ui.add(body, heading, 0);
        ui.add(body, ui.text("v" + (state == null ? 0 : state.optInt("revision")) + " · Stable prefix: "
                + (state == null ? "Not injected yet" : shortHash(state.optString("prefixHash"))), 11, false), 4);
        LinearLayout context = group();
        row(context, "View assembled stable context", "Showing only context managed by Taku", data.optString("background", "Not injected yet")
                + "\n\nInjection location: " + data.optString("placement") + "\nHidden runtime system prompts are not included.", "Harness stable context");
        JSONObject current = data.optJSONObject("current");
        JSONObject environment = current == null ? null : current.optJSONObject("environment");
        if (environment != null) {
            JSONObject os = environment.optJSONObject("os");
            String summary = os == null ? "View observed environment data" : os.optString("distribution") + " · Android "
                    + os.optString("release") + " / API " + os.optString("api") + " · "
                    + (os.optBoolean("emulator") ? "Emulator" : "Physical device");
            row(context, "OS and execution environment", summary, pretty(environment)
                    + "\nGlobal SELinux status does not establish Agent isolation; prepared tooling does not establish ROM inclusion.", "Harness environment details");
        }
        ui.add(body, context, 8);

        JSONArray modules = data.optJSONArray("modules");
        LinearLayout moduleGroup = group();
        if (modules != null) for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.optJSONObject(i);
            if (module == null) continue;
            row(moduleGroup, module.optString("title"), module.optString("id") + " · v" + module.optString("version"),
                    "Source: " + module.optString("source") + "\nLocation: " + module.optString("placement")
                            + "\nSHA-256：" + module.optString("hash") + "\n\n" + module.optString("content")
                            + "\n\n" + pretty(module.optJSONObject("data")), "Harness module " + module.optString("id"));
        }
        section("Context module · " + (modules == null ? 0 : modules.length()), moduleGroup, 12);

        JSONArray events = data.optJSONArray("events");
        LinearLayout timeline = group();
        if (events != null) for (int i = events.length() - 1; i >= 0; i--) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            String kind = event.optString("kind"), subtitle = event.optString("nativeTurnId");
            if (event.has("changedModules")) subtitle = "Changes " + event.optString("changedModules");
            if (kind.equals("usage")) {
                JSONObject usage = event.optJSONObject("usage"), last = usage == null ? null : usage.optJSONObject("last");
                if (last != null) subtitle = "Input " + last.optLong("inputTokens") + " · Cached " + last.optLong("cachedInputTokens") + " tokens";
            }
            if (kind.equals("tool")) subtitle = event.optString("command");
            row(timeline, "#" + event.optInt("sequence") + " · " + eventTitle(kind) + " · "
                    + event.optString("phase", event.optString("status")), subtitle, pretty(event),
                    "Harness receipt " + event.optInt("sequence"));
        }
        if (events == null || events.length() == 0) ui.add(timeline, ui.text("No injection or execution receipts yet.", 12, false), 8);
        section("Injection and execution chain · " + (events == null ? 0 : events.length()), timeline, 12);
        ui.add(body, ui.text(data.optString("cachePolicy") + "\n" + data.optString("causalityBoundary"), 11, false), 12);
    }

    private static String pretty(JSONObject value) {
        if (value == null) return "{}";
        try { return value.toString(2); } catch (JSONException impossible) { return value.toString(); }
    }

    private static String eventTitle(String kind) {
        switch (kind) {
            case "background": return "Conversation context";
            case "existing-thread-background": return "Append conversation context";
            case "context-update": return "Context update";
            case "turn": return "Execution turn";
            case "tool": return "Tool call";
            case "usage": return "Actual usage";
            default: return kind;
        }
    }

    private static String shortHash(String value) { return value.length() > 12 ? value.substring(0, 12) : value; }
}
