package org.agentos.assistant;

import android.content.*;
import android.widget.*;
import org.json.*;

/** Native connection picker; upstream Magpie owns provider keys, routing rules and its management UI. */
final class ModelConnectionView extends LinearLayout {
    private final AgentSettingsUi ui;
    private final SharedPreferences preferences;
    private final TextView status;
    private final LinearLayout content;
    private boolean attached = true, waiting;

    ModelConnectionView(Context context, DrawerTheme theme, SharedPreferences preferences, Runnable dismiss) {
        super(context); setOrientation(VERTICAL);
        this.preferences = preferences; ui = new AgentSettingsUi(context, theme);
        status = ui.text("Reading model connection…", 13, false);
        status.setContentDescription("Model connection status");
        ui.add(this, status, 0);
        content = ui.column(); ui.add(this, content, 12);
        ui.add(this, ui.button("Manage models and routes", false, () -> {
            context.startActivity(new Intent(context, ModelManagementActivity.class)
                    .putExtra("conversationId", preferences.getString("conversation", ""))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            dismiss.run();
        }), 16);
        ui.add(this, ui.button("Refresh model service", false, this::refresh), 8);
        ui.add(this, ui.text("Add an API service or routing group in management, then return and refresh. The new connection is used next turn; conversation history and drafts are preserved.", 12, false), 12);
        refresh();
    }

    private void request(String action, JSONObject params, java.util.function.Consumer<JSONObject> result) {
        if (waiting) return;
        waiting = true;
        try {
            JSONObject input = params == null ? new JSONObject() : params;
            input.put("op", "modelConnection").put("action", action);
            NativeClient.async(input, new NativeClient.Callback() {
                public void ready(JSONObject value) { waiting = false; result.accept(value); }
                public void failed(String message) { waiting = false; if (attached) status.setText(message); }
            });
        } catch (Exception invalid) { waiting = false; status.setText("Invalid model connection request"); }
    }

    private void refresh() { request("read", null, this::render); }

    private void render(JSONObject state) {
        if (!attached) return;
        content.removeAllViews();
        String mode = state.optString("mode", "openai"), selected = state.optString("model");
        if (!mode.equals(preferences.getString("modelConnectionMode", "openai"))) saveOverrides(mode, selected);
        status.setText((mode.equals("magpie") ? "Current: Local Magpie" : "Current: ChatGPT")
                + "\nMagpie " + state.optString("version") + " · " + state.optString("message"));
        LinearLayout direct = ui.listGroup();
        ui.chooseRow(direct, "ChatGPT sign-in", mode.equals("openai"), () -> select("openai", ""));
        ui.add(content, direct, 0);
        JSONArray models = state.optJSONArray("models");
        LinearLayout choices = ui.listGroup();
        if (models != null) for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i); if (model == null) continue;
            String id = model.optString("id");
            ui.chooseRow(choices, model.optString("name", id), mode.equals("magpie") && id.equals(selected),
                    () -> select("magpie", id));
        }
        if (choices.getChildCount() == 0)
            ui.add(choices, ui.text("No models are available. Open management and add a service first.", 13, false), 12);
        ui.section(content, "Local gateway · Default model or routing group", choices);
    }

    private void select(String mode, String model) {
        try {
            request("select", new JSONObject().put("mode", mode).put("model", model), value -> {
                saveOverrides(mode, model);
                if (attached) refresh();
            });
        } catch (Exception invalid) { status.setText("Cannot select this model connection"); }
    }

    private static boolean modelField(String name) {
        return name.equals("model") || name.equals("effort") || name.equals("serviceTier")
                || name.matches("composer\\.[a-f0-9-]{36}\\.(model|effort|serviceTier)");
    }

    private void saveOverrides(String mode, String model) {
        // Keep each connection's selections without touching drafts, receipts, attachments or history.
        SharedPreferences.Editor edit = preferences.edit();
        String previous = preferences.getString("modelConnectionMode", "openai");
        if (!mode.equals(previous)) try {
            JSONObject saved = new JSONObject();
            for (String key : preferences.getAll().keySet()) if (modelField(key)) {
                saved.put(key, preferences.getString(key, "")); edit.remove(key);
            }
            edit.putString("modelSettings." + previous, saved.toString());
            JSONObject restore = new JSONObject(preferences.getString("modelSettings." + mode, "{}"));
            for (java.util.Iterator<String> keys = restore.keys(); keys.hasNext();) {
                String key = keys.next(); if (modelField(key)) edit.putString(key, restore.optString(key));
            }
        } catch (JSONException ignored) { /* An invalid optional preference snapshot is disposable. */ }
        if (mode.equals("magpie")) edit.putString("model", model).remove("effort").remove("serviceTier");
        edit.putString("modelConnectionMode", mode).apply();
    }

    @Override protected void onDetachedFromWindow() { attached = false; super.onDetachedFromWindow(); }
}
