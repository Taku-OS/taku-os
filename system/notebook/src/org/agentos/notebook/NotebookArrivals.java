package org.agentos.notebook;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class NotebookArrivals {
    private static final String TAG = "NotebookArrivals";
    private static final Uri ARRIVAL = Uri.parse("content://org.agentos.environment.arrival");
    private static final int WATCH_LIMIT = 16;
    private static final int LOOKUP_BATCH_SIZE = 4;
    private static final long WATCH_LIFETIME_MS = 23 * 60 * 60_000L;
    private static final long RENEW_WITHIN_MS = 6 * 60 * 60_000L;
    private static final long FRESH_EVENT_MS = 5 * 60_000L;
    private static final long RETRY_LOOKUP_MS = 30 * 60_000L;
    private static final long NO_MATCH_RETRY_MS = 24 * 60 * 60_000L;
    private static final String ENABLED = "enabled";
    private static volatile String hostStatus = "idle";
    private static volatile int watchCount;

    record Target(NotebookPlaces.Place place, NotebookStore.Entry entry) {}

    private NotebookArrivals() {}

    static boolean enabled(Context context) {
        return context.getSharedPreferences("notebook_place_reminders", Context.MODE_PRIVATE)
                .getBoolean(ENABLED, false);
    }

    private static SharedPreferences resolutions(Context context) {
        return context.getSharedPreferences("notebook_resolved_places", Context.MODE_PRIVATE);
    }

    static JSONObject setEnabled(Context context, boolean value) throws IOException, JSONException {
        if (value && !context.getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            throw new IOException("PLACE_NOTIFICATIONS_REQUIRED");
        }
        if (!context.getSharedPreferences("notebook_place_reminders", Context.MODE_PRIVATE)
                .edit().putBoolean(ENABLED, value).commit()) {
            throw new IOException("PLACE_SETTINGS_UNAVAILABLE");
        }
        NotebookArrivalJob.resume(context);
        return state(context);
    }

    static JSONObject state(Context context) throws JSONException {
        var location = context.getSystemService(LocationManager.class);
        return new JSONObject().put("enabled", enabled(context))
                .put("hostStatus", hostStatus).put("watchCount", watchCount)
                .put("locationEnabled", location != null && location.isLocationEnabled())
                .put("notificationsEnabled", context.getSystemService(NotificationManager.class)
                        .areNotificationsEnabled());
    }

    static NotebookPlaces.Place placeForEntry(Context context, String id) throws IOException, JSONException {
        if (!enabled(context)) return null;
        var entry = new NotebookStore(context).get(id);
        var hint = entry == null ? null : NotebookArrivalIntent.cached(context, entry);
        var saved = hint == null ? null : cachedResolution(context, entry, hint);
        var place = saved == null ? null : saved.optJSONObject("place");
        return place == null ? null : NotebookPlaces.Place.read(place);
    }

    static JSONObject placeStatus(Context context, NotebookStore.Entry entry, NotebookArrivalIntent.Hint hint)
            throws JSONException {
        if (hint == null) return new JSONObject().put("status", "analyzing");
        if (!NotebookPlaces.eligible(hint)) return new JSONObject().put("status", "no_specific_place");
        JSONObject cached = cachedResolution(context, entry, hint);
        if (cached == null) return new JSONObject().put("status", enabled(context) ? "pending" : "reminders_off");
        return new JSONObject().put("status", cached.getString("status"))
                .putOpt("place", cached.optJSONObject("place"));
    }

    private static JSONObject cachedResolution(Context context, NotebookStore.Entry entry,
            NotebookArrivalIntent.Hint hint) {
        try {
            JSONObject saved = new JSONObject(resolutions(context).getString(entry.id, "{}"));
            return NotebookPlaces.key(hint).equals(saved.optString("query")) ? saved : null;
        } catch (JSONException invalid) {
            return null;
        }
    }

    static void refresh(Context context) throws IOException, JSONException, InterruptedException {
        try {
            refreshWatches(context);
        } catch (IOException failure) {
            String code = failure.getMessage();
            hostStatus = code != null && code.matches("[A-Z_]{1,64}") ? code : "ARRIVAL_UNAVAILABLE";
            Log.i(TAG, "Arrival service state: " + hostStatus);
            throw failure;
        }
    }

    private static void refreshWatches(Context context)
            throws IOException, JSONException, InterruptedException {
        var entries = new NotebookStore(context).listAll();
        Map<String, Target> targets = new LinkedHashMap<>();
        if (enabled(context)) {
            int lookups = 0;
            var cachedQueries = new LinkedHashMap<String, JSONObject>();
            var resolved = resolutions(context);
            for (var entry : entries) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (targets.size() == WATCH_LIMIT) break;
                var hint = NotebookArrivalIntent.cached(context, entry);
                if (hint == null || !NotebookPlaces.eligible(hint)) continue;
                String query = NotebookPlaces.key(hint);
                JSONObject cached = cachedResolution(context, entry, hint);
                if (cached == null) cached = cachedQueries.get(query);
                if (lookups < LOOKUP_BATCH_SIZE
                        && (cached == null || cached.optLong("retry") > 0
                            && cached.optLong("retry") <= System.currentTimeMillis())) {
                    lookups++;
                    var resolution = NotebookPlaces.resolve(context, hint);
                    cached = new JSONObject().put("query", query).put("status", resolution.status());
                    if (resolution.place() != null) {
                        cached.put("place", resolution.place().json());
                    } else {
                        long retry = switch (resolution.status()) {
                            case "not_found", "ambiguous" -> NO_MATCH_RETRY_MS;
                            default -> RETRY_LOOKUP_MS;
                        };
                        cached.put("retry", System.currentTimeMillis() + retry);
                        Log.i(TAG, "Place lookup: " + resolution.status());
                    }
                    if (!resolved.edit().putString(entry.id, cached.toString()).commit()) {
                        throw new IOException("PLACE_CACHE_UNAVAILABLE");
                    }
                    context.getContentResolver().notifyChange(NotebookStore.ENTRIES_URI.buildUpon()
                            .appendPath(entry.id).build(), null, 0);
                }
                if (cached == null) continue;
                cachedQueries.putIfAbsent(query, cached);
                if (cachedResolution(context, entry, hint) == null) {
                    resolved.edit().putString(entry.id, cached.toString()).apply();
                }
                var value = cached.optJSONObject("place");
                if (value == null) continue;
                var place = NotebookPlaces.Place.read(value);
                targets.putIfAbsent(place.id(), new Target(place, entry));
            }
        }
        checkInterrupted();
        if (!enabled(context)) targets.clear();

        JSONArray watches = call(context, "list", new JSONObject()).getJSONArray("watches");
        deliver(context, targets);
        long now = System.currentTimeMillis();
        int count = 0;
        for (int i = 0; i < watches.length(); i++) {
            checkInterrupted();
            JSONObject watch = watches.getJSONObject(i);
            String id = watch.getString("id");
            var target = targets.get(id);
            if (target != null && enabled(context) && watch.getLong("expiresAtMs") - now > RENEW_WITHIN_MS
                    && watch.getInt("radiusMeters") == target.place().radiusMeters()) {
                targets.remove(id);
                count++;
                continue;
            }
            call(context, "cancel", new JSONObject().put("id", id));
        }
        if (enabled(context)) {
            for (var target : targets.values()) {
                checkInterrupted();
                if (!enabled(context)) break;
                var place = target.place();
                call(context, "register", new JSONObject().put("id", place.id()).put("placeId", place.id())
                        .put("latitude", place.latitude()).put("longitude", place.longitude())
                        .put("radiusMeters", place.radiusMeters()).put("expiresAtMs", now + WATCH_LIFETIME_MS)
                        .put("receiverClass", NotebookArrivalReceiver.class.getName()));
                count++;
            }
        }
        watchCount = count;
        hostStatus = enabled(context) ? "ready" : "off";
    }

    private static void deliver(Context context, Map<String, Target> targets) throws IOException, JSONException {
        JSONArray events = call(context, "pending", new JSONObject()).getJSONArray("events");
        long now = System.currentTimeMillis();
        var store = new NotebookStore(context);
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            String eventId = event.getString("eventId");
            var target = targets.get(event.getString("placeId"));
            if (enabled(context) && target != null && now - event.getLong("arrivedAtMs") <= FRESH_EVENT_MS) {
                var current = store.get(target.entry().id);
                var hint = current == null ? null : NotebookArrivalIntent.cached(context, current);
                var resolved = hint == null ? null : cachedResolution(context, current, hint);
                var place = resolved == null ? null : resolved.optJSONObject("place");
                if (place != null && target.place().id().equals(place.optString("id"))) {
                    NotebookArrivalPresentation.post(context, eventId, target.place(), current);
                }
            }
            call(context, "ack", new JSONObject().put("eventId", eventId));
        }
    }

    private static JSONObject call(Context context, String method, JSONObject args) throws IOException, JSONException {
        try (var client = context.getContentResolver().acquireContentProviderClient(ARRIVAL)) {
            if (client == null) throw new IOException("ARRIVAL_UNAVAILABLE");
            var reply = client.call(method, args.toString(), null);
            if (reply == null) throw new IOException("ARRIVAL_UNAVAILABLE");
            var json = new JSONObject(reply.getString("result", "{}"));
            if (!json.optBoolean("ok")) throw new IOException(json.optString("code", "ARRIVAL_UNAVAILABLE"));
            return json.getJSONObject("result");
        } catch (SecurityException denied) {
            throw new IOException("PERMISSION_DENIED", denied);
        } catch (RemoteException unavailable) {
            throw new IOException("ARRIVAL_UNAVAILABLE", unavailable);
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
}
