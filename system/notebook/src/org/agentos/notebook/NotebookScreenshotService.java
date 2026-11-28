package org.agentos.notebook;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.res.Configuration;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.agentos.ai.NotebookSocialSource;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class NotebookScreenshotService extends AccessibilityService {
    private static final String TAG = "NotebookScreenshot";
    private static final Object LOCK = new Object();
    private static final long READING_DISPLAY_MS = 550;
    private static final List<NotebookScreenshotOverlay.Action> ACTIONS = List.of(
            new NotebookScreenshotOverlay.Action("save", "Save"),
            new NotebookScreenshotOverlay.Action("send", "Send to…"),
            new NotebookScreenshotOverlay.Action("edit", "Edit"));
    private static final List<NotebookScreenshotOverlay.Action> SAVED_ACTIONS = List.of(
            new NotebookScreenshotOverlay.Action("go_to_notebook", "Go to Notebook"),
            new NotebookScreenshotOverlay.Action("change_list", "Change List"),
            new NotebookScreenshotOverlay.Action("add_note", "Add note"));
    private static final List<String> ACTION_IDS = ACTIONS.stream().map(NotebookScreenshotOverlay.Action::id).toList();
    private static volatile NotebookScreenshotService active;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread mediaThread = new HandlerThread("notebook-screenshot-media");
    private final java.util.concurrent.ExecutorService saves = Executors.newSingleThreadExecutor();
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (prefs, key) -> main.post(this::updateObserver);
    private volatile SystemScreenshots observer;
    private NotebookScreenshotOverlay overlay;
    private volatile String overlayError;
    private volatile Pending pending;
    private volatile String state = "idle";
    private Runnable timeout;
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
        final SystemScreenshots.Capture capture;
        final ScreenSource source;
        final String title;
        final String sourceLabel;
        long readingAt;
        boolean ready;
        volatile boolean saving;
        volatile boolean actionTaken;
        String savedId;
        boolean editing;
        boolean editorBusy;
        boolean actionable() {
            return ready && !saving && !actionTaken;
        }
        Pending(SystemScreenshots.Capture capture, ScreenSource source, String sourceLabel) {
            this.capture = capture;
            this.source = source;
            this.sourceLabel = sourceLabel;
            String visible = NotebookAnalysis.screenTitle(source);
            title = visible.isEmpty() ? sourceLabel + " screenshot" : visible;
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
                .put("overlayHost", service == null ? "disconnected" : "accessibility")
                .put("observing", service != null && service.observer != null);
        if (service != null) result.put("overlay", service.overlayStatus());
        synchronized (LOCK) {
            result.put("state", service == null ? "disconnected" : service.state);
            if (service != null && service.overlayError != null) result.put("overlayError", service.overlayError);
            Pending item = service == null ? null : service.pending;
            if (item != null) {
                result.put("capture", new JSONObject().put("imageUri", item.capture.uri().toString())
                        .put("capturedAt", item.capture.capturedAt()).put("title", item.title)
                        .put("sourcePackage", item.source.packageName())
                        .put("sourceUrl", item.source.url()).put("saving", item.saving));
                if (item.savedId != null) {
                    result.put("savedEntryId", item.savedId);
                } else if (item.actionable()) {
                    result.put("actions", new JSONArray(ACTION_IDS));
                    result.put("actionSource", "fixed");
                }
            }
        }
        return result;
    }

    private JSONObject overlayStatus() throws JSONException {
        if (Looper.myLooper() == main.getLooper()) return overlay.snapshot();
        var query = new FutureTask<>(overlay::snapshot);
        main.post(query);
        try {
            return query.get(800, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException unavailable) {
            return null;
        } finally {
            query.cancel(false);
        }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        mediaThread.start();
        overlay = new NotebookScreenshotOverlay(this);
        active = this;
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

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (overlay != null) overlay.configurationChanged();
    }

    @Override public void onDestroy() {
        active = null;
        settings(this).unregisterOnSharedPreferenceChangeListener(settingsListener);
        dismiss();
        var media = observer;
        observer = null;
        if (media != null) media.close();
        mediaThread.quitSafely();
        saves.shutdown();
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
            saves.execute(() -> NotebookSourceJob.resumeAfterLeavingSource(this));
        }
    }

    private ScreenSource sourceFor(long capturedAt) {
        for (var history = sources.descendingIterator(); history.hasNext();) {
            SourceChange seen = history.next();
            if (seen.at() > capturedAt) continue;
            if (screenshotSourceLabel(seen.source().packageName()) == null) return seen.source();
            ScreenSource current = ScreenSource.read(this, false, true);
            if (!seen.source().matches(current)) return seen.source();
            if (lastXSnapshot != null && lastXSnapshot.source().matches(current)
                    && current.windowId() == lastXChangedWindow && lastXSnapshot.at() >= lastXChangedAt
                    && lastXSnapshot.at() <= capturedAt) {
                return current.withPost(lastXSnapshot.source().post());
            }
            return current.withPost(null);
        }
        return ScreenSource.UNKNOWN;
    }

    private void sampleXSource() {
        if (observer == null) return;
        ScreenSource source = ScreenSource.read(this, false, true);
        if ("com.twitter.android".equals(source.packageName()) && source.windowId() == lastXChangedWindow) {
            lastXSnapshot = new SourceChange(System.currentTimeMillis(), source);
        }
    }

    private void accept(SystemScreenshots.Capture capture) {
        if (!canObserve()) { updateObserver(); return; }
        if (observer == null || !getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isDeviceLocked()) return;
        if (pending != null && pending.editing) return;
        ScreenSource source = sourceFor(capture.capturedAt());
        String sourceLabel = screenshotSourceLabel(source.packageName());
        if (sourceLabel == null) return;
        dismiss();
        Pending item = new Pending(capture, source, sourceLabel);
        synchronized (LOCK) {
            pending = item;
            state = "reading";
        }
        try {
            overlay.reading();
            item.readingAt = SystemClock.elapsedRealtime();
            Log.i(TAG, "Screenshot reading trace=" + item.traceId
                    + " captureToReadingMs=" + (System.currentTimeMillis() - capture.capturedAt())
                    + " mediaToReadingMs=" + (item.readingAt - capture.mediaReadyAt()));
            setTimeout(() -> showOptions(item), READING_DISPLAY_MS);
        } catch (RuntimeException failure) {
            overlayUnavailable("overlay_failed", failure);
        }
    }

    private static String screenshotSourceLabel(String packageName) {
        if ("com.discord".equals(packageName)) return "Discord";
        var source = NotebookSocialSource.byPackage(packageName);
        return source == null ? null : source.displayName();
    }

    private void showOptions(Pending item) {
        if (pending != item || !"reading".equals(state)) return;
        try {
            synchronized (LOCK) {
                item.ready = true;
                state = "ready";
            }
            showCard(item, item.title, ACTIONS);
            Log.i(TAG, "Screenshot actions trace=" + item.traceId
                    + " readingToActionsMs=" + (SystemClock.elapsedRealtime() - item.readingAt));
        } catch (RuntimeException failure) {
            overlayUnavailable("overlay_failed", failure);
        }
    }

    private void overlayUnavailable(String reason, RuntimeException failure) {
        Log.w(TAG, "Screenshot overlay unavailable: " + reason, failure);
        clear("saved".equals(state) ? "saved" : "surface_unavailable", reason);
    }

    private void showCard(Pending item, String title, List<NotebookScreenshotOverlay.Action> actions) {
        overlay.card(title, actions, action -> handleOverlayAction(item, action), () -> {
            if (pending == item && !item.saving && !item.editing) dismiss();
        });
    }

    private void handleOverlayAction(Pending item, String action) {
        if (pending != item) return;
        try {
            if (item.savedId != null) {
                switch (action) {
                    case "go_to_notebook" -> openSavedList(item);
                    case "change_list" -> openListPicker(item);
                    case "add_note" -> openNoteEditor(item);
                    default -> throw new IOException("NOTEBOOK_ACTION_UNAVAILABLE");
                }
            } else if ("save".equals(action)) {
                saveFromOverlay(item);
            } else {
                performAction(this, item, action);
            }
        } catch (IOException | RuntimeException unavailable) {
            Log.w(TAG, "Could not run screenshot action " + action, unavailable);
            Toast.makeText(this, "Action unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private void performAction(Context caller, Pending item, String action) throws IOException {
        synchronized (LOCK) {
            requireAvailable(item);
            if (!"preview".equals(action) && !ACTION_IDS.contains(action)) {
                throw new IOException("NOTEBOOK_ACTION_UNAVAILABLE");
            }
            try {
                NotebookActions.open(caller, action, item.capture, item.source, item.title);
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
        if ("save".equals(action)) {
            service.save(service.pending);
        } else if ("go_to_notebook".equals(action)) {
            service.openSavedList(service.pending);
        } else {
            service.performAction(service, service.pending, action);
        }
    }

    private void saveFromOverlay(Pending item) throws IOException {
        beginSave(item);
        saves.execute(() -> {
            try { writeSave(item); }
            catch (IOException ignored) { /* The save path reports the failure on the overlay. */ }
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
        main.post(() -> {
            if (pending != item) return;
            cancelTimeout();
            state = "saving";
            try {
                overlay.status("Saving");
            } catch (RuntimeException failure) {
                Log.w(TAG, "Could not display saving status", failure);
            }
        });
    }

    private void requireAvailable(Pending item) throws IOException {
        if (item == null || pending != item || !item.actionable()) {
            throw new IOException("NO_PENDING_SCREENSHOT");
        }
    }

    private NotebookStore.Entry writeSave(Pending item) throws IOException {
        var store = new NotebookStore(this);
        NotebookStore.Entry saved;
        try {
            saved = store.saveScreenshot(item.capture.uri(), item.title,
                    item.source.packageName(), item.sourceLabel, item.source.url(), item.capture.capturedAt(),
                    NotebookPostEvidence.fromCapturedXPost(item.source.post()));
        } catch (IOException | RuntimeException failure) {
            main.post(() -> showSaveFailure(item));
            throw new IOException("SCREENSHOT_SAVE_FAILED", failure);
        }

        main.post(() -> showSaved(item, saved));
        try {
            if (saved.filingPending || saved.summary.isEmpty()) NotebookFilingJob.schedule(this);
            else NotebookArrivalIntent.prepare(this, saved);
            NotebookSourceJob.onSave(this, saved);
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Post-save background work unavailable", unavailable);
        }
        return saved;
    }

    private void showSaved(Pending item, NotebookStore.Entry saved) {
        synchronized (LOCK) {
            if (pending != item) return;
            item.savedId = saved.id;
            item.saving = false;
            item.actionTaken = true;
            state = "saved";
        }
        try {
            showCard(item, item.title, SAVED_ACTIONS);
            Log.i(TAG, "Screenshot saved trace=" + item.traceId + " entry=" + saved.id);
        } catch (RuntimeException failure) {
            overlayUnavailable("saved_overlay_failed", failure);
        }
    }

    private void showSaveFailure(Pending item) {
        synchronized (LOCK) {
            if (pending != item) return;
            item.saving = false;
            state = "save_failed";
        }
        try {
            showCard(item, "Saving failed", ACTIONS);
        } catch (RuntimeException failure) {
            overlayUnavailable("overlay_failed", failure);
        }
    }

    private void openSavedList(Pending item) throws IOException {
        synchronized (LOCK) {
            if (item == null || pending != item || item.savedId == null) {
                throw new IOException("NO_SAVED_SCREENSHOT");
            }
            startActivity(NotebookLibraryActivity.savedActionIntent(this, item.savedId, "go_to_notebook"));
        }
        main.post(() -> { if (pending == item) dismiss(); });
    }

    private void openListPicker(Pending item) {
        if (!beginEditing(item, "editing_list")) return;
        overlay.status("Loading lists");
        saves.execute(() -> {
            try {
                var store = new NotebookStore(this);
                var saved = store.get(item.savedId);
                if (saved == null) throw new IOException("ENTRY_NOT_FOUND");
                var choices = store.listLists().stream().map(list -> new NotebookScreenshotOverlay.ListChoice(
                        list.id, list.name, list.id.equals(saved.listId))).toList();
                main.post(() -> {
                    if (pending == item && item.editing) overlay.lists(choices,
                            list -> editSaved(item, null, list), () -> cancelEditing(item));
                });
            } catch (IOException | RuntimeException failure) {
                main.post(() -> {
                    if (pending != item) return;
                    cancelEditing(item);
                    Toast.makeText(this, "Could not open lists", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void openNoteEditor(Pending item) {
        if (!beginEditing(item, "editing_note")) return;
        try {
            overlay.note(note -> editSaved(item, note, null), () -> cancelEditing(item));
        } catch (RuntimeException failure) {
            cancelEditing(item);
            Log.w(TAG, "Could not open note editor", failure);
            Toast.makeText(this, "Could not open the note editor", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean beginEditing(Pending item, String nextState) {
        if (pending != item || item.editing) return false;
        cancelTimeout();
        item.editing = true;
        state = nextState;
        return true;
    }

    private void cancelEditing(Pending item) {
        if (pending != item || item.editorBusy) return;
        item.editing = false;
        state = "saved";
        showCard(item, item.title, SAVED_ACTIONS);
    }

    private void editSaved(Pending item, String note, String listId) {
        if (pending != item || !item.editing || item.editorBusy) return;
        item.editorBusy = true;
        saves.execute(() -> {
            try {
                var updated = new NotebookStore(this).editEntry(item.savedId, null, note, listId);
                Log.i(TAG, "Screenshot " + (listId == null ? "note added" : "list changed")
                        + " entry=" + item.savedId);
                main.post(() -> completeEditing(item));
                if (!updated.filingPending && !updated.summary.isEmpty()) NotebookArrivalIntent.prepare(this, updated);
            } catch (IOException | RuntimeException failure) {
                main.post(() -> {
                    if (pending != item) return;
                    item.editorBusy = false;
                    Toast.makeText(this, "Could not save changes", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void completeEditing(Pending item) {
        if (pending != item) return;
        dismiss();
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
        synchronized (LOCK) {
            pending = null;
            state = nextState;
            overlayError = error;
        }
        if (overlay != null) overlay.hide();
    }
}
