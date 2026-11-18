package org.agentos.assistant;

import android.app.*;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;

/** Native BYOK form; reads return only masked key summaries. */
public final class ByokSettingsActivity extends Activity {
    private AgentSettingsUi ui;
    private DrawerTheme theme;
    private JSONObject state;
    private boolean busy;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        theme = new DrawerTheme((getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        ui = new AgentSettingsUi(this, theme);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finish);
        render();
    }
    @Override public void onResume() { super.onResume(); refresh(); }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private void refresh() {
        if (busy) return;
        busy = true;
        NativeClient.async(request("read"), new NativeClient.Callback() {
            public void ready(JSONObject value) { busy = false; state = value; if (alive()) render(); }
            public void failed(String message) { busy = false; if (alive()) { render(); toast(message); } }
        });
    }
    private JSONObject request(String action) {
        try { return new JSONObject().put("op", "byok").put("action", action).put("provider", "openrouter"); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private void render() {
        LinearLayout root = ui.column(); root.setBackgroundColor(theme.surface);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(ui.dp(22), bars.top + ui.dp(12), ui.dp(22), bars.bottom + ui.dp(12));
            return insets;
        });
        ui.add(root, ui.button("Back to Taku AI", false, this::finish), 0);
        ui.add(root, ui.text("BYOK", 30, true), 18);
        ui.add(root, ui.text("Use your own API Key. The provider bills you directly.", 14, false), 8);
        ScrollView scroll = new ScrollView(this); LinearLayout body = ui.column();
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (state == null) ui.add(body, ui.text("Key status has not been read", 14, false), 16);
        else openRouterSection(body);
        ui.add(body, ui.button("Taku Audio · Voice and fal Key", false, () -> {
            try { startActivity(AudioEngine.homeIntent()); }
            catch (android.content.ActivityNotFoundException unavailable) { toast("Install Taku Audio first"); }
        }), 20);
        ui.add(body, ui.button("Refresh", false, this::refresh), 20);
        ui.add(body, ui.text("Keys stay on this device; only the last four characters are shown. Saved status does not establish provider validation.", 12, false), 12);
        setContentView(root);
        int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(theme.dark ? 0 : lightBars, lightBars);
    }
    private void openRouterSection(LinearLayout body) {
        LinearLayout card = ui.card(); ui.add(card, ui.text("After saving, select available models in Taku AI. Adding a key does not switch the current assistant connection.", 13, false), 0);
        JSONArray keys = state.optJSONArray("keys"); boolean found = false;
        if (keys != null) for (int i = 0; i < keys.length(); i++) {
            JSONObject key = keys.optJSONObject(i);
            if (key == null || !"openrouter".equals(key.optString("provider"))) continue;
            found = true;
            ui.add(card, ui.text(key.optString("name") + " · Saved " + key.optString("masked"), 14, true), 16);
            ui.add(card, ui.button("Replace key", false, () -> edit(key)), 8);
            ui.add(card, ui.button("Remove this key", false, () -> remove(key)), 6);
        }
        if (!found) ui.add(card, ui.text("No API Key added", 14, false), 14);
        ui.add(card, ui.button("Add OpenRouter API Key", true, () -> edit(null)), 14);
        ui.add(card, ui.button("Manage models and routes", false, () -> {
            if (!busy) startActivity(new Intent(this, ModelManagementActivity.class).putExtra("returnPage", "systemAi"));
        }), 8);
        ui.section(body, "OpenRouter", card);
    }
    private void edit(JSONObject existing) {
        if (busy) return;
        LinearLayout form = ui.column(); form.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(8));
        EditText field = ui.input(existing == null ? "Paste API Key" : "Paste new API Key",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true); field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setContentDescription("OpenRouter API Key"); ui.add(form, field, 0);
        TextView hint = ui.text("Only the last four characters will be shown after saving", 12, false); ui.add(form, hint, 10);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("OpenRouter API Key").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnDismissListener(d -> field.setText(""));
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy) return;
            String key = field.getText().toString().trim();
            if (key.isEmpty()) { field.setError("Enter an API Key"); return; }
            try {
                JSONObject payload = request("save").put("key", key);
                if (existing != null)
                    payload.put("id", existing.getString("id")).put("ref", existing.getString("ref"));
                busy = true; field.setEnabled(false); dialog.setCancelable(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false); hint.setText("Saving…");
                NativeClient.async(payload, new NativeClient.Callback() {
                    public void ready(JSONObject value) {
                        payload.remove("key"); busy = false; field.setText(""); state = value;
                        if (alive()) { dialog.dismiss(); render(); toast("Key saved"); }
                    }
                    public void failed(String message) {
                        payload.remove("key"); busy = false;
                        if (!alive()) return;
                        field.setEnabled(true); dialog.setCancelable(true);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true); hint.setText(message);
                    }
                });
            } catch (JSONException e) { toast("Refresh and retry"); }
        }));
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }
    private void remove(JSONObject key) {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("Remove OpenRouter key?")
                .setMessage("This will remove " + key.optString("masked") + ". Features using this key can no longer call the service. If it is the service's only key, its service configuration will also be removed.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> {
                    if (busy) return;
                    try {
                        JSONObject payload = request("remove").put("id", key.getString("id")).put("ref", key.getString("ref"));
                        busy = true;
                        NativeClient.async(payload, new NativeClient.Callback() {
                            public void ready(JSONObject value) { busy = false; state = value; if (alive()) { render(); toast("Key removed"); } }
                            public void failed(String message) { busy = false; if (alive()) toast(message); }
                        });
                    } catch (JSONException e) { toast("Refresh and retry"); }
                }).show();
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
