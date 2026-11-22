// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.*;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.agentos.ai.AiClient;
import org.json.*;

/** Normal-UID LIVE observer. Only an explicit Try on click or authenticated command captures a reference. */
public final class ShoppingObserverService extends AccessibilityService {
    private static volatile ShoppingObserverService instance;
    private static volatile String displayStatus = "Enable Live Try-On Observer in accessibility settings";
    private static final AtomicBoolean MODEL_CALL_ACTIVE = new AtomicBoolean();
    private static volatile long lastAcceptedReceiptAt = -1;
    public static boolean connected() { return instance != null && !instance.destroyed; }
    public static String status() { return displayStatus; }

    static Bundle command(String method, Bundle args) {
        ShoppingObserverService service = instance;
        if (service == null || service.destroyed) return reply(false, "service_not_connected");
        if (Looper.myLooper() == Looper.getMainLooper()) return service.executeCommand(method, args);
        java.util.concurrent.FutureTask<Bundle> task = new java.util.concurrent.FutureTask<>(() -> {
            try { return service.executeCommand(method, args); }
            catch (RuntimeException invalid) { return reply(false, "invalid_request"); }
        });
        service.main.post(task);
        try { return task.get(3, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception unavailable) {
            task.cancel(false); service.main.removeCallbacks(task);
            return reply(false, "runtime_busy");
        }
    }
    static void requestAfterSettings() {
        ShoppingObserverService service = instance;
        if (service == null) return;
        service.main.postDelayed(() -> {
            Bundle result = service.executeCommand("tryOn", Bundle.EMPTY);
            if (!result.getBoolean("ok")) Toast.makeText(service, "Could not start: " + result.getString("code"), Toast.LENGTH_LONG).show();
        }, 500);
    }
    static Bundle reply(boolean ok, String code) {
        Bundle result = new Bundle(); result.putBoolean("ok", ok); result.putString("code", code); return result;
    }
    private Bundle executeCommand(String method, Bundle args) {
        if (destroyed) return reply(false, "service_not_connected");
        if ("status".equals(method)) {
            Bundle result = reply(true, "ok");
            result.putString("phase", phase); result.putString("recognitionStage", liveAnalysisStage);
            result.putString("recognitionCode", liveAnalysisCode);
            result.putString("acceptanceStage", acceptanceStage); result.putString("acceptanceCode", acceptanceCode);
            result.putBoolean("liveRoomVerified", policy.currentPage() != null);
            result.putBoolean("recognitionBusy", decisionBusy || captureBusy || MODEL_CALL_ACTIVE.get());
            LiveFrameCapturePolicy choice = liveFrame != null ? liveFrame : failedLiveFrame;
            result.putBoolean("selectionAvailable", choice != null && choiceStage(choice) && selectable(choice)
                && !decisionBusy && !captureBusy && !MODEL_CALL_ACTIVE.get());
            result.putBoolean("confirmationPending", confirmation != null);
            result.putBoolean("retryAvailable", failedLiveFrame != null && now() < liveFailureExpiresAt
                && !decisionBusy && !captureBusy && !MODEL_CALL_ACTIVE.get());
            result.putLong("sampledFrames", sampledFrames); result.putLong("handoffs", liveFrameHandoffs);
            result.putLong("referenceCaptures", referenceCaptures); result.putLong("visionRequests", visionRequests);
            result.putLong("classificationRequests", classificationRequests); result.putLong("liveFrameAnalyses", liveFrameAnalyses);
            result.putLong("selectedAnalyses", selectedAnalyses); result.putLong("captures", captures);
            result.putString("liveFrameStage", liveFrame == null ? "none" : liveFrame.stage().name());
            result.putLong("recognitionElapsedMs", liveAiElapsedMs);
            return result;
        }
        if ("cancel".equals(method)) {
            if (liveFrame != null) cancelLiveFrame(liveFrame);
            else if (failedLiveFrame != null) cancelLiveFrame(failedLiveFrame);
            else cancelConfirmation();
            return reply(true, "cancelled");
        }
        if ("tryOn".equals(method)) {
            if (!Preferences.enabled(this)) return reply(false, "disabled");
            if (!unlocked()) return reply(false, "screen_locked");
            if (liveFrame != null || decisionBusy || captureBusy || MODEL_CALL_ACTIVE.get()) return reply(false, "recognition_busy");
            if (failedLiveFrame != null) return reply(false, "use_retry_or_cancel");
            policy.configure(true, Preferences.classification(this), Preferences.vision(this), now());
            clearCache(); WindowSnapshot.ReadResult read = WindowSnapshot.read(this, activityName); reads++; lastRead = now();
            if (read.page() == null) return reply(false, "source_" + read.reason());
            policy.observe(read.page(), now());
            ObservationPolicy.Candidate candidate = policy.explicit(now());
            if (confirmation != null) { confirmation.close(); confirmation = null; }
            return reply(beginExplicit(candidate, false), liveFrame != null ? "started" : "start_unavailable");
        }
        LiveFrameCapturePolicy session = liveFrame != null ? liveFrame : failedLiveFrame;
        if (session == null) return reply(false, "no_active_attempt");
        if ("retry".equals(method)) {
            if (liveFrame != null) return reply(false, "recognition_busy");
            retryLiveFrame(session); return reply("started".equals(retryState), retryState);
        }
        if ("select".equals(method)) {
            if (args != null && (args.containsKey("x") || args.containsKey("y"))) {
                if (!(args.get("x") instanceof Integer) || !(args.get("y") instanceof Integer)) return reply(false, "invalid_coordinates");
                if (args.getInt("x") < 0 || args.getInt("x") > 1000 || args.getInt("y") < 0 || args.getInt("y") > 1000)
                    return reply(false, "invalid_coordinates");
                boolean selected = selectOutfit(session, args.getInt("x"), args.getInt("y"));
                return reply(selected, selected ? "selection_requested" : "selection_unavailable");
            }
            openOutfitPicker(session); return reply(outfitPicker != null, outfitPicker != null ? "selection_opened" : "selection_unavailable");
        }
        return reply(false, "unknown_command");
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ObservationPolicy policy = new ObservationPolicy();
    private volatile boolean destroyed;
    private volatile long lifecycle;
    private ReminderConnection reminders;
    private ReminderConnection acceptedReminder;
    private LiveFrameProgressHost.Session acceptedReminderSession;
    private Runnable acceptedReminderExpiry;
    private ObservationPolicy.Candidate offer;
    private ObservationPolicy.Candidate pendingAcceptedOffer;
    private Runnable pendingAcceptedAction;
    private long pendingAcceptedDeadline;
    private volatile LiveFrameCapturePolicy liveFrame;
    private LiveFrameCapturePolicy failedLiveFrame;
    private LiveFrameProgressHost.Session livePresentationSession, failedPresentationSession;
    private ObservationPolicy.Candidate liveFrameCandidate, failedLiveCandidate;
    private String liveFrameEventId = "";
    private long liveFailureExpiresAt;
    private LiveFrameContinuity liveContinuity, failureContinuity;
    private final LiveFrameReadThrottle liveReads=new LiveFrameReadThrottle();
    private String lastLiveReadReason = "none";
    private int lastLiveReadWindowId = -1;
    private String lastLiveWindows = "{}", failureLiveWindows = "{}";
    private int failureProgressWindowId = -1;
    private long retryClicks;
    private String retryState = "none";
    private volatile LiveFrameCapturePolicy diagnosticLiveFrame;
    private volatile boolean liveDiagnosticTerminal;
    private volatile String liveAnalysisStage = "none", liveAnalysisCode = "none";
    private volatile long liveAnalysisStartedAt = -1, liveAiElapsedMs = -1;
    private volatile int liveAnswerChars = -1, liveFrameWidth, liveFrameHeight;
    private LiveFrameProgress liveProgress, confirmation;
    private long liveFrameCaptures, liveFrameHandoffs;
    private volatile long liveFrameAnalyses;
    private long liveAcceptedAt, sampledFrames, manualPicks;
    private volatile long selectedAnalyses;
    private CapturedOutfit capturedOutfit;
    private LiveOutfitPicker outfitPicker;
    private String lastLiveFrameFailure = "";
    private String eventId = "", activityName = "", phase = "starting", hostStatus = "";
    private boolean captureBusy, decisionBusy, reading, receiverRegistered;
    private long lastRead, lastConnect;
    private long reads, captures, visionRequests, referenceCaptures, published, accepted, stale;
    private volatile long classificationRequests;
    private long classificationCacheHits, classificationToastGeneration = -1;
    private String classificationStatus = "not_requested";
    private String lastOfferInvalidation = "";
    private String acceptanceStage = "none", acceptanceCode = "none", lastRefreshReason = "none";

    /** At most one retained original and one bounded preview; never persisted to disk. Main-thread ownership. */
    private static final class CapturedOutfit implements AutoCloseable {
        final LiveFrameImage image;
        final Bitmap preview;
        final LivePage source;
        final LiveFrameWindow.Layout layout;
        final long generation, expiresAt;
        final double quality;
        int x = -1, y = -1;
        long selectionVersion;
        byte[] prepared;
        LiveFrameTarget preparedTarget;
        CapturedOutfit(LiveFrameImage image, Bitmap preview, LivePage source, LiveFrameWindow.Layout layout, long generation,
                       long expiresAt, double quality) {
            this.image=image; this.preview=preview; this.source=source; this.layout=layout; this.generation=generation;
            this.expiresAt=expiresAt; this.quality=quality;
        }
        boolean matches(LivePage page, long currentGeneration, long now) {
            return page != null && now < expiresAt && generation == currentGeneration
                && source.windowId()==page.windowId() && source.packageName().equals(page.packageName())
                && source.hostKey().equals(page.hostKey());
        }
        boolean selected() { return x >= 0 && y >= 0; }
        void select(int px,int py) { x=px; y=py; selectionVersion++; prepared=null; preparedTarget=null; }
        @Override public void close() { image.close(); if (!preview.isRecycled()) preview.recycle(); prepared=null; preparedTarget=null; }
    }
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener preferences = (p, key) -> main.post(() -> {
        if (!destroyed) { reset("Settings changed. Previous suggestion withdrawn."); refresh(true); }
    });
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reset("Screen state changed. Previous suggestion withdrawn."); if (!destroyed) refresh(true); }
    };
    private final Runnable tick = new Runnable() {
        public void run() { if (destroyed) return; refresh(false); main.postDelayed(this, 1000); }
    };
    private final Runnable changed = () -> { if (!destroyed) refresh(true); };
    private final Runnable windowChanged = () -> { if (!destroyed) refresh(true,true); };

    @Override protected void onServiceConnected() {
        instance = this;
        Preferences.get(this).registerOnSharedPreferenceChangeListener(preferences);
        IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED); receiverRegistered = true;
        ReferenceStore.purge(this); main.post(tick);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (destroyed || !Preferences.enabled(this)) return;
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (SnapshotParser.TIKTOK_PACKAGES.contains(pkg) && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String cls = event.getClassName() == null ? "" : event.getClassName().toString();
            if (cls.endsWith("Activity")) activityName = cls;
        }
        if (policy.currentPage() != null || SnapshotParser.TIKTOK_PACKAGES.contains(pkg)
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if(liveFrame!=null&&(event.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    ||event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
                // Structural changes bypass the passive cooldown; comment/text churn does not.
                main.removeCallbacks(changed);
                if(!main.hasCallbacks(windowChanged))main.post(windowChanged);
                return;
            }
            // A fixed coalescing window does not let an endless comment stream postpone observation.
            if (!main.hasCallbacks(changed)) main.postDelayed(changed, 200);
        }
    }
    @Override public void onInterrupt() { reset("Observation interrupted"); }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); reset("Screen orientation changed"); }
    private long now() { return SystemClock.elapsedRealtime(); }
    private boolean unlocked() {
        PowerManager power=getSystemService(PowerManager.class);KeyguardManager keyguard=getSystemService(KeyguardManager.class);
        return power!=null&&power.isInteractive()&&keyguard!=null&&!keyguard.isDeviceLocked()&&!keyguard.isKeyguardLocked();
    }
    private void refresh(boolean force) {
        refresh(force,false);
    }
    private void refresh(boolean force,boolean windowChanged) {
        if (destroyed || reading) return;
        boolean enabled = Preferences.enabled(this), vision = Preferences.vision(this);
        boolean classification = Preferences.classification(this);
        boolean screenUnlocked=unlocked();
        policy.configure(enabled, classification, vision, now());
        if (!enabled || !screenUnlocked) { reset(enabled ? "Observation paused while the screen is locked" : "Observation is off"); return; }
        if (capturedOutfit != null && now() >= capturedOutfit.expiresAt && liveFrame == null && failedLiveFrame == null) {
            clearCapturedOutfit();
        }
        if (outfitPicker != null) {
            LiveFrameCapturePolicy selection = liveFrame != null ? liveFrame : failedLiveFrame;
            if (selection == null || !selectable(selection) || !pickerRoomMatches(selection)) {
                if (selection != null) cancelLiveFrame(selection); else clearCapturedOutfit();
                return;
            }
            if (liveFrame == selection && !selection.isActive(now()))
                failLiveFrame(selection, "selection_confirmation_needed", liveFrameFailureText("selection_confirmation_needed"));
            return; // A frozen-image UI never authorizes capture, model work or a native start.
        }
        if (liveFrame != null) {
            LiveFrameCapturePolicy session = liveFrame;
            boolean waitingForChoice = session.stage() == LiveFrameCapturePolicy.Stage.WAITING_SELECTION;
            // Lock/disabled checks above and the original deadline are never deferred by content churn.
            if(liveReads.passive(now(),enabled,screenUnlocked,session.isActive(now()),windowChanged)
                    ==LiveFrameReadThrottle.Decision.SKIP)return;
            if (observeLiveFrame(session) == LiveFrameContinuity.Result.CANCEL) {
                String code = now() >= session.deadlineAt()
                    ? waitingForChoice ? "selection_confirmation_needed" : "recognition_timeout" : "source_" + lastLiveReadReason;
                failLiveFrame(session, code, now() >= session.deadlineAt() ? liveFrameFailureText(code)
                    : "The LIVE page couldn't be verified. Please try again when the presenter is visible.");
            }
            return;
        }
        if (failedLiveFrame != null) { updateLiveFrameFailure(); return; }
        if (!force && now() - lastRead < 900) return;
        reading = true;
        try {
            if (force) clearCache();
            WindowSnapshot.ReadResult result = offer==null ? WindowSnapshot.read(this, activityName)
                : WindowSnapshot.readForOffer(this,activityName,offer.page());
            reads++; lastRead = now();
            if(maintainPendingAcceptance(offer,result) || retainOccludedOffer(offer,result))return;
            if (policy.observe(result.page(), now())) { if (offer != null) lastOfferInvalidation = result.reason(); lifecycle++; withdraw(); }
            LivePage page = policy.currentPage();
            if (page == null) { phase = "idle"; displayStatus = "Waiting for a supported TikTok LIVE room"; return; }
            ensureHost();
            if (reminders == null || !reminders.allowed) { phase = "needs_permission"; displayStatus = hostStatus.isEmpty() ? "Allow system reminders first" : hostStatus; return; }
            if (offer != null && !policy.isCurrent(offer, now())) { policy.dismiss(offer, now()); withdraw(); }
            if (offer != null || captureBusy || decisionBusy) return;
            phase = "watching"; displayStatus = page.hostName() + (page.hasProduct() ? " · Viewing a product" : " · No product card yet");
            if (ObservationHistory.suppressed(this, page)) return;
            if (page.hasProduct()) {
                if (!classification) {
                    phase = "needs_product_ai";
                    displayStatus = "Enable product recognition and select a text model in system AI settings";
                    return;
                }
                ProductTypeAnswer.Result resultType = policy.classificationResult();
                if (resultType != null && resultType != ProductTypeAnswer.Result.APPAREL
                        && resultType != ProductTypeAnswer.Result.UNKNOWN) {
                    showClassificationResult(resultType); return;
                }
                long delay = ProductTypeStore.waitMillis(this, page);
                if (delay > 0) {
                    phase = "classification_wait";
                    displayStatus = "Waiting about " + ((delay + 999) / 1000) + " seconds before checking the product";
                    return;
                }
            }
            ObservationPolicy.Candidate next = policy.poll(now());
            if (next != null) {
                if (next.kind() == ObservationPolicy.Kind.CLASSIFY) classifyProduct(next);
                else if (next.kind() == ObservationPolicy.Kind.VISION) prepareVision(next);
                else publish(next);
            }
        } catch (RuntimeException e) { reset("Page unavailable. Waiting for a new LIVE page."); }
        finally { reading = false; }
    }
    private boolean refreshCurrent(ObservationPolicy.Candidate candidate) {
        if (destroyed || !Preferences.enabled(this) || !unlocked()) return false;
        clearCache(); WindowSnapshot.ReadResult read = WindowSnapshot.read(this, activityName); reads++; lastRead = now();
        lastRefreshReason = acceptanceReadReason(read);
        if (policy.observe(read.page(), now())) { if (offer != null) lastOfferInvalidation = read.reason(); lifecycle++; withdraw(); }
        return policy.isCurrent(candidate, now());
    }

    private boolean retainOccludedOffer(ObservationPolicy.Candidate candidate,WindowSnapshot.ReadResult read) {
        return candidate!=null && candidate==offer && reminders!=null && reminders.allowed
            && OfferOcclusionGuard.retain(candidate,policy.isCurrent(candidate,now()),read.reason(),read.page()!=null,
                read.offerOcclusion(),now(),policy.config().offerTtlMs());
    }

    private OfferOcclusionGuard.Acceptance pendingAcceptance(ObservationPolicy.Candidate candidate,WindowSnapshot.ReadResult read) {
        if(candidate==null || pendingAcceptedOffer!=candidate || candidate!=offer || reminders==null || !reminders.allowed)
            return OfferOcclusionGuard.Acceptance.REJECT;
        OfferOcclusionGuard.Acceptance result=OfferOcclusionGuard.acceptance(candidate,policy.isCurrent(candidate,now()),read.reason(),read.page()!=null,
            read.offerOcclusion(),now(),pendingAcceptedDeadline,policy.config().offerTtlMs());
        traceAcceptance("pending_read", result==OfferOcclusionGuard.Acceptance.READY ? "ready"
            : result==OfferOcclusionGuard.Acceptance.WAIT ? "waiting_hint_exit" : acceptanceGateCode(candidate,read));
        return result;
    }

    private void traceAcceptance(String stage,String code) { acceptanceStage=stage;acceptanceCode=code; }
    private boolean rejectAcceptance(String stage,String code) { traceAcceptance(stage,code);return false; }
    /** Fixed diagnostic vocabulary only; no node text, package identity, reference or capability. */
    private static String acceptanceReadReason(WindowSnapshot.ReadResult read) {
        if(read==null)return "read_unavailable";
        return switch(read.reason()) {
            case "live_room","not_live_room","root_unavailable","app_not_focused","wrong_thread","screen_locked",
                "window_unavailable","unsupported_orientation","keyboard_visible","picture_in_picture",
                "multiple_app_windows","window_not_fullscreen","outside_tiktok","snapshot_unavailable" -> read.reason();
            default -> "other_read_rejection";
        };
    }
    private String acceptanceGateCode(ObservationPolicy.Candidate candidate,WindowSnapshot.ReadResult read) {
        if(now()>=pendingAcceptedDeadline)return "accept_deadline";
        if(!policy.isCurrent(candidate,now()))return "candidate_stale";
        if(read.page()!=null)return "verified_page_gate_rejected";
        if(!"not_live_room".equals(read.reason()))return acceptanceReadReason(read);
        OfferOcclusionGuard.Evidence evidence=read.offerOcclusion();
        if(evidence==null)return "metadata_unavailable";
        if(evidence.windowId()!=candidate.page().windowId())return "metadata_window_changed";
        if(!candidate.page().packageName().equals(evidence.packageName()))return "metadata_package_changed";
        if(!evidence.focusedFullscreen())return "metadata_not_focused_fullscreen";
        if(!evidence.sameRoomAnchors())return "metadata_room_anchors";
        if(evidence.unknownOverlay())return "metadata_unknown_overlay";
        if(evidence.ownHints()<0 || evidence.ownHints()>1)return "metadata_hint_count";
        return "metadata_rejected";
    }

    private boolean maintainPendingAcceptance(ObservationPolicy.Candidate candidate,WindowSnapshot.ReadResult read) {
        return pendingAcceptance(candidate,read)!=OfferOcclusionGuard.Acceptance.REJECT;
    }

    /** Publication ACK maintenance never authorizes work; acceptance/capture use refreshCurrent. */
    private boolean refreshDisplayedOffer(ObservationPolicy.Candidate candidate) {
        if(candidate==null || candidate!=offer || destroyed || !Preferences.enabled(this) || !unlocked())return false;
        clearCache();
        WindowSnapshot.ReadResult read=WindowSnapshot.readForOffer(this,activityName,candidate.page());
        reads++;lastRead=now();
        if(maintainPendingAcceptance(candidate,read) || retainOccludedOffer(candidate,read))return true;
        if(policy.observe(read.page(),now())) {lastOfferInvalidation=read.reason();lifecycle++;withdraw();}
        return policy.isCurrent(candidate,now());
    }

    private boolean classificationAlive(ObservationPolicy.Candidate candidate) {
        return !destroyed && Preferences.enabled(this) && Preferences.classification(this)
            && unlocked() && policy.isCurrent(candidate, now());
    }

    private boolean sameClassificationPage(ObservationPolicy.Candidate candidate) {
        LivePage current = policy.currentPage(), source = candidate.page();
        return current != null && current.windowId() == source.windowId()
            && current.packageName().equals(source.packageName()) && current.contentKey().equals(source.contentKey());
    }

    /** Only the opted-in product title is sent. No screenshot, host name or comment is included. */
    private void classifyProduct(ObservationPolicy.Candidate candidate) {
        ProductTypeAnswer.Result cached = ProductTypeStore.cached(this, candidate.page());
        if (cached != null) {
            classificationCacheHits++;
            classificationStatus = "cache_" + cached.name();
            completeClassification(candidate, cached, false); return;
        }
        decisionBusy = true; phase = "classifying"; classificationStatus = "running";
        displayStatus = "Checking whether this product is clothing…";
        final Runnable timeout = () -> {
            if (!destroyed && policy.classificationTimedOut(candidate, now())
                    && Preferences.classification(this) && sameClassificationPage(candidate)) {
                classificationStatus = "TIMED_OUT";
                showClassificationResult(ProductTypeAnswer.Result.TIMED_OUT);
            }
        };
        main.postDelayed(timeout, Math.max(1, policy.config().visionTtlMs() - (now() - candidate.createdAt())));
        try {
            work.execute(() -> {
                ProductTypeAnswer.Result result = ProductTypeAnswer.Result.UNAVAILABLE;
                boolean submitted = false, modelLease = false;
                try {
                    if (classificationAlive(candidate)) {
                        AiClient client = new AiClient(this);
                        client.resolve("text");
                        if (classificationAlive(candidate) && MODEL_CALL_ACTIVE.compareAndSet(false, true)) {
                            modelLease = true;
                            if (ProductTypeStore.claim(this, candidate.page()) && classificationAlive(candidate)) {
                                submitted = true; classificationRequests++;
                                String answer = client.text(ProductTypeAnswer.prompt(
                                    candidate.page().productTitle())).getString("text");
                                result = ProductTypeAnswer.valid(answer) ? ProductTypeAnswer.parse(answer)
                                    : ProductTypeAnswer.Result.UNAVAILABLE;
                            }
                        }
                    }
                } catch (Exception | OutOfMemoryError unavailable) { }
                finally { if (modelLease) MODEL_CALL_ACTIVE.set(false); }
                final ProductTypeAnswer.Result answer = result;
                final boolean modelCalled = submitted;
                main.post(() -> {
                    main.removeCallbacks(timeout); decisionBusy = false;
                    completeClassification(candidate, answer, modelCalled);
                });
            });
        } catch (RuntimeException stopped) {
            main.removeCallbacks(timeout); decisionBusy = false;
            completeClassification(candidate, ProductTypeAnswer.Result.UNAVAILABLE, false);
        }
    }

    private void completeClassification(ObservationPolicy.Candidate candidate, ProductTypeAnswer.Result result,
                                        boolean modelCalled) {
        boolean current = classificationAlive(candidate) && refreshCurrent(candidate);
        if (current && modelCalled) ProductTypeStore.save(this, candidate.page(), result);
        // Completion, not a UI timeout or a page switch, releases the one real model lease.
        ObservationPolicy.Candidate next = policy.resolveClassification(candidate, result, now());
        if (destroyed || !sameClassificationPage(candidate)) return;
        ProductTypeAnswer.Result settled = policy.classificationResult();
        if (settled != null) classificationStatus = (modelCalled ? "model_" : "local_") + settled.name();
        if (next != null && current) publish(next);
        else if (settled != null) {
            showClassificationResult(settled);
            if ((settled == ProductTypeAnswer.Result.UNAVAILABLE || settled == ProductTypeAnswer.Result.TIMED_OUT)
                    && classificationToastGeneration != candidate.generation() && Preferences.classification(this)) {
                classificationToastGeneration = candidate.generation();
                Toast.makeText(this, displayStatus, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showClassificationResult(ProductTypeAnswer.Result result) {
        phase = "classification_" + result.name().toLowerCase(java.util.Locale.ROOT);
        displayStatus = switch (result) {
            case APPAREL -> "Clothing detected. Preparing a try-on reminder.";
            case NON_APPAREL -> "This product is not clothing. No reminder for this item.";
            case UNKNOWN -> "Product type is uncertain. Check the presenter’s outfit before trying on.";
            case UNAVAILABLE -> "Text recognition unavailable. Check system AI settings or try again later.";
            case TIMED_OUT -> "Product recognition timed out. Reopen the LIVE room to try again later.";
        };
    }
    private void ensureHost() {
        if (reminders != null && (reminders.connected || now() - lastConnect < 5000)) return;
        withdraw(); lastConnect = now();
        final long connectionLife = lifecycle;
        reminders = new ReminderConnection(this, new ReminderConnection.Listener() {
            public void authorizationRevoked() {
                if (destroyed || connectionLife != lifecycle) return;
                if (capturedOutfit != null || liveFrame != null || failedLiveFrame != null)
                    reset("Try-on permission changed. Previous outfit selection cleared.");
            }
            public void state(boolean allowed, String state) {
                if (destroyed || connectionLife != lifecycle) return;
                hostStatus = state;
                if (!allowed && acceptedReminder != null && (liveFrame != null || failedLiveFrame != null)) {
                    reset("Try-on reminder connection ended. Previous outfit selection cleared."); return;
                }
                if (!allowed && offer != null) {
                    cancelPendingAcceptance();
                    policy.dismiss(offer, now()); offer = null; eventId = "";
                    if (confirmation != null) { confirmation.close(); confirmation = null; }
                }
            }
            public void event(String type, JSONObject receipt) {
                boolean accepting="accepted".equals(type);
                if(accepting)traceAcceptance("receipt","received");
                if(destroyed) {if(accepting)traceAcceptance("receipt_rejected","destroyed");return;}
                if(connectionLife!=lifecycle) {if(accepting)traceAcceptance("receipt_rejected","connection_stale");return;}
                if(offer==null) {if(accepting)traceAcceptance("receipt_rejected","offer_missing");return;}
                if(!matches(receipt)) {if(accepting)traceAcceptance("receipt_rejected","event_revision_mismatch");return;}
                if (type.equals("published")) {
                    if (!refreshDisplayedOffer(offer)) { withdraw(); return; }
                    if (policy.published(offer, now())) {
                        ObservationHistory.published(ShoppingObserverService.this, offer.page()); published++; phase = "suggesting";
                        displayStatus = policy.classificationResult() == ProductTypeAnswer.Result.UNKNOWN
                            ? "Product type is uncertain. An outfit suggestion is shown." : "Clothing detected. A try-on suggestion is shown.";
                    }
                } else if (type.equals("accepted")) {
                    lastAcceptedReceiptAt = now();
                    if (ReminderConnection.isTryOnAcceptance(receipt)) {
                        LiveFrameProgressHost.Session presentation;
                        try { presentation = LiveFrameProgressHost.session(receipt); }
                        catch (IllegalArgumentException invalid) {
                            traceAcceptance("session_rejected","invalid_presentation");
                            policy.dismiss(offer, now()); withdraw(); phase = "watching";
                            displayStatus = "The try-on prompt is unavailable. Please try again."; return;
                        }
                        acceptAfterHintExit(offer,presentation);
                    } else {traceAcceptance("confirmation","legacy_choice");showConfirmation(offer);}
                }
                else if (type.equals("dismissed") || type.equals("expired") || type.equals("error")) {
                    policy.dismiss(offer, now()); withdraw(); phase = "watching";
                }
            }
        });
        reminders.connect();
    }
    private boolean matches(JSONObject receipt) {
        JSONArray records = receipt.optJSONArray("records");
        if (records == null || records.length() != 1) return false;
        JSONObject record = records.optJSONObject(0);
        return record != null && eventId.equals(record.optString("id"))
            && record.optInt("pageRevision", -1) == (int)(offer.generation() & 0x7fffffff);
    }
    private void publish(ObservationPolicy.Candidate candidate) {
        if (!refreshCurrent(candidate) || reminders == null || !reminders.allowed) return;
        offer = candidate; eventId = "shopping-" + UUID.randomUUID();
        lastOfferInvalidation = "";
        try {
            reminders.emit(eventId, (int)(candidate.generation() & 0x7fffffff));
            final long expiryLife = lifecycle;
            main.postDelayed(() -> {
                if (!destroyed && expiryLife == lifecycle && offer == candidate) {
                    policy.dismiss(candidate, now()); withdraw(); phase = "watching"; displayStatus = "Suggestion expired. Waiting for a new product.";
                }
            }, Math.max(1, policy.config().offerTtlMs() - (now() - candidate.createdAt())));
        } catch (Exception e) { policy.dismiss(candidate, now()); withdraw(); displayStatus = "System reminders are unavailable"; }
    }

    /** A single user gesture waits only for hint detachment; it never renews the offer or runs work while waiting. */
    private void acceptAfterHintExit(ObservationPolicy.Candidate candidate,LiveFrameProgressHost.Session presentation) {
        if(candidate==null) {traceAcceptance("pending_rejected","candidate_missing");return;}
        if(candidate!=offer) {traceAcceptance("pending_rejected","offer_replaced");return;}
        if(pendingAcceptedOffer==candidate) {traceAcceptance("pending","duplicate_receipt");return;}
        cancelPendingAcceptance();pendingAcceptedOffer=candidate;
        traceAcceptance("pending","waiting_hint_exit");
        pendingAcceptedDeadline=Math.min(now()+1200,candidate.createdAt()+policy.config().offerTtlMs());
        final long acceptedLife=lifecycle;
        pendingAcceptedAction=new Runnable() { public void run() {
            if(pendingAcceptedAction!=this || pendingAcceptedOffer!=candidate)return;
            String rejection=destroyed ? "destroyed" : acceptedLife!=lifecycle ? "lifecycle_changed"
                : offer!=candidate ? "offer_replaced" : reminders==null ? "reminder_missing"
                : !reminders.allowed ? "reminder_not_allowed" : !Preferences.enabled(ShoppingObserverService.this) ? "disabled"
                : !unlocked() ? "screen_locked" : !policy.isCurrent(candidate,now()) ? "candidate_stale"
                : now()>=pendingAcceptedDeadline ? "accept_deadline" : null;
            if(rejection!=null) {traceAcceptance("pending_rejected",rejection);reject();return;}
            clearCache();
            WindowSnapshot.ReadResult read=WindowSnapshot.readForOffer(ShoppingObserverService.this,activityName,candidate.page());
            reads++;lastRead=now();
            OfferOcclusionGuard.Acceptance state=pendingAcceptance(candidate,read);
            if(state==OfferOcclusionGuard.Acceptance.WAIT) {
                main.postDelayed(this,Math.max(1,Math.min(100,pendingAcceptedDeadline-now())));return;
            }
            // Metadata only gates waiting. beginExplicit performs its own fresh, strict visible read.
            if(state==OfferOcclusionGuard.Acceptance.READY && beginExplicit(candidate,false,presentation))return;
            reject();
        }
        private void reject() {
            cancelPendingAcceptance();
            if(offer==candidate) {policy.dismiss(candidate,now());withdraw();phase="watching";}
        }
        };
        main.post(pendingAcceptedAction);
    }

    private void cancelPendingAcceptance() {
        if(pendingAcceptedAction!=null)main.removeCallbacks(pendingAcceptedAction);
        pendingAcceptedAction=null;pendingAcceptedOffer=null;pendingAcceptedDeadline=0;
    }
    /** Legacy Viewed callbacks grant no screenshot/model/camera work before a fresh Try on tap. */
    private void showConfirmation(ObservationPolicy.Candidate candidate) {
        if (candidate == null || !refreshCurrent(candidate)) { withdraw(); return; }
        if (confirmation != null) confirmation.close();
        confirmation = new LiveFrameProgress(this, null, this::cancelConfirmation);
        confirmation.showConfirmation(() -> {
            if (confirmation != null) { confirmation.close(); confirmation = null; }
            beginExplicit(candidate, false);
        });
        phase = "awaiting_try_on_confirmation";
        displayStatus = "Choose Try on to capture the current LIVE outfit. Camera video uses billed cloud AI for up to 60 seconds.";
    }

    private void cancelConfirmation() {
        policy.dismiss(offer, now()); withdraw(); phase = "watching";
        displayStatus = "Try-on cancelled. The camera has not started.";
    }

    /** A real Try on click or authenticated Agent command is the sole preparation entry. */
    private boolean beginExplicit(ObservationPolicy.Candidate candidate, boolean resumeSelection) {
        return beginExplicit(candidate, resumeSelection, null);
    }
    private boolean beginExplicit(ObservationPolicy.Candidate candidate, boolean resumeSelection,
            LiveFrameProgressHost.Session presentation) {
        traceAcceptance("begin","checking");
        if(candidate==null)return rejectAcceptance("begin_rejected","candidate_missing");
        if(destroyed)return rejectAcceptance("begin_rejected","destroyed");
        if(liveFrame!=null)return rejectAcceptance("begin_rejected","attempt_active");
        if(captureBusy)return rejectAcceptance("begin_rejected","capture_busy");
        if(decisionBusy)return rejectAcceptance("begin_rejected","decision_busy");
        if(MODEL_CALL_ACTIVE.get())return rejectAcceptance("begin_rejected","model_busy");
        if(!Preferences.enabled(this))return rejectAcceptance("begin_rejected","disabled");
        if(!unlocked())return rejectAcceptance("begin_rejected","screen_locked");
        if(!refreshCurrent(candidate))return rejectAcceptance("strict_read_rejected",
            "live_room".equals(lastRefreshReason) ? "candidate_stale" : lastRefreshReason);
        int revision = (int)(candidate.generation() & 0x7fffffff);
        long acceptedNow = now();
        LiveFrameCapturePolicy session = new LiveFrameCapturePolicy(candidate.page(), revision, acceptedNow);
        if (resumeSelection && (capturedOutfit == null || !capturedOutfit.selected()
                || !capturedOutfit.matches(candidate.page(), candidate.generation(), now())))
            return rejectAcceptance("begin_rejected","retained_selection_stale");
        if (!policy.accept(candidate, now())) return rejectAcceptance("policy_rejected","accept_unavailable");
        cancelPendingAcceptance();
        accepted++;
        traceAcceptance("accepted","started");
        String acceptedEventId = eventId.isEmpty() ? "explicit-" + UUID.randomUUID() : eventId;
        retainAcceptedReminder(presentation, acceptedNow + ObservationPolicy.ACCEPTED_RETRY_TTL_MS);
        offer = null; eventId = "";
        closeOutfitPicker(); closeLiveProgress(); failedLiveFrame = null; failedLiveCandidate = null;
        livePresentationSession = presentation; failedPresentationSession = null;
        if (!resumeSelection) clearCapturedOutfit();
        liveFrame = session; liveFrameCandidate = candidate; lastLiveFrameFailure = "";
        liveAcceptedAt = now();
        liveFrameEventId = acceptedEventId;
        liveContinuity = new LiveFrameContinuity(session.source(), now(), session.deadlineAt());
        liveReads.reset();
        // accept() just strongly refreshed this candidate; avoid a queued passive tick before first capture.
        liveReads.readCompleted(lastRead);
        liveDiagnosticTerminal = false;
        diagnosticLiveFrame = session; liveAnalysisStage = "waiting_capture"; liveAnalysisCode = "none";
        lastLiveWindows = failureLiveWindows = "{}"; failureProgressWindowId = -1;
        liveAnalysisStartedAt = now(); liveAiElapsedMs = -1; liveAnswerChars = -1; liveFrameWidth = liveFrameHeight = 0;
        phase = "live_frame_waiting"; displayStatus = "Finding the outfit…";
        if (!LiveTryOnHandoff.directAvailable(this)) { failLiveFrame(session, "Update Live Try-On, then tap Try on again."); return false; }
        main.postDelayed(() -> {
            if (liveFrame == session) {
                String code = session.stage() == LiveFrameCapturePolicy.Stage.WAITING_SELECTION
                    ? "selection_confirmation_needed" : "recognition_timeout";
                failLiveFrame(session, code, liveFrameFailureText(code));
            }
        }, Math.max(1, session.deadlineAt() - now()));
        if (resumeSelection) {
            if (session.claimRetainedSelection(capturedOutfit.x, capturedOutfit.y, now())) startLiveAnalysis(session, true);
            else failLiveFrame(session, "recognition_timeout", liveFrameFailureText("recognition_timeout"));
        } else {
            // Let the host's reminder depart; sample only after this explicit action, never on LIVE entry.
            main.postDelayed(() -> captureLiveFrame(session), 250);
        }
        return true;
    }

    private record LiveFrameRead(LiveFrameContinuity.Result result,LiveFrameWindow.Layout layout) {}
    private LiveFrameContinuity.Result observeLiveFrame(LiveFrameCapturePolicy session) {
        return readLiveFrame(session,false).result();
    }
    /** Strong reads never consult the passive cooldown. Capture page/layout share exactly one fresh root. */
    private LiveFrameRead readLiveFrame(LiveFrameCapturePolicy session,boolean withLayout) {
        if (destroyed || liveFrame != session || !Preferences.enabled(this) || !unlocked() || !session.isActive(now()))
            return new LiveFrameRead(LiveFrameContinuity.Result.CANCEL,null);
        boolean wasReading=reading;reading=true;
        WindowSnapshot.ReadResult read;
        try {clearCache();read=WindowSnapshot.read(this,activityName,withLayout);}
        finally {reading=wasReading;}
        reads++;lastRead=now();liveReads.readCompleted(lastRead);
        lastLiveReadReason = read.reason(); lastLiveReadWindowId = read.windowId();
        lastLiveWindows = WindowSnapshot.diagnostic();
        LiveFrameContinuity.Result result = liveContinuity.observe(read.page(), read.reason(), read.windowId(), read.packageName(), now());
        if (result == LiveFrameContinuity.Result.VERIFIED && !session.observe(read.page(), now())) result=LiveFrameContinuity.Result.CANCEL;
        return new LiveFrameRead(result,read.layout());
    }

    private void captureLiveFrame(LiveFrameCapturePolicy session) { captureLiveFrame(session, 0); }

    private void captureLiveFrame(LiveFrameCapturePolicy session, int sample) {
        if (liveFrame != session) return;
        if (captureBusy || decisionBusy) { failLiveFrame(session, "model_busy", liveFrameFailureText("model_busy")); return; }
        LiveFrameRead before=readLiveFrame(session,true);
        LiveFrameContinuity.Result room = before.result();
        if (room == LiveFrameContinuity.Result.WAIT) { main.postDelayed(() -> captureLiveFrame(session, sample), 100); return; }
        if (room != LiveFrameContinuity.Result.VERIFIED) { failLiveFrame(session, "source_unavailable", liveFrameFailureText("source_changed")); return; }
        LiveFrameWindow.Layout layout = before.layout();
        boolean claimed = sample == 0 ? session.claimScreenshot(now()) : session.claimAdditionalScreenshot(now());
        if (layout == null || !claimed) { failLiveFrame(session, "source_changed", liveFrameFailureText("source_changed")); return; }
        captureBusy = true; phase = "live_frame_sampling";
        try {
            takeScreenshotOfWindow(session.source().windowId(), getMainExecutor(), new TakeScreenshotCallback() {
                public void onSuccess(ScreenshotResult shot) {
                    captureBusy = false;
                    LiveFrameRead after=readLiveFrame(session,true);
                    if (after.result()!=LiveFrameContinuity.Result.VERIFIED || !layout.equals(after.layout())) {
                        shot.getHardwareBuffer().close(); stale++;
                        failLiveFrame(session, "source_changed", liveFrameFailureText("source_changed")); return;
                    }
                    decisionBusy = true;
                    try { work.execute(() -> {
                        LiveFrameImage image = null; Bitmap preview = null; double quality = -1;
                        try {
                            image = LiveFrameImage.take(shot, layout);
                            quality = image.qualityScore(); preview = image.previewBitmap();
                        } catch (Exception | OutOfMemoryError unavailable) {
                            if (image != null) image.close(); image = null;
                            if (preview != null) preview.recycle(); preview = null;
                        }
                        final LiveFrameImage readyImage = image; final Bitmap readyPreview = preview; final double score = quality;
                        main.post(() -> {
                            decisionBusy = false;
                            if (liveFrame != session || !session.isActive(now())) {
                                if (readyImage != null) readyImage.close(); if (readyPreview != null) readyPreview.recycle();
                                if (liveFrame == session) failLiveFrame(session, "recognition_timeout", liveFrameFailureText("recognition_timeout"));
                                return;
                            }
                            if (readyImage == null || !Double.isFinite(score) || !session.screenshotReady(now())) {
                                if (readyImage != null) readyImage.close(); if (readyPreview != null) readyPreview.recycle();
                                samplingFailed(session); return;
                            }
                            liveFrameCaptures++; sampledFrames++; captures++;
                            if (capturedOutfit != null && !capturedOutfit.layout.equals(layout)) {
                                readyImage.close(); readyPreview.recycle(); startLiveAnalysis(session, false); return;
                            }
                            if (capturedOutfit == null || score > capturedOutfit.quality) {
                                clearCapturedOutfit();
                                capturedOutfit = new CapturedOutfit(readyImage, readyPreview, session.source(), layout,
                                    liveFrameCandidate.generation(), liveAcceptedAt + ObservationPolicy.ACCEPTED_RETRY_TTL_MS, score);
                            } else { readyImage.close(); readyPreview.recycle(); }
                            if (sample + 1 < LiveFrameCapturePolicy.MAX_SAMPLES) main.postDelayed(() -> captureLiveFrame(session, sample + 1), 650);
                            else startLiveAnalysis(session, false);
                        });
                    }); } catch (RuntimeException stopped) {
                        shot.getHardwareBuffer().close(); decisionBusy = false; samplingFailed(session);
                    }
                }
                public void onFailure(int code) { captureBusy = false; samplingFailed(session); }
            });
        } catch (RuntimeException unavailable) { captureBusy = false; samplingFailed(session); }
    }

    private void samplingFailed(LiveFrameCapturePolicy session) {
        if (liveFrame != session) return;
        if (capturedOutfit != null && session.skipAdditionalScreenshot(now())) startLiveAnalysis(session, false);
        else failLiveFrame(session, "capture_failed", "Could not capture the LIVE. Please try again.");
    }

    private void startLiveAnalysis(LiveFrameCapturePolicy session, boolean selected) {
        CapturedOutfit frame = capturedOutfit;
        if (liveFrame != session || frame == null || !frame.matches(session.source(), liveFrameCandidate.generation(), now())) {
            failLiveFrame(session, "source_changed", liveFrameFailureText("source_changed")); return;
        }
        if (!selected && !session.claimAnalysis(now())) { failLiveFrame(session, "recognition_timeout", liveFrameFailureText("recognition_timeout")); return; }
        if (liveProgress == null) {
            LiveFrameProgress progress = new LiveFrameProgress(this, livePresentationSession, () -> cancelLiveFrame(session));
            progress.show();
            // A publication failure can synchronously cancel the attempt from show().
            if (liveFrame != session || !session.isActive(now())) { progress.close(); return; }
            liveProgress = progress;
        } else liveProgress.show();
        if (destroyed || liveFrame != session || !session.isActive(now())
                || !Preferences.enabled(this) || !unlocked()) return;
        decisionBusy = true; phase = selected ? "live_frame_selected_analysis" : "live_frame_analyzing";
        final int x = frame.x, y = frame.y; final long choice = frame.selectionVersion;
        if (selected && frame.prepared != null && frame.preparedTarget != null) {
            decisionBusy = false;
            if (session.analysisReady(frame.preparedTarget, now())) deliverLiveFrame(session, frame.prepared, frame.preparedTarget);
            else failLiveFrame(session, "recognition_timeout", liveFrameFailureText("recognition_timeout"));
            return;
        }
        try { work.execute(() -> analyzeLiveFrame(session, frame, selected, x, y, choice)); }
        catch (RuntimeException unavailable) { decisionBusy = false; failLiveFrame(session, "ai_request_failed", liveFrameFailureText("ai_request_failed")); }
    }

    private void analyzeLiveFrame(LiveFrameCapturePolicy session, CapturedOutfit frame, boolean selected,
                                  int x, int y, long choice) {
        byte[] jpeg = null; LiveFrameTarget target = null; boolean modelLease = false;
        String step = "image_encode", failureCode = "none"; long aiStarted = -1;
        try {
            if (!session.isActive(now())) { failureCode = "recognition_timeout"; throw new IllegalStateException(); }
            if (diagnosticLiveFrame == session) { liveFrameWidth = frame.image.width(); liveFrameHeight = frame.image.height(); }
            String data = frame.image.analysisData();
            step = "ai_resolve"; traceLiveFrame(session, step, "none");
            // image() performs the broker's authoritative resolve/access check in generate().
            AiClient client = new AiClient(this);
            if (!session.isActive(now())) { failureCode = "recognition_timeout"; throw new IllegalStateException(); }
            if (!MODEL_CALL_ACTIVE.compareAndSet(false, true)) { failureCode = "model_busy"; throw new IllegalStateException(); }
            modelLease = true;
            liveFrameAnalyses++; if (selected) selectedAnalyses++;
            step = selected ? "selected_ai_request" : "ai_request"; traceLiveFrame(session, step, "none"); aiStarted = now();
            String answer = client.image(selected ? LiveFrameTarget.selectionPrompt(x, y) : LiveFrameTarget.PROMPT, data).getString("text");
            if (diagnosticLiveFrame == session) { liveAiElapsedMs = now() - aiStarted; liveAnswerChars = answer.length(); }
            step = selected ? "selected_parse" : "parse"; traceLiveFrame(session, step, "none");
            LiveFrameTarget.Decision decision = selected ? LiveFrameTarget.parseSelected(answer, x, y) : LiveFrameTarget.parseDetailed(answer);
            if (decision.target() == null) { failureCode = decision.code(); throw new IllegalStateException(); }
            target = frame.image.toOriginal(decision.target());
            step = "crop"; traceLiveFrame(session, step, "none"); jpeg = frame.image.crop(target);
        } catch (OutOfMemoryError unavailable) { failureCode = "memory_pressure"; }
        catch (Exception unavailable) {
            if ("none".equals(failureCode)) failureCode = unavailable instanceof SecurityException ? "ai_permission_denied"
                : "crop".equals(step) && "target_outside_live_video".equals(unavailable.getMessage()) ? "target_outside_video"
                : "crop".equals(step) && "garment_too_small".equals(unavailable.getMessage()) ? "garment_too_small"
                : step.contains("ai_request") ? "ai_request_failed" : step + "_failed";
        } finally { if (modelLease) MODEL_CALL_ACTIVE.set(false); }
        if (diagnosticLiveFrame == session && aiStarted >= 0 && liveAiElapsedMs < 0) liveAiElapsedMs = now() - aiStarted;
        final String resultCode = failureCode; final byte[] ready = jpeg; final LiveFrameTarget outfit = target;
        main.post(() -> {
            decisionBusy = false;
            boolean sameFrame = capturedOutfit == frame && frame.selectionVersion == choice && now() < frame.expiresAt;
            if (liveFrame != session) {
                // A late, already requested localization may be retained, but can never start a new session.
                if (selected && sameFrame && failedLiveFrame == session && ready != null && outfit != null) {
                    frame.prepared = ready; frame.preparedTarget = outfit;
                }
                if (failedLiveFrame == session) updateLiveFrameFailure(); return;
            }
            traceLiveFrame(session, selected ? "selected_parse" : "parse", resultCode);
            if (!sameFrame) { failLiveFrame(session, "source_changed", liveFrameFailureText("source_changed")); return; }
            if (ready == null || outfit == null) {
                if (!selected && selectionFailure(resultCode) && session.waitForSelection(now())) {
                    phase = "live_frame_selection_ready"; lastLiveFrameFailure = liveFrameFailureText(resultCode);
                    renderLiveFailure(session); return;
                }
                failLiveFrame(session, resultCode, liveFrameFailureText(resultCode)); return;
            }
            if (selected) { frame.prepared = ready; frame.preparedTarget = outfit; }
            if (!session.analysisReady(outfit, now())) { failLiveFrame(session, "recognition_timeout", liveFrameFailureText("recognition_timeout")); return; }
            deliverLiveFrame(session, ready, outfit);
        });
    }

    private static boolean selectionFailure(String code) {
        return java.util.Set.of("model_unclear", "multiple_targets", "not_worn", "screen_or_photo", "invalid_bounds",
            "target_outside_video", "garment_too_small", "invalid_selection", "selection_outside_target").contains(code);
    }
    private void closeOutfitPicker() {
        if (outfitPicker != null) { outfitPicker.close(); outfitPicker = null; }
    }
    private void clearCapturedOutfit() {
        closeOutfitPicker();
        if (capturedOutfit != null) { capturedOutfit.close(); capturedOutfit = null; }
    }
    private boolean selectable(LiveFrameCapturePolicy session) {
        ObservationPolicy.Candidate candidate = liveFrame == session ? liveFrameCandidate : failedLiveCandidate;
        return capturedOutfit != null && candidate != null && capturedOutfit.matches(session.source(), candidate.generation(), now());
    }
    private void renderLiveFailure(LiveFrameCapturePolicy session) {
        if (liveProgress == null || outfitPicker != null) return;
        boolean canSelect = selectable(session) && !decisionBusy && !captureBusy && !MODEL_CALL_ACTIVE.get();
        liveProgress.showFailure(lastLiveFrameFailure, () -> retryLiveFrame(session), canSelect ? () -> openOutfitPicker(session) : null);
    }
    private boolean pickerRoomMatches(LiveFrameCapturePolicy session) {
        clearCache(); WindowSnapshot.ReadResult read = WindowSnapshot.read(this, activityName);
        reads++; lastRead = now(); lastLiveReadReason=read.reason(); lastLiveReadWindowId=read.windowId();
        if (read.page() != null) {
            LivePage page = read.page();
            return page.windowId()==session.source().windowId() && page.packageName().equals(session.source().packageName())
                && page.hostKey().equals(session.source().hostKey());
        }
        // Only retain the frozen UI while our own non-focusable picker obscures the same sole app.
        // No model call/handoff is permitted by this weaker evidence; closing the picker must reverify LIVE.
        return outfitPicker != null && outfitPicker.isShowing() && outfitPicker.accessibilityWindowId() >= 0
            && "app_not_focused".equals(read.reason()) && read.windowId()==session.source().windowId()
            && read.packageName().equals(session.source().packageName());
    }
    private void continuePickedOutfit(LiveFrameCapturePolicy session, CapturedOutfit frame, long choice, long until) {
        if (capturedOutfit != frame || frame.selectionVersion != choice || !unlocked()
                || (liveFrame != session && failedLiveFrame != session) || !selectable(session)) return;
        if (liveFrame == session && !session.isActive(now()))
            failLiveFrame(session, "selection_confirmation_needed", liveFrameFailureText("selection_confirmation_needed"));
        LiveFrameContinuity.Result room = liveFrame == session ? observeLiveFrame(session) : failedLiveRoom(session);
        if (room == LiveFrameContinuity.Result.WAIT && now() < until) {
            main.postDelayed(() -> continuePickedOutfit(session, frame, choice, until), 100); return;
        }
        if (room != LiveFrameContinuity.Result.VERIFIED) { cancelLiveFrame(session); return; }
        if (liveFrame == session && session.claimSelectedAnalysis(frame.x, frame.y, now())) startLiveAnalysis(session, true);
        else {
            if (liveFrame == session) failLiveFrame(session, "recognition_timeout", "Your outfit is selected. Confirm Try on to continue.");
            showRetainedSelectionConfirmation(session);
        }
    }
    /** Selecting a frame after its attempt expired does not authorize a new billed attempt. */
    private void showRetainedSelectionConfirmation(LiveFrameCapturePolicy session) {
        if (failedLiveFrame != session || capturedOutfit == null || !capturedOutfit.selected()) return;
        closeLiveProgress();
        if (confirmation != null) confirmation.close();
        confirmation = new LiveFrameProgress(this, failedPresentationSession, () -> cancelLiveFrame(session));
        confirmation.showConfirmation(() -> {
            if (confirmation != null) { confirmation.close(); confirmation = null; }
            retryLiveFrame(session, true);
        });
        phase = "awaiting_selected_try_on_confirmation";
        displayStatus = "Outfit selected. Choose Try on to start a new attempt.";
    }
    private boolean choiceStage(LiveFrameCapturePolicy session) {
        return failedLiveFrame == session && now() < liveFailureExpiresAt || liveFrame == session
            && session.stage() == LiveFrameCapturePolicy.Stage.WAITING_SELECTION;
    }
    private boolean selectOutfit(LiveFrameCapturePolicy session, int x, int y) {
        if (!choiceStage(session) || !selectable(session)
                || x < 0 || x > 1000 || y < 0 || y > 1000
                || decisionBusy || captureBusy || MODEL_CALL_ACTIVE.get()) return false;
        CapturedOutfit frame = capturedOutfit;
        frame.select(x, y); manualPicks++; closeOutfitPicker();
        continuePickedOutfit(session, frame, frame.selectionVersion, now()+1500);
        return true;
    }
    private void openOutfitPicker(LiveFrameCapturePolicy session) {
        if (!choiceStage(session) || !selectable(session)
                || decisionBusy || captureBusy || MODEL_CALL_ACTIVE.get()) return;
        CapturedOutfit frame = capturedOutfit;
        LiveOutfitPicker picker = new LiveOutfitPicker(this, frame.preview, new LiveOutfitPicker.Callback() {
            public void onPick(int x, int y) {
                if (capturedOutfit != frame || (liveFrame != session && failedLiveFrame != session)
                        || !selectable(session)) { closeOutfitPicker(); return; }
                selectOutfit(session, x, y);
            }
            public void onCancel() { cancelLiveFrame(session); }
        });
        outfitPicker = picker;
        try { picker.show(); phase = "live_frame_selecting"; }
        catch (RuntimeException unavailable) { closeOutfitPicker(); renderLiveFailure(session); }
    }

    /** A brief missing node can delay delivery, but it never authorizes a native start. */
    private void deliverLiveFrame(LiveFrameCapturePolicy session, byte[] ready, LiveFrameTarget outfit) {
        if (liveFrame != session) return;
        LiveFrameContinuity.Result room = observeLiveFrame(session);
        if (room == LiveFrameContinuity.Result.WAIT) {
            main.postDelayed(() -> deliverLiveFrame(session, ready, outfit), 100); return;
        }
        if (room != LiveFrameContinuity.Result.VERIFIED) {
            String code = now() >= session.deadlineAt() ? "recognition_timeout" : "source_" + lastLiveReadReason;
            failLiveFrame(session, code, liveFrameFailureText(code)); return;
        }
        String reference = null;
        try {
            reference = ReferenceStore.put(this, ready, session.source());
            if (!session.complete(now())) throw new IllegalStateException();
            LiveTryOnHandoff.openDirect(this, reference, session, outfit);
            referenceCaptures++; liveFrameHandoffs++;
            policy.dismiss(liveFrameCandidate, now()); // Native owns retries after the exact handoff.
            finishLiveDiagnostic(session, "handoff", "handed_off");
            liveFrame = null; liveFrameCandidate = null; liveFrameEventId = ""; liveContinuity = null;
            clearCapturedOutfit();
            closeLiveProgress(); phase = "live_frame_handed_off"; displayStatus = "Opening Live Try-On…";
            livePresentationSession = null;
            closeAcceptedReminder();
        } catch (Exception unavailable) {
            if (reference != null) ReferenceStore.discard(this, reference);
            failLiveFrame(session, "handoff_failed", "Could not open Live Try-On. Please try again.");
        }
    }

    private void closeLiveProgress() {
        if (liveProgress != null) { liveProgress.close(); liveProgress = null; }
    }
    /** Keep a bound-only presentation host alive across progress, selection and explicit retry. */
    private void retainAcceptedReminder(LiveFrameProgressHost.Session presentation, long expiresAt) {
        ReminderConnection retained = reminders; reminders = null;
        if (retained == null) return; // A retry keeps the original binding and its original deadline.
        if (presentation == null) { retained.close(); return; }
        closeAcceptedReminder();
        acceptedReminder = retained; acceptedReminderSession = presentation;
        acceptedReminderExpiry = () -> {
            if (acceptedReminder != retained) return;
            if (liveFrame != null && livePresentationSession == presentation) cancelLiveFrame(liveFrame);
            else if (failedLiveFrame != null && failedPresentationSession == presentation) dismissLiveFrameFailure();
            else closeAcceptedReminder();
        };
        main.postDelayed(acceptedReminderExpiry, Math.max(1, expiresAt - now()));
    }
    private void closeAcceptedReminder() {
        if (acceptedReminderExpiry != null) main.removeCallbacks(acceptedReminderExpiry);
        acceptedReminderExpiry = null;
        ReminderConnection retained = acceptedReminder;
        acceptedReminder = null; acceptedReminderSession = null;
        if (retained != null) retained.close();
    }
    private void failLiveFrame(LiveFrameCapturePolicy session, String message) {
        failLiveFrame(session, "source_unavailable", message);
    }
    private void failLiveFrame(LiveFrameCapturePolicy session, String code, String message) {
        session.cancel();
        if (liveFrame != session) return;
        liveFrame = null; failedLiveFrame = session; failedLiveCandidate = liveFrameCandidate; liveFrameCandidate = null;
        failedPresentationSession = livePresentationSession; livePresentationSession = null;
        liveFrameEventId = ""; liveContinuity = null;
        liveFailureExpiresAt = liveAcceptedAt + ObservationPolicy.ACCEPTED_RETRY_TTL_MS;
        if (capturedOutfit != null) liveFailureExpiresAt = Math.min(liveFailureExpiresAt, capturedOutfit.expiresAt);
        if (code.startsWith("source_") || "cancelled".equals(code)) clearCapturedOutfit();
        phase = "live_frame_failed";
        finishLiveDiagnostic(session, liveAnalysisStage, code);
        long failedAt = now();
        failureContinuity = new LiveFrameContinuity(session.source(), failedAt, failedAt + LiveFrameCapturePolicy.DEADLINE_MS);
        displayStatus = lastLiveFrameFailure = message;
        if (destroyed || !unlocked()) { dismissLiveFrameFailure(); return; }
        if (liveProgress == null) {
            LiveFrameProgress progress = new LiveFrameProgress(this, failedPresentationSession, () -> cancelLiveFrame(session));
            if (failedLiveFrame != session) { progress.close(); return; }
            liveProgress = progress;
        }
        if (liveProgress == null) { dismissLiveFrameFailure(); return; }
        renderLiveFailure(session);
        if (outfitPicker == null) liveProgress.setRetryEnabled(!decisionBusy && !captureBusy && !MODEL_CALL_ACTIVE.get());
    }
    private synchronized void traceLiveFrame(LiveFrameCapturePolicy session, String stage, String code) {
        if (diagnosticLiveFrame == session && !liveDiagnosticTerminal) {
            liveAnalysisStage = stage; liveAnalysisCode = code;
        }
    }
    private synchronized void finishLiveDiagnostic(LiveFrameCapturePolicy session, String stage, String code) {
        traceLiveFrame(session, stage, code);
        if (diagnosticLiveFrame == session) {
            if (!liveDiagnosticTerminal && code.startsWith("source_")) {
                failureLiveWindows = lastLiveWindows;
                failureProgressWindowId = liveProgress == null ? -1 : liveProgress.accessibilityWindowId();
            }
            liveDiagnosticTerminal = true;
        }
    }
    private static String liveFrameFailureText(String code) {
        return switch (code) {
            case "model_unclear" -> "The outfit is unclear. Select it in the image, or wait for a clearer view.";
            case "multiple_targets" -> "More than one outfit may match. Select the one you want to try on.";
            case "not_worn" -> "No clear top worn by the presenter was found. You can select a displayed outfit instead.";
            case "screen_or_photo" -> "The detected outfit is on a screen or photo. Select real clothing in the LIVE.";
            case "invalid_selection", "selection_outside_target" -> "The clothing at that spot couldn't be isolated. Select the middle of the item and try again.";
            case "malformed_json", "invalid_bounds", "invalid_description" -> "The recognizer returned an unusable result. Please try again.";
            case "target_outside_video", "garment_too_small" -> "The top couldn't be isolated from this frame. Wait for a closer, clearer view and try again.";
            case "recognition_timeout" -> "Outfit recognition took too long. Please try again.";
            case "selection_confirmation_needed" -> "Choose an outfit in the image, then confirm Try on to continue.";
            case "ai_permission_denied" -> "Allow Live Try-On Suggestions to use system AI, then try again.";
            case "ai_resolve_failed", "ai_request_failed", "model_busy" -> "The recognition service couldn't finish. Please try again.";
            case "source_changed" -> "The LIVE room changed. Return to the presenter and try again.";
            default -> "The LIVE frame couldn't be prepared. Please try again.";
        };
    }
    private void cancelLiveFrame(LiveFrameCapturePolicy session) {
        if (liveFrame == session) {
            finishLiveDiagnostic(session, liveAnalysisStage, "cancelled");
            session.cancel(); policy.dismiss(liveFrameCandidate, now()); liveFrame = null; liveFrameCandidate = null;
            liveFrameEventId = ""; liveContinuity = null;
            livePresentationSession = null;
        }
        else if (failedLiveFrame != session) return;
        dismissLiveFrameFailure(); phase = "watching"; displayStatus = "Try-on cancelled. The camera has not started.";
    }
    private void dismissLiveFrameFailure() {
        dismissLiveFrameFailure(false);
    }
    private void dismissLiveFrameFailure(boolean keepPresentation) {
        if (confirmation != null) { confirmation.close(); confirmation = null; }
        if (failedLiveCandidate != null) policy.dismiss(failedLiveCandidate, now());
        failedLiveFrame = null; failedLiveCandidate = null;
        failedPresentationSession = null;
        liveFailureExpiresAt = 0; failureContinuity = null; closeLiveProgress(); clearCapturedOutfit();
        if (!keepPresentation) closeAcceptedReminder();
    }
    private LiveFrameContinuity.Result failedLiveRoom(LiveFrameCapturePolicy session) {
        if (destroyed || !Preferences.enabled(this) || !unlocked() || now() >= liveFailureExpiresAt)
            return LiveFrameContinuity.Result.CANCEL;
        clearCache(); WindowSnapshot.ReadResult read = WindowSnapshot.read(this, activityName); reads++; lastRead = now();
        lastLiveReadReason = read.reason(); lastLiveReadWindowId = read.windowId();
        lastLiveWindows = WindowSnapshot.diagnostic();
        // Selection UI may remain open longer than the old preparation lease. Only a new,
        // strictly verified same-room page can renew metadata continuity; never the old attempt deadline.
        LivePage verified = read.page();
        long verifiedAt = now();
        if (verifiedAt >= liveFailureExpiresAt) return LiveFrameContinuity.Result.CANCEL;
        if (verified != null && verified.windowId()==session.source().windowId()
                && verified.packageName().equals(session.source().packageName())
                && verified.hostKey().equals(session.source().hostKey())) {
            failureContinuity = new LiveFrameContinuity(session.source(), verifiedAt,
                Math.min(liveFailureExpiresAt, verifiedAt + LiveFrameCapturePolicy.DEADLINE_MS));
        }
        LiveFrameContinuity.Result result = failureContinuity.observe(read.page(), read.reason(), read.windowId(), read.packageName(), now());
        if (result == LiveFrameContinuity.Result.VERIFIED) {
            policy.observe(read.page(), now());
        }
        return result;
    }
    private void updateLiveFrameFailure() {
        if (failedLiveFrame == null) return;
        LiveFrameContinuity.Result room = failedLiveRoom(failedLiveFrame);
        if (now() >= liveFailureExpiresAt || room == LiveFrameContinuity.Result.CANCEL) { dismissLiveFrameFailure(); return; }
        if (liveProgress != null && outfitPicker == null) {
            boolean canSelect = selectable(failedLiveFrame) && !decisionBusy && !captureBusy && !MODEL_CALL_ACTIVE.get();
            if (canSelect != liveProgress.hasSelectionAction()) renderLiveFailure(failedLiveFrame);
            liveProgress.setRetryEnabled(room == LiveFrameContinuity.Result.VERIFIED
                && !decisionBusy && !captureBusy && !MODEL_CALL_ACTIVE.get());
        }
    }
    private void retryLiveFrame(LiveFrameCapturePolicy session) {
        retryLiveFrame(session, capturedOutfit != null && capturedOutfit.selected());
    }
    private void retryLiveFrame(LiveFrameCapturePolicy session, boolean keepSelection) {
        retryClicks++; retryState = "checking_page";
        if (liveFrame == session && session.stage() == LiveFrameCapturePolicy.Stage.WAITING_SELECTION)
            failLiveFrame(session, "manual_retry", "Preparing a new LIVE frame.");
        if (failedLiveFrame != session) { retryState = "stale_session"; return; }
        if (now() >= liveFailureExpiresAt) { retryState = "expired"; dismissLiveFrameFailure(); return; }
        LiveFrameContinuity.Result room = failedLiveRoom(session);
        if (room == LiveFrameContinuity.Result.CANCEL) { retryState = "source_unverified"; dismissLiveFrameFailure(); return; }
        if (room != LiveFrameContinuity.Result.VERIFIED || decisionBusy || captureBusy || MODEL_CALL_ACTIVE.get()) {
            retryState = room != LiveFrameContinuity.Result.VERIFIED ? "waiting_page" : "waiting_model";
            if (liveProgress != null) liveProgress.showFailure(lastLiveFrameFailure, () -> retryLiveFrame(session));
            updateLiveFrameFailure(); return;
        }
        // This method is called only by a fresh user click / authenticated command.
        // A retry never re-registers with Proactivity or runs an automatic paid loop.
        ObservationPolicy.Candidate previous = failedLiveCandidate;
        LiveFrameProgressHost.Session presentation = failedPresentationSession;
        CapturedOutfit retained = keepSelection ? capturedOutfit : null;
        if (retained != null) capturedOutfit = null;
        // Retry retains the host-issued presentation and the accepted connection together.
        dismissLiveFrameFailure(presentation != null && presentation == acceptedReminderSession);
        if (retained != null) capturedOutfit = retained;
        ObservationPolicy.Candidate candidate = policy.explicit(now());
        if (candidate == null || previous == null
                || candidate.page().windowId() != previous.page().windowId()
                || !candidate.page().hostKey().equals(previous.page().hostKey())
                || !candidate.page().packageName().equals(previous.page().packageName())) {
            retryState = "source_unverified"; clearCapturedOutfit(); closeAcceptedReminder(); return;
        }
        retryState = beginExplicit(candidate, retained != null && retained.selected(), presentation) ? "started" : "unavailable";
        if (!"started".equals(retryState) && liveFrame == null && failedLiveFrame == null) closeAcceptedReminder();
    }

    private void prepareVision(ObservationPolicy.Candidate candidate) {
        decisionBusy = true; phase = "checking_ai"; displayStatus = "Checking image recognition availability";
        final long requestLife = lifecycle;
        work.execute(() -> {
            boolean ready = false;
            try { if (alive(candidate, requestLife)) { new AiClient(this).resolve("image"); ready = true; } }
            catch (Exception ignored) {}
            final boolean available = ready;
            main.post(() -> {
                decisionBusy = false;
                if (!available || !alive(candidate, requestLife) || !refreshCurrent(candidate)) {
                    policy.resolveVision(candidate, false, now());
                    if (available && !destroyed) stale++;
                    if (!available && !destroyed) displayStatus = "Image recognition is not ready. Authorize this app and select an image model.";
                    return;
                }
                screenshotForVision(candidate);
            });
        });
    }
    private boolean alive(ObservationPolicy.Candidate candidate, long requestLife) {
        return !destroyed && lifecycle == requestLife && Preferences.enabled(this)
            && unlocked() && policy.isCurrent(candidate, now())
            && (candidate.kind() != ObservationPolicy.Kind.VISION || Preferences.vision(this));
    }
    private void screenshotForVision(ObservationPolicy.Candidate candidate) {
        final boolean vision = true;
        if (captureBusy || !refreshCurrent(candidate)) { if (vision) policy.resolveVision(candidate, false, now()); return; }
        captureBusy = true; final long requestLife = lifecycle;
        try {
            takeScreenshotOfWindow(candidate.page().windowId(), getMainExecutor(), new TakeScreenshotCallback() {
                public void onSuccess(ScreenshotResult shot) {
                    if (!alive(candidate, requestLife)) {
                        shot.getHardwareBuffer().close(); captureBusy = false;
                        if (vision) policy.resolveVision(candidate, false, now()); return;
                    }
                    try { work.execute(() -> {
                        byte[] jpeg = null;
                        try { jpeg = ScreenshotCodec.encode(shot, vision ? 960 : 1920, vision ? 220000 : 3 * 1024 * 1024); }
                        catch (Exception | OutOfMemoryError ignored) {}
                        final byte[] ready = jpeg;
                        main.post(() -> {
                            captureBusy = false;
                            if (ready == null || !alive(candidate, requestLife) || !refreshCurrent(candidate)) {
                                if (vision) policy.resolveVision(candidate, false, now()); stale++; return;
                            }
                            captures++;
                            analyze(candidate, ready, requestLife);
                        });
                    }); } catch (RuntimeException rejected) { shot.getHardwareBuffer().close(); captureBusy = false; if (vision) policy.resolveVision(candidate, false, now()); }
                }
                public void onFailure(int errorCode) {
                    captureBusy = false;
                    if (vision) policy.resolveVision(candidate, false, now());
                    if (!destroyed && lifecycle == requestLife) { phase = "capture_failed"; displayStatus = "Could not capture the current screen. This check has stopped."; }
                }
            });
        } catch (RuntimeException unavailable) {
            captureBusy = false; if (vision) policy.resolveVision(candidate, false, now()); displayStatus = "Screen capture is unavailable";
        }
    }
    private void analyze(ObservationPolicy.Candidate candidate, byte[] image, long requestLife) {
        if (!alive(candidate, requestLife)) { policy.resolveVision(candidate, false, now()); return; }
        decisionBusy = true; phase = "analyzing"; displayStatus = "Checking whether clothing is shown";
        work.execute(() -> {
            boolean result = false;
            boolean modelLease = false;
            try {
                String data = ScreenshotCodec.dataUrl(image);
                if (alive(candidate, requestLife) && MODEL_CALL_ACTIVE.compareAndSet(false, true)) {
                    modelLease = true;
                    if (ObservationHistory.claimVision(this, candidate.page())) {
                        visionRequests++; result = VisionAnswer.apparel(new AiClient(this).image(VisionAnswer.PROMPT, data).getString("text"));
                    }
                }
            } catch (Exception | OutOfMemoryError ignored) {}
            finally { if (modelLease) MODEL_CALL_ACTIVE.set(false); }
            final boolean answer = result;
            main.post(() -> {
                decisionBusy = false;
                if (!destroyed && alive(candidate, requestLife)) refreshCurrent(candidate);
                ObservationPolicy.Candidate next = policy.resolveVision(candidate, answer, now());
                if (next != null && !destroyed) publish(next);
                else if (!destroyed && lifecycle == requestLife) { phase = "watching"; displayStatus = "Clothing could not be confirmed. No reminder for this LIVE room."; }
            });
        });
    }
    private void withdraw() {
        cancelPendingAcceptance();
        if (confirmation != null) { confirmation.close(); confirmation = null; }
        if (reminders != null) { reminders.close(); reminders = null; }
        offer = null; eventId = "";
    }
    private void reset(String message) {
        if (liveFrame != null) { liveFrame.cancel(); liveFrame = null; }
        liveFrameCandidate = null; liveFrameEventId = "";
        livePresentationSession = null;
        liveContinuity = null; dismissLiveFrameFailure();
        lifecycle++; policy.invalidate(); withdraw(); phase = "idle"; displayStatus = message;
    }
    @Override public void onDestroy() {
        destroyed = true; reset("Observation service stopped"); main.removeCallbacksAndMessages(null);
        Preferences.get(this).unregisterOnSharedPreferenceChangeListener(preferences);
        if (receiverRegistered) unregisterReceiver(screen); work.shutdownNow();
        if (instance == this) instance = null; super.onDestroy();
    }
    @Override protected void dump(FileDescriptor fd, PrintWriter out, String[] args) {
        out.println("ShoppingObserver enabled=" + Preferences.enabled(this) + " classification=" + Preferences.classification(this)
            + " vision=" + Preferences.vision(this) + " phase=" + phase);
        LivePage page = policy.currentPage();
        out.println("live=" + (page != null) + " hasProduct=" + (page != null && page.hasProduct()) + " generation=" + policy.generation());
        out.println("historySuppressed=" + (page != null && ObservationHistory.suppressed(this, page)));
        out.println("reads=" + reads + " captures=" + captures + " visionRequests=" + visionRequests + " referenceCaptures=" + referenceCaptures);
        out.println("classificationRequests=" + classificationRequests + " classificationCacheHits=" + classificationCacheHits
            + " classificationStatus=" + classificationStatus + " modelCallActive=" + MODEL_CALL_ACTIVE.get());
        out.println("published=" + published + " accepted=" + accepted + " stale=" + stale + " captureBusy=" + captureBusy + " decisionBusy=" + decisionBusy);
        out.println("acceptanceStage=" + acceptanceStage + " acceptanceCode=" + acceptanceCode);
        out.println("liveFrameStage=" + (liveFrame == null ? "none" : liveFrame.stage())
            + " liveFrameCaptures=" + liveFrameCaptures + " liveFrameAnalyses=" + liveFrameAnalyses + " liveFrameHandoffs=" + liveFrameHandoffs);
        out.println("outfitSelection samples=" + sampledFrames + " manualPicks=" + manualPicks + " selectedAnalyses=" + selectedAnalyses
            + " frameRetained=" + (capturedOutfit != null) + " pickerVisible=" + (outfitPicker != null)
            + " attemptSamples=" + (diagnosticLiveFrame == null ? 0 : diagnosticLiveFrame.samplesCaptured())
            + " attemptSelectionClaimed=" + (diagnosticLiveFrame != null && diagnosticLiveFrame.selectionAnalysisClaimed()));
        out.println("lastLiveFrameFailure=" + lastLiveFrameFailure);
        out.println("liveAnalysisStage=" + liveAnalysisStage + " liveAnalysisCode=" + liveAnalysisCode
            + " liveAnalysisAgeMs=" + (liveAnalysisStartedAt < 0 ? -1 : Math.max(0, now() - liveAnalysisStartedAt))
            + " liveAiElapsedMs=" + liveAiElapsedMs + " liveAnswerChars=" + liveAnswerChars
            + " liveFrameSize=" + liveFrameWidth + "x" + liveFrameHeight
            + " recognitionFailureVisible=" + (failedLiveFrame != null)
            + " liveReadReason=" + lastLiveReadReason + " liveReadWindowId=" + lastLiveReadWindowId);
        out.println("failureProgressWindowId=" + failureProgressWindowId + " failureLiveWindows=" + failureLiveWindows);
        out.println("retryClicks=" + retryClicks + " retryState=" + retryState);
        out.println("lastOfferInvalidation=" + lastOfferInvalidation);
    }
}
