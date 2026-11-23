// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.taku.tryon.common.IslandPublisher;

/** Business callbacks shared by the normal notification and an optional host-issued presentation. */
final class LiveFrameProgress implements AutoCloseable {
    private static final String ID = "live-observer";
    private final Context context;
    private final IslandPublisher publisher;
    private final LiveFrameProgressHost host;
    private final Runnable cancel;
    private Runnable retry, select;
    private String failure, callbackKey;
    private boolean retryEnabled, closed, cancelled;
    private long callbackRevision;
    private Map<String, Runnable> callbacks = Map.of();

    LiveFrameProgress(Context context, Runnable cancel) {
        this(context, null, cancel);
    }
    LiveFrameProgress(Context context, LiveFrameProgressHost.Session session, Runnable cancel) {
        this.context = context; this.cancel = cancel;
        publisher = new IslandPublisher(context, "live_outfit", "Live outfit recognition");
        host = LiveFrameProgressHost.create(context, session, new LiveFrameProgressHost.Listener() {
            public void action(String id) { dispatch(id, callbackRevision); }
            public void event(String type) {
                if ("presentation_dismissed".equals(type) || "presentation_expired".equals(type)
                        || "presentation_error".equals(type)) requestCancel();
            }
        });
    }
    void show() {
        if (closed) return;
        failure = null; retry = select = null; retryEnabled = false;
        post("Matching…", "Matching…", "Checking the presenter's top.", true,
            Map.of("cancel", this::requestCancel), List.<String[]>of(new String[]{"cancel", "Cancel"}));
    }
    void showConfirmation(Runnable start) {
        if (closed) return;
        post("Try on?", "Try on this LIVE outfit?", ReminderConnection.CONSENT_SUMMARY
                + ". The current LIVE image and front-camera video are sent to cloud AI only after Try on.", false,
            Map.of("try_on", start, "cancel", this::requestCancel),
            List.of(new String[]{"try_on", "Try on"}, new String[]{"cancel", "Cancel"}));
    }
    void showFailure(String message, Runnable retryAction) { showFailure(message, retryAction, null); }
    void showFailure(String message, Runnable retryAction, Runnable selectAction) {
        if (closed) return;
        failure = message == null || message.isBlank() ? "Please try again." : message;
        retry = retryAction; select = selectAction; retryEnabled = retryAction != null || selectAction != null;
        renderFailure();
    }
    void setRetryEnabled(boolean enabled) {
        if (closed || failure == null) return;
        boolean ready = enabled && (retry != null || select != null);
        if (ready != retryEnabled) { retryEnabled = ready; renderFailure(); }
    }
    boolean hasSelectionAction() { return select != null; }
    private void renderFailure() {
        var callbacks = new java.util.HashMap<String, Runnable>();
        var labels = new ArrayList<String[]>();
        String title = retryEnabled && select != null ? "Select an outfit" : "Couldn't start try-on";
        if (retryEnabled && select != null) {
            callbacks.put("select", () -> {
                if (retryEnabled && select != null) {
                    Runnable action = select; retry = select = null; retryEnabled = false; action.run();
                }
            });
            labels.add(new String[]{"select", "Select outfit"});
        }
        if (retryEnabled && retry != null) {
            callbacks.put("retry", () -> {
                if (retryEnabled && retry != null) {
                    Runnable action = retry; retry = select = null; retryEnabled = false; action.run();
                }
            });
            labels.add(new String[]{"retry", "Try again"});
        }
        callbacks.put("close", this::requestCancel); labels.add(new String[]{"close", "Close"});
        post(title, title, failure + (!retryEnabled && retry != null ? " Finishing the previous check…" : ""),
            false, callbacks, labels);
    }
    private void post(String compact, String title, String body, boolean ongoing,
                      Map<String, Runnable> callbacks, List<String[]> labels) {
        ObserverActions.clear(callbackKey); callbackKey = null;
        this.callbacks = Map.copyOf(callbacks); final long revision = ++callbackRevision;
        try {
            if (host != null) {
                var hostActions = new ArrayList<LiveFrameProgressHost.Action>();
                for (int i = 0; i < labels.size(); i++) {
                    String[] label = labels.get(i);
                    hostActions.add(new LiveFrameProgressHost.Action(label[0], label[1], i == 0));
                }
                host.show(new LiveFrameProgressHost.Card(compact, title, body, ongoing, hostActions));
                return;
            }
            var guarded = new java.util.HashMap<String, Runnable>();
            for (String id : callbacks.keySet()) guarded.put(id, () -> dispatch(id, revision));
            callbackKey = ObserverActions.register(guarded);
            var actions = new ArrayList<IslandPublisher.Action>();
            for (String[] label : labels)
                actions.add(new IslandPublisher.Action(label[0], label[1], ObserverActions.pending(context, callbackKey, label[0])));
            PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (!publisher.post(ID, compact, title, body, ongoing, actions, labels.get(0)[0], open, !ongoing)) requestCancel();
        } catch (RuntimeException unavailable) {
            requestCancel();
        }
    }
    private void dispatch(String id, long revision) {
        if (closed || cancelled || revision != callbackRevision) return;
        Runnable action = callbacks.get(id);
        if (action == null) return;
        callbacks = Map.of(); ObserverActions.clear(callbackKey); callbackKey = null;
        action.run();
    }
    private void requestCancel() {
        if (closed || cancelled) return;
        cancelled = true; retry = select = null;
        callbacks = Map.of(); ObserverActions.clear(callbackKey); callbackKey = null;
        if (cancel != null) cancel.run();
    }
    int accessibilityWindowId() { return -1; }
    @Override public void close() {
        if (closed) return;
        closed = true; retry = select = null; callbacks = Map.of();
        ObserverActions.clear(callbackKey); publisher.dismiss(ID);
        if (host != null) host.close();
    }
}
