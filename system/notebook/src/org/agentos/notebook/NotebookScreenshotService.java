package org.agentos.notebook;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class NotebookScreenshotService extends AccessibilityService {
    private static final String TAG = "NotebookScreenshot";
    private static final Object LOCK = new Object();
    private static final long READABLE_MS = 6000;
    private static final long READING_DISPLAY_MS = 150;
    private static final long SAVED_ACTIONS_MS = 30_000;
    private static final List<NotebookScreenshotIsland.Action> ACTIONS = List.of(
            new NotebookScreenshotIsland.Action("save", "Save"),
            new NotebookScreenshotIsland.Action("send", "Send to…"),
            new NotebookScreenshotIsland.Action("edit", "Edit"));
    private static final List<String> ACTION_IDS = ACTIONS.stream().map(NotebookScreenshotIsland.Action::id).toList();
    private static final List<NotebookScreenshotIsland.Action> SAVED_ACTIONS = List.of(
            new NotebookScreenshotIsland.Action("go_to_notebook", "Go to Notebook"),
            new NotebookScreenshotIsland.Action("change_list", "Change List"),
            new NotebookScreenshotIsland.Action("add_note", "Add note"));
    private static final List<String> SAVED_ACTION_IDS = SAVED_ACTIONS.stream()
            .map(NotebookScreenshotIsland.Action::id).toList();
    private static volatile NotebookScreenshotService active;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread mediaThread = new HandlerThread("notebook-screenshot-media");
    private final HandlerThread islandThread = new HandlerThread("notebook-screenshot-island");
    private final java.util.concurrent.ExecutorService saves = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService enrichment = Executors.newSingleThreadExecutor();
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (prefs, key) -> main.post(this::updateObserver);
    private volatile SystemScreenshots observer;
    private NotebookAnalysis analysis;
    private NotebookScreenshotIsland island;
    private Handler islandWorker;
    private ContentObserver islandObserver;
    private String notificationTag;
    private volatile String islandHost = "unchecked";
    private volatile String islandError;
    private volatile boolean notificationFallback;
    private volatile Pending pending;
    private volatile String state = "idle";
    private JSONObject lastFourActions;
    private Runnable timeout;
    private final Runnable readIslandState = this::readIslandState;
    private final ArrayDeque<SourceChange> sources = new ArrayDeque<>();
    private final Runnable sampleXSource = this::sampleXSource;
    private SourceChange lastXSnapshot;
    private int lastXChangedWindow = -1;
    private long lastXChangedAt;

    static JSONObject backgroundSourceUi(JSONObject input) throws IOException {
        NotebookScreenshotService service = active;
        if (service == null) throw new IOException("NOTEBOOK_SCREEN_SERVICE_UNAVAILABLE");
        var work = new java.util.concurrent.FutureTask<>(
                () -> NotebookBackgroundControls.call(active == service ? service : null, input));
        service.main.post(work);
        try {
            return work.get(4, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("NOTEBOOK_BACKGROUND_CANCELLED", interrupted);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IOException("NOTEBOOK_BACKGROUND_TIMEOUT", timeout);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IOException("NOTEBOOK_BACKGROUND_UI_UNAVAILABLE", failure.getCause());
        } finally {
            work.cancel(false);
        }
    }

    private record SourceChange(long at, ScreenSource source) {}

    private static final class Pending {
        final String traceId = UUID.randomUUID().toString().replace("-", "");
        final String notificationTag = "notebook-screenshot-" + traceId;
        final SystemScreenshots.Capture capture;
        final ScreenSource source;
        final long acceptedAt;
        final long contextReadyAt;
        final NotebookAnalysis.Metadata metadata;
        long initialPostedAt;
        long readingVisibleAt;
        long readyPostedAt;
        long actionDeadlineAt;
        boolean actionsCommitted;
        boolean notificationFallback;
        volatile boolean saving;
        volatile boolean actionTaken;
        volatile String savedId;
        volatile boolean presentationPending;
        volatile boolean expansionRequested;
        volatile long presentationDeadlineAt;
        volatile long presentationGeneration;
        boolean actionable() {
            return actionsCommitted && !saving && !actionTaken && SystemClock.elapsedRealtime() < actionDeadlineAt;
        }
        boolean named() { return !metadata.title().isEmpty(); }
        String title() {
            if (named()) return metadata.title();
            return source.label().isBlank() ? "Screenshot" : source.label() + " screenshot";
        }
        String category() { return metadata.category(); }
        Pending(SystemScreenshots.Capture capture, ScreenSource source, long acceptedAt) {
            this.capture = capture;
            this.source = source;
            this.acceptedAt = acceptedAt;
            this.metadata = NotebookAnalysis.visiblePage(source);
            this.contextReadyAt = SystemClock.elapsedRealtime();
        }
    }

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("notebook", MODE_PRIVATE);
    }

    static void setEnabled(Context context, boolean enabled) {
        settings(context).edit().putBoolean("enabled", enabled).apply();
    }

    static boolean canReadImages(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
    }

    static JSONObject status(Context context) throws JSONException {
        var service = active;
        JSONObject result = new JSONObject()
                .put("enabled", settings(context).getBoolean("enabled", false))
                .put("accessibilityConnected", service != null)
                .put("imageAccess", canReadImages(context))
                .put("notificationAccess", NotebookScreenshotIsland.canNotify(context))
                .put("islandHost", service == null ? "unchecked" : service.islandHost)
                .put("presentation", service != null && service.notificationFallback ? "notification" : "island")
                .put("observing", service != null && service.observer != null);
        synchronized (LOCK) {
            result.put("state", service == null ? "disconnected" : service.state);
            if (service != null && service.islandError != null) result.put("islandError", service.islandError);
            if (service != null && service.lastFourActions != null) {
                result.put("lastFourActions", service.lastFourActions);
            }
            Pending item = service == null ? null : service.pending;
            if (item != null) {
                result.put("capture", new JSONObject().put("imageUri", item.capture.uri().toString())
                        .put("capturedAt", item.capture.capturedAt()).put("title", item.title())
                        .put("category", item.category()).put("sourcePackage", item.source.packageName())
                        .put("sourceUrl", item.source.url()).put("saving", item.saving));
                if (item.savedId != null) {
                    result.put("savedEntryId", item.savedId);
                    result.put("actions", new JSONArray(SAVED_ACTION_IDS));
                    result.put("actionSource", "saved");
                } else if (item.actionable()) {
                    result.put("actions", new JSONArray(ACTION_IDS));
                    result.put("actionSource", "fixed");
                }
            }
        }
        return result;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        mediaThread.start();
        islandThread.start();
        island = new NotebookScreenshotIsland(this);
        islandWorker = new Handler(islandThread.getLooper());
        ContentObserver listener = new ContentObserver(islandWorker) {
            @Override public void onChange(boolean selfChange) {
                islandWorker.removeCallbacks(readIslandState);
                islandWorker.post(readIslandState);
            }
        };
        try {
            getContentResolver().registerContentObserver(NotebookScreenshotIsland.URI, false, listener);
            islandObserver = listener;
        } catch (IllegalArgumentException | SecurityException unavailable) {
            Log.i(TAG, "Screenshot island state observer unavailable");
        }
        islandWorker.post(() -> islandHost = island.hostStatus());
        active = this;
        analysis = new NotebookAnalysis(this);
        settings(this).registerOnSharedPreferenceChangeListener(settingsListener);
        updateObserver();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        updateObserver();
        if (observer == null || event.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) return;
        long at = System.currentTimeMillis() - Math.max(0, SystemClock.uptimeMillis() - event.getEventTime());
        int changes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | AccessibilityEvent.TYPE_VIEW_SCROLLED
                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        if ("com.twitter.android".equals(String.valueOf(event.getPackageName()))
                && (event.getEventType() & changes) != 0) {
            lastXChangedWindow = event.getWindowId();
            lastXChangedAt = at;
            main.removeCallbacks(sampleXSource);
            main.postDelayed(sampleXSource, 150);
        }
        rememberSource(at);
    }

    @Override public void onInterrupt() { dismiss(); }

    @Override public void onDestroy() {
        active = null;
        settings(this).unregisterOnSharedPreferenceChangeListener(settingsListener);
        dismiss();
        if (islandObserver != null) getContentResolver().unregisterContentObserver(islandObserver);
        islandThread.quitSafely();
        var media = observer;
        observer = null;
        if (media != null) media.close();
        mediaThread.quitSafely();
        saves.shutdown();
        enrichment.shutdown();
        super.onDestroy();
    }

    private void updateObserver() {
        if (!canObserve()) {
            if (observer != null) {
                observer.close();
                observer = null;
                sources.clear();
                main.removeCallbacks(sampleXSource);
                lastXSnapshot = null;
                dismiss();
            }
            state = "paused";
            return;
        }
        if (observer == null) {
            try {
                rememberSource(System.currentTimeMillis());
                observer = new SystemScreenshots(getContentResolver(), new Handler(mediaThread.getLooper()),
                        capture -> main.post(() -> accept(capture)));
                state = "watching";
            } catch (RuntimeException failure) {
                state = "media_unavailable";
                Log.w(TAG, "System screenshot observer unavailable", failure);
            }
        }
    }

    private boolean canObserve() {
        return settings(this).getBoolean("enabled", false) && canReadImages(this);
    }

    private void rememberSource(long at) {
        ScreenSource source = ScreenSource.read(this, false);
        if (source == ScreenSource.UNKNOWN) return;
        SourceChange latest = sources.peekLast();
        if (latest != null && (latest.source().matches(source) || at < latest.at())) return;
        sources.addLast(new SourceChange(at, source));
        if (sources.size() > 12) sources.removeFirst();
        if (latest != null && "com.twitter.android".equals(latest.source().packageName())
                && !"com.twitter.android".equals(source.packageName())) {
            NotebookSourceJob.resumeAfterLeavingX(this);
        }
    }

    private ScreenSource sourceFor(long capturedAt) {
        for (var history = sources.descendingIterator(); history.hasNext();) {
            SourceChange seen = history.next();
            if (seen.at() > capturedAt) continue;
            ScreenSource current = ScreenSource.read(this, true);
            if (!seen.source().matches(current)) return seen.source();
            if (lastXSnapshot != null && lastXSnapshot.source().matches(current)
                    && current.windowId() == lastXChangedWindow && lastXSnapshot.at() >= lastXChangedAt
                    && lastXSnapshot.at() <= capturedAt) {
                return current.withPostText(lastXSnapshot.source().postText());
            }
            return current;
        }
        return ScreenSource.UNKNOWN;
    }

    private void sampleXSource() {
        if (observer == null) return;
        ScreenSource source = ScreenSource.read(this, true, true);
        if ("com.twitter.android".equals(source.packageName()) && source.windowId() == lastXChangedWindow) {
            lastXSnapshot = new SourceChange(System.currentTimeMillis(), source);
        }
    }

    private void accept(SystemScreenshots.Capture capture) {
        long acceptedAt = SystemClock.elapsedRealtime();
        if (!canObserve()) { updateObserver(); return; }
        if (observer == null || !getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isDeviceLocked()) return;
        dismiss();
        if (!NotebookScreenshotIsland.canNotify(this)) {
            islandUnavailable("notifications_blocked");
            return;
        }
        ScreenSource source = sourceFor(capture.capturedAt());
        Pending item = new Pending(capture, source, acceptedAt);
        Log.i(TAG, "Screenshot timing trace=" + item.traceId + " notification=" + capture.notificationAt()
                + " media=" + capture.mediaReadyAt() + " accepted=" + acceptedAt
                + " context=" + item.contextReadyAt);
        synchronized (LOCK) {
            pending = item;
            state = "reading";
        }
        islandWorker.post(() -> {
            String host = island.hostStatus();
            islandHost = host;
            main.post(() -> startIsland(item, host));
        });
    }

    private void startIsland(Pending item, String host) {
        if (pending != item) return;
        if (!"available".equals(host)) {
            showNotificationActions(item);
            return;
        }
        notificationFallback = false;
        if (islandObserver == null) {
            islandUnavailable("island_observer_unavailable");
            return;
        }
        item.initialPostedAt = SystemClock.elapsedRealtime();
        notificationTag = item.notificationTag;
        if (postIsland(item, "Reading image", "", List.of(), null, islandDeadline(item))) {
            setTimeout(() -> islandUnavailable("loading_not_presented"), READABLE_MS);
            islandWorker.postDelayed(readIslandState, 250);
        }
    }

    /** Same pending screenshot and Save path, on builds without the optional island provider. */
    private void showNotificationActions(Pending item) {
        if (pending != item) return;
        long now = SystemClock.elapsedRealtime();
        notificationFallback = true;
        item.notificationFallback = true;
        notificationTag = item.notificationTag;
        item.initialPostedAt = now;
        item.readyPostedAt = now;
        item.actionDeadlineAt = now + 30_000;
        if (!postIsland(item, item.title(), item.source.label(), ACTIONS,
                island.actionIntent(item.notificationTag, "preview"), item.actionDeadlineAt)) return;
        synchronized (LOCK) {
            item.actionsCommitted = true;
            state = "ready";
            islandError = null;
            try {
                lastFourActions = new JSONObject().put("capturedAt", item.capture.capturedAt())
                        .put("traceId", item.traceId).put("actionSource", "fixed")
                        .put("actionCount", ACTIONS.size()).put("presentation", "notification");
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        setTimeout(() -> { if (!item.saving) dismiss(); }, 30_000);
    }

    private void showOptions(Pending item) {
        if (pending != item) return;
        long now = SystemClock.elapsedRealtime();
        long deadline = Math.min(now + READABLE_MS, islandDeadline(item));
        if (now >= deadline) {
            dismiss();
            return;
        }
        synchronized (LOCK) {
            item.readyPostedAt = now;
            item.actionDeadlineAt = deadline;
        }
        String title = "save_failed".equals(state) ? "Couldn’t save screenshot" : item.title();
        String summary = item.source.label();
        if (!item.metadata.subtitle().isBlank()) summary += " · " + item.metadata.subtitle();
        if (postIsland(item, title, summary, ACTIONS,
                island.actionIntent(item.notificationTag, "preview"), deadline)) {
            islandWorker.postDelayed(readIslandState, 250);
            setTimeout(() -> {
                if (item.saving) return;
                if (item.actionsCommitted) dismiss();
                else islandUnavailable("content_not_ready");
            }, deadline - now);
        }
    }

    private static long islandDeadline(Pending item) {
        return item.initialPostedAt + (item.notificationFallback ? 30_000 : NotebookScreenshotIsland.LIFETIME_SECONDS * 1000L);
    }

    private boolean postIsland(Pending item, String title, String body,
                               List<NotebookScreenshotIsland.Action> actions, PendingIntent open, long deadline) {
        if (!NotebookScreenshotIsland.canNotify(this)) {
            islandUnavailable("notifications_blocked");
            return false;
        }
        try {
            island.post(item.notificationTag, title, body, actions, open,
                    Math.max(1, deadline - SystemClock.elapsedRealtime()));
            if (!actions.isEmpty() && !item.notificationFallback) {
                synchronized (LOCK) {
                    item.presentationPending = false;
                    item.presentationGeneration++;
                    item.expansionRequested = false;
                    item.presentationDeadlineAt = Math.min(deadline, SystemClock.elapsedRealtime() + READABLE_MS);
                    item.presentationPending = true;
                }
                islandWorker.removeCallbacks(readIslandState);
                islandWorker.post(readIslandState);
            }
            return true;
        } catch (RuntimeException | JSONException failure) {
            Log.w(TAG, "Failed to post screenshot notification", failure);
            islandUnavailable("notification_failed");
            return false;
        }
    }

    private void islandUnavailable(String reason) {
        Log.w(TAG, "Screenshot island unavailable: " + reason);
        clear("saved".equals(state) ? "saved" : "surface_unavailable", reason);
    }

    private void readIslandState() {
        Pending item = pending;
        if (item == null) return;
        long generation = item.presentationGeneration;
        JSONObject snapshot = island.state();
        if (generation == item.presentationGeneration && item.presentationPending && !item.expansionRequested
                && SystemClock.elapsedRealtime() < item.presentationDeadlineAt) {
            List<NotebookScreenshotIsland.Action> expected = item.savedId == null ? ACTIONS : SAVED_ACTIONS;
            JSONObject activity = screenshotActivity(snapshot.optJSONArray("activities"), item, expected);
            if (activity != null && pending == item && generation == item.presentationGeneration) {
                String key = activity.optString("key");
                JSONObject presentation = snapshot.optJSONObject("presentation");
                JSONObject card = presentation == null ? null : presentation.optJSONObject("card");
                boolean reopenBelow = card != null && key.equals(card.optString("key"))
                        && !card.optBoolean("cover");
                boolean expanded = island.expand(key, item.notificationTag, reopenBelow);
                synchronized (LOCK) {
                    if (pending == item && generation == item.presentationGeneration) {
                        item.expansionRequested = expanded;
                    }
                }
                snapshot = island.state();
            }
        }
        JSONObject observed = snapshot;
        main.post(() -> {
            if (pending != item) return;
            if (generation != item.presentationGeneration) {
                schedulePresentationRead(item, 0);
                return;
            }
            observeIsland(item, observed, generation);
            if (pending == item && item.presentationPending) {
                // Only the publication-to-native-card handoff is polled. Once expanded,
                // a user collapse is respected and never triggers another automatic open.
                if (SystemClock.elapsedRealtime() >= item.presentationDeadlineAt) {
                    item.presentationPending = false;
                    islandUnavailable("content_not_ready");
                } else {
                    schedulePresentationRead(item, 100);
                }
            }
        });
    }

    private void schedulePresentationRead(Pending item, long delay) {
        if (pending != item || !item.presentationPending) return;
        islandWorker.removeCallbacks(readIslandState);
        islandWorker.postDelayed(readIslandState, delay);
    }

    private void observeIsland(Pending item, JSONObject snapshot, long generation) {
        if (pending != item || generation != item.presentationGeneration
                || !snapshot.optBoolean("ok") || item.initialPostedAt == 0) return;
        JSONObject activity = screenshotActivity(snapshot.optJSONArray("activities"), item);
        if (activity == null) {
            if (item.readingVisibleAt != 0 && !item.saving && !item.actionTaken) dismiss();
            return;
        }
        JSONObject presentation = snapshot.optJSONObject("presentation");
        if (presentation == null) return;
        String key = activity.optString("key");
        long now = SystemClock.elapsedRealtime();
        if (item.readyPostedAt == 0 && item.readingVisibleAt == 0
                && key.equals(presentation.optString("selected")) && presentation.optBoolean("compactVisible")) {
            item.readingVisibleAt = now;
            setTimeout(() -> showOptions(item), READING_DISPLAY_MS);
        } else if (item.presentationPending && now < item.presentationDeadlineAt
                && presentation.optBoolean("expanded")) {
            List<NotebookScreenshotIsland.Action> expected = item.savedId == null ? ACTIONS : SAVED_ACTIONS;
            activity = screenshotActivity(snapshot.optJSONArray("activities"), item, expected);
            JSONObject card = presentation.optJSONObject("card");
            if (activity != null && card != null && activity.optString("key").equals(card.optString("key"))
                    && card.optBoolean("nativeReady") && card.optBoolean("cover")
                    && "body".equals(card.optString("layout"))) {
                item.presentationPending = false;
                if (item.savedId == null) recordActions(item, now);
            }
        }
    }

    private JSONObject screenshotActivity(JSONArray activities, Pending item) {
        return screenshotActivity(activities, item, null);
    }

    private JSONObject screenshotActivity(JSONArray activities, Pending item,
                                           List<NotebookScreenshotIsland.Action> expected) {
        if (activities == null) return null;
        for (int index = 0; index < activities.length(); index++) {
            JSONObject entry = activities.optJSONObject(index);
            if (entry != null && island.matches(entry.optString("key"), item.notificationTag)
                    && (expected == null || hasActions(entry, expected))) return entry;
        }
        return null;
    }

    private static boolean hasActions(JSONObject activity, List<NotebookScreenshotIsland.Action> expected) {
        JSONArray actions = activity.optJSONArray("availableActions");
        if (actions == null || actions.length() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            var option = expected.get(index);
            JSONObject action = actions.optJSONObject(index);
            if (action == null || !option.id().equals(action.optString("id"))
                    || !option.label().equals(action.optString("title"))) return false;
        }
        return true;
    }

    private void recordActions(Pending item, long attachedAt) {
        synchronized (LOCK) {
            if (pending != item || item.actionsCommitted) return;
            item.actionsCommitted = true;
            state = "ready";
            try {
                long captureToMediaMs = item.capture.mediaReadyWallTime() - item.capture.capturedAt();
                long elapsed = captureToMediaMs + attachedAt - item.capture.mediaReadyAt();
                JSONObject timing = new JSONObject().put("captureToMediaWallMs", captureToMediaMs);
                addDuration(timing, "notificationToMediaMs", item.capture.notificationAt(), item.capture.mediaReadyAt());
                addDuration(timing, "mediaToAcceptMs", item.capture.mediaReadyAt(), item.acceptedAt);
                addDuration(timing, "sourceContextMs", item.acceptedAt, item.contextReadyAt);
                addDuration(timing, "contextToPostMs", item.contextReadyAt, item.initialPostedAt);
                addDuration(timing, "postToCompactMs", item.initialPostedAt, item.readingVisibleAt);
                addDuration(timing, "readyPostToExpandedMs", item.readyPostedAt, attachedAt);
                addDuration(timing, "mediaToExpandedMs", item.capture.mediaReadyAt(), attachedAt);
                addDuration(timing, "compactToExpandedMs", item.readingVisibleAt, attachedAt);
                lastFourActions = new JSONObject().put("capturedAt", item.capture.capturedAt())
                        .put("traceId", item.traceId)
                        .put("mediaDetection", item.capture.notificationAt() > 0 ? "item" : "scan")
                        .put("elapsedMs", Math.max(0, elapsed))
                        .put("identification", item.named() ? "page" : "unavailable")
                        .put("actionSource", "fixed")
                        .put("actionCount", ACTIONS.size())
                        .put("timingMs", timing);
                Log.i(TAG, "Screenshot timing trace=" + item.traceId + " expanded=" + attachedAt
                        + " durations=" + timing);
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    private static void addDuration(JSONObject timing, String key, long start, long end) throws JSONException {
        if (start > 0 && end >= start) timing.put(key, end - start);
    }

    static void onIslandAction(Activity activity, String tag, String action) {
        var service = active;
        Pending item = service == null ? null : service.pending;
        if (item == null || !item.notificationTag.equals(tag) || action == null) {
            activity.finish();
            return;
        }
        if (item.savedId != null) {
            if (!SAVED_ACTION_IDS.contains(action)) { activity.finish(); return; }
            service.cancelTimeout();
            // The dialog and all writes operate on this same saved entry. Remove the island
            // before showing an editor so its surface cannot cover the activity's controls.
            service.island.cancel(item.notificationTag);
            NotebookSavedScreenshotActions.open(activity, item.savedId, action, () -> {
                if (service.pending == item) service.clear("saved", null);
                activity.finish();
            });
            return;
        }
        if (item.actionsCommitted) {
            service.runIslandAction(activity, item, action);
            return;
        }
        // The tap can arrive before ContentObserver delivery of the expanded state.
        service.islandWorker.post(() -> {
            long generation = item.presentationGeneration;
            JSONObject snapshot = service.island.state();
            service.main.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                service.observeIsland(item, snapshot, generation);
                if (generation != item.presentationGeneration) service.schedulePresentationRead(item, 0);
                service.runIslandAction(activity, item, action);
            });
        });
    }

    static void saveFromNotification(String tag) {
        var service = active;
        Pending item = service == null ? null : service.pending;
        if (item == null || !item.notificationTag.equals(tag)) return;
        Runnable save = () -> {
            if (active != service || service.pending != item) return;
            try { service.saveFromIsland(item); }
            catch (IOException unavailable) {
                Log.i(TAG, "Screenshot save action no longer available");
            }
        };
        // A user can tap a rendered surface before its READY state reaches the observer.
        if (item.actionsCommitted) service.main.post(save);
        else service.islandWorker.post(() -> {
            long generation = item.presentationGeneration;
            JSONObject snapshot = service.island.state();
            service.main.post(() -> {
                service.observeIsland(item, snapshot, generation);
                if (generation != item.presentationGeneration) service.schedulePresentationRead(item, 0);
                save.run();
            });
        });
    }

    private void runIslandAction(Activity activity, Pending item, String action) {
        try {
            if ("save".equals(action)) saveFromIsland(item);
            else performAction(activity, item, action);
        } catch (IOException unavailable) {
            Toast.makeText(activity, "Action unavailable", Toast.LENGTH_SHORT).show();
        } finally {
            activity.finish();
        }
    }

    private void performAction(Context caller, Pending item, String action) throws IOException {
        synchronized (LOCK) {
            requireAvailable(item);
            if (!"preview".equals(action) && !ACTION_IDS.contains(action)) {
                throw new IOException("NOTEBOOK_ACTION_UNAVAILABLE");
            }
            try {
                NotebookActions.open(caller, action, item.capture, item.source, item.title());
                item.actionTaken = true;
            } catch (RuntimeException failure) {
                throw new IOException("NOTEBOOK_ACTION_UNAVAILABLE", failure);
            }
        }
        main.post(() -> { if (pending == item) dismiss(); });
    }

    static void runPendingAction(String action) throws IOException {
        var service = active;
        if (service == null) throw new IOException("NOTEBOOK_SCREENSHOT_SERVICE_UNAVAILABLE");
        Pending saved = service.pending;
        if (saved != null && saved.savedId != null) {
            if (!SAVED_ACTION_IDS.contains(action)) throw new IOException("NOTEBOOK_ACTION_UNAVAILABLE");
            try { service.island.actionIntent(saved.notificationTag, action).send(); }
            catch (PendingIntent.CanceledException unavailable) {
                throw new IOException("NOTEBOOK_ACTION_UNAVAILABLE", unavailable);
            }
            return;
        }
        if ("save".equals(action)) {
            service.save(service.pending);
        } else {
            service.performAction(service, service.pending, action);
        }
    }

    private void saveFromIsland(Pending item) throws IOException {
        beginSave(item);
        saves.execute(() -> {
            try { writeSave(item); }
            catch (IOException ignored) { /* The shared save path reports errors on the island. */ }
        });
    }

    static NotebookStore.Entry savePending() throws IOException {
        var service = active;
        if (service == null) throw new IOException("NOTEBOOK_SCREENSHOT_SERVICE_UNAVAILABLE");
        return service.save(service.pending);
    }

    private NotebookStore.Entry save(Pending item) throws IOException {
        beginSave(item);
        return writeSave(item);
    }

    private void beginSave(Pending item) throws IOException {
        synchronized (LOCK) {
            requireAvailable(item);
            item.saving = true;
        }
    }

    private void requireAvailable(Pending item) throws IOException {
        if (item == null || pending != item || !item.actionable()) {
            throw new IOException("NO_PENDING_SCREENSHOT");
        }
    }

    private NotebookStore.Entry writeSave(Pending item) throws IOException {
        try {
            var store = new NotebookStore(this);
            var saved = store.save(item.capture.uri(), item.title(), item.category(),
                    item.source.packageName(), item.source.label(), item.source.url(), item.capture.capturedAt());
            enrichment.execute(() -> {
                enrich(store, saved);
                });
            main.post(() -> {
                synchronized (LOCK) {
                    if (pending != item) return;
                    item.savedId = saved.id;
                    item.saving = false;
                    item.actionTaken = true;
                    state = "saved";
                }
                long deadline = SystemClock.elapsedRealtime() + SAVED_ACTIONS_MS;
                if (postIsland(item, item.title(), "", SAVED_ACTIONS,
                        island.receiptIntent(item.notificationTag, saved.id), deadline)) {
                    setTimeout(() -> clear("saved", null), Math.max(0, deadline - SystemClock.elapsedRealtime()));
                }
            });
            NotebookSourceJob.onSave(this, saved);
            return saved;
        } catch (IOException | RuntimeException failure) {
            main.post(() -> {
                synchronized (LOCK) {
                    item.saving = false;
                    if (pending != item) return;
                    state = "save_failed";
                }
                showOptions(item);
            });
            throw new IOException("SCREENSHOT_SAVE_FAILED", failure);
        }
    }

    private void enrich(NotebookStore store, NotebookStore.Entry saved) {
        if (!saved.summary.isEmpty()) return;
        try {
            var lists = store.filingLists(saved.id);
            var details = analysis.identify(analysis.encodeScreenshot(saved.imageUri),
                    saved.sourceLabel, lists);
            store.enrich(saved.id, details.title(), details.summary(), lists, details.listId());
        } catch (Exception failure) {
            Log.i(TAG, "Saved screenshot enrichment unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private void setTimeout(Runnable action, long delay) {
        cancelTimeout();
        timeout = action;
        main.postDelayed(action, delay);
    }

    private void cancelTimeout() {
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
    }

    private void dismiss() {
        clear(observer == null ? "idle" : "watching", null);
    }

    private void clear(String nextState, String error) {
        cancelTimeout();
        if (islandWorker != null) islandWorker.removeCallbacks(readIslandState);
        synchronized (LOCK) {
            pending = null;
            state = nextState;
            islandError = error;
        }
        if (notificationTag != null) {
            island.cancel(notificationTag);
            notificationTag = null;
        }
    }
}
