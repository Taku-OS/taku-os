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
            return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        } catch (org.json.JSONException e) {
            throw new IOException("Invalid account view", e);
        }
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
