// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.taku.tryon.data.FalKeyStore;

/**
 * Mints an in-memory, short-lived token for one fixed fal realtime model.
 * Call mint off the UI thread. Cancellation is terminal for this instance;
 * create a new client for a later user-initiated attempt.
 */
public final class FalRealtimeTokenClient {
    private static final int MAX_RESPONSE_BYTES = 32 * 1024;
    private static final int MAX_TOKEN_LENGTH = 16 * 1024;
    private static final int DURATION_SECONDS = 120;

    private final Context context;
    private final FalRealtimeProfile profile;
    private final Object stateLock = new Object();
    private boolean cancelled;
    private HttpsURLConnection activeConnection;
    private volatile int lastHttpStatus;

    /** Numeric status only; never exposes response bodies, credentials or tokens. */
    int diagnosticHttpStatus() { return lastHttpStatus; }

    public FalRealtimeTokenClient(Context context) {
        this(context, FalRealtimeProfile.LEGACY_VTON);
    }
    public FalRealtimeTokenClient(Context context, FalRealtimeProfile profile) {
        if (context == null || profile == null) throw new IllegalArgumentException("invalid_token_arguments");
        Context application = context.getApplicationContext();
        this.context = application != null ? application : context;
        this.profile = profile;
    }

    /** No original exception, response text, permanent key, or token is exposed. */
    public static final class Failure extends Exception {
        public final String code;
        /** Zero means no HTTP response status was received. */
        public final int httpStatus;

        private Failure(String code, int httpStatus) {
            super(code);
            this.code = code;
            this.httpStatus = httpStatus;
        }
    }

    /** Performs one POST with no retry. The returned JWT is never persisted. */
    public synchronized String mint() throws Failure {
        HttpsURLConnection connection = null;
        int status = 0;
        String token;
        try {
            checkCancelled(status);
            String key;
            try {
                key = new FalKeyStore(context).get();
            } catch (Exception ignored) {
                checkCancelled(status);
                throw new Failure("missing_key", status);
            }
            checkCancelled(status);
            if (key == null || key.length() < 8 || key.length() > 1024 ||
                    !key.matches("[A-Za-z0-9_:.-]+")) {
                throw new Failure("missing_key", status);
            }

            // Both URL and alias are fixed by the immutable profile, never supplied by an Intent.
            byte[] body = profile.tokenRequestBody().getBytes(StandardCharsets.UTF_8);
            long requestedAt = System.currentTimeMillis();
            connection = (HttpsURLConnection) new URL(profile.tokenUrl).openConnection();
            synchronized (stateLock) {
                if (cancelled) throw new Failure("token_cancelled", status);
                activeConnection = connection;
            }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Key " + key);
            key = null;
            checkCancelled(status);
            try (OutputStream stream = connection.getOutputStream()) {
                checkCancelled(status);
                stream.write(body);
            }
            checkCancelled(status);
            status = connection.getResponseCode();
            lastHttpStatus = status;
            checkCancelled(status);
            if (!acceptsTokenStatus(status)) {
                // Never read an error body: it can echo credentials or tokens.
                throw new Failure(status == 401 || status == 403 ?
                        "token_auth_failed" : "token_http_failed", status);
            }

            String response;
            try (InputStream stream = connection.getInputStream()) {
                response = readBounded(stream, status);
            }
            checkCancelled(status);
            try {
                token = parseToken(response, requestedAt);
            } catch (Exception ignored) {
                throw new Failure("token_invalid_response", status);
            }
        } catch (Failure failure) {
            checkCancelled(status);
            throw failure;
        } catch (Exception ignored) {
            checkCancelled(status);
            throw new Failure("token_network_failed", status);
        } finally {
            synchronized (stateLock) {
                if (activeConnection == connection) activeConnection = null;
            }
            disconnect(connection);
        }
        // Cleanup happens before this completion/cancellation ordering point,
        // so even cancellation during a successful disconnect discards token.
        synchronized (stateLock) {
            if (cancelled) throw new Failure("token_cancelled", status);
            return token;
        }
    }

    static boolean acceptsTokenStatus(int status) { return status >= 200 && status < 300; }

    /** Cancels an active request and prevents all later mints on this client. */
    public void cancel() {
        HttpsURLConnection connection;
        synchronized (stateLock) {
            cancelled = true;
            connection = activeConnection;
        }
        disconnect(connection);
    }

    private static void disconnect(HttpsURLConnection connection) {
        if (connection == null) return;
        try { connection.disconnect(); } catch (Exception ignored) { }
    }

    private void checkCancelled(int status) throws Failure {
        synchronized (stateLock) {
            if (cancelled) throw new Failure("token_cancelled", status);
        }
    }

    private String readBounded(InputStream stream, int status) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            checkCancelled(status);
            if (bytes.size() + read > MAX_RESPONSE_BYTES) {
                throw new Failure("token_invalid_response", status);
            }
            bytes.write(buffer, 0, read);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private static String parseToken(String response, long requestedAt) throws Exception {
        JSONTokener parser = new JSONTokener(response);
        Object value = parser.nextValue();
        if (parser.nextClean() != 0) throw new IllegalArgumentException();
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            value = object.has("token") ? object.opt("token") : object.opt("detail");
        }
        if (!(value instanceof String)) throw new IllegalArgumentException();
        String token = (String) value;
        if (token.length() > MAX_TOKEN_LENGTH ||
                !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException();
        }
        String payload = new String(Base64.decode(token.split("\\.")[1],
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP), StandardCharsets.UTF_8);
        long expires = Math.multiplyExact(new JSONObject(payload).getLong("exp"), 1000L);
        long now = System.currentTimeMillis();
        if (expires < now + 5000L ||
                expires > requestedAt + (DURATION_SECONDS + 60L) * 1000L) {
            throw new IllegalArgumentException();
        }
        return token;
    }
}
