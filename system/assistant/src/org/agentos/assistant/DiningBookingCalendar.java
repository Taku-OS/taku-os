package org.agentos.assistant;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Calendar consent after the user confirms sharing the reservation. Never sends, books or starts a
 * model.
 */
final class DiningBookingCalendar {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Set<String> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static Context owner;
    private static boolean running;
    private static volatile boolean busy;

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("dining-booking-calendar", 0);
    }

    private static JSONObject entries(Context c) throws Exception {
        return new JSONObject(prefs(c).getString("entries", "{}"));
    }

    private static synchronized void save(Context c, JSONObject entry) throws Exception {
        JSONObject all = entries(c);
        all.put(entry.getString("id"), entry);
        if (!prefs(c).edit().putString("entries", all.toString()).commit())
            throw new IOException("DINING_CALENDAR_STATE_SAVE_FAILED");
    }

    static synchronized void track(Context c, JSONObject watch) throws Exception {
        if (!"CONFIRMED".equals(watch.getJSONObject("state").optString("phase"))
                || watch.optString("feedCardId").isBlank()) return;
        DiningPollBookingFlow.receipt(DiningPollBookingCodec.state(watch.getJSONObject("state")));
        if (!latest(c, watch)) return;
        String fingerprint = DiningBookingCalendarPolicy.identity(watch),
                id = watch.getString("id") + ":" + fingerprint;
        JSONObject entry = entries(c).optJSONObject(id);
        if (entry == null) {
            entry =
                    new JSONObject()
                            .put("id", id)
                            .put("watchId", watch.getString("id"))
                            .put("fingerprint", fingerprint)
                            .put("cardId", watch.getString("feedCardId"))
                            .put("receiptAt", DiningBookingCalendarPolicy.receiptAt(watch))
                            .put("phase", "waiting_share")
                            .put("shareProofVersion", 1)
                            .put("key", UUID.randomUUID().toString())
                            .put("messageSent", false)
                            .put("createdAt", System.currentTimeMillis());
            save(c, entry);
        } else if (DiningBookingCalendarPolicy.requireShareConfirmation(entry)) {
            save(c, entry);
        }
        if (DiningBookingCalendarPolicy.needsPolling(entry)) ensurePolling(c, id);
        else ACTIVE.remove(id);
        DiningBookingRide.track(c, entry);
    }

    private static synchronized void ensurePolling(Context c, String id) {
        ACTIVE.add(id);
        owner = c.getApplicationContext();
        if (!running) {
            running = true;
            MAIN.post(DiningBookingCalendar::tick);
        }
    }

    private static void tick() {
        if (busy || owner == null) return;
        busy = true;
        IO.execute(
                () -> {
                    synchronized (DiningBookingCalendar.class) {
                        try {
                            JSONObject all = entries(owner), selected = null;
                            for (String id : new ArrayList<>(ACTIVE)) {
                                try {
                                    JSONObject entry = all.optJSONObject(id);
                                    if (entry == null) {
                                        ACTIVE.remove(id);
                                        continue;
                                    }
                                    JSONObject watch = current(owner, entry.getString("watchId"));
                                    if (!latest(owner, watch)
                                            || !entry.getString("fingerprint")
                                                    .equals(
                                                            DiningBookingCalendarPolicy.identity(
                                                                    watch))) {
                                        ACTIVE.remove(id);
                                        continue;
                                    }
                                    if (entry.optString("phase").equals("saving")) {
                                        entry.put("phase", "error")
                                                .put("error", "DINING_CALENDAR_SAVE_UNCONFIRMED")
                                                .put("key", UUID.randomUUID().toString());
                                        save(owner, entry);
                                    }
                                    if (entry.optString("phase").equals("waiting_share"))
                                        readShare(owner, entry, watch);
                                    if (entry.has("eventId")
                                            && Set.of("saved", "dismissed")
                                                    .contains(entry.optString("phase"))) {
                                        finishSaved(owner, entry);
                                        DiningBookingRide.track(owner, entry);
                                    }
                                    if (!DiningBookingCalendarPolicy.needsPolling(entry)) {
                                        ACTIVE.remove(id);
                                        continue;
                                    }
                                    if (Set.of("offer", "calendars", "error")
                                                    .contains(entry.optString("phase"))
                                            && (selected == null
                                                    || entry.optLong("shareConfirmedAt")
                                                            > selected.optLong("shareConfirmedAt")))
                                        selected = entry;
                                } catch (Exception stale) {
                                    android.util.Log.w(
                                            "TakuDining", "Skipped stale calendar binding");
                                }
                            }
                            if (selected != null && mayPresent(owner)) {
                                ensureSelection(
                                        owner,
                                        selected,
                                        current(owner, selected.getString("watchId")),
                                        true);
                                DiningBookingCalendarIsland.show(owner, selected);
                            }
                        } catch (Exception error) {
                            android.util.Log.w(
                                    "TakuDining",
                                    "Calendar continuation pending: " + error.getMessage());
                        } finally {
                            busy = false;
                            if (!ACTIVE.isEmpty() && owner != null) {
                                MAIN.postDelayed(DiningBookingCalendar::tick, 1500);
                            } else {
                                running = false;
                            }
                        }
                    }
                });
    }

    private static void readShare(Context c, JSONObject entry, JSONObject watch) throws Exception {
        JSONObject card =
                provider(c, "feed/get", new JSONObject().put("id", entry.getString("cardId")))
                        .getJSONObject("card");
        JSONObject intent =
                card.getJSONObject("actions").getJSONObject("share").getJSONObject("intent");
        String expected =
                (String)
                        DiningReservationFeedCard.whatsappShareIntent(
                                        DiningPollBookingCodec.state(watch.getJSONObject("state")))
                                .get("text");
        if (!"share-text".equals(intent.optString("type"))
                || !"com.whatsapp".equals(intent.optString("package"))
                || !expected.equals(intent.optString("text")))
            throw new IOException("DINING_CALENDAR_SHARE_CARD_CHANGED");
        Uri uri =
                Uri.parse("content://org.agentos.agent.home-feed/events")
                        .buildUpon()
                        .appendQueryParameter("after", entry.optString("feedCursor", "0"))
                        .appendQueryParameter("follow", "0")
                        .appendQueryParameter("cardId", entry.getString("cardId"))
                        .appendQueryParameter("op", "action")
                        .build();
        String checkpoint = null;
        boolean changed = false;
        int size = 0, lines = 0;
        try (var input = c.getContentResolver().openInputStream(uri);
                var reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        Objects.requireNonNull(input), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                size += line.length();
                if (++lines > 4096 || size > 2 * 1024 * 1024)
                    throw new IOException("DINING_CALENDAR_EVENT_WINDOW_TOO_LARGE");
                JSONObject event = new JSONObject(line);
                if ("checkpoint".equals(event.optString("type")))
                    checkpoint = event.getString("through");
                changed |=
                        DiningBookingCalendarPolicy.recordShareEvent(
                                entry, event, expected, System.currentTimeMillis());
            }
        }
        if (checkpoint != null) entry.put("feedCursor", checkpoint);
        if (changed || checkpoint != null) save(c, entry);
    }

    private static boolean mayPresent(Context c) throws Exception {
        if (c.getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        ActivityManager.RunningTaskInfo task = DiningPollUi.visible(c);
        if (task == null || task.topActivity == null) return false;
        if (!Set.of("org.agentos.agent", "com.android.launcher3")
                .contains(task.topActivity.getPackageName())) return false;
        Bundle result =
                c.getContentResolver()
                        .call(
                                Uri.parse("content://org.agentos.agent.home-assistant"),
                                "assistant/get",
                                null,
                                null);
        JSONObject home =
                new JSONObject(result == null ? "{}" : result.getString("result", "{}"))
                        .optJSONObject("result");
        return home != null
                && !home.optBoolean("working")
                && !home.optBoolean("needsUser")
                && !home.optBoolean("sending")
                && home.isNull("question");
    }

    private static JSONArray choices(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (var calendar : DiningReservationCalendar.calendars(c))
            a.put(
                    new JSONObject()
                            .put("id", calendar.id())
                            .put("name", calendar.name())
                            .put("account", calendar.account()));
        return a;
    }

    private static void ensureSelection(
            Context c, JSONObject entry, JSONObject watch, boolean allowPermissionError)
            throws Exception {
        JSONObject meal = DiningBookingCalendarPolicy.meal(watch);
        if (entry.has("eventId"))
            meal.put("eventId", entry.getLong("eventId"))
                    .put("calendarId", entry.getLong("calendarId"));
        entry.put("meal", meal);
        try {
            entry.put("calendars", choices(c));
            if (!entry.has("calendarId")) {
                List<DiningReservationCalendar.Result> matches =
                        DiningReservationCalendar.existing(c, meal);
                if (matches.size() == 1)
                    entry.put("calendarId", matches.get(0).calendarId())
                            .put("existingEventId", matches.get(0).eventId());
                else if (matches.size() > 1) entry.put("selectionRequired", true);
                else if (entry.getJSONArray("calendars").length() == 1)
                    entry.put(
                            "calendarId",
                            entry.getJSONArray("calendars").getJSONObject(0).getLong("id"));
            }
            DiningBookingCalendarPolicy.clearPermissionRequired(entry);
            save(c, entry);
        } catch (IOException error) {
            if (!"DINING_CALENDAR_PERMISSION_REQUIRED".equals(error.getMessage())) throw error;
            DiningBookingCalendarPolicy.recordPermissionRequired(entry, meal);
            save(c, entry);
            if (!allowPermissionError) throw error;
        }
    }

    static synchronized JSONObject call(Context c, String method, JSONObject args)
            throws Exception {
        Set<String> fields =
                switch (method) {
                    case "dining/poll/calendar-state" -> Set.of();
                    case "dining/poll/calendar-open" -> Set.of("id");
                    case "dining/poll/calendar-respond" ->
                            "select".equals(args.optString("action"))
                                    ? Set.of("id", "key", "action", "calendarId")
                                    : Set.of("id", "key", "action");
                    default -> throw new IOException("UNKNOWN_POLL_METHOD");
                };
        PanelProtocol.exact(args, fields);
        if (method.equals("dining/poll/calendar-state"))
            return new JSONObject()
                    .put("entries", entries(c))
                    .put("messageSent", false)
                    .put("presentationHost", "systemui-island")
                    .put("requiresConfirmation", true);
        JSONObject watch = current(c, args.getString("id"));
        if (!latest(c, watch)) throw new IOException("REQUEST_EXPIRED");
        track(c, watch);
        String identity = watch.getString("id") + ":" + DiningBookingCalendarPolicy.identity(watch);
        JSONObject entry = entries(c).getJSONObject(identity);
        if (method.equals("dining/poll/calendar-open")) {
            if (entry.optString("phase").equals("waiting_share")) readShare(c, entry, watch);
            if (entry.optString("phase").equals("saving"))
                entry.put("phase", "error")
                        .put("error", "DINING_CALENDAR_SAVE_UNCONFIRMED")
                        .put("key", UUID.randomUUID().toString());
            if (!Set.of("offer", "calendars", "error", "dismissed", "saved")
                    .contains(entry.optString("phase")))
                throw new IOException("DINING_CALENDAR_SHARE_REQUIRED");
            if (entry.optString("phase").equals("dismissed"))
                entry.put("phase", entry.has("eventId") ? "saved" : "offer")
                        .put("key", UUID.randomUUID().toString());
            if (DiningBookingCalendarPolicy.needsPolling(entry)) ensurePolling(c, identity);
            if (entry.has("eventId")) {
                finishSaved(c, entry);
                DiningBookingRide.track(c, entry);
                DiningBookingCalendarIsland.show(c, entry);
                return entry;
            }
            ensureSelection(c, entry, watch, true);
            DiningBookingCalendarIsland.show(c, entry);
            return entry;
        }
        DiningBookingCalendarPolicy.current(entry, watch, args.getString("key"));
        String action = args.getString("action");
        if (!Set.of("offer", "calendars", "error").contains(entry.optString("phase"))
                && !(action.equals("dismiss") && entry.optString("phase").equals("saved")))
            throw new IOException("REQUEST_EXPIRED");
        if (action.equals("dismiss")) {
            entry.put("phase", "dismissed").put("key", UUID.randomUUID().toString());
            save(c, entry);
            if (!DiningBookingCalendarPolicy.needsPolling(entry)) ACTIVE.remove(identity);
            DiningBookingCalendarIsland.hide(c);
            return entry;
        }
        try {
            ensureSelection(c, entry, watch, false);
        } catch (IOException permission) {
            if ("DINING_CALENDAR_PERMISSION_REQUIRED".equals(permission.getMessage())) {
                DiningBookingCalendarIsland.show(c, entry);
            }
            throw permission;
        }
        if (action.equals("edit")) {
            if (!Set.of("offer", "error").contains(entry.optString("phase")))
                throw new IOException("REQUEST_EXPIRED");
            entry.put("phase", "calendars").put("key", UUID.randomUUID().toString());
            save(c, entry);
            DiningBookingCalendarIsland.show(c, entry);
            return entry;
        }
        if (action.equals("select")) {
            if (!entry.optString("phase").equals("calendars"))
                throw new IOException("REQUEST_EXPIRED");
            Long selected = DiningCalendarSelection.requestedId(args.opt("calendarId"));
            DiningCalendarSelection.choose(DiningReservationCalendar.calendars(c), selected);
            entry.put("calendarId", selected)
                    .put("phase", "offer")
                    .put("key", UUID.randomUUID().toString());
            entry.remove("existingEventId");
            save(c, entry);
            DiningBookingCalendarIsland.show(c, entry);
            return entry;
        }
        if (!action.equals("confirm")) throw new IOException("INVALID_DINING_ACTION");
        if (entry.optString("phase").equals("calendars")) throw new IOException("REQUEST_EXPIRED");
        if (!entry.has("calendarId")) {
            entry.put("phase", "calendars").put("key", UUID.randomUUID().toString());
            save(c, entry);
            DiningBookingCalendarIsland.show(c, entry);
            return entry;
        }
        JSONObject meal = entry.getJSONObject("meal");
        JSONObject fresh = current(c, entry.getString("watchId"));
        if (!latest(c, fresh)) throw new IOException("REQUEST_EXPIRED");
        DiningBookingCalendarPolicy.current(entry, fresh, args.getString("key"));
        entry.put("phase", "saving").put("key", UUID.randomUUID().toString());
        save(c, entry);
        DiningBookingCalendarIsland.hide(c);
        try {
            DiningReservationCalendar.Result saved =
                    DiningReservationCalendar.save(c, meal, entry.getLong("calendarId"));
            entry.put("eventId", saved.eventId())
                    .put("calendarId", saved.calendarId())
                    .put("eventExisting", saved.existing())
                    .put("phase", "saved");
            meal.put("eventId", saved.eventId()).put("calendarId", saved.calendarId());
            save(c, entry);
            finishSaved(c, entry);
            if (!DiningBookingCalendarPolicy.needsPolling(entry)) ACTIVE.remove(identity);
            DiningBookingRide.track(c, entry);
            DiningBookingCalendarIsland.show(c, entry);
            return entry;
        } catch (Exception failure) {
            if ("DINING_CALENDAR_PERMISSION_REQUIRED".equals(failure.getMessage())) {
                entry.put("permissionRequired", true);
            }
            entry.put("error", failure.getMessage())
                    .put("phase", entry.has("eventId") ? "saved" : "error");
            save(c, entry);
            DiningBookingCalendarIsland.show(c, entry);
            throw failure;
        }
    }

    /**
     * A save or Feed publication can be interrupted independently; recovery only reads and
     * republishes.
     */
    private static void finishSaved(Context c, JSONObject entry) throws Exception {
        JSONObject meal = entry.getJSONObject("meal");
        boolean found = false;
        for (var match : DiningReservationCalendar.existing(c, meal)) {
            if (match.eventId() == entry.getLong("eventId")
                    && match.calendarId() == entry.getLong("calendarId")) found = true;
        }
        if (!found) throw new IOException("DINING_CALENDAR_EVENT_CHANGED");
        if (entry.optBoolean("feedPublished")) return;
        meal.put("eventId", entry.getLong("eventId"))
                .put("calendarId", entry.getLong("calendarId"));
        JSONObject card =
                DiningBookingCalendarCard.calendar(
                        meal, entry.optLong("createdAt", System.currentTimeMillis()));
        provider(c, "feed/publish", new JSONObject().put("card", card));
        entry.put("feedPublished", true).put("feedCardId", card.getString("id"));
        save(c, entry);
    }

    static JSONObject current(Context c, String id) throws Exception {
        return DiningPollBooking.require(c, id);
    }

    private static boolean latest(Context c, JSONObject watch) throws Exception {
        return watch.getString("id")
                .equals(
                        DiningBookingCalendarPolicy.latestWatch(
                                DiningPollBooking.ledger(c), watch.getString("feedCardId")));
    }

    private static JSONObject provider(Context c, String method, JSONObject args) throws Exception {
        Bundle value =
                c.getContentResolver()
                        .call(
                                Uri.parse("content://org.agentos.agent.home-feed"),
                                method,
                                args.toString(),
                                null);
        JSONObject result = new JSONObject(value == null ? "{}" : value.getString("result", "{}"));
        if (!result.optBoolean("ok"))
            throw new IOException(result.optString("code", "DINING_FEED_UNAVAILABLE"));
        return result.getJSONObject("result");
    }
}
