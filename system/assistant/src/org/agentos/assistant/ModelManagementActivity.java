package org.agentos.assistant;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowInsets;
import android.window.OnBackInvokedDispatcher;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Owns the browser round trip so Back returns to the originating assistant session. */
public final class ModelManagementActivity extends Activity {
    private static final int BROWSER = 1;
    private String thread;
    private String returnPage = "modelConnection";
    private TextView status;
    private boolean launched, waiting, returning;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        readDestination(getIntent());
        launched = saved != null && saved.getBoolean("browserLaunched");
        DrawerTheme theme = new DrawerTheme(getSharedPreferences("agent-settings", 0)
                .getString("theme", "dark").equals("dark"));
        AgentSettingsUi ui = new AgentSettingsUi(this, theme);
        LinearLayout root = ui.column();
        root.setBackgroundColor(theme.surface);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            root.setPadding(ui.dp(20), bars.top + ui.dp(20), ui.dp(20), bars.bottom + ui.dp(20));
            return insets;
        });
        ui.add(root, ui.text("Models and routes", 22, true), 0);
        status = ui.text("Opening local Magpie…", 14, false);
        ui.add(root, status, 16);
        ui.add(root, ui.button("Open management", false, this::openBrowser), 20);
        ui.add(root, ui.button("Back to Taku Agent", true, this::returnToAgent), 12);
        setContentView(root);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::returnToAgent);
        if (!launched) openBrowser();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // NEW_TASK can bring an existing browser task forward. Keep its page and update its return target.
        readDestination(intent);
        // A previous attempt may have failed while Magpie was starting. A new
        // request should retry instead of leaving the old error on screen.
        if (!launched) openBrowser();
    }

    private void readDestination(Intent intent) {
        returnPage = "systemAi".equals(intent.getStringExtra("returnPage")) ? "systemAi" : "jevConnection".equals(intent.getStringExtra("returnPage")) ? "jevConnection" : "modelConnection";
        thread = intent.getStringExtra("conversationId");
        if (!AgentRepository.validId(thread))
            thread = getSharedPreferences("agent-settings", 0).getString("conversation", "");
    }

    private void openBrowser() {
        if (waiting || launched || returning) return;
        waiting = true;
        try {
            NativeClient.async(new JSONObject().put("op", "modelConnection").put("action", "manage"),
                    new NativeClient.Callback() {
                public void ready(JSONObject result) {
                    waiting = false;
                    if (isFinishing() || isDestroyed() || returning) return;
                    try {
                        // No NEW_TASK: keep the browser above this return destination.
                        startActivityForResult(new Intent(Intent.ACTION_VIEW, Uri.parse(result.getString("url"))), BROWSER);
                        launched = true;
                        status.setText("Management opened. Exit the browser to return to Model connection.");
                    } catch (Exception unavailable) { failed("Could not open management. Make sure a browser is installed."); }
                }
                public void failed(String message) {
                    waiting = false;
                    if (!isFinishing() && !isDestroyed()) status.setText(message);
                }
            });
        } catch (Exception invalid) { waiting = false; status.setText("Could not read the model management endpoint."); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        // Authentication URLs stay out of saved state and preferences.
        state.putBoolean("browserLaunched", launched);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == BROWSER) returnToAgent();
    }

    private void returnToAgent() {
        if (returning || isFinishing()) return;
        returning = true;
        // Android may reuse the task without delivering onNewIntent to its covered root.
        // The assistant's current selection remains the single source of truth, including
        // when the user switches conversations while this browser is open.
        String selected = getSharedPreferences("agent-settings", 0).getString("conversation", thread);
        if (AgentRepository.validId(selected)) thread = selected;
        if (returnPage.equals("systemAi")) { startActivity(new Intent(this, SystemAiActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finishAndRemoveTask(); return; }
        startActivity(new Intent(this, PanelActivity.class).putExtra("conversationId", thread)
                .putExtra("page", returnPage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finishAndRemoveTask();
    }
}
