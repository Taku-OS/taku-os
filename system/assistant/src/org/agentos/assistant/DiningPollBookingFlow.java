package org.agentos.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic rules for one meal and its original WhatsApp poll. No phone, booking, messaging or
 * calendar operation runs here. The host must atomically persist State and its keyed Effects before
 * executing them through the existing native runtime.
 */
final class DiningPollBookingFlow {
    static final Duration MAX_POLL_AGE = Duration.ofMinutes(1);

    enum Phase {
        WATCHING,
        OFFERED,
        STARTING,
        WORKING,
        UNKNOWN,
        FAILED,
        CONFIRMED
    }

    enum Kind {
        ASK_BOOKING,
        START_BOOKING,
        PUBLISH_RECEIPT,
        PREPARE_SHARE,
        ASK_CALENDAR,
        ADD_CALENDAR
    }

    enum Source {
        WHATSAPP_POLL_UI,
        OPENTABLE_RECEIPT_UI
    }

    enum ReceiptStatus {
        CONFIRMED,
        PENDING,
        CANCELLED
    }

    static final class Invalid extends IllegalArgumentException {
        final String code;

        Invalid(String code) {
            super(code);
            this.code = code;
        }
    }

    record Restaurant(String id, String name) {
        Restaurant {
            plain(id, 120);
            plain(name, 240);
        }
    }

    record Meal(
            String id,
            String sourceThreadId,
            String groupId,
            String groupName,
            String pollId,
            String pollQuestion,
            ZonedDateTime when,
            int people,
            List<Restaurant> restaurants) {
        Meal {
            plain(id, 120);
            plain(sourceThreadId, 120);
            plain(groupId, 240);
            plain(groupName, 240);
            plain(pollId, 240);
            plain(pollQuestion, 240);
            if ((when == null ? people != 0 : people < 1 || people > 99)
                    || restaurants == null
                    || restaurants.size() < 2
                    || restaurants.size() > 12) fail("INVALID_MEAL");
            if (restaurants.stream().anyMatch(java.util.Objects::isNull)) fail("INVALID_OPTIONS");
            restaurants = List.copyOf(restaurants);
            Set<String> ids = new HashSet<>(), names = new HashSet<>();
            for (Restaurant restaurant : restaurants) {
                if (restaurant == null
                        || !ids.add(restaurant.id())
                        || !names.add(restaurant.name().toLowerCase(Locale.ROOT)))
                    fail("INVALID_OPTIONS");
            }
        }

        boolean hasSchedule() {
            return when != null;
        }
    }

    /** Only a native observation adapter may construct this from its own captured UI. */
    record Evidence(Source source, String observationId, Instant capturedAt) {
        Evidence {
            if (source == null || capturedAt == null) fail("EVIDENCE_REQUIRED");
            plain(observationId, 240);
        }
    }

    record Votes(
            String groupId,
            String pollId,
            String question,
            List<String> optionIds,
            List<Integer> counts,
            Integer eligibleVoters,
            boolean singleChoice,
            long sequence,
            Evidence evidence) {
        Votes {
            if (optionIds == null
                    || counts == null
                    || optionIds.size() != counts.size()
                    || optionIds.size() < 2
                    || !singleChoice
                    || sequence < 0
                    || evidence == null
                    || evidence.source() != Source.WHATSAPP_POLL_UI) fail("INVALID_POLL");
            long sum = 0;
            for (Integer count : counts) {
                if (count == null || count < 0) fail("INVALID_TALLY");
                sum += count;
            }
            if (sum > Integer.MAX_VALUE
                    || eligibleVoters != null && (eligibleVoters < 1 || sum > eligibleVoters))
                fail("INCONSISTENT_TALLY");
            if (optionIds.stream().anyMatch(java.util.Objects::isNull)) fail("INVALID_POLL");
            optionIds = List.copyOf(optionIds);
            counts = List.copyOf(counts);
        }

        Restaurant leader(Meal meal) {
            int highest = counts.stream().mapToInt(Integer::intValue).max().orElse(0);
            if (highest == 0 || counts.stream().filter(value -> value == highest).count() != 1)
                return null;
            return meal.restaurants().get(counts.indexOf(highest));
        }
    }

    record Offer(String key, Restaurant restaurant) {}

    record Booking(
            String key, Restaurant restaurant, Meal meal, Instant consentAt, String taskId) {}

    /** Receipt evidence comes from the native OpenTable adapter. */
    record Receipt(
            ReceiptStatus status,
            String bookingKey,
            String restaurantId,
            String restaurantName,
            ZonedDateTime when,
            int people,
            String confirmationCode,
            String seating,
            String restaurantUrl,
            String photoUrl,
            Evidence evidence) {
        Receipt {
            plain(bookingKey, 160);
            plain(restaurantId, 120);
            plain(restaurantName, 240);
            if (status != ReceiptStatus.CONFIRMED
                    || when == null
                    || people < 1
                    || people > 99
                    || evidence == null
                    || evidence.source() != Source.OPENTABLE_RECEIPT_UI) fail("RECEIPT_UNVERIFIED");
            confirmationCode = optional(confirmationCode, 120);
            seating = optional(seating, 240);
            restaurantUrl = https(restaurantUrl);
            photoUrl = https(photoUrl);
        }
    }

    record Effect(String key, Kind kind, String restaurantId, String decisionKey) {}

    record State(
            Meal meal,
            Phase phase,
            Votes votes,
            Offer offer,
            Booking booking,
            Receipt receipt,
            long offerSequence,
            Set<String> declined,
            String shareKey,
            boolean sharePrepared,
            String calendarKey,
            boolean calendarAccepted,
            boolean calendarAsked) {
        State {
            declined = Set.copyOf(declined);
        }

        static State start(Meal meal) {
            if (meal == null) fail("INVALID_MEAL");
            return new State(
                    meal,
                    Phase.WATCHING,
                    null,
                    null,
                    null,
                    null,
                    0,
                    Set.of(),
                    null,
                    false,
                    null,
                    false,
                    false);
        }
    }

    record Change(State state, List<Effect> effects) {
        Change {
            effects = List.copyOf(effects);
        }
    }

    private DiningPollBookingFlow() {}

    static Change observe(State state, Votes votes, Instant now) {
        Meal meal = state.meal();
        if (!meal.groupId().equals(votes.groupId())
                || !meal.pollId().equals(votes.pollId())
                || !meal.pollQuestion().equals(votes.question())
                || !meal.restaurants().stream()
                        .map(Restaurant::id)
                        .toList()
                        .equals(votes.optionIds())) fail("POLL_BINDING_CHANGED");
        if (state.votes() != null && votes.sequence() <= state.votes().sequence())
            return unchanged(state);
        fresh(votes.evidence(), now);
        // A changing vote must never start another reservation while one is in flight or confirmed.
        if (state.booking() != null)
            return change(
                    copy(
                            state,
                            state.phase(),
                            votes,
                            state.offer(),
                            state.booking(),
                            state.receipt(),
                            state.offerSequence(),
                            state.declined(),
                            state.shareKey(),
                            state.sharePrepared(),
                            state.calendarKey(),
                            state.calendarAccepted()));
        Restaurant leader = votes.leader(meal);
        if (leader == null
                || state.declined().contains(leader.id())
                || (meal.hasSchedule() && !meal.when().toInstant().isAfter(now)))
            return change(
                    copy(
                            state,
                            Phase.WATCHING,
                            votes,
                            null,
                            null,
                            null,
                            state.offerSequence(),
                            state.declined(),
                            null,
                            false,
                            null,
                            false));
        if (state.offer() != null && state.offer().restaurant().id().equals(leader.id()))
            return change(
                    copy(
                            state,
                            Phase.OFFERED,
                            votes,
                            state.offer(),
                            null,
                            null,
                            state.offerSequence(),
                            state.declined(),
                            null,
                            false,
                            null,
                            false));
        long sequence = Math.addExact(state.offerSequence(), 1);
        Offer offer =
                new Offer(
                        key("decision", mealKey(meal), Long.toString(sequence), leader.id()),
                        leader);
        return change(
                copy(
                        state,
                        Phase.OFFERED,
                        votes,
                        offer,
                        null,
                        null,
                        sequence,
                        state.declined(),
                        null,
                        false,
                        null,
                        false),
                new Effect(offer.key(), Kind.ASK_BOOKING, leader.id(), offer.key()));
    }

    static Change respond(State state, String decisionKey, boolean accepted, Instant now) {
        if (state.phase() != Phase.OFFERED
                || state.offer() == null
                || !state.offer().key().equals(decisionKey)) fail("REQUEST_EXPIRED");
        fresh(state.votes().evidence(), now);
        Restaurant leader = state.votes().leader(state.meal());
        if (leader == null || !leader.id().equals(state.offer().restaurant().id()))
            fail("REQUEST_EXPIRED");
        if (!accepted) {
            Set<String> declined = new HashSet<>(state.declined());
            declined.add(leader.id());
            return change(
                    copy(
                            state,
                            Phase.WATCHING,
                            state.votes(),
                            null,
                            null,
                            null,
                            state.offerSequence(),
                            declined,
                            null,
                            false,
                            null,
                            false));
        }
        if (!state.meal().hasSchedule()) fail("MEAL_DETAILS_REQUIRED");
        if (!state.meal().when().toInstant().isAfter(now)) fail("MEAL_IN_PAST");
        Booking booking =
                new Booking(
                        key("booking", mealKey(state.meal()), leader.id()),
                        leader,
                        state.meal(),
                        now,
                        null);
        return change(
                copy(
                        state,
                        Phase.STARTING,
                        state.votes(),
                        null,
                        booking,
                        null,
                        state.offerSequence(),
                        state.declined(),
                        null,
                        false,
                        null,
                        false),
                new Effect(booking.key(), Kind.START_BOOKING, leader.id(), decisionKey));
    }

    static State schedule(
            State state, String decisionKey, ZonedDateTime when, int people, Instant now) {
        if (state.phase() != Phase.OFFERED
                || state.offer() == null
                || !state.offer().key().equals(decisionKey)
                || state.meal().hasSchedule()) fail("REQUEST_EXPIRED");
        return withSchedule(state, when, people, now);
    }

    static State withSchedule(State state, ZonedDateTime when, int people, Instant now) {
        if ((state.phase() != Phase.WATCHING && state.phase() != Phase.OFFERED)
                || state.booking() != null
                || state.meal().hasSchedule()) fail("REQUEST_EXPIRED");
        if (when == null || !when.toInstant().isAfter(now)) fail("MEAL_IN_PAST");
        if (when.getZone() instanceof java.time.ZoneOffset
                || when.getZone().getRules().getValidOffsets(when.toLocalDateTime()).size() != 1)
            fail("MEAL_TIME_AMBIGUOUS");
        Meal current = state.meal();
        Meal meal =
                new Meal(
                        current.id(),
                        current.sourceThreadId(),
                        current.groupId(),
                        current.groupName(),
                        current.pollId(),
                        current.pollQuestion(),
                        when,
                        people,
                        current.restaurants());
        return new State(
                meal,
                state.phase(),
                state.votes(),
                state.offer(),
                state.booking(),
                state.receipt(),
                state.offerSequence(),
                state.declined(),
                state.shareKey(),
                state.sharePrepared(),
                state.calendarKey(),
                state.calendarAccepted(),
                state.calendarAsked());
    }

    static Change started(State state, String bookingKey, String taskId) {
        Booking booking = booking(state, bookingKey);
        plain(taskId, 160);
        if (state.phase() == Phase.WORKING && taskId.equals(booking.taskId()))
            return unchanged(state);
        if (state.phase() != Phase.STARTING) fail("INVALID_STATE");
        return change(
                copy(
                        state,
                        Phase.WORKING,
                        state.votes(),
                        null,
                        new Booking(
                                booking.key(),
                                booking.restaurant(),
                                booking.meal(),
                                booking.consentAt(),
                                taskId),
                        null,
                        state.offerSequence(),
                        state.declined(),
                        null,
                        false,
                        null,
                        false));
    }

    /** An uncertain submit is never retried automatically: it may already have booked. */
    static Change submissionUnknown(State state, String bookingKey) {
        booking(state, bookingKey);
        if (state.phase() == Phase.CONFIRMED) return unchanged(state);
        if (state.phase() != Phase.STARTING && state.phase() != Phase.WORKING)
            fail("INVALID_STATE");
        return change(
                copy(
                        state,
                        Phase.UNKNOWN,
                        state.votes(),
                        null,
                        state.booking(),
                        null,
                        state.offerSequence(),
                        state.declined(),
                        null,
                        false,
                        null,
                        false));
    }

    static Change confirmed(State state, Receipt receipt, Instant now) {
        Booking booking = booking(state, receipt.bookingKey());
        if (state.phase() == Phase.CONFIRMED) {
            if (sameReceipt(state.receipt(), receipt)) return unchanged(state);
            fail("RECEIPT_CHANGED");
        }
        if (state.phase() != Phase.STARTING
                && state.phase() != Phase.WORKING
                && state.phase() != Phase.UNKNOWN) fail("INVALID_STATE");
        if (receipt.evidence().capturedAt().isAfter(now.plusSeconds(5))) fail("EVIDENCE_IN_FUTURE");
        if (receipt.evidence().capturedAt().isBefore(booking.consentAt()))
            fail("RECEIPT_PREDATES_CONSENT");
        if (!booking.restaurant().id().equals(receipt.restaurantId())
                || !DiningRestaurantIdentity.sameName(
                        booking.restaurant().name(), receipt.restaurantName())
                || booking.meal().people() != receipt.people()
                || !booking.meal().when().toLocalDateTime().equals(receipt.when().toLocalDateTime())
                || !booking.meal().when().toInstant().equals(receipt.when().toInstant())
                || !booking.meal()
                        .when()
                        .getZone()
                        .getRules()
                        .equals(receipt.when().getZone().getRules()))
            fail("RECEIPT_TARGET_CHANGED");
        return change(
                copy(
                        state,
                        Phase.CONFIRMED,
                        state.votes(),
                        null,
                        booking,
                        receipt,
                        state.offerSequence(),
                        state.declined(),
                        null,
                        false,
                        null,
                        false),
                new Effect(
                        key("feed", booking.key()),
                        Kind.PUBLISH_RECEIPT,
                        receipt.restaurantId(),
                        null));
    }

    static Receipt receipt(State state) {
        if (state.phase() != Phase.CONFIRMED || state.receipt() == null)
            fail("RESERVATION_UNCONFIRMED");
        return state.receipt();
    }

    private static Booking booking(State state, String key) {
        if (state.booking() == null || !state.booking().key().equals(key)) fail("REQUEST_EXPIRED");
        return state.booking();
    }

    private static void fresh(Evidence evidence, Instant now) {
        if (evidence == null || now == null) fail("EVIDENCE_REQUIRED");
        if (evidence.capturedAt().isAfter(now.plusSeconds(5))) fail("EVIDENCE_IN_FUTURE");
        if (evidence.capturedAt().plus(MAX_POLL_AGE).isBefore(now)) fail("POLL_REFRESH_REQUIRED");
    }

    private static State copy(
            State s,
            Phase phase,
            Votes votes,
            Offer offer,
            Booking booking,
            Receipt receipt,
            long sequence,
            Set<String> declined,
            String shareKey,
            boolean sharePrepared,
            String calendarKey,
            boolean calendarAccepted) {
        return new State(
                s.meal(),
                phase,
                votes,
                offer,
                booking,
                receipt,
                sequence,
                declined,
                shareKey,
                sharePrepared,
                calendarKey,
                calendarAccepted,
                s.calendarAsked());
    }

    private static boolean sameReceipt(Receipt a, Receipt b) {
        return a.status() == b.status()
                && a.bookingKey().equals(b.bookingKey())
                && a.restaurantId().equals(b.restaurantId())
                && a.restaurantName().equals(b.restaurantName())
                && a.when().equals(b.when())
                && a.people() == b.people()
                && a.confirmationCode().equals(b.confirmationCode())
                && a.seating().equals(b.seating())
                && a.restaurantUrl().equals(b.restaurantUrl())
                && a.photoUrl().equals(b.photoUrl())
                && a.evidence().source() == b.evidence().source();
    }

    private static String mealKey(Meal meal) {
        List<String> fields =
                new ArrayList<>(
                        List.of(
                                meal.id(),
                                meal.sourceThreadId(),
                                meal.groupId(),
                                meal.pollId(),
                                meal.pollQuestion(),
                                meal.hasSchedule() ? meal.when().toString() : "",
                                Integer.toString(meal.people())));
        for (Restaurant restaurant : meal.restaurants()) {
            fields.add(restaurant.id());
            fields.add(restaurant.name());
        }
        return key("meal", fields.toArray(String[]::new));
    }

    private static Change unchanged(State state) {
        return new Change(state, List.of());
    }

    private static Change change(State state, Effect... effects) {
        List<Effect> present = new ArrayList<>();
        for (Effect effect : effects) if (effect != null) present.add(effect);
        return new Change(state, present);
    }

    static String watchId(String intentId) {
        return key("watch", intentId);
    }

    static String key(String kind, String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (value.length >>> 24));
                digest.update((byte) (value.length >>> 16));
                digest.update((byte) (value.length >>> 8));
                digest.update((byte) value.length);
                digest.update(value);
            }
            return kind + ":" + HexFormat.of().formatHex(digest.digest()).substring(0, 40);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String optional(String value, int limit) {
        if (value == null || value.isEmpty()) return "";
        plain(value, limit);
        return value;
    }

    private static String https(String value) {
        if (value == null || value.isEmpty()) return "";
        plain(value, 2048);
        try {
            java.net.URI uri = new java.net.URI(value);
            if (!"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) fail("INVALID_SOURCE_URL");
        } catch (java.net.URISyntaxException invalid) {
            fail("INVALID_SOURCE_URL");
        }
        return value;
    }

    private static void plain(String value, int limit) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.strip())
                || value.length() > limit
                || value.codePoints().anyMatch(Character::isISOControl)) fail("INVALID_ARGUMENT");
    }

    private static void fail(String code) {
        throw new Invalid(code);
    }
}
