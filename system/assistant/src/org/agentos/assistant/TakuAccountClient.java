package org.agentos.assistant;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Bounded HTTP client for Supabase Auth (auth.taku.ai) and Taku Workers (worker.taku.ai).
 * Separates permanent credential revocation (e.g. invalid_grant) from transient network/5xx faults.
 */
interface TakuAccountClient {
    String DEFAULT_AUTH_URL = "https://auth.taku.ai";
    String DEFAULT_PUBLISHABLE_KEY = "sb_publishable_CU6CXli0rTJmPMUjZbRJVQ_9ebw7OvV";
    String DEFAULT_WORKER_URL = "https://worker.taku.ai";

    final class TransientAuthException extends IOException {
        TransientAuthException(String message) { super(message); }
        TransientAuthException(String message, Throwable cause) { super(message, cause); }
    }

    final class PermanentAuthException extends IOException {
        PermanentAuthException(String message) { super(message); }
    }

    final class InvalidCredentialsException extends IOException {
        InvalidCredentialsException(String message) { super(message); }
    }

    final class UnauthorizedException extends IOException {
        UnauthorizedException(String message) { super(message); }
    }

    final class SessionResult {
        final String accessToken;
        final String refreshToken;
        final long expiresAtMs;
        final String userId;
        final String email;

        SessionResult(String accessToken, String refreshToken, long expiresAtMs, String userId, String email) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMs = expiresAtMs;
            this.userId = userId;
            this.email = email;
        }
    }

    final class UserInfo {
        final String userId;
        final String email;

        UserInfo(String userId, String email) {
            this.userId = userId;
            this.email = email;
        }
    }

    final class BalanceInfo {
        final double availableCredits;
        final double activeCredits;
        final double scheduledCredits;
        final double debtAmount;
        final String nextExpiryAt;

        BalanceInfo(
                double availableCredits,
                double activeCredits,
                double scheduledCredits,
                double debtAmount,
                String nextExpiryAt) {
            this.availableCredits = availableCredits;
            this.activeCredits = activeCredits;
            this.scheduledCredits = scheduledCredits;
            this.debtAmount = debtAmount;
            this.nextExpiryAt = nextExpiryAt;
        }
    }

    String authUrl();
    SessionResult signInWithPassword(String email, String password) throws IOException;
    SessionResult exchangePkceCode(String authCode, String codeVerifier) throws IOException;
    SessionResult refreshSession(String refreshToken) throws IOException;
    UserInfo verifyUser(String accessToken) throws IOException;
    BalanceInfo fetchBalance(String accessToken) throws IOException;

    final class HttpClient implements TakuAccountClient {
        private static final int CONNECT_TIMEOUT_MS = 10_000;
        private static final int READ_TIMEOUT_MS = 15_000;
        private static final int MAX_RESPONSE_BYTES = 512 * 1024;

        private final String authUrl;
        private final String publishableKey;
        private final String workerUrl;

        HttpClient() {
            this(DEFAULT_AUTH_URL, DEFAULT_PUBLISHABLE_KEY, DEFAULT_WORKER_URL);
        }

        HttpClient(String authUrl, String publishableKey, String workerUrl) {
            this.authUrl = stripTrailingSlash(authUrl);
            this.publishableKey = publishableKey;
            this.workerUrl = stripTrailingSlash(workerUrl);
        }

        @Override
        public String authUrl() {
            return authUrl;
        }

        @Override
        public SessionResult signInWithPassword(String email, String password) throws IOException {
            JSONObject body = new JSONObject();
            try {
                body.put("email", email).put("password", password);
            } catch (Exception e) {
                throw new IOException("INVALID_ARGUMENT", e);
            }
            HttpResult resp = request("POST", authUrl + "/auth/v1/token?grant_type=password", body, null, true);
            if (resp.status == 200) {
                return parseSession(resp.json);
            }
            if (resp.status == 400 || resp.status == 401 || resp.status == 403) {
                throw new InvalidCredentialsException("ERR_INVALID_CREDENTIALS");
            }
            throw new TransientAuthException("ERR_TRANSIENT_OFFLINE");
        }

        @Override
        public SessionResult exchangePkceCode(String authCode, String codeVerifier) throws IOException {
            JSONObject body = new JSONObject();
            try {
                body.put("auth_code", authCode).put("code_verifier", codeVerifier);
            } catch (Exception e) {
                throw new IOException("INVALID_ARGUMENT", e);
            }
            HttpResult resp = request("POST", authUrl + "/auth/v1/token?grant_type=pkce", body, null, true);
            if (resp.status == 200) {
                return parseSession(resp.json);
            }
            if (resp.status == 400 || resp.status == 401 || resp.status == 403) {
                throw new InvalidCredentialsException("ERR_OAUTH_EXCHANGE_FAILED");
            }
            throw new TransientAuthException("ERR_TRANSIENT_OFFLINE");
        }

        @Override
        public SessionResult refreshSession(String refreshToken) throws IOException {
            JSONObject body = new JSONObject();
            try {
                body.put("refresh_token", refreshToken);
            } catch (Exception e) {
                throw new IOException("INVALID_ARGUMENT", e);
            }
            HttpResult resp = request("POST", authUrl + "/auth/v1/token?grant_type=refresh_token", body, null, true);
            if (resp.status == 200) {
                return parseSession(resp.json);
            }
            if (isPermanentRefreshFailure(resp.status, resp.json)) {
                throw new PermanentAuthException("ERR_TOKEN_EXPIRED");
            }
            throw new TransientAuthException("ERR_TRANSIENT_OFFLINE");
        }

        @Override
        public UserInfo verifyUser(String accessToken) throws IOException {
            HttpResult resp = request("GET", authUrl + "/auth/v1/user", null, accessToken, true);
            if (resp.status == 200) {
                String id = resp.json.optString("id", "").trim();
                String email = resp.json.optString("email", "").trim();
                if (id.isEmpty()) {
                    throw new UnauthorizedException("ERR_INVALID_USER_RESPONSE");
                }
                return new UserInfo(id, email);
            }
            if (isPermanentUserFailure(resp.status, resp.json)) {
                throw new UnauthorizedException("ERR_TOKEN_INVALID");
            }
            throw new TransientAuthException("ERR_TRANSIENT_OFFLINE");
        }

        @Override
        public BalanceInfo fetchBalance(String accessToken) throws IOException {
            HttpResult resp = request("GET", workerUrl + "/payment/balance", null, accessToken, false);
            if (resp.status == 200) {
                double available = resp.json.has("available_credits")
                        ? resp.json.optDouble("available_credits", 0.0)
                        : resp.json.optDouble("credits_balance", 0.0);
                double active = resp.json.optDouble("active_credits", available);
                double scheduled = resp.json.optDouble("scheduled_credits", 0.0);
                double debt = resp.json.optDouble("debt_amount", 0.0);
                String nextExpiry = resp.json.isNull("next_expiry_at")
                        ? null
                        : resp.json.optString("next_expiry_at", null);
                return new BalanceInfo(available, active, scheduled, debt, nextExpiry);
            }
            if (resp.status == 401) {
                throw new UnauthorizedException("ERR_UNAUTHORIZED");
            }
            throw new TransientAuthException("ERR_TRANSIENT_OFFLINE");
        }

        private HttpResult request(
                String method,
                String urlString,
                JSONObject jsonBody,
                String bearerToken,
                boolean sendApiKey) throws TransientAuthException {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlString);
                String host = url.getHost();
                boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
                conn = (HttpURLConnection) (loopback
                        ? url.openConnection(Proxy.NO_PROXY)
                        : url.openConnection());
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod(method);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "taku-os-account/2");
                if (sendApiKey && publishableKey != null && !publishableKey.isEmpty()) {
                    conn.setRequestProperty("apikey", publishableKey);
                }
                if (bearerToken != null && !bearerToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
                }
                if (jsonBody != null) {
                    byte[] payload = jsonBody.toString().getBytes(StandardCharsets.UTF_8);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setDoOutput(true);
                    conn.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream out = conn.getOutputStream()) {
                        out.write(payload);
                    }
                }
                int status = conn.getResponseCode();
                InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                JSONObject json = parseJsonBody(stream);
                return new HttpResult(status, json);
            } catch (IOException e) {
                throw new TransientAuthException("ERR_TRANSIENT_OFFLINE", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private static JSONObject parseJsonBody(InputStream stream) throws IOException {
            if (stream == null) return new JSONObject();
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_RESPONSE_BYTES) {
                        throw new IOException("Response body exceeds limit");
                    }
                }
                String text = out.toString(StandardCharsets.UTF_8).trim();
                if (text.isEmpty() || !text.startsWith("{")) {
                    return new JSONObject();
                }
                try {
                    return new JSONObject(text);
                } catch (Exception ignored) {
                    return new JSONObject();
                }
            }
        }

        static SessionResult parseSession(JSONObject json) throws IOException {
            String accessToken = json.optString("access_token", "").trim();
            String refreshToken = json.optString("refresh_token", "").trim();
            long expiresAtSec = json.optLong("expires_at", 0L);
            long expiresInSec = json.optLong("expires_in", 0L);
            long expiresAtMs = expiresAtSec > 0
                    ? expiresAtSec * 1000L
                    : (expiresInSec > 0 ? System.currentTimeMillis() + expiresInSec * 1000L : 0L);
            JSONObject user = json.optJSONObject("user");
            String userId = user == null ? "" : user.optString("id", "").trim();
            String email = user == null ? "" : user.optString("email", "").trim();
            if (accessToken.isEmpty() || refreshToken.isEmpty() || expiresAtMs <= 0L) {
                throw new IOException("ERR_INVALID_SESSION_RESPONSE");
            }
            return new SessionResult(accessToken, refreshToken, expiresAtMs, userId, email);
        }

        static boolean isPermanentRefreshFailure(int status, JSONObject json) {
            if (status != 400 && status != 401 && status != 403) {
                return false;
            }
            String combined = collectErrorFields(json);
            return combined.contains("invalid_grant")
                    || combined.contains("refresh_token_not_found")
                    || combined.contains("refresh_token_already_used")
                    || combined.contains("session_not_found")
                    || combined.contains("user_banned")
                    || combined.contains("invalid refresh token")
                    || combined.contains("already used");
        }

        static boolean isPermanentUserFailure(int status, JSONObject json) {
            if (status != 400 && status != 401 && status != 403) {
                return false;
            }
            String code = json.optString("error_code", "").toLowerCase(Locale.ROOT);
            return "bad_jwt".equals(code)
                    || "no_authorization".equals(code)
                    || "user_not_found".equals(code)
                    || "session_not_found".equals(code)
                    || "user_banned".equals(code);
        }

        private static String collectErrorFields(JSONObject json) {
            if (json == null) return "";
            StringBuilder sb = new StringBuilder();
            for (String key : new String[]{"error", "error_code", "error_description", "msg", "message"}) {
                String v = json.optString(key, "");
                if (!v.isEmpty()) sb.append(v.toLowerCase(Locale.ROOT)).append(' ');
            }
            return sb.toString();
        }

        private static String stripTrailingSlash(String value) {
            if (value == null) return "";
            int end = value.length();
            while (end > 0 && value.charAt(end - 1) == '/') end--;
            return value.substring(0, end);
        }

        private static final class HttpResult {
            final int status;
            final JSONObject json;

            HttpResult(int status, JSONObject json) {
                this.status = status;
                this.json = json;
            }
        }
    }
}
