package org.agentos.lab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Host loopback test for TakuProxyRelay + TakuCreditsCatalog covering fail-closed local token checks,
 * header sanitization, upstream 503 pass-through vs 401 single retry, SSE streaming, BYOK coexistence,
 * legacy model allowlist boundaries, and instant revocation on deactivate().
 */
public final class TakuProxyRelayTest {
    private static final class MemoryStore implements SystemAiSettings.Store {
        final Map<String, String> values = new HashMap<>();

        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void delete(String key) { values.remove(key); }
        @Override public Map<String, String> list() { return values; }
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("taku-proxy-relay-test").toFile();
        File providersFile = new File(dir, "providers.json");
        File secretsFile = new File(dir, "taku-credentials.json");

        // Pre-populate existing high-priority BYOK provider and scene binding.
        MemoryStore store = new MemoryStore();
        JSONObject initialConfig =
                new JSONObject()
                        .put("version", 1)
                        .put("updated_at", 1000L)
                        .put("updated_by", "user")
                        .put(
                                "providers",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("id", "byok-openrouter")
                                                        .put("name", "My OpenRouter BYOK")
                                                        .put("preset", "openrouter")
                                                        .put("credentialRef", "byok-openrouter")))
                        .put("models", new JSONObject().put("byok-openrouter", new JSONArray().put("anthropic/claude-opus")))
                        .put(
                                "groups",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("id", "route-assistant")
                                                        .put("members", new JSONArray().put("byok-openrouter/anthropic/claude-opus"))
                                                        .put("routing", "order")))
                        .put("scenes", new JSONObject().put("assistant", "group/route-assistant"));
        store.put(SystemAiSettings.CONFIG, initialConfig.toString());
        Files.writeString(
                secretsFile.toPath(),
                new JSONObject().put("byok-openrouter", new JSONObject().put("key", "byok-test-placeholder")).toString());

        AtomicReference<String> currentJwt = new AtomicReference<>("jwt-v1");
        AtomicInteger forceRefreshCalls = new AtomicInteger();
        AtomicInteger upstreamMode = new AtomicInteger(0); // 0=ok, 1=503, 2=401-once-then-200, 3=401-always
        AtomicReference<String> lastUpstreamPath = new AtomicReference<>("");
        AtomicReference<String> lastUpstreamAuth = new AtomicReference<>("");
        AtomicReference<String> lastUpstreamXApiKey = new AtomicReference<>(null);
        AtomicReference<String> lastUpstreamUserAgent = new AtomicReference<>(null);
        AtomicReference<String> lastUpstreamIdempotency = new AtomicReference<>(null);
        AtomicReference<String> lastUpstreamBody = new AtomicReference<>("");

        HttpServer upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/", exchange -> {
            lastUpstreamPath.set(exchange.getRequestURI().toString());
            lastUpstreamAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastUpstreamXApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            lastUpstreamUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            lastUpstreamIdempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            lastUpstreamBody.set(readBody(exchange.getRequestBody()));

            int mode = upstreamMode.get();
            if (mode == 1) {
                send(exchange, 503, "application/json", "{\"error\":\"upstream_busy\"}");
                return;
            }
            if (mode == 2) {
                if (!"Bearer jwt-v2".equals(lastUpstreamAuth.get())) {
                    send(exchange, 401, "application/json", "{\"error\":\"stale_jwt\"}");
                    return;
                }
            } else if (mode == 3) {
                send(exchange, 401, "application/json", "{\"error\":\"always_unauthorized\"}");
                return;
            }

            if (lastUpstreamPath.get().startsWith("/openai/v1/responses")) {
                byte[] sse = ("event: response.output_text.delta\ndata: {\"delta\":\"hi\"}\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, sse.length);
                exchange.getResponseBody().write(sse);
                exchange.close();
                return;
            }
            send(exchange, 200, "application/json", "{\"ok\":true,\"path\":\"" + lastUpstreamPath.get() + "\"}");
        });
        upstream.start();
        String upstreamBase = "http://127.0.0.1:" + upstream.getAddress().getPort();

        TakuProxyRelay.TokenSupplier supplier = forceRefresh -> {
            if (forceRefresh) {
                forceRefreshCalls.incrementAndGet();
                currentJwt.set("jwt-v2");
            }
            return TakuProxyRelay.TokenResult.ok(currentJwt.get());
        };
        TakuProxyRelay.ProviderStoreBridge bridge = new TakuProxyRelay.ProviderStoreBridge() {
            @Override
            public void register(String relayToken) throws Exception {
                TakuCreditsCatalog.registerProvider(store, providersFile, secretsFile, relayToken);
            }

            @Override
            public void unregister() throws Exception {
                TakuCreditsCatalog.unregisterProvider(store, providersFile, secretsFile);
            }
        };

        TakuProxyRelay relay = new TakuProxyRelay(0, upstreamBase, upstreamBase, supplier, bridge);
        relay.startServer();
        String relayBase = "http://127.0.0.1:" + relay.port();

        try {
            // 1. Default gateway origin & legacy catalog allowlist verification
            check("https://worker.taku.ai".equals(TakuProxyRelay.DEFAULT_AI_PROXY_BASE),
                    "DEFAULT_AI_PROXY_BASE must point to https://worker.taku.ai");
            check(TakuCreditsCatalog.isLegacyAllowedModel("gpt-5.6-luna"), "gpt-5.6-luna must be allowed");
            check(TakuCreditsCatalog.supportsResponsesProtocol("gpt-5.6-luna"), "gpt-5.6-luna supports responses");
            check(TakuCreditsCatalog.supportsResponsesProtocol("glm-5.2"), "glm-5.2 supports responses");
            check(!TakuCreditsCatalog.supportsResponsesProtocol("glm-5.3"), "glm-5.3 must NOT be exposed as responses");
            check(!TakuCreditsCatalog.supportsMessagesProtocol("deepseek/deepseek-v4-pro"), "deepseek must NOT be messages");
            check(TakuCreditsCatalog.supportsMessagesProtocol("claude-sonnet-4-6"), "claude-sonnet-4-6 supports messages");
            check(!TakuCreditsCatalog.isLegacyAllowedModel("glm-latest"), "glm-latest forbidden on legacy");
            check(!TakuCreditsCatalog.isLegacyAllowedModel("claude-opus-5"), "claude-opus-5 forbidden on legacy");
            check(!TakuCreditsCatalog.isLegacyAllowedModel("doubao-seed-2.1-pro"), "doubao seed forbidden on legacy");

            // Before activation: requests must fail closed with 401
            HttpRes beforeActivate = callHttp("POST", relayBase + "/openai/v1/chat/completions", "taku-relay-fake", false, null, "{\"x\":1}");
            check(beforeActivate.status == 401, "inactive relay must reject 401");

            // 2. Activation writes 256-bit relay token only to taku-credentials.json & preserves BYOK/scenes
            String relayToken = relay.activate();
            check(relayToken.matches("taku-relay-[0-9a-f]{64}"), "relay token must be 256-bit hex");

            String settingsJson = store.get(SystemAiSettings.CONFIG);
            check(!settingsJson.contains(relayToken), "Settings.Global must NOT contain TAKU_PROXY_RELAY_TOKEN");
            check(!settingsJson.contains("jwt-v1"), "Settings.Global must NOT contain Supabase JWT");

            JSONObject configObj = new JSONObject(settingsJson);
            check("group/route-assistant".equals(configObj.getJSONObject("scenes").getString("assistant")),
                    "assistant scene binding must remain unchanged");
            JSONArray providers = configObj.getJSONArray("providers");
            check(providers.length() == 2, "must retain BYOK + add taku-credits, got " + providers.length());

            JSONObject secrets = new JSONObject(Files.readString(secretsFile.toPath()));
            check(relayToken.equals(secrets.getJSONObject("taku-credits").getString("key")),
                    "taku-credentials.json holds relay token");
            check("byok-test-placeholder".equals(secrets.getJSONObject("byok-openrouter").getString("key")),
                    "BYOK secret must remain untouched");

            // 3. Fail-closed header checks
            HttpRes missingAuth = callHttp("POST", relayBase + "/openai/v1/chat/completions", null, false, null, "{}");
            check(missingAuth.status == 401, "missing Bearer returns 401");
            HttpRes wrongToken = callHttp("POST", relayBase + "/openai/v1/chat/completions", "taku-relay-wrong", false, null, "{}");
            check(wrongToken.status == 401 && !wrongToken.body.contains("taku-relay-wrong"), "wrong token 401 without echoing secret");
            HttpRes xApiKeyOnOpenAi = callHttp("POST", relayBase + "/openai/v1/chat/completions", relayToken, true, null, "{}");
            check(xApiKeyOnOpenAi.status == 401, "x-api-key alone on /openai/v1/* must return 401");

            HttpRes dualConflict = callDualAuth(relayBase + "/claude/v1/messages", relayToken, "taku-relay-other");
            check(dualConflict.status == 401, "mismatched Authorization and x-api-key must return 401");

            HttpRes unknownRoute = callHttp("POST", relayBase + "/v1/unknown", relayToken, false, null, "{}");
            check(unknownRoute.status == 404, "unknown path must return 404");

            // 4. /claude/v1/messages strips x-api-key, sets fixed User-Agent, and injects Bearer JWT
            HttpRes claudeRes = callHttp("POST", relayBase + "/claude/v1/messages", relayToken, true,
                    Map.of("anthropic-version", "2023-06-01", "User-Agent", "Python-urllib/3.12"),
                    "{\"model\":\"claude-sonnet-4-6\"}");
            check(claudeRes.status == 200, "/claude/v1/messages expected 200, got " + claudeRes.status);
            check("/claude/v1/messages".equals(lastUpstreamPath.get()), "claude path forwarded verbatim");
            check(lastUpstreamXApiKey.get() == null, "x-api-key must be stripped before upstream");
            check("taku-os-relay/1".equals(lastUpstreamUserAgent.get()),
                    "User-Agent must be normalized to taku-os-relay/1, got " + lastUpstreamUserAgent.get());
            check("Bearer jwt-v1".equals(lastUpstreamAuth.get()), "upstream must receive Supabase JWT");

            // 4b. /models/v1/responses forwards cleanly with Bearer auth
            HttpRes modelsRes = callHttp("POST", relayBase + "/models/v1/responses", relayToken, false,
                    null, "{\"model\":\"glm-5.3\",\"input\":\"hi\"}");
            check(modelsRes.status == 200, "/models/v1/responses expected 200, got " + modelsRes.status);
            check("/models/v1/responses".equals(lastUpstreamPath.get()), "/models/v1/responses forwarded verbatim");

            // 5. /decide/systemone maps to /typesafe/v1/systemone and preserves Idempotency-Key
            HttpRes decideRes = callHttp("POST", relayBase + "/decide/systemone", relayToken, false,
                    Map.of("Idempotency-Key", "idem-123", "X-Request-Id", "req-456"), "{\"q\":1}");
            check(decideRes.status == 200, "/decide/systemone expected 200");
            check("/typesafe/v1/systemone".equals(lastUpstreamPath.get()), "decide path mapped cleanly");
            check("idem-123".equals(lastUpstreamIdempotency.get()), "Idempotency-Key preserved");

            // 6. SSE stream pass-through on /openai/v1/responses
            HttpRes sseRes = callHttp("POST", relayBase + "/openai/v1/responses", relayToken, false, null, "{\"stream\":true}");
            check(sseRes.status == 200 && sseRes.body.contains("response.output_text.delta"), "SSE stream passed through");

            // 7. Upstream 503 passes through WITHOUT calling forceRefresh
            int refreshesBefore503 = forceRefreshCalls.get();
            upstreamMode.set(1);
            HttpRes res503 = callHttp("POST", relayBase + "/openai/v1/chat/completions", relayToken, false, null, "{}");
            check(res503.status == 503, "503 passed through");
            check(forceRefreshCalls.get() == refreshesBefore503, "503 must NOT trigger forceRefresh");

            // 8. Upstream 401 triggers forceRefresh once and replays bounded body cleanly
            upstreamMode.set(2);
            HttpRes resRecovered = callHttp("POST", relayBase + "/openai/v1/chat/completions", relayToken, false, null, "{\"retry\":\"ok\"}");
            check(resRecovered.status == 200, "401 should recover on single retry");
            check(forceRefreshCalls.get() == refreshesBefore503 + 1, "401 triggers forceRefresh exactly once");
            check("{\"retry\":\"ok\"}".equals(lastUpstreamBody.get()), "POST body preserved cleanly on retry");

            // 9. Persistent upstream 401 must cap at 1 retry (no loop)
            upstreamMode.set(3);
            HttpRes resStill401 = callHttp("POST", relayBase + "/openai/v1/chat/completions", relayToken, false, null, "{}");
            check(resStill401.status == 401, "persistent 401 terminates cleanly");
            check(forceRefreshCalls.get() == refreshesBefore503 + 2, "must cap at 1 forceRefresh per request");

            // 10. Deactivate revokes relay token immediately and leaves BYOK untouched
            relay.deactivate();
            HttpRes afterRevoke = callHttp("POST", relayBase + "/openai/v1/chat/completions", relayToken, false, null, "{}");
            check(afterRevoke.status == 401, "revoked relay token immediately rejected");

            JSONObject afterConfig = new JSONObject(store.get(SystemAiSettings.CONFIG));
            check(afterConfig.getJSONArray("providers").length() == 1, "only taku-credits removed");
            check("byok-openrouter".equals(afterConfig.getJSONArray("providers").getJSONObject(0).getString("id")),
                    "BYOK provider kept");
            JSONObject afterSecrets = new JSONObject(Files.readString(secretsFile.toPath()));
            check(!afterSecrets.has("taku-credits") && afterSecrets.has("byok-openrouter"),
                    "only taku-credits secret removed from taku-credentials.json");

            System.out.println("TakuProxyRelayTest: PASS");
        } finally {
            relay.stopServer();
            upstream.stop(0);
            Files.deleteIfExists(providersFile.toPath());
            Files.deleteIfExists(secretsFile.toPath());
            Files.deleteIfExists(new File(dir, "providers.json.taku.tmp").toPath());
            Files.deleteIfExists(new File(dir, "taku-credentials.json.taku.tmp").toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    private static HttpRes callHttp(
            String method,
            String urlStr,
            String token,
            boolean useXApiKey,
            Map<String, String> extraHeaders,
            String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection(Proxy.NO_PROXY);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod(method);
        if (token != null) {
            if (useXApiKey) conn.setRequestProperty("x-api-key", token);
            else conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String respBody = in == null ? "" : readBody(in);
        conn.disconnect();
        return new HttpRes(status, respBody);
    }

    private static HttpRes callDualAuth(String urlStr, String bearer, String xApiKey) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection(Proxy.NO_PROXY);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + bearer);
        conn.setRequestProperty("x-api-key", xApiKey);
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(2);
        try (OutputStream out = conn.getOutputStream()) {
            out.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String respBody = in == null ? "" : readBody(in);
        conn.disconnect();
        return new HttpRes(status, respBody);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static final class HttpRes {
        final int status;
        final String body;

        HttpRes(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
