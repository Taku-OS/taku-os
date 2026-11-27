package org.agentos.assistant;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;

import org.json.*;

/** Publishes the Uber preparation offer with its native actions. */
final class DiningBookingRideIsland {
    static final String ACTION = "org.agentos.assistant.DINING_RIDE_ISLAND";
    private static final String TAG = "taku-dining-ride";
    private static final int ID = 2807;
    private static volatile String fingerprint = "";

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
        JSONArray actions = new JSONArray().put("confirm").put("edit").put("dismiss");
        JSONObject payload = DiningFollowupIsland.payload(title, body, actions, "edit");
        String value = payload + "\n" + e.getString("id") + "\n" + e.getString("key");
        if (value.equals(fingerprint)) return;
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
        extras.putString(DiningFollowupIsland.EXTRA, payload.toString());
        extras.putBundle("taku.island.actions", mapped);
        n.addExtras(extras);
        manager.notify(TAG, ID, n.build());
        fingerprint = value;
        DiningFollowupIsland.expand(c, TAG, ID, () -> fingerprint.equals(value));
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
    }
}
