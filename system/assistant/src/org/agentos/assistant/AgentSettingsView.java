package org.agentos.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;

/** Compact preferences, sharing the same grouped rows as Skills and Harness. */
final class AgentSettingsView extends LinearLayout implements AgentAccountController.Listener {
    interface Actions {
        void back();
        void account();
        void harness();
        void skills();
        void nativeControls();
        void nativeSection(String section);
        void theme(String selected);
        void rename(String name);
    }

    private final AgentSettingsUi ui;
    private final AgentAccountController accounts;
    private final TextView accountStatus;
    private boolean usingMagpie;

    AgentSettingsView(Context context, DrawerTheme theme, AgentRepository repository,
            String conversationTitle, Actions actions) {
        super(context);
        setOrientation(VERTICAL);
        ui = new AgentSettingsUi(context, theme);
        SharedPreferences preferences = repository.preferences();
        accounts = new AgentAccountController(repository, this);

        LinearLayout account = ui.listGroup();
        ui.listRow(account, "Account and connection", "View current connection and sign-in status", R.drawable.taku_brand_mark,
                () -> { if (usingMagpie) actions.nativeSection("connection"); else actions.account(); });
        accountStatus = ui.text("Checking account…", 11, false);
        accountStatus.setContentDescription("Agent account connection status");
        accountStatus.setPadding(ui.dp(48), 0, ui.dp(12), ui.dp(10));
        account.addView(accountStatus);
        ui.add(this, account, 0);

        LinearLayout assistant = ui.listGroup();
        ui.listRow(assistant, "Taku AI", "System models, accounts, and per-app AI permissions", R.drawable.taku_brand_mark,
                () -> context.startActivity(new android.content.Intent(context, SystemAiActivity.class)));
        ui.listRow(assistant, context.getString(R.string.workspace_title), context.getString(R.string.workspace_summary), R.drawable.ic_home_widgets,
                () -> context.startActivity(new android.content.Intent(context, BackgroundWorkspacesActivity.class)));
        ui.listRow(assistant, "Model connection", "ChatGPT or local Magpie gateway", R.drawable.ic_auto_awesome, () -> actions.nativeSection("connection"));
        ui.listRow(assistant, "Model and reasoning", "Choose a model for the current work", R.drawable.ic_auto_awesome, () -> actions.nativeSection("models"));
        ui.listRow(assistant, "Skills", "Teach Taku more skills", R.drawable.ic_home_widgets, actions::skills);
        ui.listRow(assistant, "Tasks and queue", "Goals, progress, and tasks", R.drawable.ic_task_alt, () -> actions.nativeSection("tasks"));
        ui.listRow(assistant, "Connections and plugins", "Connect the tools and services you use", R.drawable.ic_home_apps, () -> actions.nativeSection("apps"));
        ui.listRow(assistant, "Jev decision model", "Use decision models and credentials from local Magpie", R.drawable.ic_auto_awesome,
                () -> actions.nativeSection("jev"));
        ui.section(this, "Working preferences", assistant);

        LinearLayout appearance = ui.listGroup();
        ui.listRow(appearance, "Appearance", preferences.getString("theme", "light").equals("dark") ? "Dark" : "Light", 0,
                () -> choose(appearance, new String[]{"Dark", "Light"}, index -> actions.theme(index == 0 ? "dark" : "light")));
        ui.listRow(appearance, "Response style", preferences.getString("responseStyle", "concise").equals("concise") ? "Concise" : "Detailed", 0,
                () -> choose(appearance, new String[]{"Concise", "Detailed"}, index -> {
                    preferences.edit().putString("responseStyle", index == 0 ? "concise" : "detailed").apply();
                    actions.theme(preferences.getString("theme", "light"));
                }));
        ui.listRow(appearance, "Conversation title", conversationTitle, 0, () -> {
            EditText name = ui.input("Conversation title", InputType.TYPE_CLASS_TEXT);
            name.setText(conversationTitle); name.setSingleLine(true);
            name.setContentDescription("Edit conversation title");
            NativeDialogs.show(this, new android.app.AlertDialog.Builder(context)
                    .setTitle("Conversation title").setView(name)
                    .setPositiveButton("Save", (dialog, which) -> actions.rename(name.getText().toString()))
                    .setNegativeButton("Cancel", null));
        });
        ui.section(this, "Preferences", appearance);

        Switch screen = new Switch(context);
        screen.setText("Include main-screen screenshot when sending"); screen.setTextColor(theme.ink); screen.setTextSize(14);
        screen.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{theme.accent, theme.muted}));
        screen.setTrackTintList(ColorStateList.valueOf(theme.border));
        screen.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); screen.setMinHeight(ui.dp(48));
        screen.setChecked(preferences.getBoolean("includeScreenshot", false));
        screen.setOnCheckedChangeListener((view, checked) -> preferences.edit().putBoolean("includeScreenshot", checked).apply());
        LinearLayout contextGroup = ui.listGroup(); contextGroup.addView(screen);
        ui.listRow(contextGroup, "Context", "View context used by this conversation", R.drawable.ic_code, actions::harness);
        ui.listRow(contextGroup, "Usage", "Account limits and conversation context", R.drawable.ic_home_battery_full, () -> actions.nativeSection("usage"));
        ui.section(this, "Current conversation", contextGroup);
        ui.add(this, ui.text("With screenshots enabled, Taku sees the screen from before invocation.", 12, false), 8);
    }

    private void choose(android.view.View anchor, String[] labels, java.util.function.IntConsumer action) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        for (int i = 0; i < labels.length; i++) menu.getMenu().add(0, i, i, labels[i]);
        menu.setOnMenuItemClickListener(item -> { action.accept(item.getItemId()); return true; });
        menu.show();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); accounts.start(); }
    @Override protected void onDetachedFromWindow() { accounts.stop(); super.onDetachedFromWindow(); }
    void activate() { accounts.start(); }
    void stop() { accounts.stop(); }
    public void account(JSONObject state) {
        usingMagpie = state.optString("kind").equals("magpie");
        accountStatus.setText(state.optString("kind").equals("magpie") ? "Using local Magpie" : state.optBoolean("connected") ? "Connected" : state.optString("phase").equals("pending") ? "Awaiting authorization" : "Not connected");
    }
    public void busy(boolean busy) {}
    public void error(String message) { accountStatus.setText("Connection status temporarily unavailable"); }
}
