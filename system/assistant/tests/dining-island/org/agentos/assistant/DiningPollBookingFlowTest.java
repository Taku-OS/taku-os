package org.agentos.assistant;

import static org.agentos.assistant.DiningPollBookingFlow.*;
import static org.agentos.assistant.DiningPollIslandTest.*;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public final class DiningPollBookingFlowTest {
    private static final Instant NOW = Instant.parse("2026-11-20T17:00:00Z");
    private static final Meal MEAL =
            new Meal(
                    "meal:test",
                    "thread:test",
                    "group:test",
                    "Dinner",
                    "poll:test",
                    "Where should we eat?",
                    ZonedDateTime.parse("2026-11-29T19:15-05:00[America/New_York]"),
                    3,
                    List.of(
                            new Restaurant("first", "Harbor Cafe"),
                            new Restaurant("second", "Stone Bistro")));

    private static Votes tally(int first, int second, long sequence) {
        return new Votes(
                MEAL.groupId(),
                MEAL.pollId(),
                MEAL.pollQuestion(),
                List.of("first", "second"),
                List.of(first, second),
                null,
                true,
                sequence,
                new Evidence(Source.WHATSAPP_POLL_UI, "observation:" + sequence, NOW));
    }

    private static Receipt receipt(String key, int people) {
        return new Receipt(
                ReceiptStatus.CONFIRMED,
                key,
                "first",
                "Harbor Cafe",
                MEAL.when(),
                people,
                "",
                "",
                "",
                "",
                new Evidence(Source.OPENTABLE_RECEIPT_UI, "receipt:test", NOW));
    }

    private static void rejectsInvalid(String code, Work work) throws Exception {
        try {
            work.run();
            throw new AssertionError("Accepted " + code);
        } catch (Invalid failure) {
            check(code.equals(failure.code), failure.code);
        }
    }

    public static void main(String[] args) throws Exception {
        State initial = State.start(MEAL);
        check(
                observe(initial, tally(1, 1, 1), NOW).effects().isEmpty(),
                "Tie presents no booking action");
        Change leading = observe(initial, tally(1, 0, 1), NOW);
        check(
                leading.effects().size() == 1
                        && leading.effects().get(0).kind() == Kind.ASK_BOOKING,
                "Unique leader only asks for consent");
        String key = leading.state().offer().key();
        State declined = respond(leading.state(), key, false, NOW).state();
        check(
                observe(declined, tally(2, 0, 2), NOW).effects().isEmpty(),
                "Declined winner does not re-prompt");
        Change accepted = respond(leading.state(), key, true, NOW);
        check(
                accepted.effects().size() == 1
                        && accepted.effects().get(0).kind() == Kind.START_BOOKING,
                "Explicit decision dispatches exactly one booking");
        rejectsInvalid("REQUEST_EXPIRED", () -> respond(accepted.state(), key, true, NOW));
        State working =
                started(accepted.state(), accepted.state().booking().key(), "1700000000000")
                        .state();
        check(
                observe(working, tally(1, 3, 2), NOW).effects().isEmpty(),
                "Changed votes never dispatch a second booking");
        rejectsInvalid(
                "RECEIPT_TARGET_CHANGED",
                () -> confirmed(working, receipt(working.booking().key(), 4), NOW));
        Receipt actual = receipt(working.booking().key(), MEAL.people());
        Change verified = confirmed(working, actual, NOW);
        check(
                verified.effects().size() == 1
                        && verified.effects().get(0).kind() == Kind.PUBLISH_RECEIPT,
                "Verified receipt can publish exactly one Feed card");
        check(
                confirmed(verified.state(), actual, NOW).effects().isEmpty(),
                "Repeating a receipt creates no second card");
        Meal undecidedTime =
                new Meal(
                        MEAL.id(),
                        MEAL.sourceThreadId(),
                        MEAL.groupId(),
                        MEAL.groupName(),
                        MEAL.pollId(),
                        MEAL.pollQuestion(),
                        null,
                        0,
                        MEAL.restaurants());
        State details = observe(State.start(undecidedTime), tally(1, 0, 1), NOW).state();
        rejectsInvalid(
                "MEAL_DETAILS_REQUIRED", () -> respond(details, details.offer().key(), true, NOW));
        String summary = "Booking details: 2026-11-29 · 19:15 · America/New_York · 3 guests";
        check(
                DiningPollSchedule.prefill(summary, NOW).when().equals(MEAL.when()),
                "complete explicit details can prefill confirmation");
        check(
                !DiningPollSchedule.prefill(summary + "\n" + summary, NOW).complete(),
                "ambiguous details stay unset");
        rejects(
                "MEAL_TIME_AMBIGUOUS",
                () ->
                        DiningPollBookingForm.complete(
                                DiningPollSchedule.EMPTY,
                                new JSONObject()
                                        .put("date", "2027-11-07")
                                        .put("time", "01:30")
                                        .put("zone", "America/New_York")
                                        .put("people", 3),
                                NOW));
        check(
                DiningBookingCheckout.requiresReview("", "Deposit of $25 per person", ""),
                "deposits require review");
        check(
                DiningBookingCheckout.requiresReview("confirmation_gpay_btn", "", ""),
                "payment controls require review");
        check(
                !DiningBookingCheckout.requiresReview(
                        "confirmation_continue_btn", "Confirm Reservation", ""),
                "ordinary confirmation can be checked");
        checkoutMustMatchConfirmedTarget();
        Map<String, Object> share = DiningReservationFeedCard.whatsappShareIntent(verified.state());
        check(
                "share-text".equals(share.get("type"))
                        && "com.whatsapp".equals(share.get("package")),
                "Feed action uses WhatsApp recipient selection");
        rejectsInvalid(
                "RESERVATION_UNCONFIRMED",
                () -> DiningReservationFeedCard.whatsappShareIntent(working));
        System.out.println("Dining booking flow: " + checks + " checks passed");
    }

    private static void checkoutMustMatchConfirmedTarget() throws Exception {
        var target =
                new DiningBookingCheckout.Target(
                        "Harbor Cafe",
                        "1 Main Street, New York, NY",
                        LocalDate.parse("2026-11-29"),
                        LocalTime.parse("19:15"),
                        3);
        List<String> exact =
                List.of(
                        "Harbor Cafe",
                        "1 Main St, New York, NY",
                        "3 guests",
                        "Nov 29, 2026 at 7:15 PM");
        DiningBookingCheckout.verifySummary(exact, target, null);
        List<String> separated =
                List.of(
                        "Harbor Cafe",
                        "1 Main St, New York, NY",
                        "2026-11-29",
                        "19:15",
                        "Party of 3");
        DiningBookingCheckout.verifySummary(separated, target, null);
        List<String> partial =
                List.of(
                        "Harbor Cafe",
                        "1 Main St, New York, NY",
                        "3 guests",
                        "Sun, Nov 29 at 7:15 PM");
        DiningBookingCheckout.verifySummary(partial, target, target.date());
        String code = "OPENTABLE_CHECKOUT_TARGET_UNVERIFIED";
        rejects(code, () -> DiningBookingCheckout.verifySummary(partial, target, null));
        rejects(
                code,
                () ->
                        DiningBookingCheckout.verifySummary(
                                partial, target, target.date().plusYears(1)));
        rejects(
                code,
                () ->
                        DiningBookingCheckout.verifySummary(
                                List.of(
                                        "Harbor Cafe",
                                        "1 Main St, New York, NY",
                                        "3 guests",
                                        "Nov 30 at 7:15 PM"),
                                target,
                                target.date()));
        rejects(
                code,
                () ->
                        DiningBookingCheckout.verifySummary(
                                List.of(
                                        "Harbor Cafe",
                                        "1 Main St, New York, NY",
                                        "3 guests",
                                        "Sunday at 7:15 PM"),
                                target,
                                target.date()));
        rejects(
                code,
                () ->
                        DiningBookingCheckout.verifySummary(
                                List.of(
                                        "Harbor Cafe",
                                        "2 Main St, New York, NY",
                                        "3 guests",
                                        "Nov 29, 2026 at 7:15 PM"),
                                target,
                                null));
        rejects(
                code,
                () ->
                        DiningBookingCheckout.verifySummary(
                                List.of(
                                        "Other Cafe",
                                        "1 Main St, New York, NY",
                                        "3 guests",
                                        "Nov 29, 2026 at 7:15 PM"),
                                target,
                                null));
        rejects(
                code,
                () ->
                        DiningBookingCheckout.verifySummary(
                                List.of(
                                        "Harbor Cafe",
                                        "1 Main St, New York, NY",
                                        "4 guests",
                                        "Nov 29, 2026 at 7:15 PM"),
                                target,
                                null));
        rejects(
                code,
                () ->
                        DiningBookingCheckout.verifySummary(
                                List.of(
                                        "Harbor Cafe",
                                        "1 Main St, New York, NY",
                                        "3 guests",
                                        "Nov 29, 2026 at 7:30 PM"),
                                target,
                                null));
        rejects(
                code,
                () ->
                        DiningBookingCheckout.verifySummary(
                                List.of(
                                        "Harbor Cafe",
                                        "1 Main St, New York, NY",
                                        "3 guests",
                                        "Nov 29, 2027 at 7:15 PM"),
                                target,
                                target.date()));
        rejects(
                code,
                () ->
                        DiningBookingCheckout.verifySummary(
                                List.of(
                                        "Harbor Cafe",
                                        "1 Main St, New York, NY",
                                        "3 guests",
                                        "Nov 29, 2026 at 7:15 PM",
                                        "4 guests"),
                                target,
                                null));
    }
}
