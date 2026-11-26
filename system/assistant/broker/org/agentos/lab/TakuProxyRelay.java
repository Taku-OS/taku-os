package org.agentos.lab;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Broker-owned loopback relay on 127.0.0.1:3426 that swaps TAKU_PROXY_RELAY_TOKEN for short-lived
 * Supabase JWTs fetched over the UID-restricted taku-account-token socket.
 */
final class TakuProxyRelay {
    static final int DEFAULT_PORT = 3426;
    static final String DEFAULT_AI_PROXY_BASE = "https://worker.taku.ai";
    static final String DEFAULT_WORKER_BASE = "https://worker.taku.ai";
    static final String TOKEN_SOCKET = "taku-account-token";
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    interface TokenSupplier {
        TokenResult getAccessToken(boolean forceRefresh) throws IOException;
    }

    static final class TokenResult {
        final String accessToken;
        final String errorCode;

        private TokenResult(String accessToken, String errorCode) {
            this.accessToken = accessToken;
            this.errorCode = errorCode;
        }

        static TokenResult ok(String accessToken) {
            return new TokenResult(accessToken, null);
        }

        static TokenResult error(String errorCode) {
            return new TokenResult(null, errorCode);
        }
    }

    interface ProviderStoreBridge {
        void register(String relayToken) throws Exception;
        void unregister() throws Exception;
    }

    private static final class RouteTarget {
        final String upstreamUrl;
        final boolean allowXApiKey;

        RouteTarget(String upstreamUrl, boolean allowXApiKey) {
            this.upstreamUrl = upstreamUrl;
            this.allowXApiKey = allowXApiKey;
        }
    }

    private static final class ParsedRequest {
        final String method;
        final String target;
        final Map<String, List<String>> headers;
        final byte[] body;

        ParsedRequest(String method, String target, Map<String, List<String>> headers, byte[] body) {
            this.method = method;
            this.target = target;
            this.headers = headers;
            this.body = body;
        }
    }

    interface StartupProbe {
        boolean isProviderPersisted() throws Exception;
    }

    private static volatile TakuProxyRelay shared;
    private static volatile ProviderStoreBridge configuredBridge;
    private static volatile StartupProbe configuredStartupProbe;

    static void configureRuntime(ProviderStoreBridge bridge, StartupProbe probe) {
        configuredBridge = bridge;
        configuredStartupProbe = probe;
    }

    static synchronized void start() throws Exception {
        if (shared != null) return;
        ProviderStoreBridge bridge = configuredBridge != null
                ? configuredBridge
                : new ProviderStoreBridge() {
                    @Override
                    public void register(String relayToken) {
                        throw new IllegalStateException("RELAY_RUNTIME_NOT_CONFIGURED");
                    }

                    @Override
                    public void unregister() {
                        throw new IllegalStateException("RELAY_RUNTIME_NOT_CONFIGURED");
                    }
                };
        TakuProxyRelay relay =
                new TakuProxyRelay(
                        DEFAULT_PORT,
                        DEFAULT_AI_PROXY_BASE,
                        DEFAULT_WORKER_BASE,
                        new SocketTokenSupplier(),
                        bridge);
        relay.startServer();
        StartupProbe probe = configuredStartupProbe;
        if (probe != null) {
            try {
                if (probe.isProviderPersisted()) {
                    relay.activate();
                }
            } catch (Exception ignored) {
            }
        }
        shared = relay;
    }

    static JSONObject control(JSONObject input) throws Exception {
        start();
        String action = input.getString("action");
        switch (action) {
            case "activate":
                shared.activate();
                return new JSONObject().put("activated", true);
            case "deactivate":
                shared.deactivate();
                return new JSONObject().put("deactivated", true);
            default:
                throw new IOException("UNSUPPORTED_TAKU_ACCOUNT_ACTION");
        }
    }

    private final int requestedPort;
    private final String aiProxyBase;
    private final String workerBase;
    private final TokenSupplier tokenSupplier;
    private final ProviderStoreBridge providerBridge;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "taku-proxy-relay-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Object tokenLock = new Object();

    private ServerSocket serverSocket;
    private volatile boolean running;
    private volatile byte[] activeRelayTokenBytes;

    TakuProxyRelay(
            int port,
            String aiProxyBase,
            String workerBase,
            TokenSupplier tokenSupplier,
            ProviderStoreBridge providerBridge) {
        this.requestedPort = port;
        this.aiProxyBase = stripTrailingSlash(aiProxyBase);
        this.workerBase = stripTrailingSlash(workerBase);
        this.tokenSupplier = tokenSupplier;
        this.providerBridge = providerBridge;
    }

    void startServer() throws IOException {
        synchronized (this) {
            if (running) return;
            serverSocket = new ServerSocket(requestedPort, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            running = true;
            Thread acceptThread = new Thread(this::acceptLoop, "taku-proxy-relay");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }
    }

    void stopServer() {
        running = false;
        revokeRelayToken();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        workers.shutdownNow();
    }

    int port() {
        return serverSocket == null ? requestedPort : serverSocket.getLocalPort();
    }

    String activate() throws Exception {
        String token = activateNewRelayToken();
        try {
            providerBridge.register(token);
        } catch (Exception e) {
            revokeRelayToken();
            throw e;
        }
        return token;
    }

    void deactivate() throws Exception {
        revokeRelayToken();
        providerBridge.unregister();
    }

    String activateNewRelayToken() {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = "taku-relay-" + toLowerHex(entropy);
        Arrays.fill(entropy, (byte) 0);
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        synchronized (tokenLock) {
            byte[] old = activeRelayTokenBytes;
            activeRelayTokenBytes = tokenBytes;
            if (old != null) Arrays.fill(old, (byte) 0);
        }
        return token;
    }

    void revokeRelayToken() {
        synchronized (tokenLock) {
            byte[] old = activeRelayTokenBytes;
            activeRelayTokenBytes = null;
            if (old != null) Arrays.fill(old, (byte) 0);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                workers.execute(() -> handleConnection(client));
            } catch (IOException ignored) {
                if (!running) break;
            }
        }
    }

    private void handleConnection(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(15_000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            ParsedRequest request = readRequest(in);
            if (request == null) {
                writeJsonError(out, 400, "Bad Request");
                return;
            }
            RouteTarget route = resolveRoute(request.target);
            if (route == null) {
                writeJsonError(out, 404, "Not Found");
                return;
            }
            if (!verifyInboundToken(request, route.allowXApiKey)) {
                writeJsonError(out, 401, "Unauthorized");
                return;
            }
            forwardWithSingleRetry(request, route, out);
        } catch (BodyTooLargeException tooLarge) {
            try {
                writeJsonError(client.getOutputStream(), 413, "Payload Too Large");
            } catch (IOException ignored) {
            }
        } catch (Exception ignored) {
            try {
                writeJsonError(client.getOutputStream(), 502, "Bad Gateway");
            } catch (IOException ignoredAgain) {
            }
        }
    }

    private RouteTarget resolveRoute(String rawTarget) {
        int qIndex = rawTarget.indexOf('?');
        String path = qIndex >= 0 ? rawTarget.substring(0, qIndex) : rawTarget;
        String querySuffix = qIndex >= 0 ? rawTarget.substring(qIndex) : "";

        if (path.startsWith("/openai/v1/")) {
            return new RouteTarget(aiProxyBase + path + querySuffix, false);
        }
        if (path.equals("/claude") || path.startsWith("/claude/")) {
            return new RouteTarget(aiProxyBase + path + querySuffix, true);
        }
        if (path.startsWith("/models/v1/")) {
            return new RouteTarget(aiProxyBase + path + querySuffix, path.startsWith("/models/v1/messages"));
        }
        if (path.equals("/decide/models") || path.equals("/decide/v1/models")) {
            return new RouteTarget(workerBase + "/typesafe/v1/models" + querySuffix, false);
        }
        if (path.equals("/decide/systemone") || path.equals("/decide/v1/systemone")) {
            return new RouteTarget(workerBase + "/typesafe/v1/systemone" + querySuffix, false);
        }
        return null;
    }

    private boolean verifyInboundToken(ParsedRequest request, boolean allowXApiKey) {
        byte[] expected;
        synchronized (tokenLock) {
            expected = activeRelayTokenBytes == null
                    ? null
                    : Arrays.copyOf(activeRelayTokenBytes, activeRelayTokenBytes.length);
        }
        if (expected == null || expected.length == 0) {
            return false;
        }
        try {
            String bearerToken = extractSingleBearer(request.headers.get("authorization"));
            String xApiKey = extractSingleValue(request.headers.get("x-api-key"));
            if (hasHeaderConflict(request.headers.get("authorization"), bearerToken)
                    || hasHeaderConflict(request.headers.get("x-api-key"), xApiKey)) {
                return false;
            }
            if (!allowXApiKey) {
                if (bearerToken == null || xApiKey != null) {
                    return false;
                }
                return MessageDigest.isEqual(bearerToken.getBytes(StandardCharsets.UTF_8), expected);
            }
            if (bearerToken != null && xApiKey != null && !bearerToken.equals(xApiKey)) {
                return false;
            }
            String presented = bearerToken != null ? bearerToken : xApiKey;
            if (presented == null) {
                return false;
            }
            return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected);
        } finally {
            Arrays.fill(expected, (byte) 0);
        }
    }

    private void forwardWithSingleRetry(ParsedRequest request, RouteTarget route, OutputStream clientOut)
            throws IOException {
        TokenResult first = safeGetToken(false);
        if (first.accessToken == null) {
            writeTokenFailure(clientOut, first.errorCode);
            return;
        }
        HttpURLConnection conn = openUpstreamConnection(request, route.upstreamUrl, first.accessToken);
        try {
            int status = conn.getResponseCode();
            if (status == 401) {
                closeResponseStreams(conn);
                conn.disconnect();
                TokenResult retried = safeGetToken(true);
                if (retried.accessToken == null) {
                    writeTokenFailure(clientOut, retried.errorCode);
                    return;
                }
                conn = openUpstreamConnection(request, route.upstreamUrl, retried.accessToken);
                status = conn.getResponseCode();
            }
            streamUpstreamResponse(conn, status, request.method, clientOut);
        } catch (IOException e) {
            writeJsonError(clientOut, 502, "Upstream Unavailable");
        } finally {
            conn.disconnect();
        }
    }

    private TokenResult safeGetToken(boolean forceRefresh) {
        try {
            return tokenSupplier.getAccessToken(forceRefresh);
        } catch (IOException e) {
            return TokenResult.error("ERR_TRANSIENT_OFFLINE");
        }
    }

    private static void writeTokenFailure(OutputStream out, String errorCode) throws IOException {
        if ("ERR_TOKEN_EXPIRED".equals(errorCode) || "ERR_NOT_SIGNED_IN".equals(errorCode)) {
            writeJsonError(out, 401, "Unauthorized");
        } else {
            writeJsonError(out, 503, "Service Unavailable");
        }
    }

    private static HttpURLConnection openUpstreamConnection(
            ParsedRequest request,
            String upstreamUrl,
            String accessToken) throws IOException {
        URL url = new URL(upstreamUrl);
        String host = url.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        HttpURLConnection conn = (HttpURLConnection) (loopback
                ? url.openConnection(Proxy.NO_PROXY)
                : url.openConnection());
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(request.method);

        for (Map.Entry<String, List<String>> entry : request.headers.entrySet()) {
            String key = entry.getKey();
            if (isStrippedOutboundHeader(key)) continue;
            for (String value : entry.getValue()) {
                conn.addRequestProperty(key, value);
            }
        }
        // Always replace inbound local auth with short-lived Taku JWT and fixed UA for Cloudflare edge.
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("User-Agent", "taku-os-relay/1");

        if (request.body.length > 0 || "POST".equals(request.method) || "PUT".equals(request.method)) {
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(request.body.length);
            if (request.body.length > 0) {
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(request.body);
                }
            }
        }
        return conn;
    }

    private static void streamUpstreamResponse(
            HttpURLConnection conn,
            int status,
            String method,
            OutputStream clientOut) throws IOException {
        String reason = conn.getResponseMessage();
        if (reason == null || reason.isEmpty()) reason = defaultReason(status);

        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");

        boolean bodyDisallowed = "HEAD".equals(method) || status == 204 || status == 304;
        for (int i = 1; ; i++) {
            String name = conn.getHeaderFieldKey(i);
            String value = conn.getHeaderField(i);
            if (name == null && value == null) break;
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if ("transfer-encoding".equals(lower)
                    || "connection".equals(lower)
                    || "content-length".equals(lower)) {
                continue;
            }
            head.append(name).append(": ").append(value).append("\r\n");
        }
        head.append("Connection: close\r\n");
        if (bodyDisallowed) {
            head.append("Content-Length: 0\r\n\r\n");
            clientOut.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();
            closeResponseStreams(conn);
            return;
        }
        head.append("Transfer-Encoding: chunked\r\n\r\n");
        clientOut.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        clientOut.flush();

        InputStream upstreamStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (upstreamStream == null && status >= 400) {
            upstreamStream = conn.getInputStream();
        }
        if (upstreamStream != null) {
            try (InputStream in = upstreamStream) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (n == 0) continue;
                    writeChunk(clientOut, buffer, n);
                    clientOut.flush();
                }
            } catch (IOException ignored) {
                // Propagate end of stream cleanly if upstream terminated after headers.
            }
        }
        clientOut.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        clientOut.flush();
    }

    private static void writeChunk(OutputStream out, byte[] data, int length) throws IOException {
        String sizeLine = Integer.toHexString(length) + "\r\n";
        out.write(sizeLine.getBytes(StandardCharsets.US_ASCII));
        out.write(data, 0, length);
        out.write('\r');
        out.write('\n');
    }

    private static void writeJsonError(OutputStream out, int status, String message) throws IOException {
        byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        String headers =
                "HTTP/1.1 " + status + " " + defaultReason(status) + "\r\n"
                        + "Content-Type: application/json; charset=utf-8\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static ParsedRequest readRequest(BufferedInputStream in) throws IOException {
        String requestLine = readAsciiLine(in, 8192);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) return null;
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];

        Map<String, List<String>> headers = new LinkedHashMap<>();
        int totalHeaderBytes = requestLine.length();
        String line;
        while ((line = readAsciiLine(in, 16384)) != null) {
            if (line.isEmpty()) break;
            totalHeaderBytes += line.length();
            if (totalHeaderBytes > MAX_HEADER_BYTES) return null;
            int colon = line.indexOf(':');
            if (colon <= 0) return null;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }

        byte[] body;
        String te = extractSingleValue(headers.get("transfer-encoding"));
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            body = readChunkedBody(in);
        } else {
            String cl = extractSingleValue(headers.get("content-length"));
            if (cl != null) {
                int contentLength;
                try {
                    contentLength = Integer.parseInt(cl);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (contentLength < 0) return null;
                if (contentLength > MAX_BODY_BYTES) throw new BodyTooLargeException();
                body = readFixedBytes(in, contentLength);
            } else {
                body = new byte[0];
            }
        }
        return new ParsedRequest(method, target, headers, body);
    }

    private static String readAsciiLine(InputStream in, int maxLen) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') {
                out.write(b);
                if (out.size() > maxLen) throw new IOException("Line too long");
            }
        }
        if (b == -1 && out.size() == 0) return null;
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] readFixedBytes(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(buf, offset, length - offset);
            if (n < 0) throw new IOException("Unexpected EOF");
            offset += n;
        }
        return buf;
    }

    private static byte[] readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readAsciiLine(in, 256);
            if (sizeLine == null) throw new IOException("Unexpected chunk EOF");
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int chunkSize = Integer.parseInt(hex, 16);
            if (chunkSize == 0) {
                while (true) {
                    String trailer = readAsciiLine(in, 4096);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                return out.toByteArray();
            }
            if (out.size() + chunkSize > MAX_BODY_BYTES) throw new BodyTooLargeException();
            out.write(readFixedBytes(in, chunkSize));
            readAsciiLine(in, 16);
        }
    }

    private static boolean hasHeaderConflict(List<String> values, String extracted) {
        return values != null && !values.isEmpty() && extracted == null;
    }

    private static String extractSingleValue(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        String resolved = null;
        for (String v : values) {
            String trimmed = v == null ? "" : v.trim();
            if (trimmed.isEmpty() || containsControlOrWhitespace(trimmed)) return null;
            if (resolved == null) resolved = trimmed;
            else if (!resolved.equals(trimmed)) return null;
        }
        return resolved;
    }

    private static String extractSingleBearer(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        String resolved = null;
        for (String v : values) {
            String trimmed = v == null ? "" : v.trim();
            if (trimmed.length() <= 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return null;
            }
            String token = trimmed.substring(7).trim();
            if (token.isEmpty() || containsControlOrWhitespace(token)) return null;
            if (resolved == null) resolved = token;
            else if (!resolved.equals(token)) return null;
        }
        return resolved;
    }

    private static boolean containsControlOrWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return true;
        }
        return false;
    }

    private static boolean isStrippedOutboundHeader(String lowerName) {
        return "host".equals(lowerName)
                || "content-length".equals(lowerName)
                || "connection".equals(lowerName)
                || "transfer-encoding".equals(lowerName)
                || "accept-encoding".equals(lowerName)
                || "authorization".equals(lowerName)
                || "user-agent".equals(lowerName)
                || "x-api-key".equals(lowerName)
                || "cookie".equals(lowerName)
                || "proxy-authorization".equals(lowerName);
    }

    private static void closeResponseStreams(HttpURLConnection conn) {
        try {
            if (conn.getErrorStream() != null) conn.getErrorStream().close();
        } catch (IOException ignored) {
        }
        try {
            conn.getInputStream().close();
        } catch (IOException ignored) {
        }
    }

    private static String toLowerHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            hex[i * 2] = alphabet[b >>> 4];
            hex[i * 2 + 1] = alphabet[b & 0x0F];
        }
        return new String(hex);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }

    private static String defaultReason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "OK";
        }
    }

    private static final class BodyTooLargeException extends IOException {}

    private static final class SocketTokenSupplier implements TokenSupplier {
        @Override
        public TokenResult getAccessToken(boolean forceRefresh) throws IOException {
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(TOKEN_SOCKET));
                int uid = socket.getPeerCredentials().getUid();
                if (uid != 0 && uid != 1000) {
                    return TokenResult.error("ERR_INVALID_RUNTIME_IDENTITY");
                }
                socket.setSoTimeout(15_000);
                JSONObject req = new JSONObject()
                        .put("action", "getAccessToken")
                        .put("forceRefresh", forceRefresh);
                socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
                socket.shutdownOutput();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = socket.getInputStream().read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > 64 * 1024) {
                        return TokenResult.error("ERR_TRANSIENT_OFFLINE");
                    }
                }
                JSONObject resp = new JSONObject(out.toString(StandardCharsets.UTF_8));
                if (!resp.optBoolean("ok")) {
                    return TokenResult.error(resp.optString("code", "ERR_TRANSIENT_OFFLINE"));
                }
                String token = resp.optString("accessToken", "").trim();
                return token.isEmpty()
                        ? TokenResult.error("ERR_TRANSIENT_OFFLINE")
                        : TokenResult.ok(token);
            } catch (Exception e) {
                return TokenResult.error("ERR_TRANSIENT_OFFLINE");
            }
        }
    }
}
