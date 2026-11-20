package org.agentos.environment.arrival;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class ArrivalStore extends SQLiteOpenHelper {
    record Owner(int uid, String packageName, String signingDigest) {}

    record Watch(String id, Owner owner, String placeId, double latitude, double longitude,
                 float radiusMeters, long expiresAtMs, String receiverClass) {
        Watch {
            // SQLite stores both signed zero coordinates as zero.
            latitude += 0.0;
            longitude += 0.0;
        }
    }

    record Event(String eventId, Watch watch, long arrivedAtMs, long nextAttemptAtMs) {}

    private enum Position { OUTSIDE, INSIDE, UNCERTAIN }

    private static final String TABLE = "watches";
    private static final String OWNER_MATCH = "owner_uid = ? AND owner_package = ? AND owner_signature = ?";
    private static final String WATCH_MATCH = OWNER_MATCH + " AND id = ?";
    private static final String EVENT_MATCH = OWNER_MATCH + " AND event_id = ?";
    private static final String SAMPLING = "event_id IS NULL";
    private static final String PENDING = "event_id IS NOT NULL AND acknowledged = 0";
    private static final int MAX_WATCHES_PER_OWNER = 16;
    private static final int MAX_WATCHES = 128;
    private static final int INSIDE_SAMPLES_REQUIRED = 2;
    private static final float OUTSIDE_BUFFER_METERS = 25f;
    private static final float MAX_ACCURACY_METERS = 100f;
    private static final long MAX_CONFIRMATION_GAP_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final long MAX_FIX_AGE_NANOS = TimeUnit.SECONDS.toNanos(60);

    ArrivalStore(Context context) {
        super(context, "arrival.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // A one-shot event and its acknowledgement live with the watch until cancellation or expiry.
        db.execSQL("""
                CREATE TABLE watches (
                    id TEXT NOT NULL,
                    owner_uid INTEGER NOT NULL,
                    owner_package TEXT NOT NULL,
                    owner_signature TEXT NOT NULL,
                    place_id TEXT NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    radius_meters REAL NOT NULL,
                    expires_at_ms INTEGER NOT NULL,
                    receiver_class TEXT NOT NULL,
                    last_sample_nanos INTEGER NOT NULL DEFAULT 0,
                    confirmed_outside INTEGER NOT NULL DEFAULT 0,
                    inside_samples INTEGER NOT NULL DEFAULT 0,
                    event_id TEXT UNIQUE,
                    arrived_at_ms INTEGER,
                    next_attempt_at_ms INTEGER,
                    acknowledged INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (owner_uid, owner_package, owner_signature, id)
                )
                """);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported arrival database upgrade");
    }

    List<Watch> watches() {
        return readWatches(null);
    }

    List<Watch> samplingWatches() {
        return readWatches(SAMPLING);
    }

    List<Event> events() {
        List<Event> events = new ArrayList<>();
        try (Cursor row = getReadableDatabase().query(
                TABLE, null, PENDING, null, null, null, "next_attempt_at_ms, event_id")) {
            while (row.moveToNext()) {
                events.add(new Event(
                        row.getString(row.getColumnIndexOrThrow("event_id")),
                        readWatch(row),
                        row.getLong(row.getColumnIndexOrThrow("arrived_at_ms")),
                        row.getLong(row.getColumnIndexOrThrow("next_attempt_at_ms"))));
            }
        }
        return events;
    }

    void register(Watch watch) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long nowMs = System.currentTimeMillis();
            if (watch.expiresAtMs() <= nowMs) {
                throw new IllegalArgumentException("Arrival watch has expired");
            }
            expire(db, nowMs);
            Watch existing = findWatch(db, watch.owner(), watch.id());
            if (existing == null) {
                insertWatch(db, watch);
            } else if (!existing.equals(watch)) {
                throw new IllegalArgumentException("Arrival watch ID already uses different parameters");
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    boolean cancel(Owner owner, String id) {
        return getWritableDatabase().delete(TABLE, WATCH_MATCH, ownerArgs(owner, id)) != 0;
    }

    boolean cancelAll(Owner owner) {
        String[] args = {Integer.toString(owner.uid()), owner.packageName(), owner.signingDigest()};
        return getWritableDatabase().delete(TABLE, OWNER_MATCH, args) != 0;
    }

    void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    boolean acknowledge(Owner owner, String eventId) {
        ContentValues state = new ContentValues();
        state.put("acknowledged", 1);
        return getWritableDatabase().update(TABLE, state, EVENT_MATCH, ownerArgs(owner, eventId)) != 0;
    }

    void expire(long nowMs) {
        expire(getWritableDatabase(), nowMs);
    }

    void observe(Watch watch, Location fix) {
        long sampleNanos = fix.getElapsedRealtimeNanos();
        long ageNanos = SystemClock.elapsedRealtimeNanos() - sampleNanos;
        long nowMs = System.currentTimeMillis();
        if (sampleNanos <= 0 || ageNanos < 0 || ageNanos > MAX_FIX_AGE_NANOS
                || watch.expiresAtMs() <= nowMs) {
            return;
        }

        SQLiteDatabase db = getWritableDatabase();
        String[] args = ownerArgs(watch.owner(), watch.id());
        String selection = WATCH_MATCH + " AND " + SAMPLING;
        ContentValues state;
        try (Cursor row = db.query(TABLE, null, selection, args, null, null, null)) {
            if (!row.moveToFirst() || !watch.equals(readWatch(row))) return;
            long lastSampleNanos = row.getLong(row.getColumnIndexOrThrow("last_sample_nanos"));
            if (sampleNanos <= lastSampleNanos) return;
            Position position = classify(watch, fix);
            boolean confirmedOutside = position == Position.OUTSIDE
                    || row.getInt(row.getColumnIndexOrThrow("confirmed_outside")) != 0;
            int insideSamples = 0;
            if (confirmedOutside && position == Position.INSIDE) {
                insideSamples = 1;
                if (sampleNanos - lastSampleNanos <= MAX_CONFIRMATION_GAP_NANOS) {
                    insideSamples += row.getInt(row.getColumnIndexOrThrow("inside_samples"));
                }
            }
            state = new ContentValues();
            state.put("last_sample_nanos", sampleNanos);
            state.put("confirmed_outside", confirmedOutside ? 1 : 0);
            state.put("inside_samples", insideSamples);
            if (insideSamples == INSIDE_SAMPLES_REQUIRED) {
                state.put("event_id", UUID.randomUUID().toString());
                state.put("arrived_at_ms", nowMs);
                state.put("next_attempt_at_ms", nowMs);
            }
        }
        db.update(TABLE, state, selection, args);
    }

    void resetSamples() {
        ContentValues state = new ContentValues();
        state.put("last_sample_nanos", 0L);
        state.put("inside_samples", 0);
        getWritableDatabase().update(TABLE, state, SAMPLING, null);
    }

    void defer(Event event, long nextAttemptAtMs) {
        ContentValues state = new ContentValues();
        state.put("next_attempt_at_ms", nextAttemptAtMs);
        String[] args = {event.eventId(), Long.toString(event.nextAttemptAtMs())};
        getWritableDatabase().update(TABLE, state,
                "event_id = ? AND acknowledged = 0 AND next_attempt_at_ms = ?", args);
    }

    private List<Watch> readWatches(String selection) {
        List<Watch> watches = new ArrayList<>();
        try (Cursor row = getReadableDatabase().query(
                TABLE, null, selection, null, null, null, "expires_at_ms, id")) {
            while (row.moveToNext()) {
                watches.add(readWatch(row));
            }
        }
        return watches;
    }

    private static void expire(SQLiteDatabase db, long nowMs) {
        db.delete(TABLE, "expires_at_ms <= ?", new String[]{Long.toString(nowMs)});
    }

    private static Watch findWatch(SQLiteDatabase db, Owner owner, String id) {
        try (Cursor row = db.query(TABLE, null, WATCH_MATCH, ownerArgs(owner, id), null, null, null)) {
            return row.moveToFirst() ? readWatch(row) : null;
        }
    }

    private static void insertWatch(SQLiteDatabase db, Watch watch) {
        Owner owner = watch.owner();
        String[] ownerArgs = {Integer.toString(owner.uid()), owner.packageName(), owner.signingDigest()};
        if (DatabaseUtils.queryNumEntries(db, TABLE, OWNER_MATCH, ownerArgs) >= MAX_WATCHES_PER_OWNER) {
            throw new IllegalArgumentException("Too many arrival watches for this owner");
        }
        if (DatabaseUtils.queryNumEntries(db, TABLE) >= MAX_WATCHES) {
            throw new IllegalArgumentException("Too many arrival watches");
        }
        ContentValues values = new ContentValues();
        values.put("id", watch.id());
        values.put("owner_uid", owner.uid());
        values.put("owner_package", owner.packageName());
        values.put("owner_signature", owner.signingDigest());
        values.put("place_id", watch.placeId());
        values.put("latitude", watch.latitude());
        values.put("longitude", watch.longitude());
        values.put("radius_meters", watch.radiusMeters());
        values.put("expires_at_ms", watch.expiresAtMs());
        values.put("receiver_class", watch.receiverClass());
        values.put("last_sample_nanos", SystemClock.elapsedRealtimeNanos());
        db.insertOrThrow(TABLE, null, values);
    }

    private static Watch readWatch(Cursor row) {
        Owner owner = new Owner(
                row.getInt(row.getColumnIndexOrThrow("owner_uid")),
                row.getString(row.getColumnIndexOrThrow("owner_package")),
                row.getString(row.getColumnIndexOrThrow("owner_signature")));
        return new Watch(
                row.getString(row.getColumnIndexOrThrow("id")),
                owner,
                row.getString(row.getColumnIndexOrThrow("place_id")),
                row.getDouble(row.getColumnIndexOrThrow("latitude")),
                row.getDouble(row.getColumnIndexOrThrow("longitude")),
                row.getFloat(row.getColumnIndexOrThrow("radius_meters")),
                row.getLong(row.getColumnIndexOrThrow("expires_at_ms")),
                row.getString(row.getColumnIndexOrThrow("receiver_class")));
    }

    private static String[] ownerArgs(Owner owner, String id) {
        return new String[]{Integer.toString(owner.uid()), owner.packageName(), owner.signingDigest(), id};
    }

    private static Position classify(Watch watch, Location fix) {
        double latitude = fix.getLatitude();
        double longitude = fix.getLongitude();
        float accuracy = fix.getAccuracy();
        float radius = watch.radiusMeters();
        if (!fix.hasAccuracy() || !Float.isFinite(accuracy) || accuracy <= 0
                || accuracy > Math.min(MAX_ACCURACY_METERS, radius)
                || !Double.isFinite(latitude) || Math.abs(latitude) > 90
                || !Double.isFinite(longitude) || Math.abs(longitude) > 180) {
            return Position.UNCERTAIN;
        }
        float[] result = new float[1];
        Location.distanceBetween(watch.latitude(), watch.longitude(), latitude, longitude, result);
        float distance = result[0];
        if (distance - accuracy > radius + OUTSIDE_BUFFER_METERS) {
            return Position.OUTSIDE;
        }
        if (distance + accuracy <= radius) {
            return Position.INSIDE;
        }
        return Position.UNCERTAIN;
    }
}
