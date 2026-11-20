package org.agentos.assistant;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.agentos.surfaces.client.SurfaceClient;
import org.json.JSONArray;
import org.json.JSONObject;

/** Presents the broker's pending conversation questions while another app has the main screen. */
public final class ConversationQuestionCardsService extends Service implements SurfaceClient.Listener {
    private static final long REFRESH_MS = 1500;
    private final IBinder binding = new Binder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refresh;
    private final Map<String, Card> cards = new LinkedHashMap<>();
    private SurfaceClient surfaces;
    private boolean connected, reading, discardRead, stopped;

    private static final class Card {
        final String key;
        final String id;
        final BackgroundQuestion question;
        long revision;
        boolean shown, hidden, answering;

        Card(String key, BackgroundQuestion question) {
            this.key = key;
            this.id = "ask-" + key;
            this.question = question;
        }

        boolean canShow() { return !shown && !hidden && !answering; }
    }

    @Override public void onCreate() {
        super.onCreate();
        BackgroundScreenTasks.restore(this);
        surfaces = new SurfaceClient(this, this);
        main.post(refresh);
    }

    @Override public IBinder onBind(Intent intent) { return binding; }

    @Override public void onDestroy() {
        stopped = true;
        main.removeCallbacks(refresh);
        surfaces.close();
        super.onDestroy();
    }

    private void refresh() {
        main.postDelayed(refresh, REFRESH_MS);
        if (!otherAppHasFocus()) {
            withdrawAll();
            return;
        }
        if (reading) return;
        reading = true;
        discardRead = false;
        NativeClient.async(new JSONObject(Map.of("op", "pendingPhoneQuestions")), new NativeClient.Callback() {
            @Override public void ready(JSONObject value) {
                reading = false;
                if (stopped || discardRead) return;
                JSONArray pending = value.optJSONArray("requests");
                if (pending == null || !otherAppHasFocus()) withdrawAll();
                else reconcile(pending);
            }

            @Override public void failed(String message) {
                reading = false;
                if (!stopped && !discardRead) withdrawAll();
            }
        });
    }

    private boolean otherAppHasFocus() {
        if (!getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isDeviceLocked()) return false;
        try {
            Field displayId = ActivityManager.RunningTaskInfo.class.getField("displayId");
            for (ActivityManager.RunningTaskInfo task : getSystemService(ActivityManager.class).getRunningTasks(20)) {
                if (displayId.getInt(task) != Display.DEFAULT_DISPLAY || task.topActivity == null) continue;
                String foreground = task.topActivity.getPackageName();
                return !getPackageName().equals(foreground) && !"org.agentos.agent".equals(foreground);
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return false;
        }
        return false;
    }

    private void reconcile(JSONArray pending) {
        Set<String> live = new HashSet<>();
        LocaleList locales = getResources().getConfiguration().getLocales();
        Locale locale = locales.isEmpty() ? Locale.getDefault() : locales.get(0);
        for (int i = 0; i < pending.length(); i++) {
            JSONObject request = pending.optJSONObject(i);
            if (request == null) continue;
            JSONObject params = request.optJSONObject("params");
            String key = request.optString("key");
            if (key.isEmpty() || params == null || BackgroundScreenTasks.ownsThread(params.optString("threadId"))) continue;
            String id = "ask-" + key;
            try {
                if (!cards.containsKey(id)) {
                    cards.put(id, new Card(key, BackgroundQuestion.fromPending(request.getString("method"), params, locale)));
                }
                live.add(id);
            } catch (Exception invalid) { /* A malformed question cannot be presented or answered. */ }
        }
        var entries = cards.values().iterator();
        while (entries.hasNext()) {
            Card card = entries.next();
            if (live.contains(card.id)) continue;
            withdraw(card);
            entries.remove();
        }
        if (cards.values().stream().noneMatch(Card::canShow)) return;
        if (!connected) {
            try { surfaces.connect(); } catch (RuntimeException unavailable) { /* Retry on the next read. */ }
            return;
        }
        for (Card card : cards.values()) {
            if (!card.canShow()) continue;
            try {
                surfaces.publish(card.question.surface(card.id, ++card.revision));
                card.shown = true;
            } catch (Exception unavailable) { /* Retry only while the request remains pending. */ }
        }
    }

    @Override public void onConnected() {
        connected = true;
        discardRead = true;
    }

    @Override public void onEvent(String type, String id, long revision, String value) {
        if ("disconnected".equals(type)) {
            connected = false;
            discardRead = true;
            for (Card card : cards.values()) card.shown = false;
            return;
        }
        Card card = cards.get(id);
        if (card == null || revision != card.revision) return;
        if ("dismissed".equals(type) || "expired".equals(type)) {
            card.shown = false;
            card.hidden = true;
        } else if ("error".equals(type)) {
            card.shown = false;
        }
    }

    @Override public void onAction(SurfaceClient.ActionEvent event) {
        Card card = cards.get(event.id());
        if (stopped || card == null || !card.shown || card.answering || !otherAppHasFocus()) return;
        try {
            JSONObject response = card.question.response(event.actionId(), event.revision(), card.revision);
            if (response == null) return;
            card.answering = true;
            NativeClient.respond(card.key, response, new NativeClient.Callback() {
                @Override public void ready(JSONObject value) { answerFinished(card); }
                @Override public void failed(String message) { answerFinished(card); }
            });
        } catch (Exception invalid) { /* Ignore an action without a valid response. */ }
    }

    private void answerFinished(Card card) {
        if (stopped) return;
        // A read started before the reply could still contain the answered request.
        discardRead = true;
        card.answering = false;
        withdraw(card);
    }

    private void withdrawAll() {
        for (Card card : cards.values()) withdraw(card);
    }

    private void withdraw(Card card) {
        if (!card.shown) return;
        card.shown = false;
        try { surfaces.dismiss(card.id); } catch (Exception unavailable) { /* The host cleans up lost connections. */ }
    }
}
