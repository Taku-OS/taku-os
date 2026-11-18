package org.agentos.assistant;

import android.content.*;
import android.widget.*;
import org.json.*;

/** Decision selection shares Magpie's catalog and provider management with the main model. */
final class JevConnectionView extends LinearLayout {
    private final AgentSettingsUi ui;
    private final AgentRepository repository;
    private final TextView status;
    private final LinearLayout choices;
    private final Switch enabled;
    private boolean attached = true, waiting, updating;
    private String selected = "";

    JevConnectionView(Context context, DrawerTheme theme, AgentRepository repository, Runnable dismiss) {
        super(context); setOrientation(VERTICAL);
        this.repository = repository; ui = new AgentSettingsUi(context, theme);
        enabled = new Switch(context); enabled.setText("Enable Jev decision model");
        enabled.setTextColor(theme.ink); enabled.setChecked(repository.preferences().getBoolean("jevEnabled", false));
        ui.add(this, enabled, 0);
        status = ui.text("Reading Magpie decision models…", 13, false);
        status.setContentDescription("Jev local configuration status"); ui.add(this, status, 12);
        choices = ui.column(); ui.add(this, choices, 12);
        enabled.setOnCheckedChangeListener((button, value) -> {
            if (!updating) save(selected, value);
        });
        ui.add(this, ui.button("Manage models and routes", false, () -> {
            context.startActivity(new Intent(context, ModelManagementActivity.class)
                    .putExtra("conversationId", repository.preferences().getString("conversation", ""))
                    .putExtra("returnPage", "jevConnection").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            dismiss.run();
        }), 16);
        ui.add(this, ui.button("Refresh decision models", false, this::refresh), 8);
        ui.add(this, ui.button("Test decision connection", false, () -> {
            if (waiting) return;
            waiting = true; status.setText("Sending a synthetic text test through Magpie…");
            repository.testJev(new AgentRepository.Callback<JSONObject>() {
                public void ready(JSONObject result) { waiting = false; if (attached) status.setText("Connected. The test uses synthetic text and incurs a small amount of model usage."); }
                public void failed(String message) { waiting = false; if (attached) status.setText(message); }
            });
        }), 8);
        ui.add(this, ui.text("Manage service credentials and routes in Magpie. Select the Jev scene routing group here without changing the main conversation model."
                + "Text models read page text from this invocation; image models can read the screenshot. After accepting a suggestion, the original Agent receives the same screenshot."
                + "This remains an explicit test trigger; it does not continuously observe the screen.", 12, false), 12);
        refresh();
    }

    private void save(String model, boolean value) {
        if (waiting) { restoreSwitch(); return; }
        waiting = true; enabled.setEnabled(false);
        repository.saveJevConfig(model, value, new AgentRepository.Callback<Boolean>() {
            public void ready(Boolean result) { waiting = false; if (attached) { restoreSwitch(); refresh(); } }
            public void failed(String message) { waiting = false; if (attached) { restoreSwitch(); status.setText(message); } }
        });
    }

    private void restoreSwitch() {
        updating = true;
        enabled.setChecked(repository.preferences().getBoolean("jevEnabled", false));
        enabled.setEnabled(true); updating = false;
    }

    private void refresh() {
        if (waiting) return;
        waiting = true; status.setText("Reading Magpie decision models…");
        repository.jevConfig(new AgentRepository.Callback<JSONObject>() {
            public void ready(JSONObject config) {
                waiting = false; if (!attached) return;
                restoreSwitch(); choices.removeAllViews();
                JSONArray models = config.optJSONArray("models");
                selected = config.optString("selected");
                boolean found = false;
                LinearLayout rows = ui.listGroup();
                if (models != null) for (int i = 0; i < models.length(); i++) {
                    JSONObject model = models.optJSONObject(i); if (model == null) continue;
                    String id = model.optString("id"); found |= id.equals(selected);
                    String label = model.optString("name", id) + (model.optBoolean("imageInput") ? " · Text and images" : model.optBoolean("inputKnown") ? " · Text" : " · Page text (image capability unknown)");
                    ui.chooseRow(rows, label, id.equals(selected),
                            () -> save(id, repository.preferences().getBoolean("jevEnabled", false)));
                }
                ui.add(choices, rows, 0);
                status.setText(models == null || models.length() == 0
                        ? "No decision models are available. Add a service in Magpie and refresh."
                        : found ? "Current decision model: " + selected : "Select a decision model. The original selection will not be silently replaced.");
            }
            public void failed(String message) { waiting = false; if (attached) { restoreSwitch(); status.setText(message); } }
        });
    }

    @Override protected void onDetachedFromWindow() { attached = false; super.onDetachedFromWindow(); }
}
