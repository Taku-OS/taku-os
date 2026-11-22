package org.taku.tryon.realtime;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.taku.tryon.contract.LiveTryOnContract;

/** Transparent foreground bridge for one source-admitted LIVE request. Owns no settings or preview UI. */
public final class DirectLiveTryOnActivity extends Activity {
    private static final long STARTUP_TIMEOUT_MS = 15_000;
    private final Handler liveMain = new Handler(Looper.getMainLooper());
    private final ExecutorService liveAdmissions = Executors.newSingleThreadExecutor();
    private Future<?> pendingWork;
    private long liveGeneration, startGeneration, startupDeadline;
    private boolean resumed, destroyed, requested, decided, started, handedOff;
    private Admission ready;
    private String pendingFailure;

    @Override public void onCreate(Bundle savedState) {
        Input input = takeInput(getIntent());
        // Never retain external control actions, tokens, ClipData or old voice extras.
        setIntent(cleanIntent());
        super.onCreate(savedState);
        showTransparentBridge();
        if (savedState != null || input == null) {
            if (input != null) input.clearSecret();
            fail("live_admission_invalid"); return;
        }
        redeem(input);
    }
    @Override protected void onNewIntent(Intent intent) {
        // Each instance admits at most one request. Even a second valid Intent cannot restart it.
        removeToken(intent);
        Intent clean = cleanIntent(); setIntent(clean); super.onNewIntent(clean);
    }
    @Override protected void onResume() {
        super.onResume();
        TryOnController.visible(true);
        resumed = true;
        if (pendingFailure != null) { showFailure(); return; }
        maybeStart();
    }
    @Override protected void onPause() {
        resumed = false; ++liveGeneration; ready = null;
        if (pendingWork != null) pendingWork.cancel(true);
        cancelPendingStartup();
        // A late receipt cannot auto-start after the user left this foreground admission.
        if (!handedOff && !isFinishing()) { decided = true; pendingFailure = "live_admission_cancelled"; }
        TryOnController.visible(false);
        super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; ++liveGeneration; ready = null; pendingFailure = null;
        cancelPendingStartup();
        if (pendingWork != null) pendingWork.cancel(true);
        liveMain.removeCallbacksAndMessages(null); liveAdmissions.shutdownNow();
        super.onDestroy();
    }

    private void showTransparentBridge() {
        View bridge = new View(this);
        bridge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setContentView(bridge);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        getWindow().setGravity(Gravity.TOP | Gravity.LEFT);
        getWindow().setLayout(1, 1);
        getWindow().setWindowAnimations(0);
        overridePendingTransition(0, 0);
    }

    private Intent cleanIntent() { return new Intent(this, DirectLiveTryOnActivity.class); }
    private static void removeToken(Intent intent) {
        if (intent != null) try { intent.removeExtra(LiveTryOnContract.ADMISSION_TOKEN); }
        catch (RuntimeException ignored) { }
    }
    private static Input takeInput(Intent intent) {
        try {
            if (intent == null || !LiveTryOnContract.ACTION_TRY_ON.equals(intent.getAction())) return null;
            Bundle values = intent.getExtras();
            if (values == null || !values.keySet().equals(Set.of(LiveTryOnContract.REFERENCE_URI,
                    LiveTryOnContract.ADMISSION_TOKEN, LiveTryOnContract.REQUEST_ID, LiveTryOnContract.EXPIRES_AT_ELAPSED_MS,
                    LiveTryOnContract.CATEGORY, LiveTryOnContract.PROMPT))) return null;
            Object token = values.get(LiveTryOnContract.ADMISSION_TOKEN), request = values.get(LiveTryOnContract.REQUEST_ID),
                    reference = values.get(LiveTryOnContract.REFERENCE_URI), expires = values.get(LiveTryOnContract.EXPIRES_AT_ELAPSED_MS);
            if (!(token instanceof String) || !((String) token).matches("[A-Za-z0-9_-]{43}")
                    || !(request instanceof String) || !((String) request).matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                    || !(reference instanceof String) || ((String) reference).length() > 2048 || !(expires instanceof Long)
                    || !(values.get(LiveTryOnContract.CATEGORY) instanceof String) || !(values.get(LiveTryOnContract.PROMPT) instanceof String)) return null;
            long now = SystemClock.elapsedRealtime(), deadline = (Long) expires;
            if (deadline <= now || deadline - now > LiveTryOnContract.ADMISSION_TTL_MS
                    || !"content".equals(Uri.parse((String) reference).getScheme())) return null;
            return new Input((String) token, (String) request, (String) reference, deadline);
        } catch (RuntimeException malformed) { return null; }
        finally { removeToken(intent); }
    }
    private void redeem(Input input) {
        if (requested || destroyed || decided) { input.clearSecret(); return; }
        requested = true; final long generation = ++liveGeneration;
        try {
            pendingWork = liveAdmissions.submit(() -> {
                Bundle result = null;
                Bundle args = new Bundle(); args.putString(LiveTryOnContract.REQUEST_ID, input.requestId);
                args.putString(LiveTryOnContract.ADMISSION_TOKEN, input.token);
                try {
                    ProviderInfo provider = getPackageManager().resolveContentProvider(LiveTryOnContract.SOURCE_AUTHORITY, 0);
                    if (provider != null && LiveTryOnContract.SOURCE_PACKAGE.equals(provider.packageName))
                        result = getContentResolver().call(Uri.parse(LiveTryOnContract.SOURCE_API), LiveTryOnContract.REDEEM, null, args);
                } catch (RuntimeException unavailable) { }
                finally { args.clear(); input.clearSecret(); }
                Admission admission = validate(input, result, SystemClock.elapsedRealtime());
                liveMain.post(() -> {
                    if (destroyed || decided || generation != liveGeneration) return;
                    if (admission == null) { fail("live_admission_invalid"); return; }
                    ready = admission; maybeStart();
                });
            });
        } catch (RuntimeException unavailable) { input.clearSecret(); fail("live_admission_invalid"); }
    }
    private static Admission validate(Input input, Bundle receipt, long now) {
        try {
            if (receipt == null || !Boolean.TRUE.equals(receipt.get("ok"))
                    || !(receipt.get(LiveTryOnContract.REQUEST_ID) instanceof String)
                    || !input.requestId.equals(receipt.getString(LiveTryOnContract.REQUEST_ID))
                    || !(receipt.get(LiveTryOnContract.REFERENCE_URI) instanceof String)
                    || !input.reference.equals(receipt.getString(LiveTryOnContract.REFERENCE_URI))
                    || !(receipt.get(LiveTryOnContract.EXPIRES_AT_ELAPSED_MS) instanceof Long)
                    || input.deadline != receipt.getLong(LiveTryOnContract.EXPIRES_AT_ELAPSED_MS)
                    || now >= input.deadline || !(receipt.get(LiveTryOnContract.CATEGORY) instanceof String)
                    || !"upper_body".equals(receipt.getString(LiveTryOnContract.CATEGORY))
                    || !(receipt.get(LiveTryOnContract.PROMPT) instanceof String)) return null;
            String prompt = receipt.getString(LiveTryOnContract.PROMPT);
            if (prompt.isBlank() || prompt.length() > 2000) return null;
            return new Admission(input.reference, prompt, input.deadline);
        } catch (RuntimeException malformed) { return null; }
    }
    private void maybeStart() {
        if (!resumed || destroyed || decided || ready == null || isFinishing() || isDestroyed()) return;
        Admission admission = ready; ready = null;
        if (SystemClock.elapsedRealtime() >= admission.deadline) { fail("live_admission_invalid"); return; }
        // Latch before dispatch: repeated callbacks, resume and Intents can never bill twice.
        decided = true; ++liveGeneration;
        Bundle args = new Bundle(); args.putString("mode", TryOnController.OUTFIT);
        args.putString("referenceUri", admission.reference); args.putString("prompt", admission.prompt);
        args.putLong("referenceExpires", Math.min(SystemClock.elapsedRealtime() + LiveTryOnContract.REFERENCE_TTL_MS,
                admission.deadline + LiveTryOnContract.REFERENCE_TTL_MS - LiveTryOnContract.ADMISSION_TTL_MS));
        Bundle result;
        try { result = TryOnController.start(this, args); }
        catch (RuntimeException unavailable) { fail("live_start_failed"); return; }
        if (result != null && result.getBoolean("ok")) {
            Object generation = result.get("attemptGeneration");
            if (!(generation instanceof Long) || (Long) generation <= 0) { fail("live_start_failed"); return; }
            started = true; startGeneration = (Long) generation;
            startupDeadline = Math.min(admission.deadline, SystemClock.elapsedRealtime() + STARTUP_TIMEOUT_MS);
            checkStartup.run(); return;
        }
        String code = result == null ? "live_start_failed" : result.getString("code", "live_start_failed");
        fail(code != null && code.matches("[a-z0-9_]{1,80}") ? code : "live_start_failed");
    }
    private void fail(String code) {
        cancelPendingStartup();
        decided = true; ++liveGeneration; ready = null; pendingFailure = code;
        if (resumed && !destroyed) showFailure();
    }
    private void showFailure() {
        String code = pendingFailure; pendingFailure = null;
        if (code != null) Toast.makeText(this, code, Toast.LENGTH_SHORT).show();
        finishBridge();
    }
    private final Runnable checkStartup = new Runnable() {
        @Override public void run() {
            if (!started || handedOff || !resumed || destroyed || isFinishing() || isDestroyed()) return;
            int state = CallTryOnService.startupState(startGeneration);
            if (state == CallTryOnService.STARTUP_ATTACHED) {
                handedOff = true; finishBridge(); return;
            }
            if (state == CallTryOnService.STARTUP_ENDED) { fail("live_start_failed"); return; }
            long remaining = startupDeadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) { fail("live_start_timeout"); return; }
            liveMain.postDelayed(this, Math.min(100, remaining));
        }
    };
    private void cancelPendingStartup() {
        liveMain.removeCallbacks(checkStartup);
        if (started && !handedOff) {
            long owned = startGeneration; started = false; startGeneration = 0;
            CallTryOnService.cancelStartup(owned);
        }
    }
    private void finishBridge() { finish(); overridePendingTransition(0, 0); }
    private static final class Input {
        String token;
        final String requestId, reference;
        final long deadline;
        Input(String token, String requestId, String reference, long deadline) {
            this.token = token; this.requestId = requestId; this.reference = reference; this.deadline = deadline;
        }
        void clearSecret() { token = null; }
        @Override public String toString() { return "LiveAdmissionInput"; }
    }
    private static final class Admission {
        final String reference, prompt;
        final long deadline;
        Admission(String reference, String prompt, long deadline) { this.reference = reference; this.prompt = prompt; this.deadline = deadline; }
        @Override public String toString() { return "RedeemedLiveAdmission"; }
    }
}
