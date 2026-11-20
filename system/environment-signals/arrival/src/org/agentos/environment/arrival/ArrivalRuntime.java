package org.agentos.environment.arrival;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.UserManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.agentos.environment.arrival.ArrivalStore.Event;
import static org.agentos.environment.arrival.ArrivalStore.Owner;
import static org.agentos.environment.arrival.ArrivalStore.Watch;

final class ArrivalRuntime {
    static final String LOCATION_UPDATE = "org.agentos.environment.arrival.LOCATION_UPDATE";
    private static final String MAINTENANCE = "org.agentos.environment.arrival.MAINTENANCE";
    private static final long SAMPLE_INTERVAL_MS = 10_000;
    private static final long RETRY_INTERVAL_MS = 30_000;
    private static final long RECHECK_INTERVAL_MS = 5 * 60_000;
    private static final long MAX_LIFETIME_MS = 24 * 60 * 60_000;

    private static ArrivalRuntime instance;
    private final Context context;
    private final ArrivalAccess access;
    private final ArrivalStore store;
    private final LocationManager location;
    private final AlarmManager alarms;
    private boolean sampling;

    static synchronized ArrivalRuntime get(Context context) {
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) {
            throw new Failure("UNAVAILABLE", "Unlock the device to use arrival monitoring");
        }
        if (instance == null) instance = new ArrivalRuntime(context.getApplicationContext());
        return instance;
    }

    private ArrivalRuntime(Context context) {
        this.context = context;
        access = new ArrivalAccess(context);
        store = new ArrivalStore(context);
        location = context.getSystemService(LocationManager.class);
        alarms = context.getSystemService(AlarmManager.class);
    }

    synchronized JSONObject call(int uid, String method, JSONObject args) throws JSONException {
        long now = System.currentTimeMillis();
        Owner owner = access.identify(uid);
        ArrivalAccess.Verdict verdict = access.check(owner);
        if (verdict == ArrivalAccess.Verdict.DENIED) {
            if (owner != null && store.cancelAll(owner)) reconcile(now, null, null);
            throw new Failure("PERMISSION_DENIED", "Arrival access requires location and Taku control");
        }
        if (verdict == ArrivalAccess.Verdict.UNAVAILABLE) {
            throw new Failure("UNAVAILABLE", "App Permission Center is unavailable");
        }
        if (!hostLocationGranted()) {
            throw new Failure("LOCATION_PERMISSION_REQUIRED", "Grant Taku Location Arrival precise background location");
        }
        store.expire(System.currentTimeMillis());
        JSONObject result = switch (method) {
            case "register" -> register(owner, args, now);
            case "list" -> new JSONObject().put("watches", watchesFor(owner));
            case "cancel" -> new JSONObject().put("cancelled", store.cancel(owner, identifier(args, "id")));
            case "pending" -> new JSONObject().put("events", eventsFor(owner));
            case "ack" -> new JSONObject().put("acknowledged",
                    store.acknowledge(owner, identifier(args, "eventId")));
            default -> throw new IllegalArgumentException("Unknown arrival method");
        };
        reconcile(now, owner, null);
        return new JSONObject().put("ok", true).put("result", result);
    }

    synchronized void onWake(String action, Location fix) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) store.resetSamples();
        reconcile(System.currentTimeMillis(), null, fix);
    }

    private JSONObject register(Owner owner, JSONObject args, long now) throws JSONException {
        if (!location.hasProvider(LocationManager.GPS_PROVIDER)) {
            throw new Failure("LOCATION_UNAVAILABLE", "Android GPS provider is unavailable");
        }
        String id = identifier(args, "id");
        String placeId = identifier(args, "placeId");
        double latitude = number(args, "latitude");
        double longitude = number(args, "longitude");
        double radius = number(args, "radiusMeters");
        double expiry = number(args, "expiresAtMs");
        if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180 || radius < 100 || radius > 5000
                || expiry != (long) expiry || expiry <= now || expiry > now + MAX_LIFETIME_MS) {
            throw new IllegalArgumentException("Invalid coordinates, radius (100–5000 m) or expiry (within 24 h)");
        }
        String receiver = args.optString("receiverClass", "");
        if (owner.packageName().isEmpty() ? !receiver.isEmpty()
                : receiver.isEmpty() || !access.canReceive(owner, receiver)) {
            throw new IllegalArgumentException("Supply a receiver in the calling package for AVAILABLE with SEND_LOCATION_ARRIVAL protection");
        }
        Watch watch = new Watch(id, owner, placeId, latitude, longitude, (float) radius, (long) expiry, receiver);
        store.register(watch);
        return watchJson(watch);
    }

    private boolean hostLocationGranted() {
        if (access.hasBackgroundLocation(context.getPackageName())) return true;
        store.clear();
        schedule(Long.MAX_VALUE);
        syncSampling(false);
        return false;
    }

    private void reconcile(long now, Owner authorizedCaller, Location fix) {
        if (!hostLocationGranted()) return;
        store.expire(now);
        List<Watch> watches = store.watches();
        Map<Owner, ArrivalAccess.Verdict> verdicts = new HashMap<>();
        if (authorizedCaller != null) verdicts.put(authorizedCaller, ArrivalAccess.Verdict.ALLOWED);

        for (Watch watch : watches) {
            Owner owner = watch.owner();
            ArrivalAccess.Verdict verdict = verdicts.computeIfAbsent(owner, this::recheckOwner);
            if (verdict == ArrivalAccess.Verdict.DENIED) store.cancel(owner, watch.id());
        }

        boolean shouldSample = false;
        for (Watch watch : store.samplingWatches()) {
            if (verdicts.get(watch.owner()) == ArrivalAccess.Verdict.ALLOWED) {
                if (fix != null) store.observe(watch, fix);
                shouldSample = true;
            }
        }
        if (fix != null && shouldSample) {
            shouldSample = store.samplingWatches().stream()
                    .anyMatch(watch -> verdicts.get(watch.owner()) == ArrivalAccess.Verdict.ALLOWED);
        }

        List<Event> events = store.events();
        long nextWake = Long.MAX_VALUE;
        for (Watch watch : store.watches()) {
            nextWake = Math.min(nextWake, Math.min(watch.expiresAtMs(), now + RECHECK_INTERVAL_MS));
        }
        for (Event event : events) {
            if (event.watch().receiverClass().isEmpty()
                    || verdicts.get(event.watch().owner()) != ArrivalAccess.Verdict.ALLOWED) continue;
            long retry = event.nextAttemptAtMs() <= now ? now + RETRY_INTERVAL_MS : event.nextAttemptAtMs();
            nextWake = Math.min(nextWake, retry);
        }
        schedule(nextWake);
        syncSampling(shouldSample);
        for (Event event : events) {
            Watch watch = event.watch();
            if (event.nextAttemptAtMs() > now || watch.expiresAtMs() <= System.currentTimeMillis()
                    || watch.receiverClass().isEmpty()
                    || verdicts.get(watch.owner()) != ArrivalAccess.Verdict.ALLOWED) continue;
            store.defer(event, now + RETRY_INTERVAL_MS);
            context.sendBroadcast(new Intent(ArrivalAccess.AVAILABLE).setComponent(
                    new ComponentName(watch.owner().packageName(), watch.receiverClass())));
        }
    }

    private ArrivalAccess.Verdict recheckOwner(Owner owner) {
        return owner.equals(access.identify(owner.uid())) ? access.check(owner) : ArrivalAccess.Verdict.DENIED;
    }

    private void syncSampling(boolean enabled) {
        try {
            if (!enabled) {
                PendingIntent registered = target(LOCATION_UPDATE, 1, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_NO_CREATE);
                if (registered != null) {
                    try {
                        location.removeUpdates(registered);
                    } finally {
                        registered.cancel();
                    }
                }
                sampling = false;
            } else if (!sampling) {
                LocationRequest request = new LocationRequest.Builder(SAMPLE_INTERVAL_MS)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build();
                location.requestLocationUpdates(LocationManager.GPS_PROVIDER, request,
                        target(LOCATION_UPDATE, 1, PendingIntent.FLAG_MUTABLE));
                sampling = true;
            }
        } catch (SecurityException | IllegalArgumentException unavailable) {
            sampling = false;
            Log.w("TakuArrival", "GPS updates are unavailable", unavailable);
        }
    }

    private void schedule(long nextWake) {
        PendingIntent wake = target(MAINTENANCE, 2, PendingIntent.FLAG_IMMUTABLE);
        if (nextWake == Long.MAX_VALUE) {
            alarms.cancel(wake);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextWake, wake);
        }
    }

    private PendingIntent target(String action, int requestCode, int flags) {
        return PendingIntent.getBroadcast(context, requestCode,
                new Intent(context, ArrivalReceiver.class).setAction(action), flags);
    }

    private JSONArray watchesFor(Owner owner) throws JSONException {
        JSONArray result = new JSONArray();
        for (Watch watch : store.watches()) {
            if (watch.owner().equals(owner)) result.put(watchJson(watch));
        }
        return result;
    }

    private JSONArray eventsFor(Owner owner) throws JSONException {
        JSONArray result = new JSONArray();
        for (Event event : store.events()) {
            Watch watch = event.watch();
            if (watch.owner().equals(owner)) {
                result.put(new JSONObject().put("eventId", event.eventId()).put("id", watch.id())
                        .put("placeId", watch.placeId()).put("arrivedAtMs", event.arrivedAtMs()));
            }
        }
        return result;
    }

    private static JSONObject watchJson(Watch watch) throws JSONException {
        return new JSONObject().put("id", watch.id()).put("placeId", watch.placeId())
                .put("latitude", watch.latitude()).put("longitude", watch.longitude())
                .put("radiusMeters", watch.radiusMeters()).put("expiresAtMs", watch.expiresAtMs());
    }

    private static double number(JSONObject args, String key) throws JSONException {
        if (!(args.get(key) instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(key + " must be a finite number");
        }
        return number.doubleValue();
    }

    private static String identifier(JSONObject args, String key) throws JSONException {
        if (!(args.get(key) instanceof String value) || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(key + " must contain 1–128 characters");
        }
        return value;
    }

    static final class Failure extends IllegalStateException {
        final String code;

        Failure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
