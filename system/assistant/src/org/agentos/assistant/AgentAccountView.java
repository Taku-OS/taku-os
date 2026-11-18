package org.agentos.assistant;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.widget.*;

import org.json.JSONObject;

/** Agent account ceremony. Official pages handle passwords; challenge data stays in RAM. */
final class AgentAccountView extends LinearLayout implements AgentAccountController.Listener {
    private final AgentSettingsUi ui;
    private final AgentDrawerView.Host host;
    private final AgentAccountController controller;
    private final TextView status, detail, code;
    private final Button connect, browser, open, cancel, refresh;
    private JSONObject state = new JSONObject();
    private boolean busy;

    AgentAccountView(
            Context context,
            DrawerTheme theme,
            AgentRepository repository,
            AgentDrawerView.Host host,
            Runnable back) {
        super(context);
        setOrientation(VERTICAL);
        this.host = host;
        ui = new AgentSettingsUi(context, theme);
        controller = new AgentAccountController(repository, this);
        ui.add(this, ui.button("Back to settings", false, back), 0);
        LinearLayout card = ui.card();
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.taku_brand_mark);
        mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(mark, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        status = ui.heading("Connect your account");
        status.setContentDescription("Account authorization status");
        ui.add(card, status, 16);
        detail = ui.text("Connect with your ChatGPT account and return here after authorization.", 14, false);
        ui.add(card, detail, 10);
        code = ui.text("", 22, true);
        code.setTypeface(Typeface.MONOSPACE);
        code.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        code.setBackground(theme.rounded(context, theme.surface, 12, true));
        code.setVisibility(GONE);
        ui.add(card, code, 16);
        connect = ui.button("Sign in with ChatGPT", true, () -> controller.login(false));
        browser = ui.button("Authorize in browser", false, () -> controller.login(true));
        open = ui.button("Open official authorization page", true, this::openOfficialPage);
        cancel = ui.button("Cancel this sign-in", false, controller::cancel);
        refresh = ui.button("Refresh connection status", false, controller::refresh);
        for (Button action : new Button[] {connect, browser, open, cancel, refresh})
            ui.add(card, action, 10);
        ui.add(this, card, 16);
        ui.add(this, ui.text("Device-code sign-in: sign in on the official page and enter this one-time code. Use browser authorization if your account does not support device codes.", 13, false), 18);
        ui.add(this, ui.text("Taku does not receive your account password. This authorization code is only for this connection; do not take screenshots of sign-in pages containing authorization information.", 12, false), 12);
        render();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        activate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    void activate() {
        controller.start();
    }

    void stop() {
        controller.stop();
        state = new JSONObject();
        code.setText("");
        host.sensitive(false);
    }

    public void account(JSONObject view) {
        state = view;
        render();
    }

    public void busy(boolean value) {
        busy = value;
        render();
    }

    public void error(String message) {
        detail.setText(message);
    }

    private void render() {
        boolean connected = state.optBoolean("connected");
        String phase = state.optString("phase");
        boolean pending = phase.equals("pending");
        boolean verifying = phase.equals("verifying");
        status.setText(
                connected
                        ? "Account connected"
                        : pending
                                ? "Waiting for your authorization"
                                : busy || phase.equals("starting") || verifying
                                        ? "Connecting…"
                                        : "Connect your account");
        String copy = state.optString("message");
        detail.setText(copy.isEmpty() ? "Connect with your ChatGPT account and return here after authorization." : copy);
        code.setText(state.optString("code"));
        code.setVisibility(pending && !state.optString("code").isEmpty() ? VISIBLE : GONE);
        host.sensitive(pending && !state.optString("url").isEmpty());
        connect.setVisibility(!connected && !pending && !verifying ? VISIBLE : GONE);
        browser.setVisibility(!connected && !pending && !verifying ? VISIBLE : GONE);
        open.setVisibility(pending ? VISIBLE : GONE);
        cancel.setVisibility(pending ? VISIBLE : GONE);
        for (Button action : new Button[] {connect, browser, open, cancel, refresh})
            action.setEnabled(!busy);
    }

    private void openOfficialPage() {
        Uri url = Uri.parse(state.optString("url"));
        if (!"https".equals(url.getScheme())
                || !("auth.openai.com".equals(url.getHost())
                        || "chatgpt.com".equals(url.getHost()))) {
            detail.setText("Invalid authorization URL. Reconnect.");
            return;
        }
        try {
            getContext()
                    .startActivity(
                            new Intent(Intent.ACTION_VIEW, url)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            host.dismiss();
        } catch (android.content.ActivityNotFoundException missingBrowser) {
            detail.setText("No browser is available on this device. Open the official authorization page on another device and enter the device code.");
        }
    }
}
