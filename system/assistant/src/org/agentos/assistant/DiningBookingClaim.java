package org.agentos.assistant;

import static org.agentos.assistant.DiningPollBookingFlow.*;

import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;

/** The saved booking transaction is also the reservation-wide exclusion record. */
final class DiningBookingClaim {
    private DiningBookingClaim() {}

    record Target(String restaurant, String address, Instant instant, int people, int user) {
        boolean same(Target other) {
            if (user != other.user
                    || people != other.people
                    || !instant.equals(other.instant)
                    || !DiningRestaurantIdentity.sameName(restaurant, other.restaurant))
                return false;
            try {
                DiningPollReceiptTarget.verifyAddress(address, other.address);
                return true;
            } catch (IOException differentBranch) {
                return false;
            }
        }
    }

    static JSONObject reserve(
            JSONObject ledger, String watchId, Booking expected, JSONObject attempt, int user)
            throws Exception {
        JSONObject watch = ledger.optJSONObject(watchId);
        if (watch == null || watch.has("openTableSubmission") || !watch.optBoolean("active", true))
            throw new IOException("BOOKING_SUBMISSION_UNAVAILABLE");
        State current = state(watch);
        if (current.phase() != Phase.WORKING || !Objects.equals(expected, current.booking()))
            throw new IOException("BOOKING_SUBMISSION_UNAVAILABLE");
        Target requested = target(watch, current, user);
        String conflict = conflict(ledger, watchId, requested);
        if (conflict != null) throw new IOException(conflict);
        watch.put("openTableSubmission", attempt);
        return watch;
    }

    static String conflict(JSONObject ledger, String watchId, Target requested) throws Exception {
        boolean unresolved = false;
        for (var ids = ledger.keys(); ids.hasNext(); ) {
            String id = ids.next();
            if (watchId.equals(id)) continue;
            JSONObject watch = ledger.getJSONObject(id);
            JSONObject raw = watch.getJSONObject("state");
            String phase = raw.optString("phase");
            boolean confirmed = "CONFIRMED".equals(phase);
            if (!confirmed && !"UNKNOWN".equals(phase) && !watch.has("openTableSubmission"))
                continue;
            int user = androidUser(watch, requested.user());
            if (user != requested.user()) continue;
            State stored;
            try {
                stored = state(watch);
            } catch (Exception unavailable) {
                // An unsupported legacy receipt must not globally block unrelated reservations.
                JSONObject receipt = raw.optJSONObject("receipt"),
                        evidence = receipt == null ? null : receipt.optJSONObject("evidence");
                if (evidence != null && "PHONE_CALL".equals(evidence.optString("source"))) continue;
                throw new IOException("BOOKING_RESERVATION_HISTORY_UNVERIFIED", unavailable);
            }
            Target known = target(watch, stored, user);
            if (!requested.same(known)) continue;
            if (confirmed) return "BOOKING_RESERVATION_ALREADY_CONFIRMED";
            unresolved = true;
        }
        return unresolved ? "BOOKING_EQUIVALENT_OUTCOME_UNKNOWN" : null;
    }

    record PublishedReceipt(
            String cardId, String ownerWatchId, long publishedAt, int templateVersion) {}

    static PublishedReceipt publishedReceipt(JSONObject ledger, JSONObject watch, int localUser)
            throws Exception {
        String id = watch.getString("id");
        State confirmed = state(watch);
        Target requested = target(watch, confirmed, androidUser(watch, localUser));
        PublishedReceipt result = null;
        for (var ids = ledger.keys(); ids.hasNext(); ) {
            String other = ids.next();
            if (id.equals(other)) continue;
            JSONObject candidate = ledger.optJSONObject(other);
            if (candidate == null) continue;
            String cardId = candidate.optString("feedCardId");
            String owner = candidate.optString("feedReceiptOwnerWatchId", other);
            long publishedAt = candidate.optLong("feedPublishedAt");
            if (!cardId.matches("dining\\.reservation\\.[a-f0-9]{24}(?:[a-f0-9]{16})?")
                    || publishedAt <= 0) continue;
            try {
                State verified = state(candidate);
                if (verified.phase() != Phase.CONFIRMED
                        || !requested.same(
                                target(candidate, verified, androidUser(candidate, localUser))))
                    continue;
                if (result == null
                        || publishedAt < result.publishedAt()
                        || publishedAt == result.publishedAt()
                                && cardId.compareTo(result.cardId()) < 0) {
                    result =
                            new PublishedReceipt(
                                    cardId,
                                    owner,
                                    publishedAt,
                                    candidate.optInt("feedTemplateVersion", 2));
                }
            } catch (Exception unavailable) {
                // An unverified journal row cannot select the Feed card for this receipt.
            }
        }
        return result;
    }

    static void verifyPublishedCard(JSONObject expected, JSONObject returned) throws Exception {
        JSONObject actual = returned.getJSONObject("card");
        JSONObject source = actual.getJSONObject("source"),
                expectedSource = expected.getJSONObject("source");
        JSONObject share =
                actual.getJSONObject("actions").getJSONObject("share").getJSONObject("intent");
        JSONObject expectedShare =
                expected.getJSONObject("actions").getJSONObject("share").getJSONObject("intent");
        if (!(returned.opt("removed") instanceof Boolean)
                || !expected.getString("id").equals(actual.opt("id"))
                || !expected.getString("schema").equals(actual.opt("schema"))
                || expected.getLong("createdAt") != actual.optLong("createdAt", -1))
            throw new IOException("DINING_RECEIPT_FEED_CARD_CHANGED");
        for (String field : new String[] {"id", "app", "package"}) {
            if (!expectedSource.getString(field).equals(source.opt(field)))
                throw new IOException("DINING_RECEIPT_FEED_CARD_CHANGED");
        }
        for (String field : new String[] {"type", "package", "text"}) {
            if (!expectedShare.getString(field).equals(share.opt(field)))
                throw new IOException("DINING_RECEIPT_FEED_CARD_CHANGED");
        }
    }

    static int androidUser(JSONObject watch, int defaultUser) {
        for (String key :
                new String[] {
                    "openTableSubmission", "openTableReceiptBinding", "pollExecutionScreen"
                }) {
            JSONObject binding = watch.optJSONObject(key);
            if (binding != null && binding.opt("userId") instanceof Integer value && value >= 0)
                return value;
        }
        // The Assistant ledger lives in this Android user's private SharedPreferences directory.
        return defaultUser;
    }

    private static State state(JSONObject watch) throws Exception {
        return DiningPollBookingCodec.state(watch.getJSONObject("state"));
    }

    private static Target target(JSONObject watch, State state, int user) throws Exception {
        if (state.booking() == null || !state.meal().hasSchedule())
            throw new IOException("BOOKING_RESERVATION_HISTORY_UNVERIFIED");
        String address = watch.optString("bookingAddress");
        String restaurant = state.booking().restaurant().name();
        if (state.phase() == Phase.CONFIRMED) {
            Receipt evidence = receipt(state);
            String observed = watch.optString("restaurantAddress", address);
            if (address.isBlank()) address = observed;
            DiningPollReceiptTarget.verifyAddress(address, observed);
            if (!DiningRestaurantIdentity.sameName(restaurant, evidence.restaurantName())
                    || !state.meal().when().toInstant().equals(evidence.when().toInstant())
                    || state.meal().people() != evidence.people())
                throw new IOException("BOOKING_RESERVATION_HISTORY_UNVERIFIED");
        }
        DiningPollReceiptTarget.verifyAddress(address, address);
        return new Target(
                restaurant, address, state.meal().when().toInstant(), state.meal().people(), user);
    }
}
