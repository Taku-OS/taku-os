package org.agentos.assistant;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * The broker's RAM account view (`taku-agent-account`), read synchronously. The native account
 * page, the Home onboarding (gateway `readAccount`) and Agents (`account/status`) all read this one
 * snapshot, so they can never disagree about whether ChatGPT is signed in or AI is available.
 */
final class AccountStatus {
    private static final int MAX_VIEW = 32768;
    private static boolean refreshing;
    private static long nextRefresh;

    private AccountStatus() {}

    /** The complete view, including a pending sign-in's URL and one-time code. Native UI only. */
    static JSONObject raw() throws IOException {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("taku-agent-account"));
            if (socket.getPeerCredentials().getUid() != 0)
                throw new IOException("Unexpected account endpoint owner");
            socket.setSoTimeout(3000);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = socket.getInputStream().read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
                if (bytes.size() > MAX_VIEW) throw new IOException("Account view too large");
            }
            JSONObject view = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if ("loading".equals(view.optString("phase"))) refreshColdAccount();
            return view;
        } catch (org.json.JSONException e) {
            throw new IOException("Invalid account view", e);
        }
    }

    /** A cold broker has no account snapshot until account/read starts its lazy runtime. */
    private static synchronized void refreshColdAccount() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (refreshing || now < nextRefresh) return;
        refreshing = true;
        Thread worker = new Thread(() -> {
            try {
                // Same read as the account page: never starts sign-in or changes model mode.
                TaskRequests.control("account/read", new JSONObject());
            } catch (Exception unavailable) {
                // Keep the honest loading state; retry after backoff, without logging secrets.
            } finally {
                synchronized (AccountStatus.class) {
                    nextRefresh = android.os.SystemClock.elapsedRealtime() + 15_000;
                    refreshing = false;
                }
            }
        }, "taku-account-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    /** The shareable status; throws `ACCOUNT_UNAVAILABLE` when the broker cannot be reached. */
    static JSONObject read() throws IOException {
        JSONObject view;
        try {
            view = raw();
        } catch (IOException e) {
            throw new IOException("ACCOUNT_UNAVAILABLE", e);
        }
        return status(view);
    }

    /**
     * [read] plus a pending device-code sign-in's official `url` and one-time `code`, the two
     * things the Home onboarding card shows for the user to enter on another device. Absent for a
     * browser sign-in (its URL only works on this phone) and when nothing is pending.
     */
    static JSONObject readWithDeviceCode() throws IOException {
        JSONObject view;
        try {
            view = raw();
        } catch (IOException e) {
            throw new IOException("ACCOUNT_UNAVAILABLE", e);
        }
        JSONObject out = status(view);
        String code = view.optString("code");
        if ("pending".equals(view.optString("phase")) && !code.isEmpty()) {
            try {
                out.put("url", view.optString("url")).put("code", code);
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return out;
    }

    /**
     * Drops sign-in artifacts (`url`, `code`) and adds `chatgptConnected` (the broker's own
     * `account/read` returned a ChatGPT account) and `aiAvailable` (that, or Magpie mode running
     * with at least one model, the broker's own readiness check). `phase:"loading"` or
     * `"verifying"` means not yet settled.
     */
    static JSONObject status(JSONObject view) {
        try {
            boolean connected = view.optBoolean("connected");
            String kind = view.optString("kind");
            return new JSONObject()
                    .put("connected", connected)
                    .put("phase", view.optString("phase", "loading"))
                    .put("kind", kind)
                    .put("plan", view.optString("plan"))
                    .put("message", view.optString("message"))
                    .put("chatgptConnected", connected && "chatgpt".equals(kind))
                    .put("aiAvailable", connected && ("chatgpt".equals(kind) || "magpie".equals(kind)));
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
