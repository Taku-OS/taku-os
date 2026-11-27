package org.agentos.assistant;

import static org.agentos.assistant.DiningPollBookingFlow.*;
import static org.agentos.assistant.DiningPollIslandTest.*;

import org.json.JSONObject;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

public final class DiningBookingClaimTest {
    private static final Instant NOW = Instant.parse("2026-11-20T17:00:00Z");
    private static final ZonedDateTime MEAL =
            ZonedDateTime.parse("2026-11-29T19:15-05:00[America/New_York]");
    private static final String OLD_NAME = "Harbor Café — Union Square",
            NEW_NAME = "harbor café - union square";
    private static final String OLD_ADDRESS = "1 Main Street, New York, NY",
            NEW_ADDRESS = "1 Main St, New York, NY";

    public static void main(String[] args) throws Exception {
        State first = working("poll-a", OLD_NAME, MEAL, 3),
                second = working("poll-b", NEW_NAME, MEAL, 3);
        JSONObject one = watch("watch-a", first, OLD_ADDRESS, 0),
                two = watch("watch-b", second, NEW_ADDRESS, 0);
        JSONObject ledger = new JSONObject().put("watch-a", one).put("watch-b", two);
        JSONObject attempt = new JSONObject().put("userId", 0).put("outcome", "unknown");
        DiningBookingClaim.reserve(ledger, "watch-a", first.booking(), attempt, 0);
        check(!two.has("openTableSubmission"), "claim changes only the owning watch");
        JSONObject recovered = new JSONObject(ledger.toString());
        rejects(
                "BOOKING_SUBMISSION_UNAVAILABLE",
                () ->
                        DiningBookingClaim.reserve(
                                recovered, "watch-a", first.booking(), attempt, 0));
        rejects(
                "BOOKING_EQUIVALENT_OUTCOME_UNKNOWN",
                () ->
                        DiningBookingClaim.reserve(
                                recovered, "watch-b", second.booking(), attempt, 0));

        String oldCard = "dining.reservation." + "a".repeat(24);
        JSONObject original =
                watch("receipt-a", confirmed(first), OLD_ADDRESS, 0)
                        .put("restaurantAddress", OLD_ADDRESS)
                        .put("feedCardId", oldCard)
                        .put("feedPublishedAt", NOW.toEpochMilli())
                        .put("active", false);
        recovered.put("receipt-a", original);
        rejects(
                "BOOKING_RESERVATION_ALREADY_CONFIRMED",
                () ->
                        DiningBookingClaim.reserve(
                                recovered, "watch-b", second.booking(), attempt, 0));
        JSONObject anotherReading =
                watch("receipt-b", confirmed(second), NEW_ADDRESS, 0)
                        .put("restaurantAddress", NEW_ADDRESS);
        var linked = DiningBookingClaim.publishedReceipt(recovered, anotherReading, 0);
        check(
                linked != null
                        && oldCard.equals(linked.cardId())
                        && "receipt-a".equals(linked.ownerWatchId())
                        && linked.publishedAt() == NOW.toEpochMilli(),
                "equivalent latest receipt keeps legacy Feed identity and owner");
        check(!original.has("feedReceiptOwnerWatchId"), "legacy author row stays unchanged");
        check(
                DiningBookingClaim.publishedReceipt(
                                recovered,
                                watch("receipt-c", confirmed(second), NEW_ADDRESS, 12)
                                        .put("restaurantAddress", NEW_ADDRESS),
                                12)
                        == null,
                "Android scope is part of Feed reuse");

        State otherDay = working("other-day", OLD_NAME, MEAL.plusDays(1), 3);
        State otherParty = working("other-party", OLD_NAME, MEAL, 4);
        for (JSONObject independent :
                List.of(
                        watch("date", otherDay, OLD_ADDRESS, 0),
                        watch("party", otherParty, OLD_ADDRESS, 0),
                        watch("branch", first, "2 Main St, New York, NY", 0),
                        watch("user", first, OLD_ADDRESS, 12))) {
            String id = independent.getString("id");
            recovered.put(id, independent);
            State candidate = DiningPollBookingCodec.state(independent.getJSONObject("state"));
            int user = independent.getJSONObject("pollExecutionScreen").getInt("userId");
            DiningBookingClaim.reserve(
                    recovered,
                    id,
                    candidate.booking(),
                    new JSONObject(attempt.toString()).put("userId", user),
                    user);
            check(
                    independent.has("openTableSubmission"),
                    "independent reservation targets retain normal checkout");
        }
        State unpublishedOutcome =
                submissionUnknown(
                                working("poll-c", OLD_NAME, MEAL, 3),
                                working("poll-c", OLD_NAME, MEAL, 3).booking().key())
                        .state();
        JSONObject unknown =
                new JSONObject()
                        .put("watch-b", two)
                        .put(
                                "legacy-unknown",
                                watch("legacy-unknown", unpublishedOutcome, OLD_ADDRESS, 0));
        rejects(
                "BOOKING_EQUIVALENT_OUTCOME_UNKNOWN",
                () -> DiningBookingClaim.reserve(unknown, "watch-b", second.booking(), attempt, 0));
        check(
                DiningBookingNavigation.rejectsTap("confirmation_continue_btn", "search"),
                "final native control ID is never generic navigation");
        check(
                DiningBookingNavigation.rejectsTap("unrecognized", "complete reservation"),
                "generic label cannot hide final action");
        check(
                DiningBookingNavigation.rejectsTap("unrecognized", "cancel reservation"),
                "generic navigation cannot cancel an existing booking");
        check(
                !DiningBookingNavigation.rejectsTap("search_edit", "search"),
                "known navigation can continue to exact-node verification");
        feedResponseLossMustNotResurfaceRemovedCard(confirmed(first));
        System.out.println("Dining booking claim: " + checks + " checks passed");
    }

    private static void feedResponseLossMustNotResurfaceRemovedCard(State confirmed)
            throws Exception {
        JSONObject expected =
                new JSONObject(
                        DiningReservationFeedCard.create(
                                confirmed,
                                java.util.Map.of("type", "open-app", "package", "com.opentable"),
                                DiningReservationFeedCard.whatsappShareIntent(confirmed)));
        JSONObject existing = new JSONObject(expected.toString());
        JSONObject removed = new JSONObject().put("card", existing).put("removed", true);
        DiningBookingClaim.verifyPublishedCard(expected, removed);
        check(removed.getBoolean("removed"), "verified read-back does not reset removal");
        existing.put("createdAt", existing.getLong("createdAt") + 1);
        rejects(
                "DINING_RECEIPT_FEED_CARD_CHANGED",
                () -> DiningBookingClaim.verifyPublishedCard(expected, removed));
        existing.put("createdAt", expected.getLong("createdAt"));
        existing.getJSONObject("actions")
                .getJSONObject("share")
                .getJSONObject("intent")
                .put("text", "different reservation");
        rejects(
                "DINING_RECEIPT_FEED_CARD_CHANGED",
                () -> DiningBookingClaim.verifyPublishedCard(expected, removed));
        JSONObject otherSource = new JSONObject(expected.toString());
        otherSource.getJSONObject("source").put("package", "other.app");
        JSONObject changed = new JSONObject().put("card", otherSource).put("removed", false);
        rejects(
                "DINING_RECEIPT_FEED_CARD_CHANGED",
                () -> DiningBookingClaim.verifyPublishedCard(expected, changed));
    }

    private static JSONObject watch(String id, State state, String address, int user)
            throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("state", DiningPollBookingCodec.state(state))
                .put("bookingAddress", address)
                .put("pollExecutionScreen", new JSONObject().put("userId", user));
    }

    private static State working(String poll, String restaurant, ZonedDateTime time, int people) {
        Meal meal =
                new Meal(
                        "meal:" + poll,
                        "thread:" + poll,
                        "group:" + poll,
                        "Dinner",
                        poll,
                        "Where should we eat?",
                        time,
                        people,
                        List.of(
                                new Restaurant("first", restaurant),
                                new Restaurant("second", "Other")));
        Votes votes =
                new Votes(
                        meal.groupId(),
                        meal.pollId(),
                        meal.pollQuestion(),
                        List.of("first", "second"),
                        List.of(1, 0),
                        null,
                        true,
                        1,
                        new Evidence(Source.WHATSAPP_POLL_UI, "proof:" + poll, NOW));
        State offered = observe(State.start(meal), votes, NOW).state();
        State starting = respond(offered, offered.offer().key(), true, NOW).state();
        return started(starting, starting.booking().key(), "1700000000000").state();
    }

    private static State confirmed(State working) {
        Booking booking = working.booking();
        var receipt =
                new Receipt(
                        ReceiptStatus.CONFIRMED,
                        booking.key(),
                        booking.restaurant().id(),
                        booking.restaurant().name(),
                        booking.meal().when(),
                        booking.meal().people(),
                        "",
                        "",
                        "",
                        "",
                        new Evidence(
                                Source.OPENTABLE_RECEIPT_UI,
                                "receipt:" + working.meal().id(),
                                NOW));
        return DiningPollBookingFlow.confirmed(working, receipt, NOW).state();
    }
}
