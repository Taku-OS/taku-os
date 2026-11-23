// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Fixed X2.1 REST/COS protocol from Xmax Android SDK 1.1.0; secrets and pixels stay in memory. */
final class XmaxSessionClient {
    static final String APP_ID = "5588dfe2e7874bd08ea28f4968ec2d40";
    static final long SESSION_MS = 60_000L, FIRST_FRAME_MS = 30_000L;
    static final int MAX_BODY = 128 * 1024;
    interface Http {
        JSONObject json(String method, String url, String key, JSONObject body, boolean cleanup) throws Failure;
        void upload(String url, Map<String, String> headers, byte[] bytes) throws Failure;
        void cancel();
        default int lastStatus() { return 0; }
    }
    static final Set<String> PROVIDER_STEPS = Set.of("temporary_key", "session_create", "session_parse",
            "reference_sts", "reference_upload", "heartbeat", "cleanup", "unknown");
    static final Set<String> PROVIDER_FAILURE_KINDS = Set.of("malformed_json", "missing_data", "response_too_large",
            "business_rejected", "missing_temporary_key", "missing_session_id", "invalid_model_extra",
            "missing_room_id", "missing_rtc_user_id", "missing_room_token", "missing_bot_id", "missing_app_id",
            "app_id_mismatch", "inactive_session", "invalid_sts_bucket", "invalid_sts_region", "invalid_sts_prefix",
            "missing_sts_credentials", "invalid_sts_access_key", "invalid_sts_secret_key", "invalid_sts_token",
            "http_rejected", "network_failure", "invalid_reference", "cancelled", "unknown");
    /** Fixed, bounded metadata only; messages and causes never contain response or request values. */
    static final class Failure extends Exception {
        final String code, providerStep, providerFailureKind;
        final int providerHttpStatus;
        Failure(String code) { this(code, "unknown", "unknown", 0); }
        Failure(String code, String step, String kind, int status) {
            super(code); this.code = code;
            providerStep = PROVIDER_STEPS.contains(step == null ? "" : step) ? step : "unknown";
            providerFailureKind = PROVIDER_FAILURE_KINDS.contains(kind == null ? "" : kind) ? kind : "unknown";
            providerHttpStatus = status >= 100 && status <= 599 ? status : 0;
        }
        Failure at(String step, int status) {
            return new Failure(code, "unknown".equals(providerStep) ? step : providerStep,
                    providerFailureKind, providerHttpStatus == 0 ? status : providerHttpStatus);
        }
    }
    static final class Session {
        final String id, room, user, token, bot;
        Session(JSONObject body) throws Failure {
            try {
                id = field(body, "sessionUid");
                Object extra = body.opt("modelExtra");
                JSONObject connection;
                try { connection = extra instanceof JSONObject ? (JSONObject) extra : new JSONObject((String) extra); }
                catch (Exception ignored) { throw failure("invalid_signal", "invalid_model_extra"); }
                room = field(connection, "room_id"); user = field(connection, "rtc_user_id");
                token = field(connection, "room_token"); bot = field(connection, "rtc_bot_id");
                if (!APP_ID.equals(field(connection, "rtc_app_id"))) throw failure("invalid_signal", "app_id_mismatch");
                active(body);
            } catch (Failure failure) { throw failure.at("session_parse", 0); }
        }
    }
    private final String api, realtimeApi;
    private final Http http;
    private volatile boolean cancelled;
    private String permanent, temporary, sessionId;

    XmaxSessionClient(String environment) { this(environment, new Network()); }
    XmaxSessionClient(String environment, Http http) {
        if (!"china".equals(environment) && !"global".equals(environment)) throw new IllegalArgumentException("invalid_request");
        api = "china".equals(environment) ? "https://cloud.xmax.22duck.cn/open/api/v1" : "https://api.xmax.cloud/open/api/v1";
        realtimeApi = "china".equals(environment) ? api : "https://api.xmax.ai/open/api/v1";
        this.http = http;
    }
    // Call on a single owner worker. cancel() may run concurrently; close() runs after this worker settles.
    Session open(String key) throws Failure {
        if (key == null || key.length() < 8 || key.length() > 4096 || !key.matches("[A-Za-z0-9_.~:/+=-]+")) throw new Failure("cloud_auth_failed");
        ensure(); permanent = key;
        String step = "temporary_key"; int status = 0;
        try {
            JSONObject result = http.json("POST", api + "/temporary-api-key", key,
                    object("expireSeconds", 120, "pointsLimit", 60, "temporaryApiKeySessionLimit", 1), false);
            status = http.lastStatus();
            temporary = field(result, "temporaryApiKey"); // Capture ownership BEFORE observing cancellation.
            ensure(); step = "session_create"; status = 0;
            JSONObject created = http.json("POST", realtimeApi + "/session", temporary, object("model", "x2.1"), false);
            status = http.lastStatus();
            sessionId = field(created, "sessionUid"); // Late success is still deleted by the owner cleanup.
            ensure(); step = "session_parse";
            return new Session(created);
        } catch (Failure failure) { throw failure.at(step, status); }
        catch (Exception ignored) { throw new Failure("invalid_signal", step, "unknown", status); }
    }
    void heartbeat() throws Failure {
        try {
            ensure();
            JSONObject response = http.json("PUT", realtimeApi + "/session/" + encode(sessionId) + "/heartbeat", temporary, null, false);
            active(response); ensure();
        } catch (Failure failure) { throw failure.at("heartbeat", http.lastStatus()); }
    }
    String uploadReference(String dataUri) throws Failure {
        if (dataUri == null) return null;
        String step = "reference_sts"; int status = 0;
        try {
            ensure();
            String prefix = "data:image/jpeg;base64,";
            if (!dataUri.startsWith(prefix) || dataUri.length() > 512 * 1024) throw failure("invalid_request", "invalid_reference");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(dataUri.substring(prefix.length())); }
            catch (Exception ignored) { throw failure("invalid_request", "invalid_reference"); }
            if (bytes.length < 4 || bytes.length > 360 * 1024 || bytes[0] != (byte) 0xff || bytes[1] != (byte) 0xd8)
                throw failure("invalid_request", "invalid_reference");
            JSONObject storage = http.json("GET", api + "/cos/sts", temporary, null, false);
            status = http.lastStatus(); ensure();
            String bucket = field(storage, "bucket"), region = field(storage, "region");
            if (!bucket.matches("[a-zA-Z0-9-]{3,100}")) throw failure("invalid_signal", "invalid_sts_bucket");
            if (!region.matches("[a-z0-9-]{3,60}")) throw failure("invalid_signal", "invalid_sts_region");
            // Use the documented Tencent COS bucket endpoint; never send temporary signing credentials to an arbitrary endpoint.
            String host = bucket + ".cos." + region + ".myqcloud.com";
            Object rawPrefix = storage.opt("prefix");
            if (!(rawPrefix instanceof String) || ((String) rawPrefix).length() > 1024 || ((String) rawPrefix).contains(".."))
                throw failure("invalid_signal", "invalid_sts_prefix");
            String objectKey = (String) rawPrefix + System.currentTimeMillis() + "_" + UUID.randomUUID() + "_reference.jpg";
            String path = "/" + encodePath(objectKey);
            JSONObject credential = storage.optJSONObject("credentials");
            if (credential == null) throw failure("invalid_signal", "missing_sts_credentials");
            String access = field(credential, "accessKeyId"), secret = field(credential, "secretAccessKey"), token = field(credential, "sessionToken");
            // COS signs SecretId verbatim in q-ak. Permit opaque unreserved IDs without guessing their length,
            // but do not allow separators to inject another Authorization parameter.
            if (!access.matches("[A-Za-z0-9_.~-]+")) throw failure("invalid_signal", "invalid_sts_access_key");
            if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) throw failure("invalid_signal", "invalid_sts_token");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "image/jpeg"); headers.put("x-cos-security-token", token);
            headers.put("Authorization", cosAuthorization(access, secret, token, host, "/" + objectKey, System.currentTimeMillis() / 1000));
            String url = "https://" + host + path;
            step = "reference_upload"; status = 0;
            http.upload(url, headers, bytes); ensure(); return url;
        } catch (Failure failure) { throw failure.at(step, status); }
    }
    void cancel() { cancelled = true; http.cancel(); }
    /** Runs once on the owner worker after all in-flight creations have settled. No retries or response bodies in errors. */
    boolean close() {
        cancelled = true;
        boolean confirmed = true;
        if (sessionId != null && temporary != null) {
            try { http.json("DELETE", realtimeApi + "/session/" + encode(sessionId), temporary, null, true); }
            catch (Exception ignored) { confirmed = false; }
        }
        if (temporary != null && permanent != null) {
            try { http.json("PUT", api + "/temporary-api-key/" + encode(temporary) + "/inactive", permanent, null, true); }
            catch (Exception ignored) { confirmed = false; }
        }
        temporary = permanent = sessionId = null; return confirmed;
    }
    private void ensure() throws Failure { if (cancelled) throw failure("session_limit", "cancelled"); }
    static void active(JSONObject body) throws Failure {
        Object status = body.opt("status");
        if (status != null && !JSONObject.NULL.equals(status) && !"ACTIVE".equals(status)) throw failure("model_reported_error", "inactive_session");
    }
    static String field(JSONObject body, String key) throws Failure {
        Object raw = body == null ? null : body.opt(key);
        if (!(raw instanceof String) || ((String) raw).length() > 8192
                || ((String) raw).codePoints().anyMatch(Character::isISOControl))
            throw failure("invalid_signal", fieldFailureKind(key));
        // Match the SDK's requiredString normalization, without changing opaque characters inside the value.
        String value = ((String) raw).trim();
        if (value.isEmpty()) throw failure("invalid_signal", fieldFailureKind(key));
        return value;
    }
    private static Failure failure(String code, String kind) { return new Failure(code, "unknown", kind, 0); }
    private static String fieldFailureKind(String key) {
        if (key == null) return "unknown";
        return switch (key) {
            case "temporaryApiKey" -> "missing_temporary_key";
            case "sessionUid" -> "missing_session_id";
            case "room_id" -> "missing_room_id";
            case "rtc_user_id" -> "missing_rtc_user_id";
            case "room_token" -> "missing_room_token";
            case "rtc_bot_id" -> "missing_bot_id";
            case "rtc_app_id" -> "missing_app_id";
            case "bucket" -> "invalid_sts_bucket";
            case "region" -> "invalid_sts_region";
            case "accessKeyId" -> "invalid_sts_access_key";
            case "secretAccessKey" -> "invalid_sts_secret_key";
            case "sessionToken" -> "invalid_sts_token";
            default -> "unknown";
        };
    }
    static JSONObject object(Object... pairs) {
        try { JSONObject result = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
        catch (Exception ignored) { throw new IllegalArgumentException("invalid_request"); }
    }
    static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException("invalid_request"); }
    }
    static String encodePath(String value) { return encode(value).replace("%2F", "/"); }
    static String cosAuthorization(String access, String secret, String token, String host, String path, long now) throws Failure {
        try {
            String time = (now - 60) + ";" + (now + 300);
            String canonical = "put\n" + path + "\n\ncontent-type=image%2Fjpeg&host=" + encode(host) + "&x-cos-security-token=" + encode(token) + "\n";
            String stringToSign = "sha1\n" + time + "\n" + hex(MessageDigest.getInstance("SHA-1").digest(canonical.getBytes(StandardCharsets.UTF_8))) + "\n";
            String signKey = hex(hmac(secret, time));
            return "q-sign-algorithm=sha1&q-ak=" + access + "&q-sign-time=" + time + "&q-key-time=" + time +
                    "&q-header-list=content-type;host;x-cos-security-token&q-url-param-list=&q-signature=" + hex(hmac(signKey, stringToSign));
        } catch (Exception ignored) { throw new Failure("rtc_internal_error"); }
    }
    private static byte[] hmac(String key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return out.toString(); }

    /** Restricted HTTPS client: fixed origins, no redirects, bounded envelopes, no upstream exception text. */
    static final class Network implements Http {
        private HttpsURLConnection active;
        private boolean cancelled;
        private volatile int responseStatus;
        @Override public int lastStatus() { return responseStatus; }
        @Override public synchronized void cancel() { cancelled = true; if (active != null) active.disconnect(); }
        private synchronized HttpsURLConnection connection(String url, boolean cleanup) throws Exception {
            if (cancelled && !cleanup) throw new Failure("session_limit");
            HttpsURLConnection result = (HttpsURLConnection) new URL(url).openConnection();
            result.setInstanceFollowRedirects(false); result.setConnectTimeout(cleanup ? 5000 : 12000); result.setReadTimeout(cleanup ? 5000 : 12000);
            if (!cleanup) active = result; return result;
        }
        private synchronized void finished(HttpsURLConnection value) { if (active == value) active = null; value.disconnect(); }
        @Override public JSONObject json(String method, String url, String key, JSONObject body, boolean cleanup) throws Failure {
            HttpsURLConnection connection = null;
            int status = 0; responseStatus = 0;
            try {
                connection = connection(url, cleanup); connection.setRequestMethod(method);
                connection.setRequestProperty("X-Api-Key", key); connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("X-Platform", "android"); connection.setRequestProperty("X-SDK-Version", "taku-xmax-protocol-1.1.0");
                if (body != null) {
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    connection.setRequestProperty("Content-Type", "application/json"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                    try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                }
                status = connection.getResponseCode(); responseStatus = status;
                if (status < 200 || status > 299) throw new Failure(status == 401 || status == 403 ? "cloud_auth_failed" : "model_reported_error", "unknown", "http_rejected", status);
                if (cleanup && status == 204) return new JSONObject();
                JSONObject result;
                try (InputStream in = connection.getInputStream()) { String raw = read(in); result = cleanup && raw.trim().isEmpty() ? new JSONObject() : envelope(raw, cleanup); }
                return result;
            } catch (Failure error) { throw error.at("unknown", status); }
            catch (Exception ignored) { throw new Failure("model_reported_error", "unknown", "network_failure", status); }
            finally { if (connection != null) finished(connection); }
        }
        @Override public void upload(String url, Map<String, String> headers, byte[] bytes) throws Failure {
            HttpsURLConnection connection = null;
            int status = 0; responseStatus = 0;
            try {
                connection = connection(url, false); connection.setRequestMethod("PUT"); connection.setDoOutput(true);
                for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                status = connection.getResponseCode(); responseStatus = status;
                if (status < 200 || status > 299) throw new Failure("model_reported_error", "reference_upload", "http_rejected", status);
            } catch (Failure error) { throw error.at("reference_upload", status); }
            catch (Exception ignored) { throw new Failure("model_reported_error", "reference_upload", "network_failure", status); }
            finally { if (connection != null) finished(connection); }
        }
        static JSONObject envelope(String raw, boolean cleanup) throws Failure {
            try {
                JSONObject envelope = new JSONObject(raw);
                if (!Boolean.TRUE.equals(envelope.opt("success"))) throw failure("model_reported_error", "business_rejected");
                JSONObject data = envelope.optJSONObject("data");
                if (data == null && !cleanup) throw failure("invalid_signal", "missing_data");
                return data == null ? new JSONObject() : data;
            } catch (Failure failure) { throw failure; }
            catch (Exception ignored) { throw failure("invalid_signal", "malformed_json"); }
        }
        private static String read(InputStream in) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int n;
            while ((n = in.read(chunk)) != -1) { if (out.size() + n > MAX_BODY) throw failure("invalid_signal", "response_too_large"); out.write(chunk, 0, n); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
