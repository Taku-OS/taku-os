package org.agentos.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.io.IOException;

/** Presents the durable dining decision using the existing SystemUI island template. */
final class DiningPollIsland {
    private static final String ACTION = "org.agentos.assistant.DINING_POLL_ISLAND";
    private static final String TAG = "taku-dining-booking";
    private static final String CHANNEL = "codex_tasks";
    private static final String CARD = "taku.dining.booking";
    private static final int ID = 2805;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Uri ISLAND = Uri.parse("content://com.android.systemui.island");

    private DiningPollIsland() {}

    static void postFailure(Context context, String id) {
        MAIN.post(() -> render(context, id));
    }

    static void render(Context context, String id) {
        if (DiningPollBookingConfirmation.busy()) return;
        try {
            JSONObject watch = DiningPollBooking.require(context, id);
            String key = DiningPollConfirmationState.offer(watch);
            if (DiningPollConfirmationState.refreshableFailure(watch)
                    || DiningPollConfirmationState.mayOffer(watch, key)) {
                publish(
                        context,
                        DiningPollIslandModel.presentation(
                                watch, DiningPollConfirmationState.refreshableFailure(watch)));
            }
        } catch (Exception unavailable) {
            android.util.Log.w("TakuDining", "Dining poll island unavailable");
        }
    }

    static String visibleKey(Context context) {
        try {
            JSONObject card = visible(context);
            return card == null ? "" : card.optString("key");
        } catch (Exception unavailable) {
            return "";
        }
    }

    static void hide(Context context, String key) {
        if (!key.isEmpty() && key.equals(visibleKey(context))) manager(context).cancel(TAG, ID);
    }

    static void hideAndWait(Context context, String key) throws Exception {
        StatusBarNotification posted = notification(context);
        JSONObject card = savedCard(posted);
        if (card == null || !key.equals(card.optString("key"))) return;
        String notificationKey = posted.getKey();
        manager(context).cancel(TAG, ID);
        long deadline = SystemClock.elapsedRealtime() + 2_000;
        while (islandContains(context, notificationKey)) {
            if (SystemClock.elapsedRealtime() >= deadline)
                throw new IOException("POLL_ISLAND_BUSY");
            Thread.sleep(35);
        }
    }

    private static NotificationManager manager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }

    private static JSONObject visible(Context context) throws Exception {
        return savedCard(notification(context));
    }

    private static StatusBarNotification notification(Context context) {
        for (StatusBarNotification posted : manager(context).getActiveNotifications()) {
            if (posted.getId() == ID
                    && TAG.equals(posted.getTag())
                    && context.getPackageName().equals(posted.getPackageName())) return posted;
        }
        return null;
    }

    private static JSONObject savedCard(StatusBarNotification posted) throws Exception {
        if (posted == null) return null;
        String saved = posted.getNotification().extras.getString(CARD);
        return saved == null ? null : new JSONObject(saved);
    }

    private static synchronized void publish(Context context, JSONObject card) throws Exception {
        JSONObject previous = visible(context);
        if (previous != null && card.toString().equals(previous.toString())) return;
        NotificationManager manager = manager(context);
        manager.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL, "Taku phone task", NotificationManager.IMPORTANCE_LOW));
        JSONObject payload = card.getJSONObject("payload");
        JSONObject expanded = payload.getJSONObject("expanded");
        Notification.Builder notification =
                new Notification.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(expanded.getString("title"))
                        .setContentText(expanded.getString("body"))
                        .setCategory(Notification.CATEGORY_SERVICE)
                        .setOnlyAlertOnce(true)
                        .setOngoing(true);
        JSONObject labels = card.getJSONObject("actions");
        Bundle actions = new Bundle();
        for (var keys = labels.keys(); keys.hasNext(); ) {
            String id = keys.next();
            Notification.Action action =
                    new Notification.Action.Builder(
                                    (android.graphics.drawable.Icon) null,
                                    labels.getString(id),
                                    decision(context, card, id))
                            .build();
            actions.putParcelable(id, action);
            notification.addAction(action);
        }
        Intent open =
                new Intent()
                        .setClassName("org.agentos.agent", "org.agentos.agent.MainActivity")
                        .setData(
                                new Uri.Builder()
                                        .scheme("taku-dining")
                                        .authority("open")
                                        .appendPath(card.getString("token"))
                                        .build())
                        .putExtra("threadId", card.getString("threadId"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent link =
                PendingIntent.getActivity(
                        context,
                        0,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        actions.putParcelable(
                "open",
                new Notification.Action.Builder(
                                (android.graphics.drawable.Icon) null, "Open conversation", link)
                        .build());
        notification.setContentIntent(link);
        Bundle extras = new Bundle();
        extras.putString("taku.island.v2", payload.toString());
        extras.putBundle("taku.island.actions", actions);
        extras.putString(CARD, card.toString());
        manager.notify(TAG, ID, notification.addExtras(extras).build());
        String token = card.getString("token");
        Context app = context.getApplicationContext();
        DiningFollowupIsland.expand(
                app,
                TAG,
                ID,
                () -> {
                    try {
                        JSONObject current = visible(app);
                        return current != null && token.equals(current.optString("token"));
                    } catch (Exception unavailable) {
                        return false;
                    }
                });
    }

    private static PendingIntent decision(Context context, JSONObject card, String action)
            throws Exception {
        if ("details".equals(action)) {
            Intent details =
                    new Intent(context, DiningPollBookingDetailsActivity.class)
                            .setData(
                                    new Uri.Builder()
                                            .scheme("taku-dining")
                                            .authority("details")
                                            .appendPath(card.getString("watchId"))
                                            .appendPath(card.getString("key"))
                                            .appendPath(card.getString("revision"))
                                            .build())
                            .putExtra("watchId", card.getString("watchId"))
                            .putExtra("decisionKey", card.getString("key"))
                            .putExtra("decisionRevision", card.getString("revision"));
            return PendingIntent.getActivity(
                    context,
                    0,
                    details,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        Intent intent =
                new Intent(context, TaskNotificationReceiver.class)
                        .setAction(ACTION)
                        .setData(
                                new Uri.Builder()
                                        .scheme("taku-dining")
                                        .authority(action)
                                        .appendPath(card.getString("watchId"))
                                        .appendPath(card.getString("key"))
                                        .appendPath(card.getString("revision"))
                                        .build())
                        .putExtra("watchId", card.getString("watchId"))
                        .putExtra("decisionKey", card.getString("key"))
                        .putExtra("decisionAction", action)
                        .putExtra("decisionRevision", card.getString("revision"));
        return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean receive(BroadcastReceiver receiver, Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return false;
        BroadcastReceiver.PendingResult pending = receiver.goAsync();
        DiningPollBooking.execute(
                () -> {
                    try {
                        String id = intent.getStringExtra("watchId");
                        String key = intent.getStringExtra("decisionKey");
                        String action = intent.getStringExtra("decisionAction");
                        JSONObject watch = DiningPollBooking.require(context, id);
                        DiningPollIslandModel.validateAction(
                                watch, key, action, intent.getStringExtra("decisionRevision"));
                        JSONObject args = new JSONObject().put("id", id).put("key", key);
                        String method =
                                switch (action) {
                                    case "retry" -> "dining/poll/retry-confirmation";
                                    case "dismiss" -> "dining/poll/dismiss-confirmation";
                                    default -> "dining/poll/respond";
                                };
                        if ("book".equals(action) || "decline".equals(action))
                            args.put("accepted", "book".equals(action));
                        DiningPollBooking.operate(context, method, args);
                    } catch (Exception rejected) {
                        android.util.Log.w("TakuDining", "Dining decision could not be applied");
                    } finally {
                        pending.finish();
                    }
                });
        return true;
    }

    private static boolean islandContains(Context context, String notificationKey)
            throws Exception {
        Bundle bundle = context.getContentResolver().call(ISLAND, "get", notificationKey, null);
        JSONObject result = new JSONObject(bundle == null ? "{}" : bundle.getString("json", "{}"));
        if (!result.optBoolean("ok")) {
            if ("not_found".equals(result.optString("error"))) return false;
            throw new IOException("DINING_ISLAND_UNAVAILABLE");
        }
        JSONObject activity = result.optJSONObject("activity");
        return activity != null
                && notificationKey.equals(activity.optString("key"))
                && context.getPackageName().equals(activity.optString("package"));
    }
}
