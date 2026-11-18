package org.agentos.assistant;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.*;
import org.json.*;

/** Optional runtime state only. No plan, goal or request is created by the UI. */
final class NativeTimelineView extends LinearLayout {
    NativeTimelineView(Context context, DrawerTheme theme, JSONObject state, ConversationExpansion expansion) {
        super(context);
        setOrientation(VERTICAL);
        if (state == null) return;
        AgentSettingsUi ui = new AgentSettingsUi(context, theme);
        LinearLayout details = ui.column();
        JSONObject plan = state.optJSONObject("plan");
        JSONArray steps = plan == null ? null : plan.optJSONArray("plan");
        if (steps != null && steps.length() > 0) {
            LinearLayout card = ui.card();
            ui.add(card, ui.heading("Current plan"), 0);
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                String status = step.optString("status");
                String marker = status.equals("completed") ? "✓ "
                        : status.equals("in_progress") || status.equals("inProgress") ? "● " : "○ ";
                ui.add(card, ui.text(marker + step.optString("step"), 14, !status.equals("pending")), 8);
            }
            if (!plan.optString("explanation").isEmpty())
                ui.add(card, ui.text(plan.optString("explanation"), 12, false), 8);
            ui.add(details, card, 12);
        }
        JSONObject goal = state.optJSONObject("goal");
        if (goal != null) {
            LinearLayout card = ui.card();
            ui.add(card, ui.heading("Current goal"), 0);
            ui.add(card, ui.text(goal.optString("objective"), 14, true), 8);
            ui.add(card, ui.text(goal.optString("status") + " · Used " + goal.optLong("tokensUsed") + " Token", 12, false), 6);
            ui.add(details, card, 12);
        }
        if (!state.optString("error").isEmpty())
            ui.add(this, ui.text(state.optString("error"), 14, false), 8);
        JSONArray requests = state.optJSONArray("requests");
        if (requests != null) for (int i = 0; i < requests.length(); i++) {
            JSONObject request = requests.optJSONObject(i);
            if (request != null) ui.add(this, new NativeRequestView(context, theme, request), 12);
        }
        String diff = state.optString("diff");
        if (!diff.isEmpty()) {
            LinearLayout changes = ui.card();
            TextView text = ui.text(diff, 12, false);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextIsSelectable(true);
            text.setVisibility(GONE);
            ui.add(changes, ui.button("File changes", false,
                    () -> text.setVisibility(text.getVisibility() == GONE ? VISIBLE : GONE)), 0);
            ui.add(changes, text, 8);
            ui.add(details, changes, 12);
        }
        if (details.getChildCount() > 0) {
            boolean open = expansion.contains("runtime-details");
            Button toggle = ui.button(open ? "Collapse plan and changes" : "Expand plan and changes", false, () -> {});
            toggle.setOnClickListener(view -> {
                expansion.toggle("runtime-details");
                boolean expanded = expansion.contains("runtime-details");
                details.setVisibility(expanded ? VISIBLE : GONE);
                toggle.setText(expanded ? "Collapse plan and changes" : "Expand plan and changes");
            });
            ui.add(this, toggle, 4);
            details.setVisibility(open ? VISIBLE : GONE);
            addView(details);
        }
        // Item output is rendered beside its native item by NativeActivityView, never as a second feed.
        // Context usage remains available in the existing native capability/settings surface.
    }
}
