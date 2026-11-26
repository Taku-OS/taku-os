package org.agentos.assistant;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * UID-restricted local socket bridge exposing only short-lived access tokens to the root/system
 * Broker (TakuProxyRelay). Refresh tokens and Keystore ciphertext never leave org.agentos.assistant.
 */
final class TakuAccountTokenServer {
    static final String SOCKET_NAME = "taku-account-token";
    private static final int MAX_REQUEST_BYTES = 4096;
    private static volatile boolean started;

    private TakuAccountTokenServer() {}

    static synchronized void ensureStarted(Context context) {
        if (started) return;
        final Context appContext = context.getApplicationContext();
        try {
            LocalServerSocket server = new LocalServerSocket(SOCKET_NAME);
            started = true;
            Thread listener =
                    new Thread(
                            () -> {
                                while (true) {
                                    try (LocalSocket client = server.accept()) {
                                        int uid = client.getPeerCredentials().getUid();
                                        if (uid != 0 && uid != 1000) continue;
                                        client.setSoTimeout(15_000);
                                        JSONObject req = readJson(client.getInputStream());
                                        JSONObject resp = handleRequest(TakuAuthManager.get(appContext), req);
                                        client.getOutputStream()
                                                .write(resp.toString().getBytes(StandardCharsets.UTF_8));
                                        client.getOutputStream().flush();
                                    } catch (Exception ignored) {
                                        // Never log token payloads or request credentials.
                                    }
                                }
                            },
                            "taku-account-token-server");
            listener.setDaemon(true);
            listener.start();
        } catch (IOException ignored) {
            // Avoid failing service creation if another instance already bound the socket.
        }
    }

    static JSONObject handleRequest(TakuAuthManager manager, JSONObject req) {
        try {
            String action = req.optString("action", "getAccessToken");
            if (!"getAccessToken".equals(action)) {
                return new JSONObject().put("ok", false).put("code", "UNKNOWN_ACTION");
            }
            boolean forceRefresh = req.optBoolean("forceRefresh", false);
            String token = manager.getValidAccessToken(forceRefresh);
            return new JSONObject().put("ok", true).put("accessToken", token);
        } catch (Exception e) {
            String code = e.getMessage();
            if (code == null || !code.matches("ERR_[A-Z0-9_]{2,64}")) {
                code = "ERR_TOKEN_UNAVAILABLE";
            }
            try {
                return new JSONObject().put("ok", false).put("code", code);
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private static JSONObject readJson(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_REQUEST_BYTES) {
                throw new IOException("Request too large");
            }
        }
        String raw = out.toString(StandardCharsets.UTF_8).trim();
        return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
    }
}
