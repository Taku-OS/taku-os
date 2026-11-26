package org.agentos.assistant;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;

import org.json.*;

import java.util.*;

/** Publishes the Calendar confirmation with its native actions. */
final class DiningBookingCalendarIsland {
    static final String ACTION = "org.agentos.assistant.DINING_CALENDAR_ISLAND";
    private static final String TAG = "taku-dining-calendar";
    private static final int ID = 2806;
    private static volatile String fingerprint = "";

    static synchronized void show(Context c, JSONObject entry) throws Exception {
        String phase = entry.getString("phase"),
                title =
                        phase.equals("saved")
                                ? "Added to Google Calendar"
                                : "Add to Google Calendar?";
        JSONObject meal = entry.getJSONObject("meal");
        String body =
                meal.getString("restaurant")
                        + "\n"
                        + DiningBookingCalendarPolicy.when(meal)
                        + " · "
                        + meal.getString("zone");
        JSONObject labels = new JSONObject();
        JSONArray actions = new JSONArray();
        if (phase.equals("calendars")) {
            title = "Choose a calendar";
            JSONArray calendars = entry.getJSONArray("calendars");
            for (int i = 0; i < calendars.length(); i++) {
                JSONObject choice = calendars.getJSONObject(i);
                String action = "select_" + choice.getLong("id");
                labels.put(action, choice.getString("account"));
                actions.put(action);
            }
            if (calendars.length() == 0) body += "\nNo writable Google Calendar is available.";
        } else if (!phase.equals("saved")) {
            labels.put("confirm", "Confirm").put("edit", "Edit");
            actions.put("confirm").put("edit");
            if (entry.has("calendarId"))
                for (int i = 0; i < entry.getJSONArray("calendars").length(); i++) {
                    JSONObject cal = entry.getJSONArray("calendars").getJSONObject(i);
                    if (cal.getLong("id") == entry.getLong("calendarId"))
                        body += "\n" + cal.getString("account");
                }
            if (phase.equals("error")) body += "\nThis could not be completed. Try again.";
        }
        labels.put("dismiss", "Dismiss");
        actions.put("dismiss");
        if (phase.equals("saved") && !entry.optBoolean("feedPublished"))
            body += "\nEvent saved. The Feed is still updating.";
        String primary =
                phase.equals("calendars") ? null : phase.equals("saved") ? "dismiss" : "edit";
        JSONObject payload = DiningFollowupIsland.payload(title, body, actions, primary);
        String value =
                payload
                        + "\n"
                        + labels
                        + "\n"
                        + entry.getString("id")
                        + "\n"
                        + entry.getString("key");
        if (fingerprint.equals(value)) return;
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
        for (var keys = labels.keys(); keys.hasNext(); ) {
            String action = keys.next();
            PendingIntent pending = decision(c, entry, action);
            Notification.Action button =
                    new Notification.Action.Builder(
                                    (android.graphics.drawable.Icon) null,
                                    labels.getString(action),
                                    pending)
                            .build();
            mapped.putParcelable(action, button);
            n.addAction(button);
        }
        if (primary != null) n.setContentIntent(decision(c, entry, primary));
        Bundle extras = new Bundle();
        extras.putString(DiningFollowupIsland.EXTRA, payload.toString());
        extras.putBundle("taku.island.actions", mapped);
        n.addExtras(extras);
        manager.notify(TAG, ID, n.build());
        fingerprint = value;
        DiningFollowupIsland.expand(c, TAG, ID, () -> fingerprint.equals(value));
    }

    private static PendingIntent decision(Context c, JSONObject e, String action) throws Exception {
        Intent i =
                new Intent(c, TaskNotificationReceiver.class)
                        .setAction(ACTION)
                        .setData(
                                new Uri.Builder()
                                        .scheme("taku-calendar")
                                        .authority(action)
                                        .appendPath(e.getString("id"))
                                        .appendPath(e.getString("key"))
                                        .build())
                        .putExtra("watchId", e.getString("watchId"))
                        .putExtra("key", e.getString("key"))
                        .putExtra("action", action);
        return PendingIntent.getBroadcast(
                c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean receive(BroadcastReceiver receiver, Context c, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return false;
        var pending = receiver.goAsync();
        DiningBookingCalendar.IO.execute(
                () -> {
                    try {
                        String action = intent.getStringExtra("action");
                        JSONObject args =
                                new JSONObject()
                                        .put("id", intent.getStringExtra("watchId"))
                                        .put("key", intent.getStringExtra("key"));
                        if (action != null && action.matches("select[_-][1-9][0-9]*"))
                            args.put("action", "select")
                                    .put("calendarId", Long.parseLong(action.substring(7)));
                        else args.put("action", action);
                        DiningBookingCalendar.call(c, "dining/poll/calendar-respond", args);
                    } catch (Exception rejected) {
                        android.util.Log.w(
                                "TakuDining", "Calendar action rejected: " + rejected.getMessage());
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
