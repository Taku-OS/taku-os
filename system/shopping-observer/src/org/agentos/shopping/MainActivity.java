// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.agentos.ai.AiClient;

/** Explicit opt-in and connection setup; viewing this page never captures the screen. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService settingsWork = Executors.newSingleThreadExecutor();
    private ShoppingUi ui;
    private Switch observation, classification, vision;
    private TextView status, reminders;
    private Button ai;
    private ReminderConnection connection;
    private boolean updating, registering, started;
    private final Runnable refresh = new Runnable() {
        public void run() {
            if (!started) return;
            status.setText(ShoppingObserverService.status());
            updateSwitches();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        ReferenceStore.purge(this);
        ui = new ShoppingUi(this);
        LinearLayout root = ui.page();
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        ui.add(body, ui.text("Live Try-On Suggestions", 28, true), 0);
        ui.add(body, ui.text("Get a reminder when clothing appears. You decide whether to try it on.", 15, false), 12);

        observation = new Switch(this);
        observation.setText("Try-on reminders in TikTok LIVE");
        observation.setTextSize(16);
        observation.setTextColor(ShoppingUi.INK);
        observation.setMinHeight(ui.dp(56));
        observation.setContentDescription("Try-on reminders in TikTok LIVE");
        observation.setOnCheckedChangeListener((button, enabled) -> {
            if (!updating) Preferences.set(this, Preferences.ENABLED, enabled);
        });
        ui.add(body, observation, 22);
        ui.add(body, ui.text("Observes the current TikTok LIVE room and product card. Pauses when you leave or lock the screen. System AI reads product titles in context to identify clothing.", 14, false), 8);
        status = ui.text("Automatic observation is off", 14, true);
        ui.add(body, status, 14);
        ui.add(body, ui.button("Enable accessibility service", this::accessibility), 14);

        reminders = ui.text("Connecting to try-on reminders…", 14, false);
        ui.add(body, reminders, 20);
        ui.add(body, ui.button("Allow try-on reminders", this::reminderSettings), 10);

        classification = new Switch(this);
        classification.setText("Identify products with system AI");
        classification.setTextSize(16);
        classification.setTextColor(ShoppingUi.INK);
        classification.setMinHeight(ui.dp(56));
        classification.setContentDescription("Identify products with system AI");
        classification.setOnCheckedChangeListener((button, enabled) -> {
            if (updating) return;
            if (!enabled) Preferences.set(this, Preferences.CLASSIFICATION, false);
            else {
                updateSwitches();
                new AlertDialog.Builder(this).setTitle("Allow product recognition?")
                    .setMessage("When enabled, the current LIVE product title is sent to your authorized system text model to check whether the product is clothing. Model usage may incur charges. Results are cached for the same product. This sends no screenshots, chat or video and does not enable image recognition below. You can turn it off at any time.")
                    .setPositiveButton("Allow", (dialog, which) -> {
                        Preferences.set(this, Preferences.CLASSIFICATION, true);
                        updateSwitches();
                    }).setNegativeButton("Not now", (dialog, which) -> updateSwitches()).show();
            }
        });
        ui.add(body, classification, 24);
        ui.add(body, ui.text("Uses your configured system text model to understand the product. Clothing triggers a reminder. Uncertain results offer a chance to check the image; other products do not trigger reminders. Enable this option, then authorize this app and select a text model in system AI settings.", 14, false), 8);

        vision = new Switch(this);
        vision.setText("Use system AI when no product card is shown");
        vision.setTextSize(16);
        vision.setTextColor(ShoppingUi.INK);
        vision.setMinHeight(ui.dp(56));
        vision.setContentDescription("Allow system AI to check LIVE images for clothing");
        vision.setOnCheckedChangeListener((button, enabled) -> {
            if (updating) return;
            if (!enabled) Preferences.set(this, Preferences.VISION, false);
            else {
                updateSwitches();
                new AlertDialog.Builder(this).setTitle("Allow occasional image recognition?")
                    .setMessage("Only when a LIVE room has no product card and you stay for a while, one screenshot is sent to your authorized system image model to check for clothing. This does not record video. Model usage may incur charges. This setting is separate from text recognition and can be turned off at any time.")
                    .setPositiveButton("Allow", (dialog, which) -> {
                        Preferences.set(this, Preferences.VISION, true);
                        updateSwitches();
                    }).setNegativeButton("Not now", (dialog, which) -> updateSwitches()).show();
            }
        });
        ui.add(body, vision, 24);
        ui.add(body, ui.text("Off by default. Also authorize this app and select an image model in system AI settings.", 14, false), 8);
        ai = ui.button("System AI permissions and models", this::aiSettings);
        ui.add(body, ai, 12);

        ui.add(body, ui.text("Try on while watching LIVE", 18, true), 24);
        ui.add(body, ui.text("After viewing the LIVE suggestion, choose Try on in the confirmation to capture the presenter’s current top and open your front-camera window. Your LIVE snapshot and camera video go to cloud AI, billed by usage for up to 60 seconds. Drag, expand or collapse the window. Close it to stop.", 14, false), 10);
        ui.add(body, ui.button("Try on the current LIVE outfit", () -> {
            finish();
            ShoppingObserverService.requestAfterSettings();
        }), 14);
        ui.add(body, ui.text(LiveTryOnHandoff.directAvailable(this) ? "Live Try-On is ready" : "Install the latest Live Try-On app", 14, true), 10);
        setContentView(root);
        updateSwitches();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        connection = new ReminderConnection(this, new ReminderConnection.Listener() {
            public void state(boolean allowed, String message) {
                if (started) reminders.setText(allowed ? "Try-on reminders are allowed" : "Try-on reminders are not allowed yet. Complete the setup below.");
            }
        });
        connection.connect();
        updateSwitches();
        handler.post(refresh);
    }

    @Override protected void onStop() {
        started = false;
        handler.removeCallbacks(refresh);
        if (connection != null) connection.close();
        connection = null;
        super.onStop();
    }

    @Override protected void onDestroy() { settingsWork.shutdownNow(); super.onDestroy(); }

    private void updateSwitches() {
        updating = true;
        observation.setChecked(Preferences.enabled(this));
        classification.setChecked(Preferences.classification(this));
        vision.setChecked(Preferences.vision(this));
        updating = false;
    }

    private void accessibility() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (RuntimeException unavailable) { toast("Enable Taku Live Try-On Observer in accessibility settings."); }
    }

    private void reminderSettings() {
        try {
            startActivity(new Intent().setComponent(new ComponentName("org.agentos.proactivity",
                "org.agentos.proactivity.ProactivityActivity")).putExtra("observerId", ReminderConnection.ID));
        } catch (RuntimeException unavailable) { toast("Install the Taku Proactivity component first."); }
    }

    private void aiSettings() {
        if (registering) return;
        registering = true;
        ai.setEnabled(false);
        settingsWork.execute(() -> {
            boolean ready = false;
            try { new AiClient(getApplicationContext()).settings(); ready = true; }
            catch (Exception unavailable) { }
            boolean registered = ready;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                registering = false;
                ai.setEnabled(true);
                if (!registered) { toast("System AI is unavailable. Check that the system service is installed, then try again."); return; }
                try { new AiClient(this).openSettings(); }
                catch (RuntimeException unavailable) { toast("Allow Live Try-On Suggestions to use models in Taku AI settings."); }
            });
        });
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
