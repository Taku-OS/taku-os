package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;

/** Production consent, event provenance, explicit input and time-zone boundary checks. */
public final class DiningRidePolicyTest {
    private static int checks;

    private interface Checked {
        void run() throws Exception;
    }

    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    private static void reject(String code, Checked action) throws Exception {
        checks++;
        try {
            action.run();
            throw new AssertionError("Accepted " + code);
        } catch (IOException failure) {
            if (!code.equals(failure.getMessage())) throw failure;
        }
    }

    private static JSONObject copy(JSONObject value) {
        return new JSONObject(value.toString());
    }

    private static void nativeInputLifecycle(JSONObject meal, long now, String fingerprint)
            throws Exception {
        String thread = "11111111-1111-4111-8111-111111111111";
        JSONObject entry =
                new JSONObject()
                        .put("phase", "offered")
                        .put("fingerprint", fingerprint)
                        .put("meal", meal)
                        .put("key", "original-consent");
        JSONObject input = DiningRideInputState.begin(entry, thread, "confirm", now);
        check(
                "collecting_input".equals(entry.getString("phase"))
                        && !entry.has("taskId")
                        && !entry.has("requestId"),
                "Opening the real native form starts no model task or submission");
        check(
                input.getString("pickup").isEmpty()
                        && input.getString("departureLocal").equals("2026-11-29T18:45"),
                "Native form begins with empty pickup and clearly suggested restaurant-local time");
        String key = input.getString("key");
        DiningRideInputState.draft(entry, key, "  Bryant Park, New York  ", "2026-11-29T18:45");
        JSONObject restored = copy(entry);
        check(
                DiningRideInputState.begin(restored, thread, "edit", now + 20 * 60_000L)
                                .getString("key")
                                .equals(key)
                        && restored.getJSONObject("input")
                                .getString("pickup")
                                .equals("  Bryant Park, New York  ")
                        && !restored.has("taskId"),
                "Process restore and a long fill wait preserve the native draft without any SDK"
                        + " request or task");
        reject(
                "REQUEST_EXPIRED",
                () -> DiningRideInputState.begin(copy(restored), "other-thread", "edit", now));
        reject(
                "REQUEST_EXPIRED",
                () ->
                        DiningRideInputState.draft(
                                copy(restored), "expired-key", "bad", "2026-11-29T18:45"));
        reject(
                "REQUEST_EXPIRED",
                () ->
                        DiningRideInputState.require(
                                copy(restored).put("fingerprint", "other-receipt"), key));
        reject(
                "REQUEST_EXPIRED",
                () ->
                        DiningRideInputState.require(
                                copy(restored).put("sourceThreadId", "other-thread"), key));
        reject(
                "DINING_RIDE_PICKUP_REQUIRED",
                () ->
                        DiningRideInputState.accept(
                                copy(restored), key, "", "2026-11-29T18:45", now));
        reject(
                "DINING_RIDE_TIME_INVALID",
                () ->
                        DiningRideInputState.accept(
                                copy(restored), key, "Bryant Park", "2026-11-29T19:15", now));
        check(
                DiningRideInputState.accept(
                        restored, key, "Bryant Park, New York", "2026-11-29T18:45", now),
                "Only actual complete input creates the persisted executable plan");
        check(
                "input_ready".equals(restored.getString("phase"))
                        && !restored.getJSONObject("ridePlan").getBoolean("suggestedTime")
                        && restored.getJSONObject("ridePlan").getLong("pickupAt")
                                == Instant.parse("2026-11-29T23:45:00Z").toEpochMilli(),
                "Validation persists exact restaurant-local time and actual pickup before"
                        + " publication");
        String request = restored.getString("requestId");
        long attempt = restored.getLong("attempt");
        check(
                !DiningRideInputState.accept(
                                restored,
                                key,
                                "  Bryant Park,   New York ",
                                "2026-11-29T18:45",
                                now + 60_000)
                        && request.equals(restored.getString("requestId"))
                        && attempt == restored.getLong("attempt"),
                "Double delivery reuses one persisted submission identity");
        JSONObject afterCrash =
                copy(restored).put("phase", "queued").put("taskId", "1790000000011");
        check(
                !DiningRideInputState.accept(
                                afterCrash,
                                key,
                                "Bryant Park, New York",
                                "2026-11-29T18:45",
                                now + 30 * 60_000L)
                        && afterCrash.getString("taskId").equals("1790000000011"),
                "Post-publication process death and replay cannot create another task");
        reject(
                "REQUEST_ID_REUSED",
                () ->
                        DiningRideInputState.accept(
                                afterCrash, key, "Different pickup", "2026-11-29T18:45", now));
        reject("REQUEST_EXPIRED", () -> DiningRideInputState.cancel(afterCrash, key));
        JSONObject cancelled = copy(entry);
        DiningRideInputState.cancel(cancelled, key);
        check(
                "input_cancelled".equals(cancelled.getString("phase"))
                        && !cancelled.has("requestId")
                        && !cancelled.has("taskId"),
                "Cancel never publishes work");
        JSONObject reopened = DiningRideInputState.begin(cancelled, thread, "edit", now);
        check(
                !key.equals(reopened.getString("key"))
                        && reopened.getString("pickup").equals("  Bryant Park, New York  "),
                "Explicit reopening invalidates old form key and preserves the user's draft");
        reject(
                "REQUEST_EXPIRED",
                () ->
                        DiningRideInputState.accept(
                                cancelled, key, "Bryant Park", "2026-11-29T18:45", now));
    }

    private static void publicationAndHandoffRecovery(JSONObject meal, long now, String fingerprint)
            throws Exception {
        String thread = "11111111-1111-4111-8111-111111111111", task = "1790000000011";
        JSONObject e =
                new JSONObject()
                        .put("phase", "offered")
                        .put("fingerprint", fingerprint)
                        .put("meal", meal)
                        .put("key", "offer");
        JSONObject input = DiningRideInputState.begin(e, thread, "confirm", now);
        DiningRideInputState.accept(
                e, input.getString("key"), "Bryant Park", "2026-11-29T18:45", now);
        e.put("phase", "submitting")
                .put("taskId", task)
                .put("prompt", "Exact bound execution prompt");
        JSONObject receipt =
                new JSONObject()
                        .put("state", "deliveryUnknown")
                        .put("taskId", task)
                        .put("threadId", thread)
                        .put("requestId", e.getString("requestId"));
        JSONObject job =
                new JSONObject()
                        .put("id", task)
                        .put("conversationId", thread)
                        .put("composerRequestId", e.getString("requestId"))
                        .put("prompt", e.getString("prompt"));
        JSONObject state =
                new JSONObject()
                        .put("id", task)
                        .put("state", "queued")
                        .put("prompt", e.getString("prompt"));
        JSONObject audit =
                DiningRidePreparationHistory.unpublished(
                        e, receipt, job, state, false, false, false, false);
        check(
                "not-published".equals(audit.getString("publicationStatus"))
                        && "not-attested".equals(audit.getString("orderStatus"))
                        && e.getString("taskId").equals(task),
                "Exact before-ready crash can be audited without deleting or falsely completing the"
                        + " old task");
        for (int marker = 0; marker < 4; marker++) {
            final int present = marker;
            reject(
                    "DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED",
                    () ->
                            DiningRidePreparationHistory.unpublished(
                                    e,
                                    receipt,
                                    job,
                                    state,
                                    present == 0,
                                    present == 1,
                                    present == 2,
                                    present == 3));
        }
        reject(
                "DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED",
                () ->
                        DiningRidePreparationHistory.unpublished(
                                e,
                                copy(receipt).put("requestId", "other"),
                                job,
                                state,
                                false,
                                false,
                                false,
                                false));
        reject(
                "DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED",
                () ->
                        DiningRidePreparationHistory.unpublished(
                                e,
                                receipt,
                                copy(job).put("composerRequestId", "other"),
                                state,
                                false,
                                false,
                                false,
                                false));
        reject(
                "DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED",
                () ->
                        DiningRidePreparationHistory.unpublished(
                                e,
                                receipt,
                                job,
                                copy(state).put("state", "running"),
                                false,
                                false,
                                false,
                                false));
        reject(
                "DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED",
                () ->
                        DiningRidePreparationHistory.unpublished(
                                e,
                                copy(receipt).put("state", "queued"),
                                job,
                                state,
                                false,
                                false,
                                false,
                                false));
        JSONObject evidence =
                new JSONObject()
                        .put("threadId", thread)
                        .put("screenKey", "task-0123456789abcdef0123")
                        .put("generation", "22222222-2222-4222-8222-222222222222")
                        .put("displayId", 8)
                        .put("userId", 0);
        JSONObject screen =
                copy(evidence)
                        .put("status", "awaiting_review")
                        .put("admitted", true)
                        .put("stopping", false)
                        .put("packageName", "com.ubercab");
        JSONObject available =
                DiningRidePreparationHistory.handoffAvailability(
                        evidence, new JSONArray().put(screen), now);
        check(
                available.getBoolean("previewAvailable") && !available.getBoolean("quoteCurrent"),
                "Exact live handoff lease permits preview but never attests that an old quote is"
                        + " current");
        check(
                "released"
                        .equals(
                                DiningRidePreparationHistory.handoffAvailability(
                                                evidence, new JSONArray(), now)
                                        .getString("status")),
                "Broker restart or screen release leaves only historical handoff evidence");
        check(
                "released"
                        .equals(
                                DiningRidePreparationHistory.handoffAvailability(
                                                evidence,
                                                new JSONArray()
                                                        .put(
                                                                copy(screen)
                                                                        .put(
                                                                                "generation",
                                                                                "replacement")),
                                                now)
                                        .getString("status")),
                "Replaced screen generation cannot appear as the previous handoff");
        check(
                !DiningRidePreparationHistory.handoffAvailability(
                                evidence,
                                new JSONArray().put(copy(screen).put("stopping", true)),
                                now)
                        .getBoolean("previewAvailable"),
                "A stopping lease cannot promise a live preview");
        check(
                !DiningRidePreparationHistory.handoffAvailability(
                                evidence,
                                new JSONArray().put(copy(screen).put("packageName", "com.other")),
                                now)
                        .getBoolean("previewAvailable"),
                "A changed App on the same display cannot attest an Uber preview");
    }

    public static void main(String[] args) throws Exception {
        long now = Instant.parse("2026-11-25T01:00:00Z").toEpochMilli();
        JSONObject receipt =
                new JSONObject()
                        .put("status", "CONFIRMED")
                        .put("bookingKey", "booking:one")
                        .put("restaurantId", "allora")
                        .put("restaurantName", "Allora Fifth Ave")
                        .put("when", "2026-11-29T19:15-05:00[America/New_York]")
                        .put("people", 3)
                        .put("confirmationCode", "")
                        .put("seating", "")
                        .put("restaurantUrl", "")
                        .put("photoUrl", "")
                        .put(
                                "evidence",
                                new JSONObject()
                                        .put("source", "OPENTABLE_RECEIPT_UI")
                                        .put("id", "native:receipt")
                                        .put("at", "2026-11-24T12:00:00Z"));
        JSONObject watch =
                new JSONObject()
                        .put("id", "meal:one")
                        .put("active", true)
                        .put("feedCardId", "booking.card")
                        .put("restaurantAddress", "292 Fifth Ave, New York, NY 10001")
                        .put(
                                "state",
                                new JSONObject()
                                        .put("phase", "CONFIRMED")
                                        .put("receipt", receipt)
                                        .put("offerSequence", 1)
                                        .put("declined", new JSONArray())
                                        .put("sharePrepared", false)
                                        .put("calendarAccepted", false)
                                        .put("calendarAsked", false)
                                        .put(
                                                "booking",
                                                new JSONObject()
                                                        .put("key", "booking:one")
                                                        .put("restaurant", "allora")
                                                        .put("consentAt", "2026-11-24T11:00:00Z")
                                                        .put("taskId", "1790000000000"))
                                        .put(
                                                "meal",
                                                new JSONObject()
                                                        .put("id", "meal:one")
                                                        .put("when", receipt.getString("when"))
                                                        .put("people", 3)
                                                        .put("groupId", "group:one")
                                                        .put("pollId", "poll:one")
                                                        .put("pollQuestion", "Which restaurant?")
                                                        .put(
                                                                "restaurants",
                                                                new JSONArray()
                                                                        .put(
                                                                                new JSONObject()
                                                                                        .put(
                                                                                                "id",
                                                                                                "allora")
                                                                                        .put(
                                                                                                "name",
                                                                                                "Allora"
                                                                                                    + " Fifth"
                                                                                                    + " Ave"))
                                                                        .put(
                                                                                new JSONObject()
                                                                                        .put(
                                                                                                "id",
                                                                                                "piazza")
                                                                                        .put(
                                                                                                "name",
                                                                                                "La Piazza")))
                                                        .put("groupName", "Original dining group")
                                                        .put(
                                                                "sourceThreadId",
                                                                "11111111-1111-4111-8111-111111111111")));
        JSONObject meal = DiningBookingCalendarPolicy.meal(watch, now);
        JSONObject calendar =
                new JSONObject()
                        .put("id", "calendar:one")
                        .put("watchId", watch.getString("id"))
                        .put("fingerprint", DiningBookingCalendarPolicy.identity(watch))
                        .put("phase", "saved")
                        .put("feedPublished", true)
                        .put("eventId", 17)
                        .put("calendarId", 8)
                        .put("feedCardId", "calendar.card");
        String identity = DiningBookingRidePolicy.identity(calendar, watch);
        check(
                identity.equals(DiningBookingRidePolicy.identity(calendar, watch)),
                "Same saved event has one ride identity");
        check(
                !identity.equals(
                        DiningBookingRidePolicy.identity(copy(calendar).put("eventId", 18), watch)),
                "Different saved event cannot share a ride decision");
        JSONObject otherThread = copy(watch);
        otherThread
                .getJSONObject("state")
                .getJSONObject("meal")
                .put("sourceThreadId", "22222222-2222-4222-8222-222222222222");
        reject(
                "DINING_RIDE_CALENDAR_NOT_SAVED",
                () -> DiningBookingRidePolicy.identity(calendar, otherThread));
        reject(
                "DINING_RIDE_CALENDAR_NOT_SAVED",
                () ->
                        DiningBookingRidePolicy.identity(
                                copy(calendar).put("feedPublished", false), watch));
        reject(
                "DINING_RIDE_CALENDAR_NOT_SAVED",
                () ->
                        DiningBookingRidePolicy.identity(
                                copy(calendar).put("phase", "confirming"), watch));

        JSONObject entry =
                new JSONObject()
                        .put("calendarFeedCardId", "calendar.card")
                        .put("notBefore", now - 1_000)
                        .put("phase", "offered")
                        .put("key", "consent:one")
                        .put("eventId", 17);
        JSONObject click =
                new JSONObject()
                        .put("type", "event")
                        .put("schema", "taku.feed-event/1")
                        .put("op", "action")
                        .put("phase", "dispatched")
                        .put("actionSource", "declared")
                        .put("intentType", "open-app")
                        .put("actionId", "open-calendar")
                        .put("cardId", "calendar.card")
                        .put("cursor", "11111111-1111-4111-8111-111111111111:5")
                        .put("at", now)
                        .put("acceptedCursor", "11111111-1111-4111-8111-111111111111:4")
                        .put(
                                "intent",
                                new JSONObject()
                                        .put("package", "com.google.android.calendar")
                                        .put("uri", "content://com.android.calendar/events/17"));
        check(
                DiningBookingRidePolicy.click(click, entry, now),
                "The Calendar launch opened the verified event");
        for (JSONObject invalid :
                new JSONObject[] {
                    copy(click).put("cardId", "other.card"),
                    copy(click).put("actionId", "share"),
                    copy(click).put("phase", "accepted"),
                    copy(click).put("actionSource", "model"),
                    copy(click)
                            .put(
                                    "intent",
                                    new JSONObject()
                                            .put("package", "com.google.android.calendar")
                                            .put(
                                                    "uri",
                                                    "content://com.android.calendar/events/18")),
                    copy(click).put("intentType", "ask-taku"),
                    copy(click).put("at", now - 2_000),
                    copy(click).put("at", now + 6_000),
                    copy(click).put("at", Long.toString(now)),
                    copy(click).put("cursor", "untrusted"),
                    copy(click).put("cursor", "------------------------------------:5")
                }) {
            check(
                    !DiningBookingRidePolicy.click(invalid, entry, now),
                    "Unrelated or unaccepted click cannot unlock preparation");
        }
        entry.put("calendarDispatchCursor", click.getString("cursor")).put("calendarOpenedAt", now);
        check(
                !DiningBookingRidePolicy.detailsWereLeft(entry, "calendar-not-visible"),
                "Dispatch alone cannot offer a ride");
        entry.put("detailDispatchCursor", click.getString("acceptedCursor"))
                .put("detailCheckedAt", now);
        check(
                !DiningBookingRidePolicy.detailsWereLeft(entry, "calendar-not-visible"),
                "Stale detail proof cannot arm an offer");
        entry.put("detailDispatchCursor", click.getString("cursor"));
        check(
                !DiningBookingRidePolicy.detailsWereLeft(entry, "matching-event-details"),
                "Leave details before offering a ride");
        check(
                !DiningBookingRidePolicy.detailsWereLeft(entry, "calendar-details-unavailable"),
                "A failed read is not departure evidence");
        check(
                DiningBookingRidePolicy.detailsWereLeft(entry, "event-detail-not-visible"),
                "The matching details were viewed and left");
        JSONObject detailMeal =
                new JSONObject()
                        .put("restaurant", "Allora")
                        .put("address", "292 Fifth Ave, New York")
                        .put("zone", "America/New_York")
                        .put(
                                "start",
                                java.time.ZonedDateTime.parse(
                                                "2026-11-02T19:15:00-05:00[America/New_York]")
                                        .toInstant()
                                        .toEpochMilli());
        check(
                "matching-event-details"
                        .equals(
                                DiningBookingRidePolicy.matchDetails(
                                        detailMeal,
                                        java.util.List.of(
                                                "Allora",
                                                "292 Fifth Ave, New York",
                                                "Nov 2 · 7:15 PM"),
                                        true)),
                "Actual Calendar detail surface matches title, address, date and start time");
        check(
                "date-or-time-mismatch"
                        .equals(
                                DiningBookingRidePolicy.matchDetails(
                                        detailMeal,
                                        java.util.List.of(
                                                "Allora",
                                                "292 Fifth Ave, New York",
                                                "Nov 20 · 7:15 PM"),
                                        true)),
                "A longer day number cannot be mistaken for the exact reservation date");
        check(
                "address-mismatch"
                        .equals(
                                DiningBookingRidePolicy.matchDetails(
                                        detailMeal,
                                        java.util.List.of(
                                                "Allora",
                                                "1292 Fifth Ave, New York",
                                                "Nov 2 · 7:15 PM"),
                                        true)),
                "Same name at another address does not count as viewing this event");
        check(
                "event-detail-not-visible"
                        .equals(
                                DiningBookingRidePolicy.matchDetails(
                                        detailMeal,
                                        java.util.List.of(
                                                "Allora",
                                                "292 Fifth Ave, New York",
                                                "Nov 2 · 7:15 PM"),
                                        false)),
                "Calendar list rows cannot establish that details were seen");
        DiningBookingRidePolicy.action(entry, "consent:one", "confirm");
        reject("REQUEST_EXPIRED", () -> DiningBookingRidePolicy.action(entry, "stale", "confirm"));
        reject(
                "INVALID_DINING_ACTION",
                () -> DiningBookingRidePolicy.action(entry, "consent:one", "order"));
        reject(
                "REQUEST_EXPIRED",
                () ->
                        DiningBookingRidePolicy.action(
                                copy(entry).put("phase", "queued"), "consent:one", "confirm"));

        JSONObject draft = DiningRidePlan.draft(meal);
        check(
                draft.getString("pickup").isEmpty() && draft.getBoolean("suggestedTime"),
                "Pickup always needs actual user input");
        check(
                "2026-11-29T18:45".equals(draft.getString("departureLocal")),
                "Suggested departure remains restaurant-local");
        JSONObject plan =
                DiningRidePlan.validate(
                        meal, "  Bryant Park,   New York  ", "2026-11-29T18:45", now);
        check(
                "Bryant Park, New York".equals(plan.getString("pickup")),
                "Validated actual address is normalized");
        check(
                plan.getLong("pickupAt") == Instant.parse("2026-11-29T23:45:00Z").toEpochMilli(),
                "New York local time converts to exact instant");
        reject(
                "DINING_RIDE_PICKUP_REQUIRED",
                () -> DiningRidePlan.validate(meal, "", "2026-11-29T18:45", now));
        reject(
                "DINING_RIDE_PICKUP_REQUIRED",
                () ->
                        DiningRidePlan.validate(
                                meal, "address\u202Ehidden", "2026-11-29T18:45", now));
        reject(
                "DINING_RIDE_TIME_INVALID",
                () -> DiningRidePlan.validate(meal, "Bryant Park", "2026-11-29T19:15", now));
        reject(
                "DINING_RIDE_TIME_INVALID",
                () -> DiningRidePlan.validate(meal, "Bryant Park", "2026-11-29T18:45:15", now));
        reject(
                "DINING_RIDE_TIME_INVALID",
                () -> DiningRidePlan.validate(meal, "Bryant Park", "2026-02-30T18:45", now));
        reject(
                "DINING_RIDE_TIME_INVALID",
                () ->
                        DiningRidePlan.validate(
                                meal,
                                "Bryant Park",
                                "2026-11-29T18:45",
                                plan.getLong("pickupAt") - 29 * 60_000L));
        JSONObject dstMeal =
                copy(meal).put("start", Instant.parse("2026-11-05T00:00:00Z").toEpochMilli());
        reject(
                "DINING_RIDE_TIME_AMBIGUOUS",
                () ->
                        DiningRidePlan.validate(
                                dstMeal,
                                "Bryant Park",
                                "2026-11-01T01:30",
                                Instant.parse("2026-10-30T00:00:00Z").toEpochMilli()));
        reject(
                "DINING_RIDE_TIME_AMBIGUOUS",
                () ->
                        DiningRidePlan.validate(
                                dstMeal,
                                "Bryant Park",
                                "2026-03-08T02:30",
                                Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()));

        JSONObject promptEntry =
                new JSONObject()
                        .put("watchId", "meal:one")
                        .put("eventId", 17)
                        .put("calendarId", 8)
                        .put("receiptFingerprint", calendar.getString("fingerprint"))
                        .put("meal", meal)
                        .put("ridePlan", plan)
                        .put("planValidatedAt", now);
        String prompt = DiningBookingRidePolicy.prompt(promptEntry, "confirm");
        check(
                !prompt.contains("taku-input/1:")
                        && prompt.contains("不要重新填写")
                        && prompt.contains("原生表单"),
                "Execution starts with persisted real native answers and never asks for the same"
                        + " input again");
        check(
                prompt.contains("pickup")
                        && prompt.contains("departureLocal")
                        && prompt.contains("pickupAt")
                        && prompt.contains("America/New_York"),
                "Form contains explicit pickup, local departure and zone");
        check(
                prompt.contains("request_apps")
                        && prompt.contains("screen_status")
                        && prompt.contains("最终Request/Reserve"),
                "Managed execution prepares and stops before ordering");
        check(
                prompt.contains("不得重新订位")
                        && prompt.contains("发送WhatsApp")
                        && prompt.contains("同一任务"),
                "Continuation preserves the original reservation and task draft");
        check(
                !prompt.contains("assistant/present-task")
                        && !prompt.contains("setPickup")
                        && !prompt.contains("app-task/claim"),
                "No retired or standalone Uber dispatch");
        reject(
                "DINING_RIDE_INPUT_REQUIRED",
                () ->
                        DiningBookingRidePolicy.prompt(
                                copy(promptEntry).put("ridePlan", draft), "confirm"));
        check(
                prompt.contains("inspect_uber_upcoming")
                        && prompt.contains("过去行程空")
                        && prompt.contains("handoff_for_review"),
                "Preparation requires native upcoming proof and same-screen user handover");
        nativeInputLifecycle(meal, now, identity);
        publicationAndHandoffRecovery(meal, now, identity);
        JSONObject current =
                new JSONObject()
                        .put("id", "ride:new")
                        .put("watchId", "meal:one")
                        .put("eventId", 17)
                        .put("calendarId", 8);
        JSONObject previous =
                new JSONObject()
                        .put("id", "ride:old")
                        .put("watchId", "meal:one")
                        .put("eventId", 17)
                        .put("calendarId", 8)
                        .put("phase", "queued")
                        .put("taskId", "1790000000000")
                        .put("requestId", "33333333-3333-4333-8333-333333333333")
                        .put("sourceThreadId", "11111111-1111-4111-8111-111111111111")
                        .put("prompt", "Prior only-preparation instruction");
        check(
                DiningRidePreparationHistory.relevant(previous, current),
                "New fingerprint still finds old same-watch task");
        check(
                DiningRidePreparationHistory.relevant(
                        copy(previous).put("watchId", "meal:changed"), current),
                "Same real event cannot bypass history with another watch");
        check(
                !DiningRidePreparationHistory.relevant(
                        copy(previous).put("watchId", "other").put("eventId", 18), current),
                "Unrelated event and watch do not collide");
        check(
                !DiningRidePreparationHistory.relevant(
                        copy(current).put("phase", "queued"), current),
                "Same journal entry is not a migration replay");
        reject(
                "DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED",
                () -> DiningRidePreparationHistory.task(copy(previous).put("phase", "submitting")));
        reject(
                "DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED",
                () -> DiningRidePreparationHistory.task(copy(previous).put("phase", "unknown")));
        reject(
                "DINING_RIDE_PRIOR_ORDER_REVIEW_REQUIRED",
                () -> DiningRidePreparationHistory.task(copy(previous).put("rideRequested", true)));
        JSONObject job =
                new JSONObject()
                        .put("id", previous.getString("taskId"))
                        .put("conversationId", previous.getString("sourceThreadId"))
                        .put("composerRequestId", previous.getString("requestId"))
                        .put("prompt", previous.getString("prompt"));
        JSONObject terminalState =
                new JSONObject()
                        .put("id", previous.getString("taskId"))
                        .put("state", "completed")
                        .put("prompt", previous.getString("prompt"));
        String thread = previous.getString("sourceThreadId");
        JSONObject audit =
                DiningRidePreparationHistory.audit(
                        previous, job, terminalState, "completed", thread);
        check(
                "not-attested".equals(audit.getString("orderStatus"))
                        && audit.getBoolean("newPreparationRequiresCurrentConsent"),
                "Terminal task audit never attests an unplaced order");
        reject(
                "DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED",
                () ->
                        DiningRidePreparationHistory.audit(
                                previous, job, terminalState, "running", thread));
        reject(
                "DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED",
                () ->
                        DiningRidePreparationHistory.audit(
                                previous,
                                copy(job).put("conversationId", "other"),
                                terminalState,
                                "completed",
                                thread));
        reject(
                "DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED",
                () ->
                        DiningRidePreparationHistory.audit(
                                previous,
                                copy(job).put("composerRequestId", "other"),
                                terminalState,
                                "completed",
                                thread));
        reject(
                "DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED",
                () ->
                        DiningRidePreparationHistory.audit(
                                previous,
                                job,
                                copy(terminalState).put("state", "running"),
                                "completed",
                                thread));
        check(
                prompt.contains("Upcoming")
                        && prompt.contains("这是用户提交原生表单后的同一次仅准备任务")
                        && prompt.contains("不证明从未下单"),
                "New preparation checks actual prior orders and never replays old requests");
        System.out.println("DiningRidePolicyTest: " + checks + " checks passed");
    }
}
