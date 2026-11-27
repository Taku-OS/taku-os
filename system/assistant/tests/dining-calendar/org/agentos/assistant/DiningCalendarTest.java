package org.agentos.assistant;

import static org.agentos.assistant.DiningPollBookingFlow.*;

import org.json.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.time.*;
import java.util.*;

/**
 * Exercises receipt isolation, historical share recovery, local-time safety and explicit account
 * selection.
 */
public final class DiningCalendarTest {
    static int checks;

    interface Checked {
        void run() throws Exception;
    }

    static void check(boolean test, String reason) {
        checks++;
        if (!test) throw new AssertionError(reason);
    }

    static void rejected(String code, Checked work) throws Exception {
        checks++;
        try {
            work.run();
            throw new AssertionError("Accepted " + code);
        } catch (IOException error) {
            if (!code.equals(error.getMessage())) throw error;
        }
    }

    static JSONObject copy(JSONObject value) {
        return new JSONObject(value.toString());
    }

    static JSONObject watch(Instant now, ZonedDateTime when) throws Exception {
        Meal meal =
                new Meal(
                        "meal:test",
                        "11111111-1111-4111-8111-111111111111",
                        "group:test",
                        "test35",
                        "poll:test",
                        "Which restaurant?",
                        when,
                        3,
                        List.of(
                                new Restaurant("allora", "Allora Fifth Ave"),
                                new Restaurant("other", "Other restaurant")));
        Votes votes =
                new Votes(
                        meal.groupId(),
                        meal.pollId(),
                        meal.pollQuestion(),
                        List.of("allora", "other"),
                        List.of(1, 0),
                        null,
                        true,
                        1,
                        new Evidence(Source.WHATSAPP_POLL_UI, "poll:read", now));
        State offered = observe(State.start(meal), votes, now).state();
        State booking = respond(offered, offered.offer().key(), true, now).state();
        Receipt receipt =
                new Receipt(
                        ReceiptStatus.CONFIRMED,
                        booking.booking().key(),
                        "allora",
                        "Allora Fifth Ave",
                        when,
                        3,
                        "res123",
                        "",
                        "",
                        "",
                        new Evidence(
                                Source.OPENTABLE_RECEIPT_UI, "receipt:read", now.plusSeconds(1)));
        return new JSONObject()
                .put("id", "meal:test")
                .put(
                        "state",
                        DiningPollBookingCodec.state(
                                confirmed(booking, receipt, now.plusSeconds(1)).state()))
                .put("active", true)
                .put("bookingAddress", "292 5th Ave, New York, NY 10001")
                .put("feedCardId", "dining.booking.test")
                .put("feedPublishedAt", now.plusSeconds(2).toEpochMilli());
    }

    static void validateEvent(JSONObject meal) throws Exception {
        var method = DiningReservationCalendar.class.getDeclaredMethod("event", JSONObject.class);
        method.setAccessible(true);
        try {
            method.invoke(null, meal);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception x) throw x;
            throw e;
        }
    }

    public static void main(String[] args) throws Exception {
        Instant now = Instant.parse("2026-11-25T01:00:00Z");
        JSONObject watch =
                watch(now, ZonedDateTime.parse("2026-11-29T19:15-05:00[America/New_York]"));
        String fingerprint = DiningBookingCalendarPolicy.identity(watch);
        JSONObject meal = DiningBookingCalendarPolicy.meal(watch, now.toEpochMilli());
        check(
                meal.getLong("start") == Instant.parse("2026-11-30T00:15:00Z").toEpochMilli(),
                "Restaurant zone retains the exact reservation instant");
        check(
                meal.getString("zone").equals("America/New_York") && meal.getInt("partySize") == 3,
                "Receipt zone and party stay frozen");
        check(
                meal.getString("sourceThreadId")
                        .equals(
                                watch.getJSONObject("state")
                                        .getJSONObject("meal")
                                        .getString("sourceThreadId")),
                "Original thread retained");
        validateEvent(meal);
        checks++;
        JSONObject wrongTime = copy(meal).put("start", meal.getLong("start") + 3600000);
        rejected("DINING_CALENDAR_TIME_CHANGED", () -> validateEvent(wrongTime));
        JSONObject ambiguous =
                copy(meal)
                        .put("year", 2026)
                        .put("month", 11)
                        .put("day", 1)
                        .put("hour", 1)
                        .put("minute", 30);
        rejected("DINING_CALENDAR_LOCAL_TIME_AMBIGUOUS", () -> validateEvent(ambiguous));
        JSONObject fixedOffset = copy(meal).put("zone", "-05:00");
        rejected("DINING_CALENDAR_TIME_ZONE_INVALID", () -> validateEvent(fixedOffset));
        JSONObject wrongPeople = copy(watch);
        wrongPeople.getJSONObject("state").getJSONObject("receipt").put("people", 4);
        rejected(
                "DINING_CALENDAR_RECEIPT_UNVERIFIED",
                () -> DiningBookingCalendarPolicy.identity(wrongPeople));
        JSONObject wrongSource = copy(watch);
        wrongSource
                .getJSONObject("state")
                .getJSONObject("receipt")
                .getJSONObject("evidence")
                .put("source", "WHATSAPP_POLL_UI");
        rejected(
                "DINING_CALENDAR_RECEIPT_UNVERIFIED",
                () -> DiningBookingCalendarPolicy.identity(wrongSource));
        JSONObject wrongName = copy(watch);
        wrongName
                .getJSONObject("state")
                .getJSONObject("receipt")
                .put("restaurantName", "Another place");
        rejected(
                "DINING_CALENDAR_RECEIPT_UNVERIFIED",
                () -> DiningBookingCalendarPolicy.identity(wrongName));
        JSONObject migrated = copy(watch);
        migrated.put("restaurantAddress", migrated.remove("bookingAddress"));
        check(
                fingerprint.equals(DiningBookingCalendarPolicy.identity(migrated)),
                "Legacy verified address remains compatible");
        JSONObject yelpAlias =
                copy(migrated).put("bookingAddress", "292 5th Avenue, New York, NY 10001, USA");
        check(
                DiningBookingCalendarPolicy.address(yelpAlias)
                        .equals(migrated.getString("restaurantAddress")),
                "Yelp Avenue and USA suffix reuse the actual receipt address spelling");
        check(
                fingerprint.equals(DiningBookingCalendarPolicy.identity(yelpAlias)),
                "Equivalent Yelp address leaves the receipt fingerprint stable");
        JSONObject aliasMeal = DiningBookingCalendarPolicy.meal(yelpAlias, now.toEpochMilli());
        check(
                aliasMeal.getString("id").equals(meal.getString("id"))
                        && aliasMeal.getString("address").equals(meal.getString("address")),
                "Equivalent Yelp alias preserves the existing Calendar and Feed meal identity");
        JSONObject fullCountry =
                copy(migrated)
                        .put("bookingAddress", "292 5th Avenue, New York, NY 10001, United States");
        check(
                fingerprint.equals(DiningBookingCalendarPolicy.identity(fullCountry)),
                "Full country suffix follows the same upstream address verification");
        JSONObject changedStreet =
                copy(yelpAlias).put("bookingAddress", "293 5th Avenue, New York, NY 10001, USA");
        rejected(
                "DINING_CALENDAR_ADDRESS_CHANGED",
                () -> DiningBookingCalendarPolicy.address(changedStreet));
        JSONObject changedCity =
                copy(yelpAlias).put("bookingAddress", "292 5th Avenue, Brooklyn, NY 10001, USA");
        rejected(
                "DINING_CALENDAR_ADDRESS_CHANGED",
                () -> DiningBookingCalendarPolicy.address(changedCity));
        check(
                DiningBookingCalendarPolicy.address(watch)
                        .equals(watch.getString("bookingAddress")),
                "A current receipt without a legacy address keeps the frozen booking address");
        migrated.put("bookingAddress", "A different location");
        rejected(
                "DINING_CALENDAR_ADDRESS_CHANGED",
                () -> DiningBookingCalendarPolicy.identity(migrated));
        JSONObject wrongThread = copy(watch);
        wrongThread
                .getJSONObject("state")
                .getJSONObject("meal")
                .put("sourceThreadId", "22222222-2222-4222-8222-222222222222");
        check(
                !fingerprint.equals(DiningBookingCalendarPolicy.identity(wrongThread)),
                "Changed thread revokes previous consent key");
        JSONObject entry =
                new JSONObject()
                        .put("fingerprint", fingerprint)
                        .put("key", "consent:one")
                        .put("cardId", watch.getString("feedCardId"))
                        .put("receiptAt", DiningBookingCalendarPolicy.receiptAt(watch));
        DiningBookingCalendarPolicy.current(entry, watch, "consent:one");
        checks++;
        rejected(
                "REQUEST_EXPIRED",
                () -> DiningBookingCalendarPolicy.current(entry, wrongThread, "consent:one"));
        rejected(
                "REQUEST_EXPIRED",
                () -> DiningBookingCalendarPolicy.current(entry, watch, "stale:key"));
        String shareText = "Dinner reservation";
        String textHash =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                shareText.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        JSONObject event =
                new JSONObject()
                        .put("type", "event")
                        .put("schema", "taku.feed-event/1")
                        .put("op", "action")
                        .put("phase", "dispatched")
                        .put("actionSource", "declared")
                        .put("intentType", "share-text")
                        .put("actionId", "share")
                        .put("cardId", watch.getString("feedCardId"))
                        .put("cursor", "00000000-0000-4000-8000-000000000001:4")
                        .put("acceptedCursor", "00000000-0000-4000-8000-000000000001:3")
                        .put("at", now.plusSeconds(2).toEpochMilli())
                        .put(
                                "intent",
                                new JSONObject()
                                        .put("package", "com.whatsapp")
                                        .put("textSha256", textHash));
        long checkAt = now.plusSeconds(60).toEpochMilli();
        check(
                DiningBookingCalendarPolicy.dispatched(event, entry, shareText, checkAt),
                "Successful WhatsApp launch is recoverable without sharing again");
        check(
                !DiningBookingCalendarPolicy.dispatched(event, entry, "Different text", checkAt),
                "Different share content cannot offer a calendar event");
        for (JSONObject invalid :
                new JSONObject[] {
                    copy(event).put("phase", "accepted"),
                    copy(event)
                            .put(
                                    "intent",
                                    new JSONObject()
                                            .put("package", "other.app")
                                            .put("textSha256", textHash)),
                    copy(event).put("at", now.toEpochMilli()),
                    copy(event).put("cardId", "dining.booking.other"),
                    copy(event).put("actionSource", "ask-about"),
                    copy(event).put("at", now.plusSeconds(90).toEpochMilli()),
                    copy(event).put("at", Double.valueOf(now.toEpochMilli())),
                    copy(event).put("cursor", "------------------------------------:1"),
                    copy(event).put("acceptedCursor", "00000000-0000-4000-8000-000000000001:4"),
                    copy(event).put("acceptedCursor", "00000000-0000-4000-8000-000000000002:3"),
                })
            check(
                    !DiningBookingCalendarPolicy.dispatched(invalid, entry, shareText, checkAt),
                    "Failed, unrelated or malformed dispatch is rejected");
        JSONObject alias =
                copy(watch)
                        .put("id", "duplicate-watch")
                        .put("feedReceiptOwnerWatchId", watch.getString("id"))
                        .put("feedPublishedAt", now.plusSeconds(4).toEpochMilli());
        JSONObject ownership = new JSONObject().put("published", watch).put("alias", alias);
        check(
                watch.getString("id")
                        .equals(
                                DiningBookingCalendarPolicy.latestWatch(
                                        ownership, watch.getString("feedCardId"))),
                "A newer equivalent receipt link never transfers the card from its original group");
        ownership.remove("published");
        rejected(
                "DINING_CALENDAR_SHARE_CARD_AMBIGUOUS",
                () ->
                        DiningBookingCalendarPolicy.latestWatch(
                                ownership, watch.getString("feedCardId")));
        JSONObject pending = copy(entry).put("phase", "waiting_share").put("shareProofVersion", 1);
        JSONObject confirm =
                copy(event)
                        .put("phase", "accepted")
                        .put("origin", "gui")
                        .put("actionId", "confirm-dining-share")
                        .put("intentType", "confirm-dining-share")
                        .put("cursor", "00000000-0000-4000-8000-000000000001:5")
                        .put("at", now.plusSeconds(3).toEpochMilli());
        check(
                !DiningBookingCalendarPolicy.recordShareEvent(pending, confirm, shareText, checkAt),
                "Confirmation without the matching picker is insufficient");
        check(
                DiningBookingCalendarPolicy.recordShareEvent(pending, event, shareText, checkAt)
                        && "waiting_share".equals(pending.getString("phase"))
                        && !pending.has("shareConfirmedAt"),
                "Picker dispatch alone cannot offer Calendar");
        for (JSONObject invalid :
                new JSONObject[] {
                    copy(confirm).put("origin", "agent"),
                    copy(confirm).put("phase", "dispatched"),
                    copy(confirm).put("intentType", "none"),
                    copy(confirm).put("actionId", "share"),
                    copy(confirm).put("cardId", "dining.booking.other"),
                    copy(confirm).put("cursor", event.getString("cursor")),
                    copy(confirm).put("cursor", "00000000-0000-4000-8000-000000000001:2"),
                    copy(confirm).put("cursor", "00000000-0000-4000-8000-000000000002:8"),
                })
            check(
                    !DiningBookingCalendarPolicy.recordShareEvent(
                            pending, invalid, shareText, checkAt),
                    "Only the later GUI confirmation on this exact card is eligible");
        check(
                DiningBookingCalendarPolicy.recordShareEvent(pending, confirm, shareText, checkAt)
                        && "offer".equals(pending.getString("phase"))
                        && pending.getBoolean("shareConfirmedByUser"),
                "Matching confirmation after the picker unlocks Calendar");
        JSONObject old =
                copy(entry)
                        .put("phase", "offer")
                        .put("shareDispatchedAt", event.getLong("at"))
                        .put("shareEventCursor", event.getString("cursor"))
                        .put("feedCursor", event.getString("cursor"));
        check(
                DiningBookingCalendarPolicy.requireShareConfirmation(old)
                        && "waiting_share".equals(old.getString("phase"))
                        && !old.has("feedCursor")
                        && !old.has("shareEventCursor"),
                "Legacy launch-only offers require explicit confirmation");
        check(
                !DiningBookingCalendarPolicy.requireShareConfirmation(old),
                "Migration keeps current pending proof");
        JSONObject saved = copy(entry).put("phase", "saved").put("eventId", 42L);
        check(
                !DiningBookingCalendarPolicy.requireShareConfirmation(saved)
                        && "saved".equals(saved.getString("phase")),
                "Existing verified event remains saved");
        var c1 =
                new DiningCalendarSelection.Calendar(
                        1,
                        "Primary",
                        "one@example.test",
                        "one@example.test",
                        "com.google",
                        1,
                        700,
                        1,
                        0);
        var c2 =
                new DiningCalendarSelection.Calendar(
                        2,
                        "Primary",
                        "two@example.test",
                        "two@example.test",
                        "com.google",
                        1,
                        700,
                        1,
                        0);
        rejected(
                "DINING_CALENDAR_AMBIGUOUS",
                () -> DiningCalendarSelection.choose(List.of(c1, c2), null));
        check(
                DiningCalendarSelection.choose(List.of(c1, c2), 2L).id() == 2,
                "Multiple accounts require an explicit exact choice");
        rejected(
                "DINING_CALENDAR_SELECTION_INVALID",
                () -> DiningCalendarSelection.requestedId("2"));
        rejected(
                "DINING_CALENDAR_SELECTION_INVALID",
                () -> DiningCalendarSelection.choose(List.of(c1), 2L));
        check(
                !DiningCalendarSelection.eligible(
                        new DiningCalendarSelection.Calendar(
                                3,
                                "Shared",
                                "one@example.test",
                                "other@example.test",
                                "com.google",
                                1,
                                700,
                                1,
                                0)),
                "Shared owner cannot silently become target");
        meal.put("eventId", 42L).put("calendarId", 1L);
        JSONObject card = DiningBookingCalendarCard.calendar(meal, now.toEpochMilli());
        JSONObject intent =
                card.getJSONObject("actions")
                        .getJSONObject("open-calendar")
                        .getJSONObject("intent");
        check(
                intent.getString("type").equals("open-app")
                        && intent.getString("uri")
                                .equals("content://com.android.calendar/events/42"),
                "Feed opens the verified event using the current native vocabulary");
        check(
                card.getString("id")
                        .equals(
                                DiningBookingCalendarCard.calendar(
                                                meal, now.plusSeconds(30).toEpochMilli())
                                        .getString("id")),
                "Recovery publishes the same card ID");
        JSONObject calendarNotice =
                DiningFollowupIsland.payload(
                        "Add to Google Calendar?",
                        "Example dinner",
                        new JSONArray().put("confirm").put("edit").put("dismiss"),
                        "edit");
        JSONObject calendarChooser =
                DiningFollowupIsland.payload(
                        "Choose a calendar",
                        "Example dinner",
                        new JSONArray().put("select_42").put("dismiss"),
                        null);
        JSONObject rideNotice =
                DiningFollowupIsland.payload(
                        "Take an Uber to dinner?",
                        "Example dinner",
                        new JSONArray().put("confirm").put("edit").put("dismiss"),
                        "edit");
        check(
                calendarNotice.getInt("version") == 2
                        && !calendarNotice.has("compact")
                        && "basic"
                                .equals(
                                        calendarNotice
                                                .getJSONObject("expanded")
                                                .getString("template")),
                "Native Calendar offer uses the v2 card-only basic template");
        check(
                !calendarChooser.getJSONObject("expanded").has("tapAction")
                        && "select_42"
                                .equals(
                                        calendarChooser
                                                .getJSONObject("expanded")
                                                .getJSONArray("actions")
                                                .getString(0)),
                "Calendar selection preserves explicit action with a v2-safe key");
        check(
                "edit".equals(rideNotice.getJSONObject("expanded").getString("tapAction")),
                "The Uber card opens the input action without placing an order");
        JSONObject capabilities = DiningCapabilities.snapshot();
        check(
                capabilities.getBoolean("calendarFollowUp")
                        && capabilities.getBoolean("rideFollowUp"),
                "Dining capabilities advertise enabled Calendar and ride follow-ups");
        JSONObject permissionEntry =
                new JSONObject()
                        .put("phase", "offer")
                        .put("calendarId", 42L)
                        .put("existingEventId", 7L);
        DiningBookingCalendarPolicy.recordPermissionRequired(permissionEntry, meal);
        check(
                DiningBookingCalendarPolicy.permissionRequired(permissionEntry)
                        && "error".equals(permissionEntry.getString("phase"))
                        && "DINING_CALENDAR_PERMISSION_REQUIRED"
                                .equals(permissionEntry.getString("error"))
                        && !permissionEntry.has("calendarId")
                        && permissionEntry.has("meal")
                        && DiningBookingCalendarPolicy.needsPolling(permissionEntry),
                "Missing Calendar permission keeps the offer visible for recovery instead of stalling");
        DiningBookingCalendarPolicy.clearPermissionRequired(permissionEntry);
        check(
                !DiningBookingCalendarPolicy.permissionRequired(permissionEntry)
                        && "offer".equals(permissionEntry.getString("phase"))
                        && !permissionEntry.has("error"),
                "Granting Calendar permission clears the error and restores the offer");
        check(
                DiningBookingCalendarPolicy.needsPolling(
                                new JSONObject().put("phase", "waiting_share"))
                        && DiningBookingCalendarPolicy.needsPolling(
                                new JSONObject()
                                        .put("phase", "saved")
                                        .put("eventId", 42L)
                                        .put("feedPublished", false))
                        && !DiningBookingCalendarPolicy.needsPolling(
                                new JSONObject()
                                        .put("phase", "saved")
                                        .put("eventId", 42L)
                                        .put("feedPublished", true))
                        && !DiningBookingCalendarPolicy.needsPolling(
                                new JSONObject().put("phase", "dismissed")),
                "Calendar background polling stops once an entry is settled or dismissed");
        JSONObject permissionNotice =
                DiningFollowupIsland.payload(
                        "Add to Google Calendar?",
                        "Example dinner\nAllow Calendar permission for Taku System Assistant, then try again.",
                        new JSONArray().put("settings").put("confirm").put("dismiss"),
                        "settings");
        check(
                "settings".equals(permissionNotice.getJSONObject("expanded").getString("tapAction"))
                        && "settings"
                                .equals(
                                        permissionNotice
                                                .getJSONObject("expanded")
                                                .getString("primaryAction")),
                "Calendar permission-required Island points to app settings");
        if (args.length > 1)
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(args[1]),
                    new JSONArray()
                            .put(calendarNotice)
                            .put(calendarChooser)
                            .put(rideNotice)
                            .toString(2));
        if (args.length > 0)
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), card.toString(2));
        System.out.println("DiningCalendarTest: " + checks + " checks passed");
    }
}
