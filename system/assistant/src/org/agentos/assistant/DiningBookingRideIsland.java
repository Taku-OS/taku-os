package org.agentos.assistant;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;

import org.json.*;

/** The same ROM assistant card and two suggestion buttons; no custom window. */
final class DiningBookingRideIsland {
    static final String ACTION = "org.agentos.assistant.DINING_RIDE_ISLAND";
    private static final String TAG = "taku-dining-ride";
    private static final int ID = 2807;
    private static String fingerprint = "", visibleToken = "";

    static synchronized void show(Context c, JSONObject e) throws Exception {
        if (DiningRideInputActivity.isShowing()) return;
        JSONObject meal = e.getJSONObject("meal");
        String title = "Take an Uber to dinner?";
        String body =
                meal.getString("restaurant")
                        + "\n"
                        + DiningBookingRideUi.when(meal)
                        + " · "
                        + meal.getString("zone")
                        + "\n"
                        + "Confirm your pickup address and departure time. Place the order in"
                        + " Uber.";
        String phase = e.optString("phase");
        if (phase.equals("failed"))
            body += "\nPreparation did not start. Your entered plan is saved.";
        if (phase.equals("collecting_input") || phase.equals("input_ready"))
            body += "\nContinue your saved pickup and departure form.";
        if (phase.equals("input_cancelled"))
            body += "\nNo preparation was started. Your draft is saved.";
        if (phase.equals("ended"))
            body +=
                    "\n"
                        + "The previous attempt ended. Existing Uber orders are not verified."
                        + " Review before preparing again.";
        String token = "dining-ride:" + e.getString("id") + ":" + e.getString("key");
        JSONArray suggestions = new JSONArray().put("confirm").put("edit"),
                actions = new JSONArray(suggestions.toString()).put("dismiss");
        JSONObject expanded =
                new JSONObject()
                        .put("template", "assistant")
                        .put("title", title)
                        .put("body", body)
                        .put("steps", new JSONArray())
                        .put("actions", actions)
                        .put("primaryAction", "edit")
                        .put(
                                "assistant",
                                new JSONObject()
                                        .put("token", token)
                                        .put("phase", "message")
                                        .put("heightDp", 220)
                                        .put("suggestions", suggestions));
        JSONObject payload =
                new JSONObject()
                        .put("version", 1)
                        .put("category", "assistant")
                        .put("compact", "hidden")
                        .put("relevance", 1)
                        .put("statusBar", "auto")
                        .put("islandTimeoutS", 28800)
                        .put(
                                "bigIsland",
                                new JSONObject()
                                        .put(
                                                "leading",
                                                new JSONObject()
                                                        .put("type", "text")
                                                        .put("text", "Taku")))
                        .put("expanded", expanded);
        String value = payload.toString();
        if (value.equals(fingerprint)) return;
        if (c.checkSelfPermission("org.agentos.permission.POST_ISLAND")
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new java.io.IOException("DINING_ISLAND_PERMISSION_UNAVAILABLE");
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
                new NotificationChannel(
                        "codex_tasks", "Taku phone task", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder n =
                new Notification.Builder(c, "codex_tasks")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setCategory(Notification.CATEGORY_SERVICE)
                        .setOnlyAlertOnce(true)
                        .setOngoing(true);
        Bundle mapped = new Bundle();
        for (String action : new String[] {"confirm", "edit", "dismiss"}) {
            String label =
                    action.equals("confirm")
                            ? "Confirm"
                            : action.equals("edit") ? "Edit" : "Dismiss";
            Notification.Action button =
                    new Notification.Action.Builder(
                                    (android.graphics.drawable.Icon) null,
                                    label,
                                    decision(c, e, action))
                            .build();
            mapped.putParcelable(action, button);
            n.addAction(button);
        }
        n.setContentIntent(decision(c, e, "edit"));
        Bundle extras = new Bundle();
        extras.putString("taku.island.v1", payload.toString());
        extras.putBundle("taku.island.actions", mapped);
        n.addExtras(extras);
        manager.notify(TAG, ID, n.build());
        fingerprint = value;
        visibleToken = token;
        expand(c, token, 0);
    }

    private static PendingIntent decision(Context c, JSONObject e, String action) throws Exception {
        Intent intent =
                new Intent(c, TaskNotificationReceiver.class)
                        .setAction(ACTION)
                        .setData(
                                new Uri.Builder()
                                        .scheme("taku-ride")
                                        .authority(action)
                                        .appendPath(e.getString("id"))
                                        .appendPath(e.getString("key"))
                                        .build())
                        .putExtra("watchId", e.getString("watchId"))
                        .putExtra("key", e.getString("key"))
                        .putExtra("action", action);
        return PendingIntent.getBroadcast(
                c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean receive(BroadcastReceiver receiver, Context c, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return false;
        var pending = receiver.goAsync();
        DiningBookingRide.IO.execute(
                () -> {
                    try {
                        DiningBookingRide.call(
                                c,
                                "dining/poll/ride-respond",
                                new JSONObject()
                                        .put("id", intent.getStringExtra("watchId"))
                                        .put("key", intent.getStringExtra("key"))
                                        .put("action", intent.getStringExtra("action")));
                    } catch (Exception rejected) {
                        android.util.Log.w(
                                "TakuDining", "Ride action rejected: " + rejected.getMessage());
                    } finally {
                        if (pending != null) pending.finish();
                    }
                });
        return true;
    }

    static synchronized void hide(Context c) {
        c.getSystemService(NotificationManager.class).cancel(TAG, ID);
        fingerprint = "";
        visibleToken = "";
    }

    private static void expand(Context c, String token, int attempt) {
        new Handler(Looper.getMainLooper())
                .postDelayed(
                        () -> {
                            if (!token.equals(visibleToken)) return;
                            try {
                                Bundle b =
                                        c.getContentResolver()
                                                .call(
                                                        Uri.parse(
                                                                "content://com.android.systemui.island"),
                                                        "state",
                                                        null,
                                                        null);
                                JSONObject state =
                                        new JSONObject(
                                                b == null ? "{}" : b.getString("json", "{}"));
                                JSONArray items = state.optJSONArray("activities");
                                if (items != null)
                                    for (int i = 0; i < items.length(); i++) {
                                        JSONObject item = items.getJSONObject(i),
                                                payload = item.optJSONObject("payload"),
                                                expanded =
                                                        payload == null
                                                                ? null
                                                                : payload.optJSONObject("expanded"),
                                                assistant =
                                                        expanded == null
                                                                ? null
                                                                : expanded.optJSONObject(
                                                                        "assistant");
                                        if (assistant != null
                                                && token.equals(assistant.optString("token"))
                                                && "org.agentos.assistant"
                                                        .equals(item.optString("package"))) {
                                            c.getContentResolver()
                                                    .call(
                                                            Uri.parse(
                                                                    "content://com.android.systemui.island"),
                                                            "expand",
                                                            item.getString("key"),
                                                            null);
                                            return;
                                        }
                                    }
                            } catch (Exception unavailable) {
                            }
                            if (attempt < 20) expand(c, token, attempt + 1);
                        },
                        attempt == 0 ? 50 : 150);
    }
}
