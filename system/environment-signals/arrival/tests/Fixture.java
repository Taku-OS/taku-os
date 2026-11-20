package org.agentos.arrivalfixture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Fixture {
    static final String ACTION = "org.agentos.environment.arrival.AVAILABLE";
    private static final Uri ARRIVAL = Uri.parse("content://org.agentos.environment.arrival");
    private static final String CHANNEL = "arrival";

    public static final class Control extends ContentProvider {
        @Override public boolean onCreate() { return true; }

        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            int caller = Binder.getCallingUid();
            if (caller != 0 && caller != 2000) throw new SecurityException("Test shell only");
            long token = Binder.clearCallingIdentity();
            Bundle out = new Bundle();
            try {
                JSONObject input = new JSONObject(arg == null ? "{}" : arg);
                JSONObject response = switch (method) {
                    case "status" -> status(getContext());
                    case "register" -> callArrival(getContext(), method, input.put(
                            "receiverClass", input.optString("receiverClass", ArrivalReceiver.class.getName())));
                    default -> callArrival(getContext(), method, input);
                };
                out.putString("result", response.toString());
            } catch (Exception e) {
                out.putString("result", new JSONObject(java.util.Map.of(
                        "fixtureError", e.toString())).toString());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return out;
        }

        @Override public Cursor query(Uri uri, String[] p, String s, String[] args, String sort) {
            throw new UnsupportedOperationException();
        }
        @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues v, String s, String[] args) {
            throw new UnsupportedOperationException();
        }
        @Override public int delete(Uri uri, String s, String[] args) { throw new UnsupportedOperationException(); }
    }

    static void onArrivalAvailable(Context context) throws Exception {
        SharedPreferences ledger = ledger(context);
        int wakeCount = ledger.getInt("wakeCount", 0) + 1;
        JSONObject pending = callArrival(context, "pending", new JSONObject());
        ledger.edit().putInt("wakeCount", wakeCount)
                .putString("lastPull", pending.toString()).commit();
        if (!pending.optBoolean("ok")) return;

        JSONArray events = pending.getJSONObject("result").getJSONArray("events");
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Arrivals", NotificationManager.IMPORTANCE_DEFAULT));
        for (int index = 0; index < events.length(); index++) {
            JSONObject event = events.getJSONObject(index);
            String eventId = event.getString("eventId");
            String key = "posted:" + eventId;
            if (ledger.contains(key)) continue;
            Notification notification = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle(context.getApplicationInfo().loadLabel(context.getPackageManager()))
                    .setContentText("Arrived at " + event.getString("placeId"))
                    .build();
            notifications.notify(eventId, 1, notification);
            ledger.edit().putString(key, event.toString()).commit();
        }
    }

    private static JSONObject status(Context context) throws Exception {
        SharedPreferences ledger = ledger(context);
        JSONArray posted = new JSONArray();
        for (String key : ledger.getAll().keySet()) {
            if (key.startsWith("posted:")) posted.put(new JSONObject(ledger.getString(key, "{}")));
        }
        JSONArray active = new JSONArray();
        for (StatusBarNotification notification : context.getSystemService(NotificationManager.class)
                .getActiveNotifications()) {
            active.put(notification.getTag());
        }
        return new JSONObject().put("uid", android.os.Process.myUid())
                .put("wakeCount", ledger.getInt("wakeCount", 0))
                .put("lastPull", new JSONObject(ledger.getString("lastPull", "{}")))
                .put("posted", posted).put("active", active);
    }

    private static JSONObject callArrival(Context context, String method, JSONObject input) throws Exception {
        Bundle response = context.getContentResolver().call(ARRIVAL, method, input.toString(), null);
        return new JSONObject(response.getString("result"));
    }

    private static SharedPreferences ledger(Context context) {
        return context.getSharedPreferences("arrival-fixture", Context.MODE_PRIVATE);
    }
}
