package org.agentos.assistant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONObject;

/**
 * Host test for TakuAuthManager covering all 6 states and invariants: locked_boot gating,
 * unlock recovery, PKCE state/implicit-flow/replay defenses, password login,
 * single-flight refresh, transient offline/5xx retention, 400 invalid_grant revocation,
 * and clean logout.
 */
public final class TakuAuthManagerTest {
    private static final String USER_ID = "11111111-1111-4111-8111-111111111111";
    private static final String EMAIL = "alice@taku.ai";
    private static final String PUB_KEY = "sb_publishable_test_key";

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("taku-auth-mgr-test").toFile();
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey secretKey = generator.generateKey();

        TakuAccountStore store =
                new TakuAccountStore(
                        dir,
                        new TakuAccountStore.KeyProvider() {
                            @Override
                            public SecretKey getOrCreate() {
                                return secretKey;
                            }

                            @Override
                            public void delete() {}
                        });

        AtomicLong fakeClock = new AtomicLong(1_700_000_000_000L);
        AtomicBoolean unlocked = new AtomicBoolean(false);
        AtomicReference<String> publishedState = new AtomicReference<>("");
        AtomicInteger activateCalls = new AtomicInteger();
        AtomicInteger deactivateCalls = new AtomicInteger();

        // Mock upstream GoTrue + Worker endpoints
        AtomicInteger refreshCalls = new AtomicInteger();
        AtomicInteger refreshMode = new AtomicInteger(0); // 0=ok, 1=transient503, 2=permanent400
        AtomicInteger balanceCalls = new AtomicInteger();
        AtomicBoolean balanceRejectFirst401 = new AtomicBoolean(false);
        AtomicReference<String> expectedCodeChallenge = new AtomicReference<>("");
        AtomicReference<String> latestAccessToken = new AtomicReference<>(makeJwt(USER_ID, 1_700_003_600L));
        AtomicReference<String> latestRefreshToken = new AtomicReference<>("rt-initial");
        AtomicReference<String> acceptedPassword = new AtomicReference<>("secret-123");

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/auth/v1/token", exchange -> handleToken(
                exchange, fakeClock, refreshCalls, refreshMode, expectedCodeChallenge,
                latestAccessToken, latestRefreshToken, acceptedPassword));
        server.createContext("/auth/v1/user", exchange -> handleUser(exchange, latestAccessToken));
        server.createContext("/payment/balance", exchange -> {
            int n = balanceCalls.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (balanceRejectFirst401.compareAndSet(true, false)) {
                sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }
            if (!("Bearer " + latestAccessToken.get()).equals(auth)) {
                sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }
            sendJson(exchange, 200, "{\"available_credits\":42.5,\"active_credits\":42.5,\"scheduled_credits\":0,\"debt_amount\":0,\"next_expiry_at\":null}");
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        TakuAccountClient client = new TakuAccountClient.HttpClient(baseUrl, PUB_KEY, baseUrl);

        TakuAuthManager.BrokerSync brokerSync =
                new TakuAuthManager.BrokerSync() {
                    @Override
                    public void activate() {
                        activateCalls.incrementAndGet();
                    }

                    @Override
                    public void deactivate() {
                        deactivateCalls.incrementAndGet();
                    }
                };
        TakuAuthManager.PublicStateSink sink = (k, v) -> publishedState.set(v);

        try {
            // 1. locked_boot: device locked must block decryption and all account mutations.
            TakuAuthManager lockedMgr = new TakuAuthManager(store, client, fakeClock::get, unlocked::get, sink, brokerSync);
            JSONObject lockedStatus = lockedMgr.status();
            check("locked_boot".equals(lockedStatus.getString("authState")), "starts in locked_boot");
            check(!lockedStatus.getBoolean("providerRegistered"), "locked must not register provider");
            expectCode("ERR_USER_LOCKED", () -> lockedMgr.loginWithPassword(EMAIL, "secret-123"));
            expectCode("ERR_USER_LOCKED", () -> lockedMgr.startGoogleOAuth(null));
            expectCode("ERR_USER_LOCKED", () -> lockedMgr.getValidAccessToken(false));

            // 2. UnlockNoSession -> signed_out
            unlocked.set(true);
            lockedMgr.onUserUnlocked();
            check("signed_out".equals(lockedMgr.status().getString("authState")), "unlock empty -> signed_out");

            // 3. Google PKCE flow + implicit flow rejection + bad state rejection + replay rejection
            JSONObject oauthStart = lockedMgr.startGoogleOAuth(null);
            check("oauth_pending".equals(oauthStart.getString("authState")), "oauth_pending state");
            String authUrl = oauthStart.getString("authorizeUrl");
            check(authUrl.contains("prompt=select_account"), "authorizeUrl includes prompt=select_account");
            check(queryParam(URI.create(authUrl).getRawQuery(), "state") == null, "top-level authorize state avoided in favor of redirect_to");
            String state = extractOAuthState(authUrl);
            String challenge = queryParam(URI.create(authUrl).getRawQuery(), "code_challenge");
            expectedCodeChallenge.set(challenge);

            expectCode("ERR_IMPLICIT_FLOW_REJECTED",
                    () -> lockedMgr.completeGoogleOAuthCallback("taku-account://auth/callback#access_token=bad&state=" + state));
            expectCode("ERR_OAUTH_STATE_INVALID",
                    () -> lockedMgr.completeGoogleOAuthCallback("taku-account://auth/callback?code=c1&state=wrong-state"));

            // Cancel resets to signed_out and clears pending PKCE
            lockedMgr.cancelOAuth();
            check("signed_out".equals(lockedMgr.status().getString("authState")), "cancel returns signed_out");

            // PKCE TTL expiration check
            JSONObject oauthStart2 = lockedMgr.startGoogleOAuth(null);
            String state2 = extractOAuthState(oauthStart2.getString("authorizeUrl"));
            expectedCodeChallenge.set(queryParam(URI.create(oauthStart2.getString("authorizeUrl")).getRawQuery(), "code_challenge"));
            fakeClock.addAndGet(TakuAuthManager.PKCE_TTL_MS + 1000L);
            expectCode("ERR_OAUTH_EXPIRED",
                    () -> lockedMgr.completeGoogleOAuthCallback("taku-account://auth/callback?code=c2&state=" + state2));
            check("signed_out".equals(lockedMgr.status().getString("authState")), "expired pkce resets signed_out");

            // Valid Google PKCE flow completes login, registers provider, fetches credits, and prevents replay
            JSONObject oauthStart3 = lockedMgr.startGoogleOAuth(null);
            String state3 = extractOAuthState(oauthStart3.getString("authorizeUrl"));
            expectedCodeChallenge.set(queryParam(URI.create(oauthStart3.getString("authorizeUrl")).getRawQuery(), "code_challenge"));
            String validCallback = "taku-account://auth/callback?code=pkce-good&state=" + state3;
            JSONObject signedIn = lockedMgr.completeGoogleOAuthCallback(validCallback);
            check("signed_in".equals(signedIn.getString("authState")), "pkce callback signed_in");
            check(signedIn.getBoolean("providerRegistered"), "pkce callback activates provider");
            check(EMAIL.equals(signedIn.getString("email")), "pkce verified email");
            check(Math.abs(42.5 - signedIn.getDouble("creditsBalance")) < 0.01, "credits balance loaded");
            check(activateCalls.get() == 1, "broker activated once");
            expectCode("ERR_OAUTH_STATE_INVALID", () -> lockedMgr.completeGoogleOAuthCallback(validCallback));
            checkNoSecretsInJson(publishedState.get(), latestAccessToken.get(), latestRefreshToken.get());

            // 4. Logout wipes store and deactivates provider cleanly
            JSONObject loggedOut = lockedMgr.logout();
            check("signed_out".equals(loggedOut.getString("authState")), "logout -> signed_out");
            check(!loggedOut.getBoolean("providerRegistered"), "logout clears provider");
            check(!store.exists(), "logout deletes session.enc");
            check(deactivateCalls.get() == 1, "logout deactivated broker");

            // 5. Password login (bad password fails cleanly; valid password signs in)
            expectCode("ERR_INVALID_CREDENTIALS", () -> lockedMgr.loginWithPassword(EMAIL, "wrong-pass"));
            JSONObject pwSignedIn = lockedMgr.loginWithPassword(EMAIL, "secret-123");
            check("signed_in".equals(pwSignedIn.getString("authState")), "password login -> signed_in");

            // 6. Single-flight refresh across 5 concurrent callers + transient offline preservation
            refreshCalls.set(0);
            refreshMode.set(0);
            // Advance clock within REFRESH_BUFFER_MS (e.g. +56 min on 60 min token)
            fakeClock.addAndGet(56 * 60_000L);
            var pool = Executors.newFixedThreadPool(5);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> lockedMgr.getValidAccessToken(false)));
            }
            String expectedToken = futures.get(0).get();
            for (Future<String> f : futures) {
                check(expectedToken.equals(f.get()), "all 5 threads receive identical refreshed JWT");
            }
            pool.shutdownNow();
            check(refreshCalls.get() == 1, "single-flight must hit /token?grant_type=refresh_token exactly once, got " + refreshCalls.get());

            // 7. Transient 503 during refresh must NEVER drop session or unregister provider!
            refreshMode.set(1);
            int deactivatesBeforeOffline = deactivateCalls.get();
            expectCode("ERR_TRANSIENT_OFFLINE", () -> lockedMgr.getValidAccessToken(true));
            JSONObject offlineStatus = lockedMgr.status();
            check("signed_in".equals(offlineStatus.getString("authState")), "transient error preserves signed_in");
            check(offlineStatus.getBoolean("transientOffline"), "transientOffline flagged true");
            check(offlineStatus.getBoolean("providerRegistered"), "transient error keeps taku-credits registered");
            check(store.exists(), "transient error retains session.enc");
            check(deactivateCalls.get() == deactivatesBeforeOffline, "transient error must not deactivate broker");

            // Recovery back online via balance 401 -> forceRefresh retry!
            refreshMode.set(0);
            balanceRejectFirst401.set(true);
            JSONObject refreshedStatus = lockedMgr.refreshAccount();
            check(!refreshedStatus.getBoolean("transientOffline"), "online refresh clears transientOffline");

            // 8. Cold unlock when stored access_token is expired (UnlockExpiredToken, non-blocking status)
            fakeClock.addAndGet(58 * 60_000L);
            AtomicBoolean secondUnlocked = new AtomicBoolean(true);
            TakuAuthManager rebootedMgr =
                    new TakuAuthManager(store, client, fakeClock::get, secondUnlocked::get, sink, brokerSync);
            JSONObject rebootStatus = rebootedMgr.status();
            String rebootState = rebootStatus.getString("authState");
            check("refreshing".equals(rebootState) || "signed_in".equals(rebootState),
                    "non-blocking status on expired unlock enters refreshing or signed_in, got " + rebootState);
            check(rebootStatus.getBoolean("providerRegistered"), "reboot preserves taku-credits provider");
            String rebootedJwt = rebootedMgr.getValidAccessToken(false);
            check(latestAccessToken.get().equals(rebootedJwt), "reboot async refresh yields valid JWT");
            check("signed_in".equals(rebootedMgr.status().getString("authState")),
                    "reboot after refresh finishes reaches signed_in");

            // 9. Permanent 400 invalid_grant revokes session, wipes Keystore store, deactivates provider, enters expired
            refreshMode.set(2);
            expectCode("ERR_TOKEN_EXPIRED", () -> rebootedMgr.getValidAccessToken(true));
            JSONObject expiredStatus = rebootedMgr.status();
            check("expired".equals(expiredStatus.getString("authState")), "400 invalid_grant -> expired");
            check(!expiredStatus.getBoolean("providerRegistered"), "expired unregisters taku-credits");
            check(!store.exists(), "expired deletes session.enc");

            rebootedMgr.clearExpired();
            check("signed_out".equals(rebootedMgr.status().getString("authState")), "clearExpired -> signed_out");

            System.out.println("TakuAuthManagerTest: PASS");
        } finally {
            server.stop(0);
            store.clear();
            Files.deleteIfExists(dir.toPath());
        }
    }

    private static void handleToken(
            HttpExchange exchange,
            AtomicLong fakeClock,
            AtomicInteger refreshCalls,
            AtomicInteger refreshMode,
            AtomicReference<String> expectedChallenge,
            AtomicReference<String> latestAccessToken,
            AtomicReference<String> latestRefreshToken,
            AtomicReference<String> acceptedPassword) throws IOException {
        String grantType = queryParam(exchange.getRequestURI().getRawQuery(), "grant_type");
        if (!PUB_KEY.equals(exchange.getRequestHeaders().getFirst("apikey"))) {
            sendJson(exchange, 401, "{\"error\":\"bad_apikey\"}");
            return;
        }
        String bodyStr = readBody(exchange.getRequestBody());
        JSONObject req = bodyStr.isEmpty() ? new JSONObject() : new JSONObject(bodyStr);

        if ("pkce".equals(grantType)) {
            String codeVerifier = req.optString("code_verifier", "");
            String derivedChallenge;
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
                derivedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            } catch (Exception e) {
                throw new IOException(e);
            }
            if (!expectedChallenge.get().equals(derivedChallenge)) {
                sendJson(exchange, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            long expSec = (fakeClock.get() / 1000L) + 3600L;
            String jwt = makeJwt(USER_ID, expSec);
            latestAccessToken.set(jwt);
            latestRefreshToken.set("rt-pkce");
            sendJson(exchange, 200, sessionPayload(jwt, "rt-pkce", expSec));
            return;
        }

        if ("password".equals(grantType)) {
            if (!EMAIL.equalsIgnoreCase(req.optString("email", ""))
                    || !acceptedPassword.get().equals(req.optString("password", ""))) {
                sendJson(exchange, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            long expSec = (fakeClock.get() / 1000L) + 3600L;
            String jwt = makeJwt(USER_ID, expSec);
            latestAccessToken.set(jwt);
            latestRefreshToken.set("rt-password");
            sendJson(exchange, 200, sessionPayload(jwt, "rt-password", expSec));
            return;
        }

        if ("refresh_token".equals(grantType)) {
            int callNo = refreshCalls.incrementAndGet();
            try {
                Thread.sleep(60); // Force concurrent callers to coalesce onto single flight.
            } catch (InterruptedException ignored) {
            }
            int mode = refreshMode.get();
            if (mode == 1) {
                sendJson(exchange, 503, "{\"error\":\"temporarily_unavailable\"}");
                return;
            }
            if (mode == 2) {
                sendJson(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid Refresh Token\"}");
                return;
            }
            long expSec = (fakeClock.get() / 1000L) + 3600L;
            String jwt = makeJwt(USER_ID, expSec);
            String nextRt = "rt-rotated-" + callNo;
            latestAccessToken.set(jwt);
            latestRefreshToken.set(nextRt);
            sendJson(exchange, 200, sessionPayload(jwt, nextRt, expSec));
            return;
        }

        sendJson(exchange, 400, "{\"error\":\"unsupported_grant\"}");
    }

    private static void handleUser(
            HttpExchange exchange,
            AtomicReference<String> latestAccessToken) throws IOException {
        if (!PUB_KEY.equals(exchange.getRequestHeaders().getFirst("apikey"))) {
            sendJson(exchange, 401, "{\"error\":\"bad_apikey\"}");
            return;
        }
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + latestAccessToken.get()).equals(auth)) {
            sendJson(exchange, 401, "{\"error_code\":\"bad_jwt\"}");
            return;
        }
        sendJson(exchange, 200, "{\"id\":\"" + USER_ID + "\",\"email\":\"" + EMAIL + "\"}");
    }

    private static void checkNoSecretsInJson(String json, String accessToken, String refreshToken) {
        check(json != null && !json.isEmpty(), "public state must be published");
        check(!json.contains(accessToken), "accessToken leaked into Settings state");
        check(!json.contains(refreshToken), "refreshToken leaked into Settings state");
        check(!json.contains("taku-relay-"), "relay token leaked into Settings state");
    }

    private static String makeJwt(String sub, long expSec) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"ES256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + expSec + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig" + expSec;
    }

    private static String sessionPayload(String accessToken, String refreshToken, long expSec) {
        return new JSONObject()
                .put("access_token", accessToken)
                .put("refresh_token", refreshToken)
                .put("expires_at", expSec)
                .put("user", new JSONObject().put("id", USER_ID).put("email", EMAIL))
                .toString();
    }

    private static void expectCode(String expectedCode, Callable<?> call) throws Exception {
        try {
            call.call();
            throw new AssertionError("Expected error " + expectedCode + " but succeeded");
        } catch (Exception e) {
            if (!expectedCode.equals(e.getMessage())) {
                throw new AssertionError("Expected error " + expectedCode + ", got: " + e.getMessage(), e);
            }
        }
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String extractOAuthState(String authorizeUrl) {
        String redirectTo = queryParam(URI.create(authorizeUrl).getRawQuery(), "redirect_to");
        check(redirectTo != null && !redirectTo.isEmpty(), "authorizeUrl missing redirect_to");
        String innerState = queryParam(URI.create(redirectTo).getRawQuery(), "state");
        check(innerState != null && !innerState.isEmpty(), "redirect_to missing state");
        return innerState;
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, StandardCharsets.UTF_8);
            if (key.equals(k)) {
                return eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
