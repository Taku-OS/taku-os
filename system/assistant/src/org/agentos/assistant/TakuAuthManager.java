package org.agentos.assistant;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.UserManager;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/**
 * Single authoritative owner of the Taku Account lifecycle on Taku OS.
 * Implements the TLC-verified TakuCreditsProvider statechart:
 * locked_boot, signed_out, oauth_pending, signed_in, refreshing, expired.
 */
final class TakuAuthManager {
    static final String STATE_SETTING = "taku_account_state";
    static final String STATE_LOCKED_BOOT = "locked_boot";
    static final String STATE_SIGNED_OUT = "signed_out";
    static final String STATE_OAUTH_PENDING = "oauth_pending";
    static final String STATE_SIGNED_IN = "signed_in";
    static final String STATE_REFRESHING = "refreshing";
    static final String STATE_EXPIRED = "expired";

    static final String DEFAULT_REDIRECT_URI = "taku-account://auth/callback";
    static final long REFRESH_BUFFER_MS = 5 * 60_000L;
    static final long PKCE_TTL_MS = 10 * 60_000L;
    private static final String PRIORITY_HINT = "低于 BYOK 与个人订阅";

    interface PublicStateSink {
        void publish(String key, String jsonValue);
    }

    interface BrokerSync {
        void activate() throws Exception;
        void deactivate() throws Exception;
    }

    private static final class PendingPkce {
        final byte[] stateBytes;
        final String codeVerifier;
        final String redirectUri;
        final long createdAtMs;

        PendingPkce(byte[] stateBytes, String codeVerifier, String redirectUri, long createdAtMs) {
            this.stateBytes = stateBytes;
            this.codeVerifier = codeVerifier;
            this.redirectUri = redirectUri;
            this.createdAtMs = createdAtMs;
        }
    }

    private static final class JwtClaims {
        final String sub;
        final long expiresAtMs;

        JwtClaims(String sub, long expiresAtMs) {
            this.sub = sub;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static volatile TakuAuthManager instance;

    static TakuAuthManager get(Context context) {
        TakuAuthManager existing = instance;
        if (existing != null) return existing;
        synchronized (TakuAuthManager.class) {
            if (instance == null) {
                Context appContext = context.getApplicationContext();
                UserManager userManager = appContext.getSystemService(UserManager.class);
                instance =
                        new TakuAuthManager(
                                new TakuAccountStore(appContext.getFilesDir()),
                                new TakuAccountClient.HttpClient(),
                                System::currentTimeMillis,
                                () -> userManager == null || userManager.isUserUnlocked(),
                                (key, value) -> {
                                    try {
                                        Settings.Global.putString(appContext.getContentResolver(), key, value);
                                    } catch (Exception ignored) {
                                        // Best-effort public projection; session truth remains in Keystore.
                                    }
                                },
                                new BrokerSync() {
                                    @Override
                                    public void activate() throws Exception {
                                        sendBrokerControl("activate");
                                    }

                                    @Override
                                    public void deactivate() throws Exception {
                                        sendBrokerControl("deactivate");
                                    }
                                });
            }
            return instance;
        }
    }

    private final TakuAccountStore store;
    private final TakuAccountClient client;
    private final LongSupplier clock;
    private final BooleanSupplier unlockedSupplier;
    private final PublicStateSink publicStateSink;
    private final BrokerSync brokerSync;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();

    private String authState = STATE_LOCKED_BOOT;
    private boolean unlocked;
    private boolean providerRegistered;
    private boolean pendingBrokerActivation;
    private boolean transientOffline;
    private TakuAccountStore.Session currentSession;
    private PendingPkce pendingPkce;
    private Double cachedCreditsBalance;
    private long creditsUpdatedAtMs;
    private long sessionGeneration;
    private CompletableFuture<String> refreshInFlight;

    TakuAuthManager(
            TakuAccountStore store,
            TakuAccountClient client,
            LongSupplier clock,
            BooleanSupplier unlockedSupplier,
            PublicStateSink publicStateSink,
            BrokerSync brokerSync) {
        this.store = store;
        this.client = client;
        this.clock = clock;
        this.unlockedSupplier = unlockedSupplier;
        this.publicStateSink = publicStateSink;
        this.brokerSync = brokerSync;
    }

    void ensureUnlockedLoaded() {
        if (unlockedSupplier.getAsBoolean()) {
            try {
                onUserUnlocked();
            } catch (Exception ignored) {
                // Status/token requests will still observe state or retry broker activation.
            }
        }
    }

    void onUserUnlocked() throws Exception {
        TakuAccountStore.Session loadedSession = null;
        boolean shouldActivateBroker = false;

        synchronized (lock) {
            if (!unlockedSupplier.getAsBoolean()) {
                authState = STATE_LOCKED_BOOT;
                providerRegistered = false;
                publishLockedStateLocked();
                return;
            }
            if (!unlocked) {
                unlocked = true;
                if (!store.exists()) {
                    authState = STATE_SIGNED_OUT;
                    providerRegistered = false;
                    currentSession = null;
                    publishStateLocked();
                    return;
                }
                loadedSession = store.read();
                currentSession = loadedSession;
                shouldActivateBroker = true;
                transientOffline = false;
                if (needsRefreshLocked(loadedSession)) {
                    authState = STATE_REFRESHING;
                    startRefreshFlightLocked();
                } else {
                    authState = STATE_SIGNED_IN;
                }
                publishStateLocked();
            } else if (pendingBrokerActivation && currentSession != null) {
                shouldActivateBroker = true;
            }
        }

        if (shouldActivateBroker) {
            tryActivateBroker();
        }
    }

    JSONObject status() throws Exception {
        ensureUnlockedLoaded();
        if (unlocked && pendingBrokerActivation && currentSession != null) {
            tryActivateBroker();
        }
        synchronized (lock) {
            return buildPublicStatusLocked();
        }
    }

    JSONObject startGoogleOAuth(String redirectUri) throws Exception {
        requireUnlocked();
        String normalizedRedirect = normalizeRedirectUri(redirectUri);
        byte[] stateRaw = new byte[16];
        random.nextBytes(stateRaw);
        String stateToken = Base64.getUrlEncoder().withoutPadding().encodeToString(stateRaw);
        byte[] verifierRaw = new byte[32];
        random.nextBytes(verifierRaw);
        String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierRaw);
        String codeChallenge = sha256Base64Url(codeVerifier);
        long now = clock.getAsLong();

        synchronized (lock) {
            if (!STATE_SIGNED_OUT.equals(authState) && !STATE_EXPIRED.equals(authState) && !STATE_OAUTH_PENDING.equals(authState)) {
                throw new IOException("ERR_ALREADY_SIGNED_IN");
            }
            pendingPkce = new PendingPkce(stateToken.getBytes(StandardCharsets.UTF_8), codeVerifier, normalizedRedirect, now);
            authState = STATE_OAUTH_PENDING;
            publishStateLocked();
        }

        String redirectToWithState = normalizedRedirect + "?state=" + urlEncode(stateToken);
        String authorizeUrl =
                client.authUrl()
                        + "/auth/v1/authorize?provider=google"
                        + "&redirect_to=" + urlEncode(redirectToWithState)
                        + "&code_challenge=" + urlEncode(codeChallenge)
                        + "&code_challenge_method=s256"
                        + "&prompt=select_account";
        return new JSONObject()
                .put("authState", STATE_OAUTH_PENDING)
                .put("authorizeUrl", authorizeUrl)
                .put("redirectUri", normalizedRedirect);
    }

    void cancelOAuth() throws Exception {
        requireUnlocked();
        synchronized (lock) {
            if (STATE_OAUTH_PENDING.equals(authState)) {
                pendingPkce = null;
                authState = STATE_SIGNED_OUT;
                publishStateLocked();
            }
        }
    }

    JSONObject completeGoogleOAuthCallback(String callbackUrl) throws Exception {
        requireUnlocked();
        if (callbackUrl == null || callbackUrl.trim().isEmpty()) {
            throw new IOException("ERR_INVALID_ARGUMENT");
        }
        URI uri;
        try {
            uri = URI.create(callbackUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("ERR_OAUTH_CALLBACK_INVALID");
        }

        String rawFragment = uri.getRawFragment();
        if (rawFragment != null && (rawFragment.contains("access_token") || rawFragment.contains("refresh_token"))) {
            throw new IOException("ERR_IMPLICIT_FLOW_REJECTED");
        }

        String errorParam = queryParam(uri.getRawQuery(), "error");
        if (errorParam != null && !errorParam.isEmpty()) {
            cancelOAuth();
            throw new IOException("ERR_OAUTH_CANCELLED");
        }

        String presentedState = queryParam(uri.getRawQuery(), "state");
        String code = queryParam(uri.getRawQuery(), "code");
        if (presentedState == null || presentedState.isEmpty() || code == null || code.isEmpty()) {
            throw new IOException("ERR_OAUTH_CALLBACK_INVALID");
        }

        PendingPkce pending;
        synchronized (lock) {
            pending = pendingPkce;
            if (pending == null || !STATE_OAUTH_PENDING.equals(authState)) {
                throw new IOException("ERR_OAUTH_STATE_INVALID");
            }
            URI expected = URI.create(pending.redirectUri);
            if (!sameCallbackEndpoint(expected, uri)) {
                throw new IOException("ERR_OAUTH_CALLBACK_INVALID");
            }
            byte[] presentedBytes = presentedState.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(presentedBytes, pending.stateBytes)) {
                throw new IOException("ERR_OAUTH_STATE_INVALID");
            }
            // Single-use consumption after state match prevents replay attacks.
            pendingPkce = null;
            if (clock.getAsLong() - pending.createdAtMs > PKCE_TTL_MS) {
                authState = STATE_SIGNED_OUT;
                publishStateLocked();
                throw new IOException("ERR_OAUTH_EXPIRED");
            }
        }

        try {
            TakuAccountClient.SessionResult sessionResult = client.exchangePkceCode(code, pending.codeVerifier);
            return verifyAndPersistLogin(sessionResult);
        } catch (IOException failure) {
            synchronized (lock) {
                if (STATE_OAUTH_PENDING.equals(authState)) {
                    authState = STATE_SIGNED_OUT;
                    publishStateLocked();
                }
            }
            throw failure;
        }
    }

    JSONObject loginWithPassword(String email, String password) throws Exception {
        requireUnlocked();
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isEmpty() || password == null || password.isEmpty()) {
            throw new IOException("ERR_INVALID_ARGUMENT");
        }
        TakuAccountClient.SessionResult result = client.signInWithPassword(normalizedEmail, password);
        return verifyAndPersistLogin(result);
    }

    void clearExpired() throws Exception {
        requireUnlocked();
        synchronized (lock) {
            if (STATE_EXPIRED.equals(authState)) {
                authState = STATE_SIGNED_OUT;
                currentSession = null;
                providerRegistered = false;
                pendingBrokerActivation = false;
                transientOffline = false;
                cachedCreditsBalance = null;
                publishStateLocked();
            }
        }
    }

    String getValidAccessToken(boolean forceRefresh) throws Exception {
        requireUnlocked();
        CompletableFuture<String> flight;
        synchronized (lock) {
            if (currentSession == null) {
                if (STATE_EXPIRED.equals(authState)) {
                    throw new IOException("ERR_TOKEN_EXPIRED");
                }
                throw new IOException("ERR_NOT_SIGNED_IN");
            }
            if (!forceRefresh && !needsRefreshLocked(currentSession)) {
                if (pendingBrokerActivation) {
                    // Best-effort sync if boot raced Broker startup.
                    tryActivateBroker();
                }
                return currentSession.accessToken;
            }
            if (refreshInFlight != null) {
                flight = refreshInFlight;
            } else {
                authState = STATE_REFRESHING;
                publishStateLocked();
                flight = startRefreshFlightLocked();
            }
        }
        return awaitRefresh(flight);
    }

    JSONObject refreshAccount() throws Exception {
        requireUnlocked();
        String token = getValidAccessToken(false);
        TakuAccountClient.BalanceInfo balance;
        try {
            balance = client.fetchBalance(token);
        } catch (TakuAccountClient.UnauthorizedException unauthorized) {
            // Upstream worker/proxy returned 401: allow exactly one forced refresh & retry before failing.
            token = getValidAccessToken(true);
            try {
                balance = client.fetchBalance(token);
            } catch (TakuAccountClient.UnauthorizedException stillUnauthorized) {
                throw new IOException("ERR_TRANSIENT_OFFLINE");
            }
        } catch (IOException transientError) {
            synchronized (lock) {
                if (currentSession != null) {
                    transientOffline = true;
                    publishStateLocked();
                }
            }
            throw transientError;
        }
        synchronized (lock) {
            cachedCreditsBalance = balance.availableCredits;
            creditsUpdatedAtMs = clock.getAsLong();
            transientOffline = false;
            if (currentSession != null && !STATE_EXPIRED.equals(authState)) {
                authState = STATE_SIGNED_IN;
            }
            publishStateLocked();
            return buildPublicStatusLocked();
        }
    }

    JSONObject logout() throws Exception {
        requireUnlocked();
        CompletableFuture<String> staleFlight;
        synchronized (lock) {
            sessionGeneration++;
            staleFlight = refreshInFlight;
            refreshInFlight = null;
            pendingPkce = null;
            currentSession = null;
            cachedCreditsBalance = null;
            creditsUpdatedAtMs = 0L;
            providerRegistered = false;
            pendingBrokerActivation = false;
            transientOffline = false;
            authState = STATE_SIGNED_OUT;
            publishStateLocked();
        }
        if (staleFlight != null) {
            staleFlight.completeExceptionally(new IOException("ERR_NOT_SIGNED_IN"));
        }
        store.clear();
        try {
            brokerSync.deactivate();
        } catch (Exception ignored) {
            // Broker restart/offline deactivation is idempotent on next activate or broker boot reconciliation.
        }
        synchronized (lock) {
            return buildPublicStatusLocked();
        }
    }

    private JSONObject verifyAndPersistLogin(TakuAccountClient.SessionResult sessionResult) throws Exception {
        JwtClaims claims = parseAndValidateJwt(sessionResult.accessToken);
        TakuAccountClient.UserInfo verifiedUser = client.verifyUser(sessionResult.accessToken);
        if (!claims.sub.equals(verifiedUser.userId)) {
            throw new IOException("ERR_USER_MISMATCH");
        }
        if (!sessionResult.userId.isEmpty() && !sessionResult.userId.equals(verifiedUser.userId)) {
            throw new IOException("ERR_USER_MISMATCH");
        }
        String resolvedEmail = !verifiedUser.email.isEmpty() ? verifiedUser.email : sessionResult.email;
        if (resolvedEmail.isEmpty()) {
            throw new IOException("ERR_INVALID_USER_RESPONSE");
        }
        long resolvedExpiresAtMs = Math.min(sessionResult.expiresAtMs, claims.expiresAtMs);
        long now = clock.getAsLong();
        TakuAccountStore.Session session =
                new TakuAccountStore.Session(
                        sessionResult.accessToken,
                        sessionResult.refreshToken,
                        resolvedExpiresAtMs,
                        verifiedUser.userId,
                        resolvedEmail,
                        now);
        store.save(session);
        CompletableFuture<String> staleFlight;
        synchronized (lock) {
            sessionGeneration++;
            staleFlight = refreshInFlight;
            refreshInFlight = null;
            currentSession = session;
            pendingPkce = null;
            transientOffline = false;
            cachedCreditsBalance = null;
            creditsUpdatedAtMs = 0L;
            authState = STATE_SIGNED_IN;
            publishStateLocked();
        }
        if (staleFlight != null) {
            staleFlight.completeExceptionally(new IOException("ERR_SESSION_REPLACED"));
        }
        tryActivateBroker();
        try {
            TakuAccountClient.BalanceInfo balance = client.fetchBalance(session.accessToken);
            synchronized (lock) {
                if (currentSession == session) {
                    cachedCreditsBalance = balance.availableCredits;
                    creditsUpdatedAtMs = clock.getAsLong();
                    publishStateLocked();
                }
            }
        } catch (Exception ignored) {
            // Balance lookup failure never rolls back a verified login.
        }
        synchronized (lock) {
            return buildPublicStatusLocked();
        }
    }

    private CompletableFuture<String> startRefreshFlightLocked() {
        CompletableFuture<String> flight = new CompletableFuture<>();
        refreshInFlight = flight;
        final long generation = sessionGeneration;
        final TakuAccountStore.Session baseSession = currentSession;
        Thread worker =
                new Thread(
                        () -> executeRefresh(flight, generation, baseSession),
                        "taku-account-refresh");
        worker.setDaemon(true);
        worker.start();
        return flight;
    }

    private void executeRefresh(
            CompletableFuture<String> flight,
            long generation,
            TakuAccountStore.Session baseSession) {
        try {
            TakuAccountClient.SessionResult refreshed = client.refreshSession(baseSession.refreshToken);
            JwtClaims claims = parseAndValidateJwt(refreshed.accessToken);
            if (!claims.sub.equals(baseSession.userId)) {
                throw new TakuAccountClient.PermanentAuthException("ERR_USER_MISMATCH");
            }
            String email = !refreshed.email.isEmpty() ? refreshed.email : baseSession.email;
            long resolvedExpiry = Math.min(refreshed.expiresAtMs, claims.expiresAtMs);
            TakuAccountStore.Session updated =
                    new TakuAccountStore.Session(
                            refreshed.accessToken,
                            refreshed.refreshToken,
                            resolvedExpiry,
                            baseSession.userId,
                            email,
                            clock.getAsLong());
            synchronized (lock) {
                if (generation != sessionGeneration || refreshInFlight != flight) {
                    flight.completeExceptionally(new IOException("ERR_NOT_SIGNED_IN"));
                    return;
                }
                store.save(updated);
                currentSession = updated;
                transientOffline = false;
                authState = STATE_SIGNED_IN;
                refreshInFlight = null;
                publishStateLocked();
            }
            if (!providerRegistered) {
                tryActivateBroker();
            }
            flight.complete(updated.accessToken);
        } catch (TakuAccountClient.PermanentAuthException permanent) {
            try {
                store.clear();
            } catch (Exception ignored) {
                // Keep transitioning to expired regardless of disk state.
            }
            synchronized (lock) {
                if (generation == sessionGeneration && refreshInFlight == flight) {
                    currentSession = null;
                    cachedCreditsBalance = null;
                    creditsUpdatedAtMs = 0L;
                    providerRegistered = false;
                    pendingBrokerActivation = false;
                    transientOffline = false;
                    authState = STATE_EXPIRED;
                    refreshInFlight = null;
                    publishStateLocked();
                }
            }
            try {
                brokerSync.deactivate();
            } catch (Exception ignored) {
            }
            flight.completeExceptionally(new IOException("ERR_TOKEN_EXPIRED"));
        } catch (Exception transientFailure) {
            // Preserve session.enc and taku-credits provider on offline/5xx/429/timeout.
            synchronized (lock) {
                if (generation == sessionGeneration && refreshInFlight == flight) {
                    transientOffline = true;
                    if (currentSession != null) {
                        authState = STATE_SIGNED_IN;
                    }
                    refreshInFlight = null;
                    publishStateLocked();
                }
            }
            flight.completeExceptionally(new IOException("ERR_TRANSIENT_OFFLINE"));
        }
    }

    private void tryActivateBroker() {
        try {
            brokerSync.activate();
            synchronized (lock) {
                if (currentSession != null && !STATE_EXPIRED.equals(authState)) {
                    providerRegistered = true;
                    pendingBrokerActivation = false;
                    publishStateLocked();
                }
            }
        } catch (Exception ignored) {
            synchronized (lock) {
                if (currentSession != null && !STATE_EXPIRED.equals(authState)) {
                    pendingBrokerActivation = true;
                    publishStateLocked();
                }
            }
        }
    }

    private void requireUnlocked() throws IOException {
        ensureUnlockedLoaded();
        synchronized (lock) {
            if (!unlocked || !unlockedSupplier.getAsBoolean() || STATE_LOCKED_BOOT.equals(authState)) {
                throw new IOException("ERR_USER_LOCKED");
            }
        }
    }

    private boolean needsRefreshLocked(TakuAccountStore.Session session) {
        return session.expiresAtMs - clock.getAsLong() <= REFRESH_BUFFER_MS;
    }

    private void publishLockedStateLocked() {
        try {
            publicStateSink.publish(STATE_SETTING, buildPublicStatusLocked().toString());
        } catch (Exception ignored) {
        }
    }

    private void publishStateLocked() {
        try {
            publicStateSink.publish(STATE_SETTING, buildPublicStatusLocked().toString());
        } catch (Exception ignored) {
        }
    }

    private JSONObject buildPublicStatusLocked() throws Exception {
        JSONObject out = new JSONObject()
                .put("authState", authState)
                .put("userUnlocked", unlocked)
                .put("providerId", "taku-credits")
                .put("providerRegistered", providerRegistered)
                .put("transientOffline", transientOffline)
                .put("priorityHint", PRIORITY_HINT);
        if (currentSession != null && (STATE_SIGNED_IN.equals(authState) || STATE_REFRESHING.equals(authState))) {
            out.put("email", currentSession.email);
        } else {
            out.put("email", JSONObject.NULL);
        }
        if (cachedCreditsBalance != null) {
            out.put("creditsBalance", cachedCreditsBalance.doubleValue());
            out.put("creditsUpdatedAt", creditsUpdatedAtMs);
        } else {
            out.put("creditsBalance", JSONObject.NULL);
            out.put("creditsUpdatedAt", 0L);
        }
        return out;
    }

    private static void sendBrokerControl(String action) throws Exception {
        JSONObject request = new JSONObject().put("op", "takuAccount").put("action", action);
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("taku-agent-native"));
            if (socket.getPeerCredentials().getUid() != 0) {
                throw new IOException("ERR_INVALID_RUNTIME_IDENTITY");
            }
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            InputStream in = socket.getInputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > 64 * 1024) {
                    throw new IOException("ERR_BROKER_RESPONSE_TOO_LARGE");
                }
            }
            JSONObject answer = new JSONObject(out.toString(StandardCharsets.UTF_8));
            if (!answer.optBoolean("ok")) {
                throw new IOException(answer.optString("message", "ERR_BROKER_SYNC_FAILED"));
            }
        }
    }

    private static String awaitRefresh(CompletableFuture<String> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("ERR_TRANSIENT_OFFLINE", cause);
        }
    }

    private static JwtClaims parseAndValidateJwt(String token) throws IOException {
        if (token == null) throw new IOException("ERR_INVALID_JWT");
        String[] parts = token.trim().split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IOException("ERR_INVALID_JWT");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JSONObject payload = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            String sub = payload.optString("sub", "").trim();
            long expSec = payload.optLong("exp", 0L);
            if (sub.isEmpty() || expSec <= 0L) {
                throw new IOException("ERR_INVALID_JWT");
            }
            return new JwtClaims(sub, expSec * 1000L);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ERR_INVALID_JWT");
        }
    }

    private static String normalizeRedirectUri(String redirectUri) throws IOException {
        String candidate = redirectUri == null || redirectUri.trim().isEmpty()
                ? DEFAULT_REDIRECT_URI
                : redirectUri.trim();
        URI parsed;
        try {
            parsed = URI.create(candidate);
        } catch (IllegalArgumentException e) {
            throw new IOException("ERR_INVALID_REDIRECT_URI");
        }
        if (!sameCallbackEndpoint(URI.create(DEFAULT_REDIRECT_URI), parsed)) {
            throw new IOException("ERR_INVALID_REDIRECT_URI");
        }
        return candidate;
    }

    private static boolean sameCallbackEndpoint(URI expected, URI actual) {
        return equalsIgnoreCase(expected.getScheme(), actual.getScheme())
                && equalsIgnoreCase(expected.getHost(), actual.getHost())
                && normalizePath(expected.getPath()).equals(normalizePath(actual.getPath()));
    }

    private static String normalizePath(String path) {
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256Base64Url(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String decodedKey = URLDecoder.decode(k, StandardCharsets.UTF_8);
            if (key.equals(decodedKey)) {
                return eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }
}
