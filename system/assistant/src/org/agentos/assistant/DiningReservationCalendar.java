package org.agentos.assistant;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;

import org.json.JSONObject;

import java.io.IOException;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Saves a verified reservation in the phone's synced primary Google Calendar. */
final class DiningReservationCalendar {
    private static final String GOOGLE_ACCOUNT = "com.google";
    private static final Duration DEFAULT_LENGTH = Duration.ofMinutes(90);
    private static final String[] CALENDAR_FIELDS = {
        Calendars._ID,
        Calendars.ACCOUNT_NAME,
        Calendars.OWNER_ACCOUNT,
        Calendars.IS_PRIMARY,
        Calendars.CALENDAR_DISPLAY_NAME,
        Calendars.ACCOUNT_TYPE,
        Calendars.CALENDAR_ACCESS_LEVEL,
        Calendars.SYNC_EVENTS,
        Calendars.DELETED
    };
    private static final String[] EVENT_FIELDS = {
        Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION,
        Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE,
        Events.ALL_DAY, Events.DELETED
    };

    record Result(long eventId, long calendarId, boolean existing) {}

    private record Event(String title, String address, long start, long end, String zone) {}

    private DiningReservationCalendar() {}

    static synchronized Result save(Context context, JSONObject meal, Long selectedCalendarId)
            throws IOException {
        Event event = event(meal);
        ContentResolver resolver = context.getContentResolver();
        try {
            long calendarId =
                    DiningCalendarSelection.choose(calendars(resolver), selectedCalendarId).id();
            long existing = findEvent(resolver, calendarId, event);
            if (existing >= 0) {
                verifyEvent(resolver, existing, calendarId, event);
                return new Result(existing, calendarId, true);
            }

            ContentValues values = new ContentValues();
            values.put(Events.CALENDAR_ID, calendarId);
            values.put(Events.TITLE, event.title());
            values.put(Events.EVENT_LOCATION, event.address());
            values.put(Events.DTSTART, event.start());
            values.put(Events.DTEND, event.end());
            values.put(Events.EVENT_TIMEZONE, event.zone());
            values.put(Events.EVENT_END_TIMEZONE, event.zone());
            values.put(Events.ALL_DAY, 0);
            Uri inserted = resolver.insert(Events.CONTENT_URI, values);
            if (inserted == null) throw failed("DINING_CALENDAR_SAVE_REJECTED");
            long eventId = ContentUris.parseId(inserted);
            verifyEvent(resolver, eventId, calendarId, event);
            return new Result(eventId, calendarId, false);
        } catch (SecurityException unavailable) {
            throw failed("DINING_CALENDAR_PERMISSION_REQUIRED");
        } catch (RuntimeException unavailable) {
            throw failed("DINING_CALENDAR_PROVIDER_ERROR");
        }
    }

    private static Event event(JSONObject meal) throws IOException {
        String restaurant = meal.optString("restaurant"),
                address = meal.optString("address"),
                zoneId = meal.optString("zone");
        if (restaurant.isBlank() || address.isBlank())
            throw failed("DINING_CALENDAR_RECEIPT_INCOMPLETE");
        if (!ZoneId.getAvailableZoneIds().contains(zoneId))
            throw failed("DINING_CALENDAR_TIME_ZONE_INVALID");
        try {
            int year = meal.getInt("year");
            if (year < 1 || year > 9999) throw failed("DINING_CALENDAR_YEAR_INVALID");
            ZoneId zone = ZoneId.of(zoneId);
            LocalDateTime local =
                    LocalDateTime.of(
                            year,
                            meal.getInt("month"),
                            meal.getInt("day"),
                            meal.getInt("hour"),
                            meal.getInt("minute"));
            List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
            if (offsets.size() != 1) throw failed("DINING_CALENDAR_LOCAL_TIME_AMBIGUOUS");
            long start = local.toInstant(offsets.get(0)).toEpochMilli(),
                    end = Math.addExact(start, DEFAULT_LENGTH.toMillis());
            if (meal.getLong("start") != start || meal.getLong("end") != end)
                throw failed("DINING_CALENDAR_TIME_CHANGED");
            return new Event(restaurant, address, start, end, zoneId);
        } catch (DateTimeException invalid) {
            throw failed("DINING_CALENDAR_DATE_INVALID");
        } catch (org.json.JSONException | ArithmeticException invalid) {
            throw failed("DINING_CALENDAR_RECEIPT_INCOMPLETE");
        }
    }

    static List<DiningCalendarSelection.Calendar> calendars(Context context) throws IOException {
        try {
            return calendars(context.getContentResolver());
        } catch (SecurityException unavailable) {
            throw failed("DINING_CALENDAR_PERMISSION_REQUIRED");
        } catch (RuntimeException unavailable) {
            throw failed("DINING_CALENDAR_PROVIDER_ERROR");
        }
    }

    /** Read-only candidate reuse; does not import old workflow phase or create an event. */
    static List<Result> existing(Context context, JSONObject meal) throws IOException {
        Event event = event(meal);
        List<Result> matches = new ArrayList<>();
        try {
            for (var calendar : calendars(context)) {
                long id = findEvent(context.getContentResolver(), calendar.id(), event);
                if (id >= 0) {
                    verifyEvent(context.getContentResolver(), id, calendar.id(), event);
                    matches.add(new Result(id, calendar.id(), true));
                }
            }
            return List.copyOf(matches);
        } catch (SecurityException denied) {
            throw failed("DINING_CALENDAR_PERMISSION_REQUIRED");
        }
    }

    private static List<DiningCalendarSelection.Calendar> calendars(ContentResolver resolver)
            throws IOException {
        List<DiningCalendarSelection.Calendar> calendars = new ArrayList<>();
        try (Cursor rows =
                resolver.query(
                        Calendars.CONTENT_URI,
                        CALENDAR_FIELDS,
                        Calendars.ACCOUNT_TYPE
                                + "=? AND "
                                + Calendars.SYNC_EVENTS
                                + "=1 AND "
                                + Calendars.DELETED
                                + "=0 AND "
                                + Calendars.CALENDAR_ACCESS_LEVEL
                                + ">=?",
                        new String[] {
                            GOOGLE_ACCOUNT, Integer.toString(Calendars.CAL_ACCESS_CONTRIBUTOR)
                        },
                        null)) {
            if (rows == null) throw failed("DINING_CALENDAR_PROVIDER_ERROR");
            while (rows.moveToNext()) {
                DiningCalendarSelection.Calendar candidate =
                        new DiningCalendarSelection.Calendar(
                                rows.getLong(0),
                                rows.getString(4),
                                rows.getString(1),
                                rows.getString(2),
                                rows.getString(5),
                                rows.getInt(3),
                                rows.getInt(6),
                                rows.getInt(7),
                                rows.getInt(8));
                if (DiningCalendarSelection.eligible(candidate)) calendars.add(candidate);
            }
        }
        return List.copyOf(calendars);
    }

    private static long findEvent(ContentResolver resolver, long calendarId, Event event)
            throws IOException {
        long eventId = -1;
        try (Cursor rows =
                resolver.query(
                        Events.CONTENT_URI,
                        EVENT_FIELDS,
                        Events.CALENDAR_ID
                                + "=? AND "
                                + Events.DTSTART
                                + "=? AND "
                                + Events.ALL_DAY
                                + "=0 AND "
                                + Events.DELETED
                                + "=0",
                        new String[] {Long.toString(calendarId), Long.toString(event.start())},
                        null)) {
            if (rows == null) throw failed("DINING_CALENDAR_PROVIDER_ERROR");
            while (rows.moveToNext()) {
                if (!normalized(event.title()).equals(normalized(rows.getString(2)))
                        || !normalized(event.address()).equals(normalized(rows.getString(3))))
                    continue;
                if (eventId >= 0) throw failed("DINING_CALENDAR_EXISTING_AMBIGUOUS");
                eventId = rows.getLong(0);
            }
        }
        return eventId;
    }

    private static void verifyEvent(
            ContentResolver resolver, long eventId, long calendarId, Event event)
            throws IOException {
        Uri uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId);
        try (Cursor row = resolver.query(uri, EVENT_FIELDS, null, null, null)) {
            if (row == null
                    || !row.moveToFirst()
                    || row.getLong(0) != eventId
                    || row.getLong(1) != calendarId
                    || !event.title().equals(row.getString(2))
                    || !event.address().equals(row.getString(3))
                    || row.getLong(4) != event.start()
                    || row.getLong(5) != event.end()
                    || !event.zone().equals(row.getString(6))
                    || !event.zone().equals(row.getString(7))
                    || row.getInt(8) != 0
                    || row.getInt(9) != 0
                    || row.moveToNext()) throw failed("DINING_CALENDAR_READBACK_UNVERIFIED");
        }
    }

    private static String normalized(String text) {
        return text == null
                ? ""
                : Normalizer.normalize(text, Normalizer.Form.NFC)
                        .replaceAll("[\\p{Z}\\s]+", " ")
                        .strip();
    }

    private static IOException failed(String code) {
        return new IOException(code);
    }
}
